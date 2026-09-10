package com.smile.chunkland.command;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.claim.ClaimOutcome;
import com.smile.chunkland.claim.ClaimRequest;
import com.smile.chunkland.claim.ClaimSaga;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land claim} handler: the single command entry point into
 * the formal {@link ClaimSaga}.
 *
 * <p>Flow: the sender must be a player with a live selection session holding
 * at least one chunk; the tail args name the land; a {@link ClaimRequest} is
 * built from the session (owner and actor are the player, the world and chunks
 * come from the session, the request revision pins the live selection revision
 * so the validator rejects stale confirmations) and handed to the saga. The
 * saga owns the operation id end to end: it is generated inside the saga,
 * carried as the Economy idempotency key outside any SQL transaction, and
 * recorded on the ledger row.
 *
 * <p>Fail-closed contract: console senders, missing or empty selections, and
 * blank or invalid names reply without touching the saga (no ledger row, no
 * charge). A missing saga replies {@code claim.unavailable} so a half-wired
 * server never pretends the claim ran. While the startup recovery scan is
 * still in flight the handler replies {@code claim.recovery_pending}, and a
 * failed scan replies {@code claim.recovery_failed} permanently until
 * restart, both without touching the saga so a stale runtime snapshot can
 * never hide a collision. The saga itself rejects player claims on
 * {@code economy.unavailable} or {@code pricing.unavailable} before any
 * side effect, so a missing Vault provider or placeholder zero table can
 * never create a silent free land. Every other outcome replies exactly
 * once on saga completion: success carries the land name and chunk count known
 * at submit time; rejections and failures carry the saga diagnostic key.
 * {@code DEGRADED} replies success because the durable domain commit already
 * won and startup recovery completes the publish to {@code ACTIVE}.
 *
 * <p>Threading: everything Bukkit-facing (sender type, UUID, selection read,
 * name parse) happens synchronously on the calling thread; only the reply runs
 * on saga completion, using values captured up front, so the callback never
 * touches Bukkit objects.
 */
public final class ClaimCommandHandler implements LandCommand.Handler {

    /** Saga entry point; kept as a seam so tests can observe the request. */
    @FunctionalInterface
    public interface ClaimRunner {
        CompletionStage<ClaimOutcome> claim(ClaimRequest request);
    }

    private final SelectionSessionManager selections;
    private final ClaimRunner runner;
    private final Supplier<CompletionStage<?>> recoveryScan;
    private final BedrockClaimFormHandler bedrockForms;

    /**
     * @param selections live selection registry; never null
     * @param runner saga entry point; null means claiming is not wired yet and
     *        every attempt replies {@code claim.unavailable} without side effects
     */
    public ClaimCommandHandler(SelectionSessionManager selections, ClaimRunner runner) {
        this(selections, runner, null);
    }

    /**
     * @param selections live selection registry; never null
     * @param runner saga entry point; null means claiming is not wired yet
     * @param recoveryScan startup recovery scan; null means no gate (tests).
     *        While the scan is still in flight claims reply
     *        {@code claim.recovery_pending}; a failed scan replies
     *        {@code claim.recovery_failed} permanently until restart. The
     *        gate is checked before any selection read reaches the saga, so
     *        a stale runtime snapshot can never hide a collision.
     */
    public ClaimCommandHandler(SelectionSessionManager selections, ClaimRunner runner,
            Supplier<CompletionStage<?>> recoveryScan) {
        this(selections, runner, recoveryScan, null);
    }

    /**
     * @param selections live selection registry; never null
     * @param runner saga entry point; null means claiming is not wired yet
     * @param recoveryScan startup recovery scan; null means no gate (tests).
     *        While the scan is still in flight claims reply
     *        {@code claim.recovery_pending}; a failed scan replies
     *        {@code claim.recovery_failed} permanently until restart. The
     *        gate is checked before any selection read reaches the saga, so
     *        a stale runtime snapshot can never hide a collision.
     * @param bedrockForms Bedrock Modal Form branch; null keeps the legacy
     *        direct path for every sender (tests and AceLib-less wiring).
     *        When present, a Bedrock player with a valid name takes the form
     *        path after the shared pre-checks; Java senders are unaffected.
     */
    public ClaimCommandHandler(SelectionSessionManager selections, ClaimRunner runner,
            Supplier<CompletionStage<?>> recoveryScan, BedrockClaimFormHandler bedrockForms) {
        this.selections = Objects.requireNonNull(selections, "selections");
        this.runner = runner;
        this.recoveryScan = recoveryScan;
        this.bedrockForms = bedrockForms;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.claim.console", Map.of());
            return;
        }
        UUID actor = player.getUniqueId();
        if (runner == null) {
            sink.reply("command.land.claim.failed", Map.of("reason", "claim.unavailable"));
            return;
        }
        String recoveryBlock = recoveryBlockReason(recoveryScan);
        if (recoveryBlock != null) {
            sink.reply("command.land.claim.failed", Map.of("reason", recoveryBlock));
            return;
        }
        SelectionSession session;
        try {
            Optional<SelectionSession> current = selections.sessionFor(actor);
            if (current.isEmpty()) {
                sink.reply("command.land.claim.no_selection", Map.of());
                return;
            }
            session = current.get();
        } catch (RuntimeException failure) {
            sink.reply("command.land.claim.failed", Map.of("reason", "claim.failed"));
            return;
        }
        Set<ChunkKey> chunks = session.selectedChunks();
        if (chunks.isEmpty()) {
            sink.reply("command.land.claim.no_selection", Map.of());
            return;
        }
        String displayName = displayNameOf(args);
        if (displayName == null) {
            sink.reply("command.land.usage", Map.of());
            return;
        }
        if (bedrockForms != null) {
            ClaimPreview preview;
            try {
                preview = ClaimPreview.fromSession(session, displayName);
            } catch (RuntimeException invalid) {
                sink.reply("command.land.usage", Map.of());
                return;
            }
            boolean handled;
            try {
                handled = bedrockForms.handle(player, preview, sink);
            } catch (RuntimeException failure) {
                sink.reply("command.land.claim.failed", Map.of("reason", "claim.failed"));
                return;
            }
            if (handled) {
                return;
            }
        }
        ClaimRequest request;
        try {
            request = new ClaimRequest(OwnerRef.player(actor), actor, session.worldId(),
                    chunks, displayName, session.selectionRevision(), session.sessionGeneration(),
                    structureTokenOf(session), session.targetLandId().orElse(null));
        } catch (RuntimeException invalid) {
            sink.reply("command.land.usage", Map.of());
            return;
        }
        int chunkCount = chunks.size();
        CompletionStage<ClaimOutcome> stage;
        try {
            stage = runner.claim(request);
        } catch (RuntimeException failure) {
            sink.reply("command.land.claim.failed", Map.of("reason", "claim.failed"));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.claim.failed", Map.of("reason", "claim.failed"));
            return;
        }
        stage.whenComplete((outcome, failure) -> replyOutcome(sink, displayName, chunkCount, outcome, failure));
    }

    private void replyOutcome(ReplySink sink, String displayName, int chunkCount,
            ClaimOutcome outcome, Throwable failure) {
        replyOutcomeTo(sink, displayName, chunkCount, outcome, failure);
    }

    /**
     * Shared terminal reply for claim outcomes, reused by the confirmation
     * handler so both entries report the saga result with the same keys.
     */
    static void replyOutcomeTo(ReplySink sink, String displayName, int chunkCount,
            ClaimOutcome outcome, Throwable failure) {
        try {
            if (failure != null || outcome == null) {
                sink.reply("command.land.claim.failed", Map.of("reason", "claim.failed"));
                return;
            }
            switch (outcome.status()) {
                case SUCCESS, DEGRADED -> sink.reply("command.land.claim.success",
                        Map.of("land_name", displayName, "chunk_count", chunkCount));
                case REJECTED -> sink.reply("command.land.claim.rejected",
                        Map.of("reason", outcome.diagnosticKey()));
                case FAILED -> sink.reply("command.land.claim.failed",
                        Map.of("reason", outcome.diagnosticKey()));
            }
        } catch (RuntimeException ignored) {
            // Terminal reply path: never let a sink failure escape onto saga threads.
        }
    }

    /**
     * Structure token for the saga-time revalidation: the session base
     * revision while the session targets an existing land, null for
     * target-less selections where there is no structure to go stale.
     */
    static Long structureTokenOf(SelectionSession session) {
        if (session.targetLandId().isEmpty()) {
            return null;
        }
        return session.baseStructureRevision();
    }

    /**
     * Inspect the startup recovery scan without blocking: an unfinished scan
     * blocks with {@code claim.recovery_pending}, an exceptionally completed
     * scan blocks permanently with {@code claim.recovery_failed}, and a
     * successfully completed scan (or no gate) lets the claim through.
     */
    static String recoveryBlockReason(Supplier<CompletionStage<?>> recoveryScan) {
        if (recoveryScan == null) {
            return null;
        }
        CompletionStage<?> stage;
        try {
            stage = recoveryScan.get();
        } catch (RuntimeException failure) {
            return "claim.recovery_failed";
        }
        if (stage == null) {
            return null;
        }
        CompletableFuture<?> future;
        try {
            future = stage.toCompletableFuture();
        } catch (RuntimeException failure) {
            return "claim.recovery_failed";
        }
        if (future == null) {
            return null;
        }
        if (!future.isDone()) {
            return "claim.recovery_pending";
        }
        if (future.isCompletedExceptionally()) {
            return "claim.recovery_failed";
        }
        return null;
    }

    /**
     * Join the tail args into a display name, or null when no usable name was
     * given. Validation reuses the land-name contract so blank input and
     * control characters fail closed to usage.
     */
    private static String displayNameOf(String[] args) {
        if (args == null || args.length < 2) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            String part = args[i];
            if (part == null) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(part);
        }
        String candidate = joined.toString();
        try {
            LandName.normalize(candidate);
        } catch (RuntimeException invalid) {
            return null;
        }
        return candidate;
    }
}

package com.smile.chunkland.command;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.claim.ClaimOutcome;
import com.smile.chunkland.claim.ClaimRequest;
import com.smile.chunkland.claim.ClaimSaga;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land confirm <generation> <revision> <name>} handler: the chat
 * confirmation entry point into the formal {@link ClaimSaga}.
 *
 * <p>Token contract: the token pair is the {@code sessionGeneration} plus the
 * {@code selectionRevision} the confirmation message was rendered for. It is a
 * self-check against the sender's own live session, never a capability over
 * another player's state: the handler always re-reads the sender's current
 * session, compares both numbers, and builds the request from that live
 * session. A token from another player can therefore only confirm the
 * sender's own selection, and only when both numbers happen to match.
 *
 * <p>Revalidation, in order: the sender must be a player; both numbers must
 * parse as non-negative; a live non-empty selection must exist; the pair must
 * equal the live {@code sessionGeneration} and {@code selectionRevision};
 * when the session targets an existing land the live structure revision must
 * resolve and equal the session {@code baseStructureRevision}. Any mismatch
 * replies {@code command.land.confirm.stale} without touching the saga, so
 * replays of old messages, replaced or timed-out sessions, and structure
 * changes never produce a ledger row, a charge, or economy activity. An
 * unresolvable structure revision fails closed the same way instead of
 * passing an unknown target by default.
 *
 * <p>Replay guard: the first accepted token pair per live session is recorded;
 * a second click with the same pair on the same session is rejected without
 * touching the saga. The mark is scoped to the session instance (by
 * reference, so an equal-valued replacement session is never blocked) and is
 * released when the saga reports anything but success, so a rejected claim
 * can be confirmed again after fixing the cause. All replies go through
 * {@link ReplySink}, so Java chat and Bedrock fallback share the single
 * message pipeline and this handler never touches MiniMessage.
 *
 * <p>Threading: everything Bukkit-facing happens synchronously on the calling
 * thread; only the terminal reply runs on saga completion, using values
 * captured up front. Concurrent confirms for one player are serialized on a
 * private monitor so two racing clicks admit exactly one saga entry.
 */
public final class ConfirmCommandHandler implements LandCommand.Handler {

    private final SelectionSessionManager selections;
    private final ClaimCommandHandler.ClaimRunner runner;
    private final Supplier<CompletionStage<?>> recoveryScan;
    private final SelectionStructureRevisionLookup structures;

    private final Object guard = new Object();
    private final java.util.Map<UUID, ConsumedMark> consumed = new java.util.HashMap<>();

    private record ConsumedMark(SelectionSession session, long generation, long revision) {
    }

    /**
     * @param selections live selection registry; never null
     * @param runner saga entry point; null means claiming is not wired yet and
     *        every attempt replies {@code claim.unavailable} without side effects
     * @param recoveryScan startup recovery scan; null means no gate (tests)
     * @param structures live structure revision lookup; never null (use the
     *        unavailable seam when no source exists, which fails targeted
     *        confirmations closed)
     */
    public ConfirmCommandHandler(SelectionSessionManager selections,
            ClaimCommandHandler.ClaimRunner runner,
            Supplier<CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures) {
        this.selections = Objects.requireNonNull(selections, "selections");
        this.runner = runner;
        this.recoveryScan = recoveryScan;
        this.structures = Objects.requireNonNull(structures, "structures");
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.confirm.console", Map.of());
            return;
        }
        UUID actor = player.getUniqueId();
        if (runner == null) {
            sink.reply("command.land.claim.failed", Map.of("reason", "claim.unavailable"));
            return;
        }
        String recoveryBlock = ClaimCommandHandler.recoveryBlockReason(recoveryScan);
        if (recoveryBlock != null) {
            sink.reply("command.land.claim.failed", Map.of("reason", recoveryBlock));
            return;
        }
        Long generation = generationOf(args);
        Long token = tokenOf(args);
        String displayName = displayNameOf(args);
        if (generation == null || token == null || displayName == null) {
            sink.reply("command.land.confirm.usage", Map.of());
            return;
        }
        Accepted accepted;
        synchronized (guard) {
            accepted = accept(actor, generation.longValue(), token.longValue());
            if (accepted == null) {
                sink.reply(staleOrMissingKey(actor), Map.of());
                return;
            }
        }
        ClaimRequest request;
        try {
            request = new ClaimRequest(OwnerRef.player(actor), actor, accepted.session().worldId(),
                    accepted.session().selectedChunks(), displayName, token, generation,
                    accepted.structureToken(), accepted.target());
        } catch (RuntimeException invalid) {
            releaseIfOurs(actor, accepted);
            sink.reply("command.land.confirm.usage", Map.of());
            return;
        }
        CompletionStage<ClaimOutcome> stage;
        try {
            stage = runner.claim(request);
        } catch (RuntimeException failure) {
            releaseIfOurs(actor, accepted);
            sink.reply("command.land.claim.failed", Map.of("reason", "claim.failed"));
            return;
        }
        if (stage == null) {
            releaseIfOurs(actor, accepted);
            sink.reply("command.land.claim.failed", Map.of("reason", "claim.failed"));
            return;
        }
        Accepted settled = accepted;
        String settledName = displayName;
        int chunkCount = accepted.session().selectedChunks().size();
        stage.whenComplete((outcome, failure) -> {
            if (!isDurableSuccess(outcome, failure)) {
                releaseIfOurs(actor, settled);
            }
            try {
                ClaimCommandHandler.replyOutcomeTo(sink, settledName, chunkCount, outcome, failure);
            } catch (RuntimeException ignored) {
                // Terminal reply path: never let a sink failure escape onto saga threads.
            }
        });
    }

    private record Accepted(SelectionSession session, LandId target, Long structureToken) {
    }

    /**
     * Validate the token pair against the live session and record the single-use
     * mark. Returns the captured session data on success, null on any
     * rejection. Callers must hold {@link #guard}.
     */
    private Accepted accept(UUID actor, long generation, long token) {
        SelectionSession session;
        try {
            Optional<SelectionSession> current = selections.sessionFor(actor);
            if (current.isEmpty()) {
                return null;
            }
            session = current.get();
        } catch (RuntimeException failure) {
            return null;
        }
        if (session.selectedChunks().isEmpty()) {
            return null;
        }
        if (session.sessionGeneration() != generation) {
            return null;
        }
        if (session.selectionRevision() != token) {
            return null;
        }
        ConsumedMark mark = consumed.get(actor);
        if (mark != null && mark.session() == session && mark.generation() == generation
                && mark.revision() == token) {
            return null;
        }
        LandId target = session.targetLandId().orElse(null);
        Long structureToken = null;
        if (target != null) {
            OptionalLong live;
            try {
                live = structures.currentRevision(target);
            } catch (RuntimeException failure) {
                return null;
            }
            if (live.isEmpty() || live.getAsLong() < 0
                    || live.getAsLong() != session.baseStructureRevision()) {
                return null;
            }
            structureToken = session.baseStructureRevision();
        }
        consumed.put(actor, new ConsumedMark(session, generation, token));
        return new Accepted(session, target, structureToken);
    }

    /**
     * Missing sessions (timeout, cleanup, never selected) report no
     * selection; every other rejection is a stale confirmation prompting a
     * fresh review. The lookup itself never throws.
     */
    private String staleOrMissingKey(UUID actor) {
        try {
            if (selections.sessionFor(actor).isEmpty()) {
                return "command.land.confirm.no_selection";
            }
        } catch (RuntimeException ignored) {
            return "command.land.confirm.no_selection";
        }
        return "command.land.confirm.stale";
    }

    private void releaseIfOurs(UUID actor, Accepted accepted) {
        synchronized (guard) {
            ConsumedMark mark = consumed.get(actor);
            if (mark != null && mark.session() == accepted.session()
                    && mark.generation() == accepted.session().sessionGeneration()
                    && mark.revision() == accepted.session().selectionRevision()) {
                consumed.remove(actor);
            }
        }
    }

    private static boolean isDurableSuccess(ClaimOutcome outcome, Throwable failure) {
        if (failure != null || outcome == null) {
            return false;
        }
        return switch (outcome.status()) {
            case SUCCESS, DEGRADED -> true;
            case REJECTED, FAILED -> false;
        };
    }

    /**
     * Parse {@code args[1]} as a non-negative generation token, or null when
     * the token is missing, non-numeric, or negative.
     */
    private static Long generationOf(String[] args) {
        return parseNonNegative(args, 1);
    }

    /**
     * Parse {@code args[2]} as a non-negative revision token, or null when the
     * token is missing, non-numeric, or negative.
     */
    private static Long tokenOf(String[] args) {
        return parseNonNegative(args, 2);
    }

    private static Long parseNonNegative(String[] args, int index) {
        if (args == null || args.length <= index) {
            return null;
        }
        String raw = args[index];
        if (raw == null || raw.isBlank()) {
            return null;
        }
        long parsed;
        try {
            parsed = Long.parseLong(raw.trim());
        } catch (NumberFormatException invalid) {
            return null;
        }
        if (parsed < 0) {
            return null;
        }
        return parsed;
    }

    /**
     * Join the tail args after the token pair into a display name, or null when no
     * usable name was given. Validation reuses the land-name contract so blank
     * input and control characters fail closed to usage.
     */
    private static String displayNameOf(String[] args) {
        if (args == null || args.length < 4) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 3; i < args.length; i++) {
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
        if (candidate.isBlank()) {
            return null;
        }
        return candidate;
    }
}

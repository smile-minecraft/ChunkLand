package com.smile.chunkland.command;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.claim.ClaimOutcome;
import com.smile.chunkland.claim.ShrinkOutcome;
import com.smile.chunkland.claim.ShrinkRequest;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land shrink} and {@code /land unclaim} handler: the
 * single command entry point into the formal shrink saga.
 *
 * <p>Both aliases share one instance and one flow: the sender must be a
 * player standing on an existing land (resolved through the injected
 * current-land view against the immutable snapshot) with a live selection
 * session that targets that same land and holds at least one delta chunk; a
 * {@link ShrinkRequest} is built from the session delta plus the full token
 * triple (selection revision, session generation and the session base
 * structure revision) and handed to the saga. The saga owns the operation id
 * end to end, mirroring the expand flow.
 *
 * <p>Owner resolution follows the already-published target snapshot: when the
 * target land is Server-owned the request carries the Server owner so a
 * steward authorised by the management gate passes validation with a zero
 * refund; when the target is player-owned the request carries the actor's
 * player owner so a non-owner still fails the validator's owner check even
 * if the gate were bypassed. A missing, unresolvable or unknown-kind target
 * owner fails closed without touching the saga.
 *
 * <p>Fail-closed contract: console senders, wilderness positions, missing or
 * empty selections, sessions without a target, a session target that does not
 * match the land under the sender, and any token mismatch reply without
 * touching the saga (no ledger row, no refund). A missing saga replies
 * {@code shrink.unavailable}. While the startup recovery scan is still in
 * flight the handler replies {@code shrink.recovery_pending}, and a failed
 * scan replies {@code shrink.recovery_failed} permanently until restart.
 * {@code DEGRADED} replies success because the durable domain commit already
 * won and startup recovery completes the publish. A {@code delete_required}
 * rejection guides the player towards {@code /land delete} instead of
 * writing an empty land.
 *
 * <p>Threading: everything Bukkit-facing happens synchronously on the calling
 * thread; only the reply runs on saga completion, using values captured up
 * front, so the callback never touches Bukkit objects. No World or Chunk is
 * ever loaded here — only the selection session and the current-land view.
 */
public final class ShrinkCommandHandler implements LandCommand.Handler {

    /** Saga entry point; kept as a seam so tests can observe the request. */
    @FunctionalInterface
    public interface ShrinkRunner {
        CompletionStage<ShrinkOutcome> shrink(ShrinkRequest request);
    }

    private final SelectionSessionManager selections;
    private final ShrinkRunner runner;
    private final Supplier<CompletionStage<?>> recoveryScan;
    private final Function<CommandSender, Optional<LandId>> currentLand;
    private final Function<LandId, Optional<OwnerRef>> targetOwner;
    private final String subcommand;
    private final Currency refundCurrency;

    /**
     * @param selections live selection registry; never null
     * @param runner saga entry point; null means shrink is not wired yet
     * @param recoveryScan startup recovery scan; null means no gate (tests)
     * @param currentLand land under the sender; null skips the position check
     *        (tests that drive the saga tokens directly)
     * @param targetOwner owner of the target land from the already-published
     *        snapshot; null keeps the legacy player-only path (Server Land
     *        then fails closed in the validator with {@code owner_mismatch})
     * @param subcommand alias used for message keys ({@code shrink} or
     *        {@code unclaim}); null defaults to {@code shrink}
     * @param refundCurrency currency used to render the refund in the success
     *        and compensation-pending replies; null keeps the legacy raw
     *        minor-unit fallback (unwired maps and legacy constructors)
     */
    public ShrinkCommandHandler(SelectionSessionManager selections, ShrinkRunner runner,
            Supplier<CompletionStage<?>> recoveryScan,
            Function<CommandSender, Optional<LandId>> currentLand,
            Function<LandId, Optional<OwnerRef>> targetOwner,
            String subcommand, Currency refundCurrency) {
        this.selections = Objects.requireNonNull(selections, "selections");
        this.runner = runner;
        this.recoveryScan = recoveryScan;
        this.currentLand = currentLand;
        this.targetOwner = targetOwner;
        this.subcommand = subcommand == null || subcommand.isBlank() ? "shrink" : subcommand;
        this.refundCurrency = refundCurrency;
    }

    /**
     * @param selections live selection registry; never null
     * @param runner saga entry point; null means shrink is not wired yet
     * @param recoveryScan startup recovery scan; null means no gate (tests)
     * @param currentLand land under the sender; null skips the position check
     *        (tests that drive the saga tokens directly)
     * @param targetOwner owner of the target land from the already-published
     *        snapshot; null keeps the legacy player-only path (Server Land
     *        then fails closed in the validator with {@code owner_mismatch})
     * @param subcommand alias used for message keys ({@code shrink} or
     *        {@code unclaim}); null defaults to {@code shrink}
     */
    public ShrinkCommandHandler(SelectionSessionManager selections, ShrinkRunner runner,
            Supplier<CompletionStage<?>> recoveryScan,
            Function<CommandSender, Optional<LandId>> currentLand,
            Function<LandId, Optional<OwnerRef>> targetOwner,
            String subcommand) {
        this(selections, runner, recoveryScan, currentLand, targetOwner, subcommand, null);
    }

    /**
     * @param selections live selection registry; never null
     * @param runner saga entry point; null means shrink is not wired yet
     * @param recoveryScan startup recovery scan; null means no gate (tests)
     * @param currentLand land under the sender; null skips the position check
     *        (tests that drive the saga tokens directly)
     * @param targetOwner owner of the target land from the already-published
     *        snapshot; null keeps the legacy player-only path
     */
    public ShrinkCommandHandler(SelectionSessionManager selections, ShrinkRunner runner,
            Supplier<CompletionStage<?>> recoveryScan,
            Function<CommandSender, Optional<LandId>> currentLand,
            Function<LandId, Optional<OwnerRef>> targetOwner) {
        this(selections, runner, recoveryScan, currentLand, targetOwner, "shrink");
    }

    /**
     * @param selections live selection registry; never null
     * @param runner saga entry point; null means shrink is not wired yet
     * @param recoveryScan startup recovery scan; null means no gate (tests)
     * @param currentLand land under the sender; null skips the position check
     *        (tests that drive the saga tokens directly)
     * @param subcommand alias used for message keys ({@code shrink} or
     *        {@code unclaim}); null defaults to {@code shrink}
     */
    public ShrinkCommandHandler(SelectionSessionManager selections, ShrinkRunner runner,
            Supplier<CompletionStage<?>> recoveryScan,
            Function<CommandSender, Optional<LandId>> currentLand,
            String subcommand) {
        this(selections, runner, recoveryScan, currentLand, null, subcommand);
    }

    /** Alias entry that keeps the canonical message keys. */
    public ShrinkCommandHandler(SelectionSessionManager selections, ShrinkRunner runner,
            Supplier<CompletionStage<?>> recoveryScan,
            Function<CommandSender, Optional<LandId>> currentLand) {
        this(selections, runner, recoveryScan, currentLand, "shrink");
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.shrink.console", Map.of());
            return;
        }
        UUID actor = player.getUniqueId();
        if (runner == null) {
            sink.reply("command.land.shrink.failed", Map.of("reason", "shrink.unavailable"));
            return;
        }
        String recoveryBlock = ClaimCommandHandler.recoveryBlockReason(recoveryScan);
        if (recoveryBlock != null) {
            String reason = recoveryBlock.replace("claim.", "shrink.");
            sink.reply("command.land.shrink.failed", Map.of("reason", reason));
            return;
        }
        SelectionSession session;
        try {
            Optional<SelectionSession> current = selections.sessionFor(actor);
            if (current.isEmpty()) {
                sink.reply("command.land.shrink.no_selection", Map.of());
                return;
            }
            session = current.get();
        } catch (RuntimeException failure) {
            sink.reply("command.land.shrink.failed", Map.of("reason", "shrink.failed"));
            return;
        }
        Set<ChunkKey> delta = session.selectedChunks();
        if (delta.isEmpty()) {
            sink.reply("command.land.shrink.no_selection", Map.of());
            return;
        }
        Optional<LandId> target = session.targetLandId();
        if (target.isEmpty() || target.get() == null) {
            sink.reply("command.land.shrink.no_target", Map.of());
            return;
        }
        if (currentLand != null) {
            Optional<LandId> standing;
            try {
                standing = currentLand.apply(sender);
            } catch (RuntimeException failure) {
                standing = Optional.empty();
            }
            if (standing == null || standing.isEmpty() || !target.get().equals(standing.get())) {
                sink.reply("command.land.shrink.no_target", Map.of());
                return;
            }
        }
        ShrinkRequest request;
        try {
            request = new ShrinkRequest(resolveRequestOwner(target.get(), actor), actor,
                    session.worldId(), target.get(), delta, session.selectionRevision(),
                    session.sessionGeneration(), session.baseStructureRevision());
        } catch (RuntimeException invalid) {
            sink.reply("command.land.shrink.failed", Map.of("reason", "shrink.failed"));
            return;
        }
        int chunkCount = delta.size();
        CompletionStage<ShrinkOutcome> stage;
        try {
            stage = runner.shrink(request);
        } catch (RuntimeException failure) {
            sink.reply("command.land.shrink.failed", Map.of("reason", "shrink.failed"));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.shrink.failed", Map.of("reason", "shrink.failed"));
            return;
        }
        stage.whenComplete((outcome, failure) -> replyOutcome(sink, chunkCount, outcome, failure,
                refundCurrency));
    }

    private OwnerRef resolveRequestOwner(LandId target, UUID actor) {
        if (targetOwner == null) {
            return OwnerRef.player(actor);
        }
        Optional<OwnerRef> resolved;
        try {
            resolved = targetOwner.apply(target);
        } catch (RuntimeException unresolved) {
            throw new IllegalStateException("shrink target owner unavailable", unresolved);
        }
        if (resolved == null || resolved.isEmpty() || resolved.get() == null) {
            throw new IllegalStateException("shrink target owner unavailable");
        }
        OwnerRef snapshotOwner = resolved.get();
        if (snapshotOwner instanceof OwnerRef.ServerOwnerRef) {
            return OwnerRef.server();
        }
        if (snapshotOwner instanceof OwnerRef.PlayerOwnerRef) {
            return OwnerRef.player(actor);
        }
        throw new IllegalStateException("shrink target owner unavailable");
    }

    private static void replyOutcome(ReplySink sink, int chunkCount,
            ShrinkOutcome outcome, Throwable failure, Currency refundCurrency) {
        try {
            if (failure != null || outcome == null) {
                sink.reply("command.land.shrink.failed", Map.of("reason", "shrink.failed"));
                return;
            }
            switch (outcome.status()) {
                case SUCCESS, DEGRADED -> {
                    if (isNegativeRefund(outcome.refundMinorUnits())) {
                        sink.reply("command.land.shrink.failed", Map.of("reason", "shrink.failed"));
                    } else {
                        sink.reply("command.land.shrink.success",
                                Map.of("chunk_count", chunkCount,
                                        "refund", formatRefund(outcome.refundMinorUnits(), refundCurrency)));
                    }
                }
                case COMPENSATION_PENDING -> {
                    if (isNegativeRefund(outcome.refundMinorUnits())) {
                        sink.reply("command.land.shrink.failed", Map.of("reason", "shrink.failed"));
                    } else {
                        sink.reply("command.land.shrink.compensation_pending",
                                Map.of("chunk_count", chunkCount,
                                        "refund", formatRefund(outcome.refundMinorUnits(), refundCurrency)));
                    }
                }
                case NEEDS_RECONCILIATION -> sink.reply("command.land.shrink.failed",
                        Map.of("reason", "shrink.reconciliation"));
                case REJECTED -> {
                    if ("shrink.delete_required".equals(outcome.diagnosticKey())) {
                        sink.reply("command.land.shrink.delete_required", Map.of());
                    } else {
                        sink.reply("command.land.shrink.rejected",
                                Map.of("reason", outcome.diagnosticKey()));
                    }
                }
                case FAILED -> sink.reply("command.land.shrink.failed",
                        Map.of("reason", outcome.diagnosticKey()));
            }
        } catch (RuntimeException ignored) {
            // Terminal reply path: never let a sink failure escape onto saga threads.
        }
    }

    /** Reply mapping for claim-style outcomes, kept for wiring probes. */
    static void replyClaimOutcome(ReplySink sink, int chunkCount, ClaimOutcome outcome, Throwable failure) {
        replyOutcome(sink, chunkCount,
                outcome == null ? null : switch (outcome.status()) {
                    case SUCCESS -> ShrinkOutcome.success(outcome.landId(), 0L);
                    case DEGRADED -> ShrinkOutcome.degraded(outcome.landId(), 0L, outcome.diagnosticKey());
                    case REJECTED -> ShrinkOutcome.rejected(outcome.diagnosticKey());
                    case FAILED -> ShrinkOutcome.failed(outcome.diagnosticKey());
                }, failure, null);
    }

    /**
     * Renders the refund var for the reply.
     *
     * <p>With a configured currency the durable minor units become readable
     * major units ({@code 50} at scale {@code 2} renders as {@code "0.50 EMC"}).
     * Without one (unwired maps and legacy constructors) the raw minor-unit
     * long is kept, so a half-wired server never invents a currency.
     *
     * <p>A negative amount fails closed in the caller (a {@code shrink.failed}
     * reply, never a success with a bogus refund): the residual catch below
     * only preserves the terminal reply against an unreachable display
     * failure, because the typed currency already guarantees a valid scale
     * and the caller already rejected negatives.
     */
    private static boolean isNegativeRefund(Long refundMinorUnits) {
        return refundMinorUnits != null && refundMinorUnits < 0;
    }

    private static Object formatRefund(Long refundMinorUnits, Currency refundCurrency) {
        long minorUnits = refundMinorUnits == null ? 0L : refundMinorUnits;
        if (refundCurrency == null) {
            return minorUnits;
        }
        try {
            return MoneyDisplay.format(minorUnits, refundCurrency);
        } catch (RuntimeException invalid) {
            return minorUnits;
        }
    }
}

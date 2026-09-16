package com.smile.chunkland.command;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.claim.DeleteOutcome;
import com.smile.chunkland.claim.DeleteRequest;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land delete} handler: the single command entry point
 * into the formal land-delete saga.
 *
 * <p>Two-step confirmation: a bare {@code /land delete} resolves the land
 * under the sender and replies a confirm prompt carrying the live structure
 * revision; {@code /land delete confirm <revision>} builds a {@link
 * DeleteRequest} with that token and hands it to the saga. The saga owns the
 * operation id end to end and revalidates the token against the live source,
 * so a land that changed between prompt and confirm fails closed with a stale
 * reply instead of deleting something the actor never reviewed.
 *
 * <p>Owner resolution follows the already-published target snapshot: when the
 * target land is Server-owned the request carries the Server owner so a
 * steward authorised by the management gate passes validation with a zero
 * refund; when the target is player-owned the request carries the actor's
 * player owner so a non-owner still fails the validator's owner check even
 * if the gate were bypassed. A missing, unresolvable or unknown-kind target
 * owner fails closed without touching the saga.
 *
 * <p>Fail-closed contract: console senders, wilderness positions and
 * unresolvable revisions reply without touching the saga (no ledger row, no
 * refund). A missing saga replies {@code delete.unavailable}. While the
 * startup recovery scan is still in flight the handler replies {@code
 * delete.recovery_pending}, and a failed scan replies {@code
 * delete.recovery_failed} permanently until restart. {@code DEGRADED} replies
 * degraded because the durable delete already won and startup recovery
 * completes the publish and the refund.
 *
 * <p>Threading: everything Bukkit-facing happens synchronously on the calling
 * thread; only the reply runs on saga completion, using values captured up
 * front, so the callback never touches Bukkit objects. No World or Chunk is
 * ever loaded here — only the current-land view and the revision lookup.
 */
public final class LandDeleteCommandHandler implements LandCommand.Handler {

    /** Saga entry point; kept as a seam so tests can observe the request. */
    @FunctionalInterface
    public interface DeleteRunner {
        CompletionStage<DeleteOutcome> delete(DeleteRequest request);
    }

    /** Read-only target view used to render the confirm and success replies. */
    public record TargetView(String displayName, UUID worldId, int chunkCount) {
        public TargetView {
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(worldId, "worldId");
            if (displayName.isBlank()) {
                throw new IllegalArgumentException("displayName must not be blank");
            }
            if (chunkCount < 1) {
                throw new IllegalArgumentException("chunkCount must be positive");
            }
        }
    }

    private final DeleteRunner runner;
    private final Supplier<CompletionStage<?>> recoveryScan;
    private final Function<CommandSender, Optional<LandId>> currentLand;
    private final Function<LandId, Optional<OwnerRef>> targetOwner;
    private final Function<LandId, Optional<TargetView>> targetView;
    private final SelectionStructureRevisionLookup structures;
    private final Currency refundCurrency;

    /**
     * @param runner saga entry point; null means delete is not wired yet
     * @param recoveryScan startup recovery scan; null means no gate (tests)
     * @param currentLand land under the sender; null skips the position check
     *        (tests that drive the saga tokens directly)
     * @param targetOwner owner of the target land from the already-published
     *        snapshot; null keeps the legacy player-only path (Server Land
     *        then fails closed in the validator with {@code owner_mismatch})
     * @param targetView display view of the target land from the
     *        already-published snapshot; null keeps raw fallbacks in replies
     * @param structures live structure revision lookup; null fails the confirm
     *        prompt closed (tests without a revision source)
     * @param refundCurrency currency used to render the refund in the success
     *        and compensation-pending replies; null keeps the legacy raw
     *        minor-unit fallback
     */
    public LandDeleteCommandHandler(DeleteRunner runner,
            Supplier<CompletionStage<?>> recoveryScan,
            Function<CommandSender, Optional<LandId>> currentLand,
            Function<LandId, Optional<OwnerRef>> targetOwner,
            Function<LandId, Optional<TargetView>> targetView,
            SelectionStructureRevisionLookup structures,
            Currency refundCurrency) {
        this.runner = runner;
        this.recoveryScan = recoveryScan;
        this.currentLand = currentLand;
        this.targetOwner = targetOwner;
        this.targetView = targetView;
        this.structures = structures;
        this.refundCurrency = refundCurrency;
    }

    /**
     * @param runner saga entry point; null means delete is not wired yet
     * @param recoveryScan startup recovery scan; null means no gate (tests)
     * @param currentLand land under the sender; null skips the position check
     * @param targetOwner owner of the target land from the already-published
     *        snapshot; null keeps the legacy player-only path
     */
    public LandDeleteCommandHandler(DeleteRunner runner,
            Supplier<CompletionStage<?>> recoveryScan,
            Function<CommandSender, Optional<LandId>> currentLand,
            Function<LandId, Optional<OwnerRef>> targetOwner) {
        this(runner, recoveryScan, currentLand, targetOwner, null, null, null);
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.delete.console", Map.of());
            return;
        }
        UUID actor = player.getUniqueId();
        if (runner == null) {
            sink.reply("command.land.delete.failed", Map.of("reason", "delete.unavailable"));
            return;
        }
        String recoveryBlock = ClaimCommandHandler.recoveryBlockReason(recoveryScan);
        if (recoveryBlock != null) {
            String reason = recoveryBlock.replace("claim.", "delete.");
            sink.reply("command.land.delete.failed", Map.of("reason", reason));
            return;
        }
        Optional<LandId> standing = resolveStanding(sender, sink);
        if (standing == null) {
            return;
        }
        if (standing.isEmpty() || standing.get() == null) {
            sink.reply("command.land.delete.no_target", Map.of());
            return;
        }
        LandId target = standing.get();
        if (isConfirm(args)) {
            Long token = confirmToken(args);
            if (token == null) {
                sink.reply("command.land.delete.usage", Map.of());
                return;
            }
            execute(actor, target, token.longValue(), sink);
            return;
        }
        prompt(target, sink);
    }

    private Optional<LandId> resolveStanding(CommandSender sender, ReplySink sink) {
        if (currentLand == null) {
            sink.reply("command.land.delete.no_target", Map.of());
            return Optional.empty();
        }
        Optional<LandId> standing;
        try {
            standing = currentLand.apply(sender);
        } catch (RuntimeException failure) {
            standing = Optional.empty();
        }
        if (standing == null) {
            standing = Optional.empty();
        }
        return standing;
    }

    private void prompt(LandId target, ReplySink sink) {
        OptionalLong live = liveRevision(target);
        if (live.isEmpty() || live.getAsLong() < 0) {
            sink.reply("command.land.delete.failed", Map.of("reason", "delete.unavailable"));
            return;
        }
        sink.reply("command.land.delete.confirm", Map.of("revision", live.getAsLong()));
    }

    private OptionalLong liveRevision(LandId target) {
        if (structures == null) {
            return OptionalLong.empty();
        }
        try {
            return structures.currentRevision(target);
        } catch (RuntimeException failure) {
            return OptionalLong.empty();
        }
    }

    private void execute(UUID actor, LandId target, long structureRevision, ReplySink sink) {
        OwnerRef owner;
        try {
            owner = resolveRequestOwner(target, actor);
        } catch (RuntimeException unresolved) {
            sink.reply("command.land.delete.failed", Map.of("reason", "delete.failed"));
            return;
        }
        TargetView view = viewOf(target);
        if (view == null) {
            sink.reply("command.land.delete.failed", Map.of("reason", "delete.failed"));
            return;
        }
        DeleteRequest request;
        try {
            request = new DeleteRequest(owner, actor, view.worldId(), target, structureRevision);
        } catch (RuntimeException invalid) {
            sink.reply("command.land.delete.failed", Map.of("reason", "delete.failed"));
            return;
        }
        CompletionStage<DeleteOutcome> stage;
        try {
            stage = runner.delete(request);
        } catch (RuntimeException failure) {
            sink.reply("command.land.delete.failed", Map.of("reason", "delete.failed"));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.delete.failed", Map.of("reason", "delete.failed"));
            return;
        }
        stage.whenComplete((outcome, failure) -> replyOutcome(sink, view, outcome, failure,
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
            throw new IllegalStateException("delete target owner unavailable", unresolved);
        }
        if (resolved == null || resolved.isEmpty() || resolved.get() == null) {
            throw new IllegalStateException("delete target owner unavailable");
        }
        OwnerRef snapshotOwner = resolved.get();
        if (snapshotOwner instanceof OwnerRef.ServerOwnerRef) {
            return OwnerRef.server();
        }
        if (snapshotOwner instanceof OwnerRef.PlayerOwnerRef) {
            return OwnerRef.player(actor);
        }
        throw new IllegalStateException("delete target owner unavailable");
    }

    private TargetView viewOf(LandId target) {
        if (targetView == null) {
            return null;
        }
        try {
            Optional<TargetView> resolved = targetView.apply(target);
            if (resolved == null || resolved.isEmpty()) {
                return null;
            }
            return resolved.get();
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    private static boolean isConfirm(String[] args) {
        return args != null && args.length >= 2 && args[1] != null
                && args[1].equalsIgnoreCase("confirm");
    }

    private static Long confirmToken(String[] args) {
        if (args == null || args.length < 3) {
            return null;
        }
        String raw = args[2];
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

    private static void replyOutcome(ReplySink sink, TargetView view,
            DeleteOutcome outcome, Throwable failure, Currency refundCurrency) {
        try {
            if (failure != null || outcome == null) {
                sink.reply("command.land.delete.failed", Map.of("reason", "delete.failed"));
                return;
            }
            switch (outcome.status()) {
                case SUCCESS -> {
                    if (isNegativeRefund(outcome.refundMinorUnits())) {
                        sink.reply("command.land.delete.failed", Map.of("reason", "delete.failed"));
                    } else {
                        sink.reply("command.land.delete.success",
                                Map.of("land_name", displayName(view),
                                        "chunk_count", chunkCount(view, outcome),
                                        "refund", formatRefund(outcome.refundMinorUnits(), refundCurrency)));
                    }
                }
                case COMPENSATION_PENDING -> {
                    if (isNegativeRefund(outcome.refundMinorUnits())) {
                        sink.reply("command.land.delete.failed", Map.of("reason", "delete.failed"));
                    } else {
                        sink.reply("command.land.delete.compensation_pending",
                                Map.of("chunk_count", chunkCount(view, outcome),
                                        "refund", formatRefund(outcome.refundMinorUnits(), refundCurrency)));
                    }
                }
                case NEEDS_RECONCILIATION -> sink.reply("command.land.delete.failed",
                        Map.of("reason", "delete.reconciliation"));
                case DEGRADED -> sink.reply("command.land.delete.degraded",
                        Map.of("reason", outcome.diagnosticKey()));
                case REJECTED -> {
                    if ("structure.stale".equals(outcome.diagnosticKey())) {
                        sink.reply("command.land.delete.stale", Map.of());
                    } else {
                        sink.reply("command.land.delete.rejected",
                                Map.of("reason", outcome.diagnosticKey()));
                    }
                }
                case FAILED -> sink.reply("command.land.delete.failed",
                        Map.of("reason", outcome.diagnosticKey()));
            }
        } catch (RuntimeException ignored) {
            // Terminal reply path: never let a sink failure escape onto saga threads.
        }
    }

    /**
     * Renders the refund var for the reply.
     *
     * <p>With a configured currency the durable minor units become readable
     * major units. Without one the raw minor-unit long is kept, so a
     * half-wired server never invents a currency.
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

    private static Object displayName(TargetView view) {
        return view == null ? "land" : view.displayName();
    }

    private static Object chunkCount(TargetView view, DeleteOutcome outcome) {
        if (view != null) {
            return view.chunkCount();
        }
        return 0;
    }
}

package com.smile.chunkland.command;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.claim.ClaimOutcome;
import com.smile.chunkland.claim.ExpandRequest;
import com.smile.chunkland.claim.ExpandSaga;
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import java.util.LinkedHashSet;
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
 * Production {@code /land expand} handler: the single command entry point
 * into the formal {@link ExpandSaga}.
 *
 * <p>Flow: the sender must be a player standing on an existing land (resolved
 * through the injected current-land view against the immutable snapshot) with
 * a live selection session that targets that same land and holds at least one
 * delta chunk; an {@link ExpandRequest} is built from the session delta plus
 * the full token triple (selection revision, session generation and the
 * session base structure revision) and handed to the saga. The saga owns the
 * operation id end to end, mirroring the claim flow.
 *
 * <p>Fail-closed contract: console senders, wilderness positions, missing or
 * empty selections, sessions without a target, a session target that does not
 * match the land under the sender, and any token mismatch reply without
 * touching the saga (no ledger row, no charge). A missing saga replies
 * {@code expand.unavailable}. While the startup recovery scan is still in
 * flight the handler replies {@code expand.recovery_pending}, and a failed
 * scan replies {@code expand.recovery_failed} permanently until restart.
 * {@code DEGRADED} replies success because the durable domain commit already
 * won and startup recovery completes the publish to {@code ACTIVE}.
 *
 * <p>Threading: everything Bukkit-facing happens synchronously on the calling
 * thread; only the reply runs on saga completion, using values captured up
 * front, so the callback never touches Bukkit objects.
 */
public final class ExpandCommandHandler implements ManagementGatedHandler {

    /** Saga entry point; kept as a seam so tests can observe the request. */
    @FunctionalInterface
    public interface ExpandRunner {
        CompletionStage<ClaimOutcome> expand(ExpandRequest request);
    }

    /**
     * Read-only lookup of a target land's immutable chunk set.
     *
     * <p>Expansion deltas are the selection minus the chunks the target
     * already owns: a wand rectangle anchored on the actor's own land always
     * covers that land, and handing those chunks to the saga would trip its
     * overlap check. The lookup reads an already-built immutable registry
     * snapshot; no World, Chunk, SQL or network access happens here. An empty
     * result means the target cannot be resolved, which fails closed.
     */
    @FunctionalInterface
    public interface TargetChunkLookup {
        Optional<Set<ChunkKey>> chunksOf(LandId targetLandId);
    }

    private final SelectionSessionManager selections;
    private final ExpandRunner runner;
    private final Supplier<CompletionStage<?>> recoveryScan;
    private final Function<CommandSender, Optional<LandId>> currentLand;
    /** Null keeps the legacy pass-through delta used by direct-token tests. */
    private final TargetChunkLookup targetChunks;
    /**
     * Owner of the target land from the already-published immutable
     * snapshot; null keeps the legacy actor-as-owner used by direct-token
     * tests. Production wires the shared target-owner view so Server-owned
     * targets carry the Server owner (like shrink and delete) instead of
     * the actor's player owner.
     */
    private final Function<LandId, Optional<OwnerRef>> targetOwner;

    /**
     * @param selections live selection registry; never null
     * @param runner saga entry point; null means expansion is not wired yet
     * @param recoveryScan startup recovery scan; null means no gate (tests)
     * @param currentLand land under the sender; null skips the position check
     *        (tests that drive the saga tokens directly)
     */
    public ExpandCommandHandler(SelectionSessionManager selections, ExpandRunner runner,
            Supplier<CompletionStage<?>> recoveryScan,
            Function<CommandSender, Optional<LandId>> currentLand) {
        this(selections, runner, recoveryScan, currentLand, null);
    }

    /**
     * Production constructor: the delta becomes {@code selection - target
     * chunks}, so only the wilderness the actor is adding reaches the saga.
     * An unknown target fails closed instead of sending the whole rectangle.
     */
    public ExpandCommandHandler(SelectionSessionManager selections, ExpandRunner runner,
            Supplier<CompletionStage<?>> recoveryScan,
            Function<CommandSender, Optional<LandId>> currentLand,
            TargetChunkLookup targetChunks) {
        this(selections, runner, recoveryScan, currentLand, targetChunks, null);
    }

    /**
     * Namespace-aware constructor: the saga request owner comes from the
     * target snapshot's owner (Server targets carry the Server owner), so a
     * steward expanding Server Land passes the validator's owner check while
     * a player actor on a Server target keeps failing it.
     *
     * @param targetOwner owner view over the immutable snapshot; null keeps
     *                    the legacy actor-as-owner behaviour
     */
    public ExpandCommandHandler(SelectionSessionManager selections, ExpandRunner runner,
            Supplier<CompletionStage<?>> recoveryScan,
            Function<CommandSender, Optional<LandId>> currentLand,
            TargetChunkLookup targetChunks,
            Function<LandId, Optional<OwnerRef>> targetOwner) {
        this.selections = Objects.requireNonNull(selections, "selections");
        this.runner = runner;
        this.recoveryScan = recoveryScan;
        this.currentLand = currentLand;
        this.targetChunks = targetChunks;
        this.targetOwner = targetOwner;
    }

    @Override
    public void handleGated(CommandSender sender, String[] args, ReplySink sink,
            ManagementGateResolver.Request gateRequest) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (gateRequest == null) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.failed"));
            return;
        }
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.expand.console", Map.of());
            return;
        }
        UUID actor;
        try {
            actor = player.getUniqueId();
        } catch (RuntimeException failure) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.failed"));
            return;
        }
        if (actor == null || !actor.equals(gateRequest.actor())) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.failed"));
            return;
        }
        if (runner == null) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.unavailable"));
            return;
        }
        String recoveryBlock = ClaimCommandHandler.recoveryBlockReason(recoveryScan);
        if (recoveryBlock != null) {
            String reason = recoveryBlock.replace("claim.", "expand.");
            sink.reply("command.land.expand.failed", Map.of("reason", reason));
            return;
        }
        // Re-run the shared domain gate from the authorised snapshot with the
        // same action and inputs: the dispatcher already allowed this exact
        // request, so anything but ALLOW here fails closed without touching
        // the saga.
        try {
            boolean allowed = ManagementPermissionGate.check(
                    gateRequest.actor(),
                    gateRequest.landId(),
                    ProtectionActionType.EXPAND_LAND,
                    gateRequest.snapshot(),
                    gateRequest.adminBypass(),
                    gateRequest.serverLandSteward(),
                    gateRequest.provider()).outcome() == PermissionState.ALLOW;
            if (!allowed) {
                sink.reply("command.land.expand.failed", Map.of("reason", "expand.failed"));
                return;
            }
        } catch (RuntimeException denied) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.failed"));
            return;
        }
        LandRegistry gateSnapshot = gateRequest.snapshot();
        LandSnapshot gateTarget = gateSnapshot.land(gateRequest.landId());
        if (gateTarget == null || gateTarget.ownerRef() == null || gateTarget.chunks() == null) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.unknown_land"));
            return;
        }
        SelectionSession session;
        try {
            Optional<SelectionSession> current = selections.sessionFor(actor);
            if (current.isEmpty()) {
                sink.reply("command.land.expand.no_selection", Map.of());
                return;
            }
            session = current.get();
        } catch (RuntimeException failure) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.failed"));
            return;
        }
        Optional<LandId> sessionTarget = session.targetLandId();
        if (sessionTarget.isEmpty() || sessionTarget.get() == null
                || !gateRequest.landId().equals(sessionTarget.get())) {
            // The authorised gate target wins over the session claim: a
            // session pointing anywhere else fails closed.
            sink.reply("command.land.expand.no_target", Map.of());
            return;
        }
        // Standing position resolves against the authorised snapshot, never a
        // second live read: only the allocation-free chunk index on the gate
        // snapshot is touched.
        Optional<LandId> standing;
        try {
            standing = PluginManagementGateResolver.TargetLandResolver.currentLocation()
                    .resolveTarget(sender, ProtectionActionType.EXPAND_LAND, args, gateSnapshot);
        } catch (RuntimeException failure) {
            standing = Optional.empty();
        }
        if (standing == null || standing.isEmpty()
                || !gateRequest.landId().equals(standing.get())) {
            sink.reply("command.land.expand.no_target", Map.of());
            return;
        }
        Set<ChunkKey> selected = session.selectedChunks();
        if (selected.isEmpty()) {
            sink.reply("command.land.expand.no_selection", Map.of());
            return;
        }
        Set<ChunkKey> wilderness = new LinkedHashSet<>(selected);
        wilderness.removeAll(gateTarget.chunks());
        if (wilderness.isEmpty()) {
            sink.reply("command.land.expand.no_selection", Map.of());
            return;
        }
        // Namespace tripwire: the live target must still carry the authorised
        // owner namespace. Any conversion between the gate snapshot and this
        // handler (Player to Server, Server to Player, or the land vanishing)
        // denies fail-closed before the saga runs. This read is deny-only —
        // the decision basis stays the gate snapshot — while mutations past
        // this point stay guarded by the saga validator's owner check and the
        // atomic commit's owner and structure-revision checks.
        if (!liveOwnerMatchesGate(gateTarget)) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.owner_mismatch"));
            return;
        }
        ExpandRequest request;
        try {
            request = new ExpandRequest(gateRequestOwner(gateTarget, actor), actor, session.worldId(),
                    gateRequest.landId(), wilderness, session.selectionRevision(),
                    session.sessionGeneration(), session.baseStructureRevision());
        } catch (RuntimeException invalid) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.failed"));
            return;
        }
        int chunkCount = wilderness.size();
        CompletionStage<ClaimOutcome> stage;
        try {
            stage = runner.expand(request);
        } catch (RuntimeException failure) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.failed"));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.failed"));
            return;
        }
        stage.whenComplete((outcome, failure) -> replyOutcome(sink, chunkCount, outcome, failure));
    }

    /**
     * Deny-only cross-check of the live target owner against the authorised
     * gate owner. An unresolvable live target, a missing owner view, a lookup
     * failure or any owner-key divergence denies; only an exact namespace
     * match lets the gated execution proceed.
     */
    private boolean liveOwnerMatchesGate(LandSnapshot gateTarget) {
        if (targetOwner == null) {
            return false;
        }
        Optional<OwnerRef> live;
        try {
            live = targetOwner.apply(gateTarget.id());
        } catch (RuntimeException unresolved) {
            return false;
        }
        if (live == null || live.isEmpty() || live.get() == null) {
            return false;
        }
        return live.get().key().equals(gateTarget.ownerRef().key());
    }

    /**
     * Request owner from the authorised gate target: a Server-owned target
     * carries the Server owner so steward expansions pass validation, while
     * a player-owned target carries the actor so a non-owner keeps failing
     * the validator's owner check.
     */
    private static OwnerRef gateRequestOwner(LandSnapshot gateTarget, UUID actor) {
        OwnerRef snapshotOwner = gateTarget.ownerRef();
        if (snapshotOwner instanceof OwnerRef.ServerOwnerRef) {
            return OwnerRef.server();
        }
        if (snapshotOwner instanceof OwnerRef.PlayerOwnerRef) {
            return OwnerRef.player(actor);
        }
        throw new IllegalStateException("expand target owner unavailable");
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.expand.console", Map.of());
            return;
        }
        UUID actor = player.getUniqueId();
        if (runner == null) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.unavailable"));
            return;
        }
        String recoveryBlock = ClaimCommandHandler.recoveryBlockReason(recoveryScan);
        if (recoveryBlock != null) {
            String reason = recoveryBlock.replace("claim.", "expand.");
            sink.reply("command.land.expand.failed", Map.of("reason", reason));
            return;
        }
        SelectionSession session;
        try {
            Optional<SelectionSession> current = selections.sessionFor(actor);
            if (current.isEmpty()) {
                sink.reply("command.land.expand.no_selection", Map.of());
                return;
            }
            session = current.get();
        } catch (RuntimeException failure) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.failed"));
            return;
        }
        Set<ChunkKey> selected = session.selectedChunks();
        if (selected.isEmpty()) {
            sink.reply("command.land.expand.no_selection", Map.of());
            return;
        }
        Optional<LandId> target = session.targetLandId();
        if (target.isEmpty() || target.get() == null) {
            sink.reply("command.land.expand.no_target", Map.of());
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
                sink.reply("command.land.expand.no_target", Map.of());
                return;
            }
        }
        Set<ChunkKey> delta = selected;
        if (targetChunks != null) {
            Optional<Set<ChunkKey>> owned;
            try {
                owned = targetChunks.chunksOf(target.get());
            } catch (RuntimeException failure) {
                owned = Optional.empty();
            }
            if (owned == null || owned.isEmpty()) {
                // The target vanished or cannot be resolved: never send the
                // whole rectangle to the saga as if it were free wilderness.
                sink.reply("command.land.expand.failed", Map.of("reason", "expand.unknown_land"));
                return;
            }
            Set<ChunkKey> wilderness = new LinkedHashSet<>(selected);
            wilderness.removeAll(owned.get());
            delta = wilderness;
            if (delta.isEmpty()) {
                sink.reply("command.land.expand.no_selection", Map.of());
                return;
            }
        }
        ExpandRequest request;
        try {
            request = new ExpandRequest(resolveRequestOwner(target.get(), actor), actor, session.worldId(),
                    target.get(), delta, session.selectionRevision(),
                    session.sessionGeneration(), session.baseStructureRevision());
        } catch (RuntimeException invalid) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.failed"));
            return;
        }
        int chunkCount = delta.size();
        CompletionStage<ClaimOutcome> stage;
        try {
            stage = runner.expand(request);
        } catch (RuntimeException failure) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.failed"));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.expand.failed", Map.of("reason", "expand.failed"));
            return;
        }
        stage.whenComplete((outcome, failure) -> replyOutcome(sink, chunkCount, outcome, failure));
    }

    /**
     * Owner for the saga request from the target snapshot's owner: a
     * Server-owned target carries the Server owner so steward expansions
     * pass validation, while a player-owned target carries the actor so a
     * non-owner keeps failing the validator's owner check. An unresolvable
     * target fails closed before the saga runs.
     */
    private OwnerRef resolveRequestOwner(LandId target, UUID actor) {
        if (targetOwner == null) {
            return OwnerRef.player(actor);
        }
        Optional<OwnerRef> resolved;
        try {
            resolved = targetOwner.apply(target);
        } catch (RuntimeException unresolved) {
            throw new IllegalStateException("expand target owner unavailable", unresolved);
        }
        if (resolved == null || resolved.isEmpty() || resolved.get() == null) {
            throw new IllegalStateException("expand target owner unavailable");
        }
        OwnerRef snapshotOwner = resolved.get();
        if (snapshotOwner instanceof OwnerRef.ServerOwnerRef) {
            return OwnerRef.server();
        }
        if (snapshotOwner instanceof OwnerRef.PlayerOwnerRef) {
            return OwnerRef.player(actor);
        }
        throw new IllegalStateException("expand target owner unavailable");
    }

    private static void replyOutcome(ReplySink sink, int chunkCount,
            ClaimOutcome outcome, Throwable failure) {
        try {
            if (failure != null || outcome == null) {
                sink.reply("command.land.expand.failed", Map.of("reason", "expand.failed"));
                return;
            }
            switch (outcome.status()) {
                case SUCCESS, DEGRADED -> sink.reply("command.land.expand.success",
                        Map.of("chunk_count", chunkCount));
                case REJECTED -> sink.reply("command.land.expand.rejected",
                        Map.of("reason", outcome.diagnosticKey()));
                case FAILED -> sink.reply("command.land.expand.failed",
                        Map.of("reason", outcome.diagnosticKey()));
            }
        } catch (RuntimeException ignored) {
            // Terminal reply path: never let a sink failure escape onto saga threads.
        }
    }
}

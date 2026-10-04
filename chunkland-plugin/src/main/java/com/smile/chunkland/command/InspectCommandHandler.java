package com.smile.chunkland.command;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Read-only {@code /land inspect} handler.
 *
 * <p>The handler reports the land under the sender's own position from the
 * same immutable snapshot the enforcement path reads: land identity
 * (id/name/owner/world/chunks/sublands/revisions), permission observables
 * (the {@code MANAGE_PERMISSION} gate verdict plus owner/server-land flags)
 * and limit observables supplied by the caller. The region-facing path
 * performs no mutation, economy, SQL, chunk-load or network access: every
 *  call re-reads the shared snapshot supplier exactly once, and an optional
 *  player argument resolves through {@link OfflinePlayerResolver} on its
 *  explicit async executor — the reply arrives on resolution completion using
 *  values captured up front, so the callback never touches server objects and
 *  never waits on the calling thread. That completion never replies
 *  directly: it hops through the injected {@link PlayerScheduler} to the
 *  sender's player thread first, so a resolver-executor thread never touches
 *  the {@link ReplySink} (production: Folia-unsafe {@code sendChat}); a
 *  retired scheduler drops the reply fail-closed.
 *
 * <p>Only callers that pass the gate see the land summary; console senders,
 * wilderness, unknown targets, unresolvable players and every failure stay
 * fail-closed on one generic denial without land-existence or player details,
 * so the response never probes whether a land or a name exists.
 */
public final class InspectCommandHandler implements LandCommand.Handler {

    /** Fail-closed async timeout for the optional player argument. */
    static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Limit observables for one owner. Implementations read memory-only
     * config snapshots; a failing or missing source yields an empty map and
     * the land summary is still reported without limit entries.
     */
    @FunctionalInterface
    public interface Limits {
        Map<String, Object> describe(OwnerRef owner);
    }

    private final Supplier<LandRegistry> snapshots;
    private final Supplier<PermissionContextProvider> providers;
    private final Limits limits;
    private final OfflinePlayerResolver players;
    private final PlayerScheduler scheduler;
    private final Function<UUID, Boolean> bypassStates;
    private final Function<UUID, String> ownerNames;

    /**
     * @param snapshots live immutable snapshot source; {@code null} or failing
     *                  reads fail closed
     * @param providers shared context provider source (the same instance the
     *                  enforcement path reads); {@code null} or failing reads
     *                  fail closed
     * @param limits limit observables; {@code null} reports the summary
     *               without limit entries
     * @param players async player resolution for the optional player
     *                argument; {@code null} fails that argument closed
     */
    public InspectCommandHandler(Supplier<LandRegistry> snapshots,
            Supplier<PermissionContextProvider> providers,
            Limits limits,
            OfflinePlayerResolver players) {
        this(snapshots, providers, limits, players, PlayerScheduler.direct());
    }

    /**
     * @param snapshots live immutable snapshot source; {@code null} or failing
     *                  reads fail closed
     * @param providers shared context provider source (the same instance the
     *                  enforcement path reads); {@code null} or failing reads
     *                  fail closed
     * @param limits limit observables; {@code null} reports the summary
     *               without limit entries
     * @param players async player resolution for the optional player
     *                argument; {@code null} fails that argument closed
     * @param scheduler player-thread hop for the async player-argument reply;
     *                  {@code null} replies inline on the completing thread
     */
    public InspectCommandHandler(Supplier<LandRegistry> snapshots,
            Supplier<PermissionContextProvider> providers,
            Limits limits,
            OfflinePlayerResolver players,
            PlayerScheduler scheduler) {
        this(snapshots, providers, limits, players, scheduler, null);
    }

    /**
     * @param snapshots live immutable snapshot source; {@code null} or failing
     *                  reads fail closed
     * @param providers shared context provider source (the same instance the
     *                  enforcement path reads); {@code null} or failing reads
     *                  fail closed
     * @param limits limit observables; {@code null} reports the summary
     *               without limit entries
     * @param players async player resolution for the optional player
     *                argument; {@code null} fails that argument closed
     * @param scheduler player-thread hop for the async player-argument reply;
     *                  {@code null} replies inline on the completing thread
     * @param bypassStates per-enable bypass memory read for the sender UUID;
     *                     {@code null} or failing reads resolve to {@code false}
     *                     (fail-closed). The permission node is never consulted:
     *                     only an explicit toggle flips the flag.
     */
    public InspectCommandHandler(Supplier<LandRegistry> snapshots,
            Supplier<PermissionContextProvider> providers,
            Limits limits,
            OfflinePlayerResolver players,
            PlayerScheduler scheduler,
            Function<UUID, Boolean> bypassStates) {
        this(snapshots, providers, limits, players, scheduler, bypassStates, null);
    }

    /**
     * @param ownerNames memory-only player-name lookup for the owner line;
     *                   {@code null}, a {@code null} answer or a failing
     *                   lookup shows the owner's UUID instead. Must never
     *                   block: it runs on the caller's region thread.
     */
    public InspectCommandHandler(Supplier<LandRegistry> snapshots,
            Supplier<PermissionContextProvider> providers,
            Limits limits,
            OfflinePlayerResolver players,
            PlayerScheduler scheduler,
            Function<UUID, Boolean> bypassStates,
            Function<UUID, String> ownerNames) {
        this.ownerNames = ownerNames;
        this.snapshots = snapshots;
        this.providers = providers;
        this.limits = limits;
        this.players = players;
        this.scheduler = scheduler == null ? PlayerScheduler.direct() : scheduler;
        this.bypassStates = bypassStates;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.inspect.denied", Map.of());
            return;
        }
        String playerArg = args == null || args.length < 2 ? null : args[1];
        if (args != null && args.length > 2) {
            sink.reply("command.land.inspect.usage", Map.of());
            return;
        }
        if (playerArg != null && playerArg.isBlank()) {
            playerArg = null;
        }
        UUID actor;
        try {
            actor = player.getUniqueId();
        } catch (RuntimeException failure) {
            sink.reply("command.land.inspect.denied", Map.of());
            return;
        }
        if (actor == null) {
            sink.reply("command.land.inspect.denied", Map.of());
            return;
        }
        LandRegistry snapshot = readSnapshot();
        PermissionContextProvider provider = readProvider();
        if (snapshot == null || provider == null) {
            sink.reply("command.land.inspect.denied", Map.of());
            return;
        }
        LandId target = resolveTarget(sender, args, snapshot);
        if (target == null) {
            sink.reply("command.land.inspect.denied", Map.of());
            return;
        }
        LandSnapshot land;
        try {
            land = snapshot.land(target);
        } catch (RuntimeException unresolved) {
            sink.reply("command.land.inspect.denied", Map.of());
            return;
        }
        if (land == null) {
            sink.reply("command.land.inspect.denied", Map.of());
            return;
        }
        boolean steward = readSteward(sender);
        boolean bypass = readBypass(actor);
        PermissionDecision gate;
        try {
            gate = ManagementPermissionGate.check(actor, target,
                    ProtectionActionType.MANAGE_PERMISSION, snapshot, bypass, steward,
                    provider);
        } catch (RuntimeException denied) {
            sink.reply("command.land.inspect.denied", Map.of());
            return;
        }
        if (gate == null || gate.outcome() != PermissionState.ALLOW) {
            sink.reply("command.land.inspect.denied", Map.of());
            return;
        }
        Map<String, Object> summary;
        try {
            summary = summarize(actor, land, steward);
        } catch (RuntimeException failure) {
            sink.reply("command.land.inspect.denied", Map.of());
            return;
        }
        if (playerArg == null) {
            sink.reply("command.land.inspect.result", summary);
            return;
        }
        if (players == null) {
            sink.reply("command.land.inspect.denied", Map.of());
            return;
        }
        CompletionStage<Optional<UUID>> resolved;
        try {
            resolved = players.resolveAsync(playerArg, RESOLVE_TIMEOUT);
        } catch (RuntimeException failure) {
            sink.reply("command.land.inspect.denied", Map.of());
            return;
        }
        if (resolved == null) {
            sink.reply("command.land.inspect.denied", Map.of());
            return;
        }
        final String wanted = playerArg;
        final Map<String, Object> captured = summary;
        final PlayerScheduler hop = scheduler;
        resolved.whenComplete((found, failure) -> {
            // The completion may arrive on the resolver executor: never touch
            // the sink here — hop to the player thread first. A retired
            // scheduler drops the reply fail-closed without leaking.
            try {
                hop.runForPlayer(player, () -> {
                    try {
                        if (failure != null || found == null || found.isEmpty()
                                || found.get() == null) {
                            sink.reply("command.land.inspect.denied", Map.of());
                            return;
                        }
                        Map<String, Object> withSubject = new HashMap<>(captured);
                        withSubject.put("playerUuid", found.get().toString());
                        withSubject.put("playerRef", wanted.strip());
                        sink.reply("command.land.inspect.result", Map.copyOf(withSubject));
                    } catch (RuntimeException replyFailure) {
                        // Terminal reply path: never let a sink failure escape.
                    }
                });
            } catch (RuntimeException retired) {
                // Scheduling itself failed: drop fail-closed without replying.
            }
        });
    }

    private Map<String, Object> summarize(UUID actor, LandSnapshot land, boolean steward) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("landId", land.id().value().toString());
        vars.put("landName", land.displayName());
        vars.put("owner", describeOwner(land.ownerRef()));
        vars.put("worldId", land.worldId().toString());
        vars.put("chunks", land.chunks().size());
        vars.put("sublands", land.subLands().size());
        vars.put("structureRevision", land.structureRevision());
        vars.put("landPolicyRevision", land.landPolicyRevision());
        vars.put("isOwner", isOwner(actor, land.ownerRef()));
        vars.put("serverLand", ManagementPermissionGate.isServerLand(land));
        vars.put("steward", steward);
        // The result template references every key below, and a missing var
        // fails rendering closed (silent reply), so defaults are always
        // present and only overwritten by real values.
        vars.put("playerUuid", "");
        vars.put("playerRef", "");
        vars.put("limitMaxChunksPerLand", "unknown");
        vars.put("limitMaxSublandsPerLand", "unknown");
        vars.put("limitMaxChunksPerLandSource", "unknown");
        vars.put("limitMaxSublandsPerLandSource", "unknown");
        Map<String, Object> limitVars = describeLimits(land.ownerRef());
        for (String key : new String[] {"limitMaxChunksPerLand", "limitMaxSublandsPerLand",
                "limitMaxChunksPerLandSource", "limitMaxSublandsPerLandSource"}) {
            Object value = limitVars.get(key);
            if (value != null) {
                vars.put(key, value);
            }
        }
        return Map.copyOf(vars);
    }

    private Map<String, Object> describeLimits(OwnerRef owner) {
        if (limits == null || owner == null) {
            return Map.of();
        }
        try {
            Map<String, Object> described = limits.describe(owner);
            return described == null ? Map.of() : described;
        } catch (RuntimeException failure) {
            return Map.of();
        }
    }

    private String describeOwner(OwnerRef owner) {
        if (owner instanceof OwnerRef.PlayerOwnerRef player) {
            if (player.uuid() == null) {
                return "unknown";
            }
            String name = ownerName(player.uuid());
            return name == null ? player.uuid().toString() : name;
        }
        if (owner instanceof OwnerRef.ServerOwnerRef) {
            return "server";
        }
        return "unknown";
    }

    private String ownerName(UUID owner) {
        if (ownerNames == null) {
            return null;
        }
        try {
            String name = ownerNames.apply(owner);
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    private static boolean isOwner(UUID actor, OwnerRef owner) {
        return actor != null
                && owner instanceof OwnerRef.PlayerOwnerRef player
                && actor.equals(player.uuid());
    }

    private LandRegistry readSnapshot() {
        try {
            return snapshots == null ? null : snapshots.get();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private PermissionContextProvider readProvider() {
        try {
            return providers == null ? null : providers.get();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static LandId resolveTarget(CommandSender sender, String[] args,
            LandRegistry snapshot) {
        try {
            return PluginManagementGateResolver.TargetLandResolver.currentLocation()
                    .resolveTarget(sender, ProtectionActionType.MANAGE_PERMISSION,
                            args, snapshot)
                    .orElse(null);
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    private static boolean readSteward(CommandSender sender) {
        try {
            return sender.hasPermission(
                    PluginManagementGateResolver.SERVER_LAND_STEWARD_NODE);
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private boolean readBypass(UUID actor) {
        if (bypassStates == null || actor == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(bypassStates.apply(actor));
        } catch (RuntimeException unresolved) {
            return false;
        }
    }
}

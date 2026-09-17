package com.smile.chunkland.command;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.PermissionResolver;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.protection.PermissionExplain;
import com.smile.chunkland.protection.PermissionExplainService;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.SubLandIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Read-only {@code /land explain <action>} handler.
 *
 * <p>The handler explains the protection outcome for the sender's own
 * position: it resolves the land under the sender against the same
 * immutable snapshot the enforcement path reads, re-checks the existing
 * {@code MANAGE_PERMISSION} domain gate, then builds the decision input
 * through the shared {@link PermissionContextProvider} — block-aware via
 * {@code provideAtBlock} when the provider supports it, so the covering
 * subland decides first exactly like live enforcement — and resolves it
 * with {@link PermissionResolver}. The structured layer comes from
 * {@link PermissionExplainService}, never from parsing wording.
 *
 * <p>Only callers that pass the gate see the outcome with the covering
 * subland summary and the owner/bypass/steward flags; everyone else,
 * wilderness, unknown targets and every failure stay fail-closed on one
 * generic denial without land-existence or action details, so the response
 * never probes whether a land exists. The handler performs no
 * mutation, economy, SQL, chunk-load or network access and keeps no cache:
 * every call re-reads the shared provider.
 */
public final class ExplainCommandHandler implements LandCommand.Handler {

    private final Supplier<LandRegistry> snapshots;
    private final Supplier<PermissionContextProvider> providers;
    private final Function<UUID, Boolean> bypassStates;

    /**
     * @param snapshots live immutable snapshot source; {@code null} or failing
     *                  reads fail closed
     * @param providers shared context provider source (the same instance the
     *                  enforcement path reads); {@code null} or failing reads
     *                  fail closed
     */
    public ExplainCommandHandler(Supplier<LandRegistry> snapshots,
            Supplier<PermissionContextProvider> providers) {
        this(snapshots, providers, null);
    }

    /**
     * @param snapshots live immutable snapshot source; {@code null} or failing
     *                  reads fail closed
     * @param providers shared context provider source (the same instance the
     *                  enforcement path reads); {@code null} or failing reads
     *                  fail closed
     * @param bypassStates per-enable bypass memory read for the sender UUID;
     *                     {@code null} or failing reads resolve to {@code false}
     *                     (fail-closed). The permission node is never consulted:
     *                     only an explicit toggle flips the flag.
     */
    public ExplainCommandHandler(Supplier<LandRegistry> snapshots,
            Supplier<PermissionContextProvider> providers,
            Function<UUID, Boolean> bypassStates) {
        this.snapshots = snapshots;
        this.providers = providers;
        this.bypassStates = bypassStates;
    }

    /** Parses one action name case-insensitively; unknown names yield empty. */
    public static Optional<ProtectionActionType> parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    ProtectionActionType.valueOf(raw.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** Deterministic completion over the legal action names. */
    public static List<String> completeAction(String prefix) {
        String wanted = prefix == null ? "" : prefix.toUpperCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (ProtectionActionType action : ProtectionActionType.values()) {
            if (action.name().startsWith(wanted)) {
                out.add(action.name());
            }
        }
        return out;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.explain.denied", Map.of());
            return;
        }
        String raw = args == null || args.length < 2 ? null : args[1];
        if (raw == null || raw.isBlank()) {
            sink.reply("command.land.explain.usage", Map.of());
            return;
        }
        Optional<ProtectionActionType> parsed = parseAction(raw);
        if (parsed.isEmpty()) {
            sink.reply("command.land.explain.denied", Map.of());
            return;
        }
        ProtectionActionType action = parsed.get();
        UUID actor;
        try {
            actor = player.getUniqueId();
        } catch (RuntimeException failure) {
            sink.reply("command.land.explain.denied", Map.of());
            return;
        }
        if (actor == null) {
            sink.reply("command.land.explain.denied", Map.of());
            return;
        }
        LandRegistry snapshot = readSnapshot();
        PermissionContextProvider provider = readProvider();
        if (snapshot == null || provider == null) {
            sink.reply("command.land.explain.denied", Map.of());
            return;
        }
        LandId target = resolveTarget(sender, args, snapshot);
        if (target == null) {
            sink.reply("command.land.explain.denied", Map.of());
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
            sink.reply("command.land.explain.denied", Map.of());
            return;
        }
        if (gate == null || gate.outcome() != PermissionState.ALLOW) {
            sink.reply("command.land.explain.denied", Map.of());
            return;
        }
        int[] position = blockPosition(player);
        if (position == null) {
            sink.reply("command.land.explain.denied", Map.of());
            return;
        }
        PermissionContext ctx;
        try {
            if (provider instanceof SnapshotPermissionContextProvider snapshotProvider) {
                ctx = snapshotProvider.provideAtBlock(actor, target,
                        position[0], position[1], position[2], action, snapshot);
            } else {
                ctx = provider.provide(actor, target, action, snapshot);
            }
        } catch (RuntimeException failure) {
            sink.reply("command.land.explain.denied", Map.of());
            return;
        }
        if (ctx == null) {
            sink.reply("command.land.explain.denied", Map.of());
            return;
        }
        PermissionDecision decision;
        try {
            decision = PermissionResolver.resolve(ctx);
        } catch (RuntimeException failure) {
            sink.reply("command.land.explain.denied", Map.of());
            return;
        }
        SubLandId covering = coveringSubland(snapshot, target, position);
        boolean serverLand = readServerLand(snapshot, target);
        PermissionExplain explained;
        try {
            explained = PermissionExplainService.explain(
                    ctx, decision, covering, steward, serverLand);
        } catch (RuntimeException failure) {
            sink.reply("command.land.explain.denied", Map.of());
            return;
        }
        // Authorized callers get the full structured answer including the
        // covering subland summary and the owner/bypass/steward flags. No
        // player ids, group or profile lists ever leave this path.
        String coveringId = explained.coveringSubLandId() == null
                ? "none"
                : explained.coveringSubLandId();
        sink.reply("command.land.explain.result", Map.of(
                "action", explained.action().name(),
                "outcome", explained.outcome().name(),
                "source", explained.source().name(),
                "layer", explained.layer().name(),
                "reason", explained.reason(),
                "coveringSubLandId", coveringId,
                "isOwner", explained.isOwner(),
                "adminBypass", explained.adminBypass(),
                "steward", explained.steward()));
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

    private static int[] blockPosition(Player player) {
        try {
            Location location = player.getLocation();
            if (location == null) {
                return null;
            }
            return new int[] {
                location.getBlockX(), location.getBlockY(), location.getBlockZ()};
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    private static SubLandId coveringSubland(LandRegistry snapshot, LandId landId,
            int[] position) {
        try {
            SubLandIndex index = snapshot.subLandIndex(landId);
            if (index == null) {
                return null;
            }
            SubLandSnapshot covering =
                    index.findAtBlock(position[0], position[1], position[2]);
            return covering == null ? null : covering.id();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static boolean readServerLand(LandRegistry snapshot, LandId landId) {
        try {
            LandSnapshot land = snapshot.land(landId);
            return land != null && ManagementPermissionGate.isServerLand(land);
        } catch (RuntimeException failure) {
            return false;
        }
    }
}

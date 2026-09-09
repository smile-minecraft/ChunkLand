package com.smile.chunkland.command;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@link ManagementGateResolver} for {@code /land} management
 * subcommands.
 *
 * <p>Actor, snapshot, steward flag and context provider come from live Bukkit
 * and registry state. The target land comes from a {@link TargetLandResolver}:
 * the formal wiring resolves the land under the sender's current location
 * against the immutable snapshot, so management subcommands act on the land
 * the player is standing on. There is no named-land argument yet — tail args
 * on trust/ban style subcommands name the affected player, never a land.
 * Wilderness, unknown worlds, non-player senders and any unresolvable
 * position stay unresolved and fail closed. Admin bypass has no dedicated
 * state yet and resolves to {@code false} (fail-closed); a future bypass
 * milestone owns that flag.
 */
public final class PluginManagementGateResolver implements ManagementGateResolver {

    /** Bukkit node granting the Server Land steward equivalent. */
    public static final String SERVER_LAND_STEWARD_NODE = "chunkland.admin.serverland";

    /** Resolves the target land for a management attempt. */
    public interface TargetLandResolver {
        Optional<LandId> resolveTarget(
                CommandSender sender, ProtectionActionType action, String[] args, LandRegistry snapshot);

        /** No land-context flow is wired yet: every target stays unresolved. */
        static TargetLandResolver unresolved() {
            return (sender, action, args, snapshot) -> Optional.empty();
        }

        /**
          * Resolves the land under the sender's current location against the
         * already-acquired immutable snapshot. Only the allocation-free chunk
         * index on that snapshot is touched — no store re-read, no SQL, no
         * I/O and no Bukkit state beyond the sender's location. Anything
         * unresolvable (non-player sender, missing location/world,
         * wilderness, unknown world, null snapshot, lookup failure) yields
         * empty so the caller fails closed.
         */
        static TargetLandResolver currentLocation() {
            return (sender, action, args, snapshot) -> {
                if (!(sender instanceof Player player)) {
                    return Optional.empty();
                }
                if (snapshot == null) {
                    return Optional.empty();
                }
                Location location;
                try {
                    location = player.getLocation();
                } catch (RuntimeException unresolved) {
                    return Optional.empty();
                }
                if (location == null) {
                    return Optional.empty();
                }
                World world;
                try {
                    world = location.getWorld();
                } catch (RuntimeException unresolved) {
                    return Optional.empty();
                }
                if (world == null) {
                    return Optional.empty();
                }
                UUID worldId;
                try {
                    worldId = world.getUID();
                } catch (RuntimeException unresolved) {
                    return Optional.empty();
                }
                if (worldId == null) {
                    return Optional.empty();
                }
                int chunkX;
                int chunkZ;
                try {
                    chunkX = location.getBlockX() >> 4;
                    chunkZ = location.getBlockZ() >> 4;
                } catch (RuntimeException unresolved) {
                    return Optional.empty();
                }
                LandId found;
                try {
                    found = snapshot.findLandId(worldId, chunkX, chunkZ);
                } catch (RuntimeException unresolved) {
                    return Optional.empty();
                }
                return Optional.ofNullable(found);
            };
        }
    }

    private final Supplier<LandRegistry> snapshots;
    private final Supplier<PermissionContextProvider> providers;
    private final TargetLandResolver targets;

    public PluginManagementGateResolver(
            Supplier<LandRegistry> snapshots,
            Supplier<PermissionContextProvider> providers,
            TargetLandResolver targets) {
        this.snapshots = snapshots;
        this.providers = providers;
        this.targets = targets == null ? TargetLandResolver.unresolved() : targets;
    }

    @Override
    public Optional<Request> resolve(CommandSender sender, ProtectionActionType action, String[] args) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(action, "action");
        UUIDActor actor = resolveActor(sender);
        if (actor == null) {
            return Optional.empty();
        }
        LandRegistry snapshot = snapshots == null ? null : snapshots.get();
        PermissionContextProvider provider = providers == null ? null : providers.get();
        if (snapshot == null || provider == null) {
            return Optional.empty();
        }
        Optional<LandId> target;
        try {
            target = targets.resolveTarget(sender, action, args, snapshot);
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
        if (target == null || target.isEmpty() || target.get() == null) {
            return Optional.empty();
        }
        boolean steward;
        try {
            steward = sender.hasPermission(SERVER_LAND_STEWARD_NODE);
        } catch (RuntimeException denied) {
            return Optional.empty();
        }
        // No admin-bypass state is wired in this milestone; fail closed.
        return Optional.of(new Request(actor.id(), target.get(), snapshot, false, steward, provider));
    }

    private static UUIDActor resolveActor(CommandSender sender) {
        if (sender instanceof Player player) {
            try {
                return new UUIDActor(player.getUniqueId());
            } catch (RuntimeException unresolved) {
                return null;
            }
        }
        // Consoles and blocks have no player UUID yet; a future milestone may
        // grant them an explicit path. Until then they fail closed.
        return null;
    }

    private record UUIDActor(java.util.UUID id) {
    }
}

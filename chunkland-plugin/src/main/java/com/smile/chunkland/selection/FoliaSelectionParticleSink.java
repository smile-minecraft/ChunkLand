package com.smile.chunkland.selection;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/**
 * Production {@link SelectionParticleSink} that only ever talks to the
 * operating player.
 *
 * <p>Emission uses the player's own {@code spawnParticle} overload, which the
 * server delivers to that player alone — nothing is broadcast to other
 * players. The viewer pose is the operating player's own location, read on
 * its scheduler thread (every renderer tick already runs in that context).
 *
 * <p>Terrain rule: no Highest Block lookup, no block data, no entity scan, no
 * Bukkit world/chunk/block query appears here. A missing player resolves to
 * empty on read and to a quiet no-op on send (the renderer stops the loop on
 * an absent viewer; the session manager's quit/world-change cleanup stops it
 * explicitly).
 */
public final class FoliaSelectionParticleSink implements SelectionParticleSink {
    private final Function<UUID, Player> playerLookup;

    public FoliaSelectionParticleSink(Function<UUID, Player> playerLookup) {
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
    }

    @Override
    public Optional<ViewerPose> viewerOf(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Player player = playerLookup.apply(playerId);
        if (player == null) {
            return Optional.empty();
        }
        Location location = player.getLocation();
        if (location == null) {
            return Optional.empty();
        }
        return Optional.of(new ViewerPose(location.getX(), location.getY(), location.getZ()));
    }

    @Override
    public void emit(UUID playerId, double x, double y, double z) {
        Objects.requireNonNull(playerId, "playerId");
        Player player = playerLookup.apply(playerId);
        if (player == null) {
            return;
        }
        player.spawnParticle(Particle.HAPPY_VILLAGER, x, y, z, 1);
    }
}

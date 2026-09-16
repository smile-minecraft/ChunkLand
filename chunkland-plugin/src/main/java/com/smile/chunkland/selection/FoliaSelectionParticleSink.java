package com.smile.chunkland.selection;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Color;
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
 * <p>Visibility rule: every point is one large gold dust mote, which reads
 * against terrain in daylight where the previous subtle effect washed out.
 * One mote per point keeps each emission cheap; the renderer budget still
 * caps the per-tick total, the render distance, and the refresh interval.
 *
 * <p>Terrain rule: no Highest Block lookup, no block data, no entity scan, no
 * Bukkit world/chunk/block query appears here. A missing player resolves to
 * empty on read and to a quiet no-op on send (the renderer stops the loop on
 * an absent viewer; the session manager's quit/world-change cleanup stops it
 * explicitly).
 */
public final class FoliaSelectionParticleSink implements SelectionParticleSink {
    /** Boundary effect: a large gold dust mote that reads against terrain in daylight. */
    public static final Particle PARTICLE = Particle.DUST;
    /** One mote per planned point; the renderer budget caps the per-tick total. */
    public static final int PARTICLE_COUNT = 1;
    /** Warm gold chosen for contrast on grass, dirt, and stone. */
    public static final Color DUST_COLOR = Color.fromRGB(255, 180, 0);
    /**
     * Occupied-land preview colour: a clearly different red-orange so a land
     * boundary can never be mistaken for the active selection outline.
     */
    public static final Color OCCUPIED_DUST_COLOR = Color.fromRGB(255, 70, 0);
    /** Larger than the default mote so the boundary stays readable at a distance. */
    public static final float DUST_SIZE = 1.5F;

    private final Function<UUID, Player> playerLookup;
    private final Color dustColor;
    private final float dustSize;

    public FoliaSelectionParticleSink(Function<UUID, Player> playerLookup) {
        this(playerLookup, DUST_COLOR, DUST_SIZE);
    }

    /** Sink variant with an explicit colour, used for the occupied-land preview. */
    public FoliaSelectionParticleSink(Function<UUID, Player> playerLookup, Color dustColor) {
        this(playerLookup, dustColor, DUST_SIZE);
    }

    public FoliaSelectionParticleSink(Function<UUID, Player> playerLookup, Color dustColor, float dustSize) {
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.dustColor = Objects.requireNonNull(dustColor, "dustColor");
        this.dustSize = dustSize;
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
        player.spawnParticle(PARTICLE, x, y, z, PARTICLE_COUNT, new Particle.DustOptions(dustColor, dustSize));
    }
}

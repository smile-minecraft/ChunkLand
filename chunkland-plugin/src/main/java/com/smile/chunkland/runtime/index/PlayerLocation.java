package com.smile.chunkland.runtime.index;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable player location state detached from Bukkit.
 *
 * <p>Holds only immutable values: player id, world id, chunk and block coordinates.
 * Never stores {@code Player}, {@code World} or {@code Chunk}.
 */
public record PlayerLocation(
        UUID playerId,
        UUID worldId,
        int chunkX,
        int chunkZ,
        int blockX,
        int blockY,
        int blockZ) {

    public PlayerLocation {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(worldId, "worldId");
        // The X / Z chunk coordinates must be the floorDiv projection of the
        // block coordinates, otherwise a publisher can hand a value that no
        // lookup will reach. Negative block coordinates follow Minecraft's
        // chunk boundaries (floorDiv(-1, 16) == -1).
        if (Math.floorDiv(blockX, 16) != chunkX
                || Math.floorDiv(blockZ, 16) != chunkZ) {
            throw new IllegalArgumentException(
                    "chunkX/chunkZ (" + chunkX + "/" + chunkZ
                            + ") must equal floorDiv(blockX/blockZ, 16) for block ("
                            + blockX + "/" + blockZ + ")");
        }
    }

    /** Derive chunk from block; helpers for publisher. */
    public static PlayerLocation of(UUID playerId, UUID worldId, int blockX, int blockY, int blockZ) {
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        return new PlayerLocation(playerId, worldId, chunkX, chunkZ, blockX, blockY, blockZ);
    }
}

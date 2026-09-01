package com.smile.chunkland.api.land;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, inclusive block-coordinate cuboid.
 *
 * <p>The X/Z projection can be converted to the complete set of covered chunk
 * columns without loading a world or a chunk. Chunk coordinates use
 * {@link Math#floorDiv(int, int)}, so negative block coordinates follow
 * Minecraft's chunk boundaries.
 */
public record Cuboid(
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ) {

    /** Maximum number of chunk columns materialised by {@link #coveredChunks(UUID)}. */
    public static final long MAX_COVERED_CHUNKS = 1_000_000L;

    public Cuboid {
        if (minX > maxX) {
            throw new IllegalArgumentException("minX (" + minX + ") > maxX (" + maxX + ")");
        }
        if (minY > maxY) {
            throw new IllegalArgumentException("minY (" + minY + ") > maxY (" + maxY + ")");
        }
        if (minZ > maxZ) {
            throw new IllegalArgumentException("minZ (" + minZ + ") > maxZ (" + maxZ + ")");
        }
    }

    /**
     * Return every chunk column touched by this cuboid's inclusive X/Z projection.
     *
     * @throws NullPointerException if {@code worldId} is null
     * @throws IllegalArgumentException if the projection exceeds
     *         {@link #MAX_COVERED_CHUNKS}
     */
    public Set<ChunkKey> coveredChunks(UUID worldId) {
        Objects.requireNonNull(worldId, "worldId");
        int minChunkX = Math.floorDiv(minX, 16);
        int maxChunkX = Math.floorDiv(maxX, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);
        long count = checkedChunkCount(minChunkX, maxChunkX, minChunkZ, maxChunkZ);

        Set<ChunkKey> result = new HashSet<>(Math.toIntExact(count));
        for (long chunkX = minChunkX; chunkX <= (long) maxChunkX; chunkX++) {
            for (long chunkZ = minChunkZ; chunkZ <= (long) maxChunkZ; chunkZ++) {
                result.add(new ChunkKey(worldId, (int) chunkX, (int) chunkZ));
            }
        }
        return Set.copyOf(result);
    }

    /** Return the number of chunk columns touched by the X/Z projection. */
    public long coveredChunkCount() {
        return checkedChunkCount(
                Math.floorDiv(minX, 16), Math.floorDiv(maxX, 16),
                Math.floorDiv(minZ, 16), Math.floorDiv(maxZ, 16));
    }

    /** Return whether this cuboid and {@code other} share at least one block. */
    public boolean intersects(Cuboid other) {
        Objects.requireNonNull(other, "other");
        return minX <= other.maxX && other.minX <= maxX
                && minY <= other.maxY && other.minY <= maxY
                && minZ <= other.maxZ && other.minZ <= maxZ;
    }

    /** Return whether every block in {@code other} is in this cuboid. */
    public boolean contains(Cuboid other) {
        Objects.requireNonNull(other, "other");
        return minX <= other.minX && other.maxX <= maxX
                && minY <= other.minY && other.maxY <= maxY
                && minZ <= other.minZ && other.maxZ <= maxZ;
    }

    private static long checkedChunkCount(
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        long width = (long) maxChunkX - minChunkX + 1L;
        long depth = (long) maxChunkZ - minChunkZ + 1L;
        long count = Math.multiplyExact(width, depth);
        if (count > MAX_COVERED_CHUNKS) {
            throw new IllegalArgumentException(
                    "Cuboid covers " + count + " chunk columns; maximum is " + MAX_COVERED_CHUNKS);
        }
        return count;
    }
}

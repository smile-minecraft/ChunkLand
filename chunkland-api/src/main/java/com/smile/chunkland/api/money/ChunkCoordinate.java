package com.smile.chunkland.api.money;

/**
 * Immutable chunk coordinate for cost basis allocation (spec §50).
 *
 * <p>Ordering is lexicographic by {@code chunkX} then {@code chunkZ}, matching the
 * deterministic allocation contract. This type is intentionally minimal and lives in
 * the money package so the allocation logic remains pure and free of Bukkit/ChunkKey
 * dependencies while preserving the spec's (chunkX, chunkZ) ordering.
 */
public record ChunkCoordinate(int chunkX, int chunkZ) implements Comparable<ChunkCoordinate> {

    @Override
    public int compareTo(ChunkCoordinate other) {
        int c = Integer.compare(this.chunkX, other.chunkX);
        if (c != 0) {
            return c;
        }
        return Integer.compare(this.chunkZ, other.chunkZ);
    }
}

package com.smile.chunkland.api.geometry;

import com.smile.chunkland.api.land.ChunkKey;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, immutable extraction of exposed chunk edges (the boundary) from a set of chunk
 * coordinates.
 *
 * <p>For each chunk and each of its four orthogonal directions, {@link #extract(Set)}
 * emits a {@link BoundarySegment} exactly when the neighbor across that edge is NOT in
 * the set. Consequences:
 * <ul>
 *   <li>An edge shared by two selected chunks is emitted zero times (the neighbor is
 *       present on both sides).</li>
 *   <li>Diagonal contact never creates or removes a segment; only orthogonal neighbors
 *       matter.</li>
 *   <li>The empty set yields the empty boundary.</li>
 * </ul>
 *
 * <p>The extractor performs no I/O, never loads a chunk or a Bukkit/World, and depends
 * only on the JDK. It is O(n) in the number of chunks: each chunk is visited once and
 * each of its four neighbors is checked in O(1) against a packed-coordinate membership
 * set. It does NOT run hole/component analysis, so it never scans the bounding box and
 * never fails fast on a large span the way {@link ChunkGeometry} does.
 *
 * <p><b>Mixed worlds.</b> Like {@link ChunkGeometry}, the input must belong to a single
 * world; a set mixing more than one {@code worldId} is rejected with
 * {@link IllegalArgumentException}. The packed-coordinate membership test is world-scoped
 * precisely because of this single-world contract (two chunks with the same x/z but
 * different worlds are different chunks and must not be conflated).
 *
 * <p><b>Extreme coordinates.</b> Neighbor computation is done in {@code long} and
 * range-checked before any {@code int} conversion, so a chunk at {@code Integer.MAX_VALUE}
 * or {@code Integer.MIN_VALUE} never wraps to a false coordinate; an edge facing the
 * world boundary is still emitted (with {@link BoundarySegment#sideChunk()} returning
 * {@code null}).
 *
 * <p>All returned collections are immutable snapshots; the input set and any internal
 * mutable state are never exposed.
 */
public final class BoundaryExtractor {

    private BoundaryExtractor() {
        // static utility only
    }

    /**
     * Extract the exposed edges of {@code chunks}.
     *
     * @param chunks the chunk set to analyze (must be non-null, contain no null
     *               elements, and share a single worldId)
     * @return an immutable set of {@link BoundarySegment}s, one per exposed edge
     * @throws NullPointerException     if {@code chunks} or any element is null
     * @throws IllegalArgumentException if {@code chunks} mixes more than one worldId
     */
    public static Set<BoundarySegment> extract(Set<ChunkKey> chunks) {
        Objects.requireNonNull(chunks, "chunks");
        UUID worldId = null;
        Set<Long> packed = new HashSet<>(chunks.size());
        for (ChunkKey key : chunks) {
            Objects.requireNonNull(key, "chunks must not contain null");
            if (worldId == null) {
                worldId = key.worldId();
            } else if (!worldId.equals(key.worldId())) {
                throw new IllegalArgumentException(
                        "BoundaryExtractor requires a single worldId; found mixed worlds");
            }
            packed.add(key.pack());
        }

        Set<BoundarySegment> result = new HashSet<>(chunks.size() * 4);
        for (ChunkKey key : chunks) {
            int x = key.chunkX();
            int z = key.chunkZ();
            for (Direction dir : Direction.values()) {
                Long nb = neighbor(x, z, dir);
                if (nb == null || !packed.contains(nb)) {
                    result.add(new BoundarySegment(key, dir));
                }
            }
        }
        return Set.copyOf(result);
    }

    /**
     * Orthogonal neighbor of (x,z) in the given direction, or {@code null} when the
     * neighbor would fall outside the valid {@code int} chunk-coordinate range.
     * Computed in {@code long} and range-checked before conversion, so extremes never
     * wrap. No magic sentinel is used.
     */
    private static Long neighbor(int x, int z, Direction dir) {
        long nx = (long) x + dir.dx();
        long nz = (long) z + dir.dz();
        if (nx < Integer.MIN_VALUE || nx > Integer.MAX_VALUE
                || nz < Integer.MIN_VALUE || nz > Integer.MAX_VALUE) {
            return null;
        }
        return ((long) (int) nx << 32) | ((int) nz & 0xFFFFFFFFL);
    }
}

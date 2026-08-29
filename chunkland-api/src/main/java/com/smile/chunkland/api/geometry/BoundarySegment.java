package com.smile.chunkland.api.geometry;

import com.smile.chunkland.api.land.ChunkKey;
import java.util.Objects;

/**
 * An exposed edge of a single chunk, on the side facing a {@link Direction}.
 *
 * <p>A {@code BoundarySegment} is the immutable record of one orthogonal edge of
 * {@link #chunk()} that borders a cell which is NOT part of the analyzed set. It is
 * produced by {@link BoundaryExtractor#extract(Set)} for every chunk/direction pair
 * whose neighbor is absent from the set. Because each segment is keyed by exactly one
 * (chunk, direction) pair, a shared edge between two selected chunks is reported zero
 * times (each chunk sees the other as present, so neither emits it), and no exposed
 * edge is ever duplicated.
 *
 * <p><b>Side coordinate.</b> The "side" of the edge is the adjacent chunk cell across
 * it, returned by {@link #sideChunk()}. When the edge faces the world boundary at an
 * {@code int} extreme, no valid neighbor coordinate exists and {@link #sideChunk()}
 * returns {@code null}; the segment is still valid and still exposed.
 *
 * <p><b>Edge geometry.</b> In chunk-coordinate space a chunk occupies the unit square
 * {@code [x, x+1) x [z, z+1)}. The exposed edge is the unit-length side of that square
 * on the face toward {@link #direction()} (NORTH is the {@code -z} face at {@code z},
 * SOUTH the {@code +z} face at {@code z+1}, EAST the {@code +x} face at {@code x+1},
 * WEST the {@code -x} face at {@code x}). {@link #edgeEndpoints()} returns its two
 * corner coordinates as a fresh {@code long[] {x0, z0, x1, z1}}; the arithmetic is done
 * in {@code long} so an edge at an {@code int} extreme (e.g. {@code x+1} when
 * {@code x == Integer.MAX_VALUE}) is reported as the correct {@code MAX+1} coordinate
 * rather than a wrapped value, and no valid edge is ever silently dropped or rejected.
 *
 * <p><b>Equality.</b> Two segments are equal iff they have the same {@link #chunk()} and
 * the same {@link #direction()} (value-based, since both are value objects). This is
 * exactly the uniqueness key described above, so a {@code Set} of segments can never
 * contain the same physical edge twice.
 */
public record BoundarySegment(ChunkKey chunk, Direction direction) {

    public BoundarySegment {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(direction, "direction");
    }

    /**
     * The adjacent chunk cell across this edge (the neighbor that is absent from the
     * set). {@code null} when the edge faces the world boundary at an {@code int}
     * extreme, where no valid neighbor coordinate exists.
     */
    public ChunkKey sideChunk() {
        long nx = (long) chunk.chunkX() + direction.dx();
        long nz = (long) chunk.chunkZ() + direction.dz();
        if (nx < Integer.MIN_VALUE || nx > Integer.MAX_VALUE
                || nz < Integer.MIN_VALUE || nz > Integer.MAX_VALUE) {
            return null;
        }
        return new ChunkKey(chunk.worldId(), (int) nx, (int) nz);
    }

    /**
     * The two corner coordinates of the exposed edge in chunk space, as a fresh
     * {@code long[] {x0, z0, x1, z1}}. Computed in {@code long} so an edge at an
     * {@code int} extreme never wraps (see class Javadoc).
     */
    public long[] edgeEndpoints() {
        long x = chunk.chunkX();
        long z = chunk.chunkZ();
        return switch (direction) {
            case NORTH -> new long[] {x, z, x + 1, z};
            case EAST  -> new long[] {x + 1, z, x + 1, z + 1};
            case SOUTH -> new long[] {x, z + 1, x + 1, z + 1};
            case WEST  -> new long[] {x, z, x, z + 1};
        };
    }
}

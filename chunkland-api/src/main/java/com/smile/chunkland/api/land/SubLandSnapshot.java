package com.smile.chunkland.api.land;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of a SubLand (spec §21).
 *
 * <p>A SubLand is a cuboid in chunk space with a vertical block range. The
 * canonical geometry is the immutable {@link #chunks()} set; the vertical bounds
 * {@code minBlockY}/{@code maxBlockY} describe the protected block range. The
 * {@code name} is optional and may be {@code null}.
 *
 * <p>Collections are defensively copied and exposed unmodifiable (see
 * {@link LandSnapshot}).
 */
public record SubLandSnapshot(
        SubLandId id,
        LandId parentLandId,
        String name,
        int minBlockY,
        int maxBlockY,
        Set<ChunkKey> chunks) {

    public SubLandSnapshot(
            SubLandId id, LandId parentLandId, String name,
            int minBlockY, int maxBlockY, Set<ChunkKey> chunks) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(parentLandId, "parentLandId");
        Objects.requireNonNull(chunks, "chunks");
        if (minBlockY > maxBlockY) {
            throw new IllegalArgumentException("minBlockY (" + minBlockY + ") > maxBlockY (" + maxBlockY + ")");
        }
        this.id = id;
        this.parentLandId = parentLandId;
        this.name = name;
        this.minBlockY = minBlockY;
        this.maxBlockY = maxBlockY;
        this.chunks = Set.copyOf(chunks);
    }
}

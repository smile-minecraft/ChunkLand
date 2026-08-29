package com.smile.chunkland.api.land;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
        // All chunks must belong to a single world. The cross-check against the
        // parent land's world is intentionally left to the downstream containment /
        // repository layer (M1-06), which owns the parent/owner relationship.
        UUID worldId = null;
        for (ChunkKey chunk : chunks) {
            Objects.requireNonNull(chunk, "chunks must not contain null");
            if (worldId == null) {
                worldId = chunk.worldId();
            } else if (!worldId.equals(chunk.worldId())) {
                throw new IllegalArgumentException(
                        "SubLand chunks must share a single worldId; found mixed worlds");
            }
        }
        this.id = id;
        this.parentLandId = parentLandId;
        this.name = name;
        this.minBlockY = minBlockY;
        this.maxBlockY = maxBlockY;
        this.chunks = Set.copyOf(chunks);
    }

    /**
     * Return a new snapshot with {@code chunk} added. The receiver is never modified.
     * The added chunk must share this subland's world; the canonical constructor
     * re-validates the whole set.
     */
    public SubLandSnapshot addChunk(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        Set<ChunkKey> next = new HashSet<>(chunks);
        next.add(chunk);
        return new SubLandSnapshot(id, parentLandId, name, minBlockY, maxBlockY, next);
    }

    /**
     * Return a new snapshot with {@code chunk} removed. The receiver is never modified.
     */
    public SubLandSnapshot removeChunk(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        Set<ChunkKey> next = new HashSet<>(chunks);
        next.remove(chunk);
        return new SubLandSnapshot(id, parentLandId, name, minBlockY, maxBlockY, next);
    }

    /**
     * Return a new snapshot whose chunk set is replaced by {@code newChunks}.
     * The receiver is never modified.
     */
    public SubLandSnapshot replaceChunks(Set<ChunkKey> newChunks) {
        Objects.requireNonNull(newChunks, "newChunks");
        return new SubLandSnapshot(id, parentLandId, name, minBlockY, maxBlockY, newChunks);
    }

    /**
     * Return a new snapshot with the vertical block range replaced. The receiver is
     * never modified; the {@code min <= max} invariant is re-checked.
     */
    public SubLandSnapshot withBounds(int minBlockY, int maxBlockY) {
        return new SubLandSnapshot(id, parentLandId, name, minBlockY, maxBlockY, chunks);
    }

    /**
     * Return a new snapshot with the (optional) name replaced. The receiver is never
     * modified; {@code null} is allowed to clear the name.
     */
    public SubLandSnapshot withName(String name) {
        return new SubLandSnapshot(id, parentLandId, name, minBlockY, maxBlockY, chunks);
    }
}

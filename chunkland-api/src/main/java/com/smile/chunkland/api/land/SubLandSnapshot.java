package com.smile.chunkland.api.land;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable snapshot of a SubLand, including its optional precise block-level
 * geometry.
 *
 * <p>The six-argument constructor is retained for callers that only have the
 * original chunk representation and produces a legacy chunk-only snapshot.
 * It does not guess a Cuboid from chunks because a partial or non-rectangular
 * set has no unique block-level geometry. The constructor accepting
 * {@link Cuboid} is the precise geometry path.
 */
public record SubLandSnapshot(
        SubLandId id,
        LandId parentLandId,
        String name,
        int minBlockY,
        int maxBlockY,
        Set<ChunkKey> chunks,
        Cuboid cuboid) {

    /** Compatibility constructor for the original chunk-based snapshot API. */
    public SubLandSnapshot(
            SubLandId id, LandId parentLandId, String name,
            int minBlockY, int maxBlockY, Set<ChunkKey> chunks) {
        this(id, parentLandId, name, minBlockY, maxBlockY, chunks, null);
    }

    /** Construct a precise SubLand and derive its complete chunk projection. */
    public SubLandSnapshot(
            SubLandId id, LandId parentLandId, String name,
            Cuboid cuboid, UUID worldId) {
        this(id, parentLandId, name,
                requireCuboid(cuboid).minY(), requireCuboid(cuboid).maxY(),
                cuboid.coveredChunks(worldId), cuboid);
    }

    /** Construct a precise SubLand from a cuboid and an already computed projection. */
    public SubLandSnapshot(
            SubLandId id, LandId parentLandId, String name,
            Cuboid cuboid, Set<ChunkKey> chunks) {
        this(id, parentLandId, name,
                requireCuboid(cuboid).minY(), requireCuboid(cuboid).maxY(), chunks, cuboid);
    }

    public SubLandSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(parentLandId, "parentLandId");
        Objects.requireNonNull(chunks, "chunks");
        for (ChunkKey chunk : chunks) {
            Objects.requireNonNull(chunk, "chunks must not contain null");
        }
        if (minBlockY > maxBlockY) {
            throw new IllegalArgumentException(
                    "minBlockY (" + minBlockY + ") > maxBlockY (" + maxBlockY + ")");
        }
        UUID worldId = worldIdOf(chunks);
        if (cuboid != null) {
            if (cuboid.minY() != minBlockY || cuboid.maxY() != maxBlockY) {
                throw new IllegalArgumentException(
                        "minBlockY/maxBlockY must match cuboid Y bounds");
            }
            if (worldId == null) {
                throw new IllegalArgumentException("a cuboid SubLand requires at least one chunk");
            }
            Set<ChunkKey> expected = cuboid.coveredChunks(worldId);
            if (!expected.equals(chunks)) {
                throw new IllegalArgumentException(
                        "chunks must equal the cuboid's complete X/Z chunk projection");
            }
        }
        chunks = Set.copyOf(chunks);
    }

    /**
     * Return a new legacy snapshot with {@code chunk} added. Precise snapshots
     * must use {@link #withCuboid(Cuboid)} instead.
     *
     * @throws UnsupportedOperationException if this snapshot has precise geometry
     */
    public SubLandSnapshot addChunk(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        rejectPreciseChunkMutation("addChunk");
        Set<ChunkKey> next = new HashSet<>(chunks);
        next.add(chunk);
        return new SubLandSnapshot(id, parentLandId, name, minBlockY, maxBlockY, next);
    }

    /**
     * Return a new legacy snapshot with {@code chunk} removed. Precise snapshots
     * must use {@link #withCuboid(Cuboid)} instead.
     *
     * @throws UnsupportedOperationException if this snapshot has precise geometry
     */
    public SubLandSnapshot removeChunk(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        rejectPreciseChunkMutation("removeChunk");
        Set<ChunkKey> next = new HashSet<>(chunks);
        next.remove(chunk);
        return new SubLandSnapshot(id, parentLandId, name, minBlockY, maxBlockY, next);
    }

    /**
     * Return a new legacy snapshot whose chunk set is replaced by {@code newChunks}.
     * Precise snapshots must use {@link #withCuboid(Cuboid)} instead.
     *
     * @throws UnsupportedOperationException if this snapshot has precise geometry
     */
    public SubLandSnapshot replaceChunks(Set<ChunkKey> newChunks) {
        Objects.requireNonNull(newChunks, "newChunks");
        rejectPreciseChunkMutation("replaceChunks");
        return new SubLandSnapshot(id, parentLandId, name, minBlockY, maxBlockY, newChunks);
    }

    /** Return a new snapshot with the vertical range replaced. */
    public SubLandSnapshot withBounds(int minBlockY, int maxBlockY) {
        if (cuboid == null) {
            return new SubLandSnapshot(id, parentLandId, name, minBlockY, maxBlockY, chunks);
        }
        var updatedCuboid = new Cuboid(
                cuboid.minX(), minBlockY, cuboid.minZ(),
                cuboid.maxX(), maxBlockY, cuboid.maxZ());
        return new SubLandSnapshot(id, parentLandId, name,
                minBlockY, maxBlockY, chunks, updatedCuboid);
    }

    /** Return a new snapshot with the optional name replaced. */
    public SubLandSnapshot withName(String name) {
        return new SubLandSnapshot(id, parentLandId, name, minBlockY, maxBlockY, chunks, cuboid);
    }

    /**
     * Return a new precise snapshot with its chunk projection recomputed from
     * {@code cuboid}. The current snapshot must have a non-empty chunk set so
     * its world can be carried forward.
     */
    public SubLandSnapshot withCuboid(Cuboid cuboid) {
        Objects.requireNonNull(cuboid, "cuboid");
        UUID worldId = worldIdOf(chunks);
        if (worldId == null) {
            throw new IllegalStateException(
                    "cannot infer a world for a precise Cuboid from an empty chunk set");
        }
        return new SubLandSnapshot(id, parentLandId, name, cuboid, worldId);
    }

    /** Return a new precise snapshot with an explicitly supplied world. */
    public SubLandSnapshot withCuboid(Cuboid cuboid, UUID worldId) {
        Objects.requireNonNull(cuboid, "cuboid");
        Objects.requireNonNull(worldId, "worldId");
        return new SubLandSnapshot(id, parentLandId, name, cuboid, worldId);
    }

    private void rejectPreciseChunkMutation(String operation) {
        if (cuboid != null) {
            throw new UnsupportedOperationException(
                    operation + " cannot change precise Cuboid geometry; use withCuboid(Cuboid)");
        }
    }

    private static Cuboid requireCuboid(Cuboid cuboid) {
        return Objects.requireNonNull(cuboid, "cuboid");
    }

    private static UUID worldIdOf(Set<ChunkKey> chunks) {
        UUID worldId = null;
        for (ChunkKey chunk : chunks) {
            if (worldId == null) {
                worldId = chunk.worldId();
            } else if (!worldId.equals(chunk.worldId())) {
                throw new IllegalArgumentException(
                        "SubLand chunks must share a single worldId; found mixed worlds");
            }
        }
        return worldId;
    }
}

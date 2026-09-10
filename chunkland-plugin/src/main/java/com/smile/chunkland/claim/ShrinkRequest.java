package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable input for a single land shrink ({@code CHUNK_REMOVE}).
 *
 * <p>The target land already exists: {@code targetLandId} is required, and the
 * chunk set is the delta to remove, never the full land. The three optimistic
 * tokens are all required — {@code selectionRevision}, {@code
 * sessionGeneration} and {@code structureRevision} — so any stale or
 * unverifiable confirmation fails closed in the validator instead of passing
 * by default.
 */
public record ShrinkRequest(
        OwnerRef owner,
        UUID actorUuid,
        UUID worldId,
        LandId targetLandId,
        Set<ChunkKey> delta,
        Long selectionRevision,
        Long sessionGeneration,
        Long structureRevision) {

    public ShrinkRequest {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(targetLandId, "targetLandId");
        Objects.requireNonNull(delta, "delta");
        if (delta.isEmpty()) {
            throw new IllegalArgumentException("delta must not be empty");
        }
        for (ChunkKey chunk : delta) {
            Objects.requireNonNull(chunk, "delta must not contain null");
            if (!worldId.equals(chunk.worldId())) {
                throw new IllegalArgumentException("chunk worldId does not match request worldId");
            }
        }
        delta = Set.copyOf(delta);
        if (selectionRevision != null && selectionRevision < 0) {
            throw new IllegalArgumentException("selectionRevision must not be negative");
        }
        if (sessionGeneration != null && sessionGeneration < 0) {
            throw new IllegalArgumentException("sessionGeneration must not be negative");
        }
        if (structureRevision != null && structureRevision < 0) {
            throw new IllegalArgumentException("structureRevision must not be negative");
        }
    }
}

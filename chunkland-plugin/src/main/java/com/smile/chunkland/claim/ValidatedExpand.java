package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Step-one revalidation result for a land expansion.
 *
 * <p>{@code ownerTotalChunks} is the pricing basis: the owner's durable global
 * chunk total, never the delta size. {@code expectedStructureRevision} is the
 * optimistic lock the atomic commit compares against; the commit bumps it by
 * exactly one. Only the delta chunks carry fresh depth and cost basis — the
 * existing land chunks are never re-priced or rewritten.
 */
public record ValidatedExpand(
        LandId targetLandId,
        OwnerRef owner,
        UUID actorUuid,
        UUID worldId,
        String displayName,
        List<ChunkDetail> delta,
        long ownerTotalChunks,
        long expectedStructureRevision) {

    /** One validated delta chunk with its persisted protection depth. */
    public record ChunkDetail(ChunkKey chunk, int storedMinProtectedY) {
        public ChunkDetail {
            Objects.requireNonNull(chunk, "chunk");
        }
    }

    public ValidatedExpand {
        Objects.requireNonNull(targetLandId, "targetLandId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(delta, "delta");
        if (delta.isEmpty()) {
            throw new IllegalArgumentException("delta must not be empty");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (ownerTotalChunks < 0) {
            throw new IllegalArgumentException("ownerTotalChunks must not be negative");
        }
        if (expectedStructureRevision < 0) {
            throw new IllegalArgumentException("expectedStructureRevision must not be negative");
        }
        delta = List.copyOf(delta);
    }
}

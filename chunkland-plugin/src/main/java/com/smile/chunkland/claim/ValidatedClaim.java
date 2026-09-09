package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Step-one revalidation result: everything the later saga steps need.
 *
 * <p>{@code ownerTotalChunks} is the pricing basis: the owner's durable global
 * chunk total, never the transaction size. {@code storedMinProtectedY} is
 * carried per chunk so the atomic commit persists the selection-plane depth
 * without re-deriving it. Economy provider identity is owned by the saga's
 * economy seam, not by validation, so the recorded payload always matches the
 * adapter that will charge and refund it.
 */
public record ValidatedClaim(
        LandId landId,
        OwnerRef owner,
        UUID actorUuid,
        UUID worldId,
        String displayName,
        List<ChunkDetail> chunks,
        long ownerTotalChunks) {

    /** One validated chunk with its persisted protection depth. */
    public record ChunkDetail(ChunkKey chunk, int storedMinProtectedY) {
        public ChunkDetail {
            Objects.requireNonNull(chunk, "chunk");
        }
    }

    public ValidatedClaim {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(chunks, "chunks");
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (ownerTotalChunks < 0) {
            throw new IllegalArgumentException("ownerTotalChunks must not be negative");
        }
        chunks = List.copyOf(chunks);
    }
}

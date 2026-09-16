package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Step-one revalidation result for a whole-land delete.
 *
 * <p>{@code chunks} carries the full chunk identities validated against the
 * snapshot; durable per-chunk cost bases are read by the saga from storage
 * after validation, so the validator itself never touches SQL. {@code
 * expectedStructureRevision} is the optimistic lock the atomic commit
 * compares against; the delete removes the land row itself, so no revision
 * bump follows.
 */
public record ValidatedDelete(
        LandId targetLandId,
        OwnerRef owner,
        UUID actorUuid,
        UUID worldId,
        String displayName,
        List<ChunkKey> chunks,
        long expectedStructureRevision) {

    public ValidatedDelete {
        Objects.requireNonNull(targetLandId, "targetLandId");
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
        if (expectedStructureRevision < 0) {
            throw new IllegalArgumentException("expectedStructureRevision must not be negative");
        }
        chunks = List.copyOf(chunks);
    }
}

package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Step-one revalidation result for a land shrink.
 *
 * <p>The delta carries only the chunk identities validated against the
 * snapshot; durable per-chunk cost bases are read by the saga from storage
 * after validation, so the validator itself never touches SQL. {@code
 * expectedStructureRevision} is the optimistic lock the atomic commit compares
 * against; the commit bumps it by exactly one. The remaining land chunks are
 * never re-priced or rewritten.
 *
 * <p>{@code affectedSubLands} is always empty on success: any SubLand whose
 * chunk projection intersects the delta rejects validation instead of
 * producing an orphan.
 */
public record ValidatedShrink(
        LandId targetLandId,
        OwnerRef owner,
        UUID actorUuid,
        UUID worldId,
        String displayName,
        List<ChunkKey> delta,
        long expectedStructureRevision,
        List<SubLandId> affectedSubLands) {

    public ValidatedShrink {
        Objects.requireNonNull(targetLandId, "targetLandId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(affectedSubLands, "affectedSubLands");
        if (delta.isEmpty()) {
            throw new IllegalArgumentException("delta must not be empty");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (expectedStructureRevision < 0) {
            throw new IllegalArgumentException("expectedStructureRevision must not be negative");
        }
        delta = List.copyOf(delta);
        affectedSubLands = List.copyOf(affectedSubLands);
    }
}

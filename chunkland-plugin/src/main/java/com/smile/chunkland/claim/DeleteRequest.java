package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable input for a single whole-land delete ({@code LAND_DELETE}).
 *
 * <p>The target land already exists: {@code targetLandId} is required and the
 * chunk set is always the land's full durable set, resolved by the saga after
 * validation — never a caller-supplied delta. The optimistic {@code
 * structureRevision} token is required so a stale confirmation fails closed
 * in the validator instead of deleting a land the actor never reviewed.
 */
public record DeleteRequest(
        OwnerRef owner,
        UUID actorUuid,
        UUID worldId,
        LandId targetLandId,
        Long structureRevision) {

    public DeleteRequest {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(targetLandId, "targetLandId");
        if (structureRevision != null && structureRevision < 0) {
            throw new IllegalArgumentException("structureRevision must not be negative");
        }
    }
}

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
 *
 * <p>{@code operationId} is the idempotency key of this operation. Chat
 * handlers mint a fresh key per accepted execution; the typed public entry
 * carries the caller-supplied key through. A {@code null} key preserves the
 * legacy behaviour: the saga mints its own key deep inside, so resubmits run
 * as independent operations. A non-null key is carried end to end into the
 * ledger, so a resubmit converges on the recorded result instead of running
 * twice. Legacy requests without a key behave exactly as before.
 */
public record DeleteRequest(
        OwnerRef owner,
        UUID actorUuid,
        UUID worldId,
        LandId targetLandId,
        Long structureRevision,
        UUID operationId) {

    public DeleteRequest {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(targetLandId, "targetLandId");
        if (structureRevision != null && structureRevision < 0) {
            throw new IllegalArgumentException("structureRevision must not be negative");
        }
    }

    /**
     * Legacy five-argument form without an idempotency key. Delegates with
     * a {@code null} key so historical callers keep the legacy mint-inside
     * behaviour unchanged.
     */
    public DeleteRequest(
            OwnerRef owner,
            UUID actorUuid,
            UUID worldId,
            LandId targetLandId,
            Long structureRevision) {
        this(owner, actorUuid, worldId, targetLandId, structureRevision, null);
    }
}

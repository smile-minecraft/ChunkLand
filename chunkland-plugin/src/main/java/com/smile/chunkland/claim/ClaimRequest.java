package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable input for a single land claim.
 *
 * <p>{@code owner} is the future land owner (player or server). {@code actorUuid}
 * is the player performing the claim; it is always required because the ledger
 * payload and the audit trail must record an actor even for server-owned land.
 * {@code selectionRevision} is an optional optimistic token supplied by the
 * confirmation layer; when present the validator compares it against the live
 * selection state and rejects stale confirmations.
 */
public record ClaimRequest(
        OwnerRef owner,
        UUID actorUuid,
        UUID worldId,
        Set<ChunkKey> chunks,
        String displayName,
        Long selectionRevision) {

    public ClaimRequest {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(displayName, "displayName");
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        for (ChunkKey chunk : chunks) {
            Objects.requireNonNull(chunk, "chunks must not contain null");
            if (!worldId.equals(chunk.worldId())) {
                throw new IllegalArgumentException("chunk worldId does not match request worldId");
            }
        }
        chunks = Set.copyOf(chunks);
        if (selectionRevision != null && selectionRevision < 0) {
            throw new IllegalArgumentException("selectionRevision must not be negative");
        }
    }

    /** Convenience for claims without a confirmation revision token. */
    public ClaimRequest(OwnerRef owner, UUID actorUuid, UUID worldId, Set<ChunkKey> chunks, String displayName) {
        this(owner, actorUuid, worldId, chunks, displayName, null);
    }
}

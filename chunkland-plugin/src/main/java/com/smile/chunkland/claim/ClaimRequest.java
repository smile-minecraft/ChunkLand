package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
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
 * selection state and rejects stale confirmations. {@code sessionGeneration}
 * is the companion token that separates replacement sessions sharing one
 * numeric revision: when present the validator compares it against the live
 * session generation and rejects old sessions even when the revision number
 * was reused. {@code structureRevision}
 * with {@code targetLandId} is the companion token for confirmations that act
 * on an existing land: when both are present the validator compares the token
 * against the live structure revision of that target and rejects stale or
 * unverifiable confirmations. Requests without a target skip the structure
 * check entirely.
 */
public record ClaimRequest(
        OwnerRef owner,
        UUID actorUuid,
        UUID worldId,
        Set<ChunkKey> chunks,
        String displayName,
        Long selectionRevision,
        Long sessionGeneration,
        Long structureRevision,
        LandId targetLandId) {

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
        if (sessionGeneration != null && sessionGeneration < 0) {
            throw new IllegalArgumentException("sessionGeneration must not be negative");
        }
        if (structureRevision != null && structureRevision < 0) {
            throw new IllegalArgumentException("structureRevision must not be negative");
        }
    }

    /** Convenience for claims without a confirmation revision token. */
    public ClaimRequest(OwnerRef owner, UUID actorUuid, UUID worldId, Set<ChunkKey> chunks, String displayName) {
        this(owner, actorUuid, worldId, chunks, displayName, null, null, null, null);
    }

    /** Convenience for confirmations that carry a selection token but no structure target. */
    public ClaimRequest(OwnerRef owner, UUID actorUuid, UUID worldId, Set<ChunkKey> chunks,
            String displayName, Long selectionRevision) {
        this(owner, actorUuid, worldId, chunks, displayName, selectionRevision, null, null, null);
    }

    /** Convenience for confirmations that carry selection and generation tokens but no structure target. */
    public ClaimRequest(OwnerRef owner, UUID actorUuid, UUID worldId, Set<ChunkKey> chunks,
            String displayName, Long selectionRevision, Long sessionGeneration) {
        this(owner, actorUuid, worldId, chunks, displayName, selectionRevision, sessionGeneration, null, null);
    }
}

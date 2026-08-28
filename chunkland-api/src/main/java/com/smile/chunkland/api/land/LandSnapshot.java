package com.smile.chunkland.api.land;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable snapshot of a Land's structural state (spec §4).
 *
 * <p>All collections are defensively copied on construction and exposed as
 * unmodifiable views, so callers can never mutate the snapshot or observe
 * mutation from the producer. Safe to share across threads.
 *
 * <p>{@code structureRevision} is the optimistic lock for chunk set / subland
 * geometry / owner changes; {@code landPolicyRevision} is bumped on ACL / rule /
 * default changes (spec §4, §33).
 */
public record LandSnapshot(
        LandId id,
        String displayName,
        String nameKey,
        OwnerRef ownerRef,
        UUID worldId,
        Set<ChunkKey> chunks,
        List<SubLandSnapshot> subLands,
        long structureRevision,
        long landPolicyRevision,
        Instant createdAt,
        Instant updatedAt) {

    public LandSnapshot(
            LandId id, String displayName, String nameKey, OwnerRef ownerRef, UUID worldId,
            Set<ChunkKey> chunks, List<SubLandSnapshot> subLands,
            long structureRevision, long landPolicyRevision, Instant createdAt, Instant updatedAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(nameKey, "nameKey");
        Objects.requireNonNull(ownerRef, "ownerRef");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(subLands, "subLands");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (nameKey.isBlank()) {
            throw new IllegalArgumentException("nameKey must not be blank");
        }
        this.id = id;
        this.displayName = displayName;
        this.nameKey = nameKey;
        this.ownerRef = ownerRef;
        this.worldId = worldId;
        this.chunks = Set.copyOf(chunks);
        this.subLands = List.copyOf(subLands);
        this.structureRevision = structureRevision;
        this.landPolicyRevision = landPolicyRevision;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}

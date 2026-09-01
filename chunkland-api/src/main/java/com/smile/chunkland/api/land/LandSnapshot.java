package com.smile.chunkland.api.land;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
        // The name key must be the canonical form of the display name so the two
        // can never diverge; downstream owner-scoped uniqueness relies on this.
        if (!nameKey.equals(LandName.normalize(displayName))) {
            throw new IllegalArgumentException(
                    "nameKey must equal LandName.normalize(displayName); got '" + nameKey
                            + "' expected '" + LandName.normalize(displayName) + "'");
        }
        // Every chunk must belong to this land's world; a mixed or foreign-world
        // chunk set is rejected up front so the geometry / containment layers can
        // trust a single worldId later.
        for (ChunkKey chunk : chunks) {
            Objects.requireNonNull(chunk, "chunks must not contain null");
            if (!worldId.equals(chunk.worldId())) {
                throw new IllegalArgumentException(
                        "chunk worldId (" + chunk.worldId() + ") does not match land worldId (" + worldId + ")");
            }
        }
        // Each subland must point back to this land and live in this land's world.
        // An empty subland is representable (its world cannot be derived from an
        // empty chunk set), so only its chunks — when present — are world-checked.
        // This is the single enforcement point: add/replace mutations rebuild via
        // this constructor, so they are re-validated automatically. Cuboid
        // containment / 3D overlap is owned by the downstream geometry validation layer.
        for (SubLandSnapshot subLand : subLands) {
            Objects.requireNonNull(subLand, "subLands must not contain null");
            if (!id.equals(subLand.parentLandId())) {
                throw new IllegalArgumentException(
                        "subLand parentLandId (" + subLand.parentLandId() + ") does not match land id (" + id + ")");
            }
            for (ChunkKey chunk : subLand.chunks()) {
                if (!worldId.equals(chunk.worldId())) {
                    throw new IllegalArgumentException(
                            "subLand chunk worldId (" + chunk.worldId() + ") does not match land worldId (" + worldId + ")");
                }
            }
        }
        if (structureRevision < 0) {
            throw new IllegalArgumentException("structureRevision must not be negative");
        }
        if (landPolicyRevision < 0) {
            throw new IllegalArgumentException("landPolicyRevision must not be negative");
        }
        if (createdAt.isAfter(updatedAt)) {
            throw new IllegalArgumentException("createdAt (" + createdAt + ") must not be after updatedAt (" + updatedAt + ")");
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

    /**
     * Return a new snapshot with {@code chunk} added, bumping {@code structureRevision}.
     *
     * <p>The receiver is never modified. The added chunk must belong to this land's
     * world; the canonical constructor re-validates the whole set.
     */
    public LandSnapshot addChunk(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        if (!worldId.equals(chunk.worldId())) {
            throw new IllegalArgumentException(
                    "chunk worldId (" + chunk.worldId() + ") does not match land worldId (" + worldId + ")");
        }
        Set<ChunkKey> next = new HashSet<>(chunks);
        next.add(chunk);
        return new LandSnapshot(id, displayName, nameKey, ownerRef, worldId, next, subLands,
                Math.addExact(structureRevision, 1), landPolicyRevision, createdAt, updatedAt);
    }

    /**
     * Return a new snapshot with {@code chunk} removed, bumping {@code structureRevision}.
     * The receiver is never modified.
     */
    public LandSnapshot removeChunk(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        Set<ChunkKey> next = new HashSet<>(chunks);
        next.remove(chunk);
        return new LandSnapshot(id, displayName, nameKey, ownerRef, worldId, next, subLands,
                Math.addExact(structureRevision, 1), landPolicyRevision, createdAt, updatedAt);
    }

    /**
     * Return a new snapshot whose chunk set is replaced by {@code newChunks},
     * bumping {@code structureRevision}. The receiver is never modified.
     */
    public LandSnapshot replaceChunks(Set<ChunkKey> newChunks) {
        Objects.requireNonNull(newChunks, "newChunks");
        return new LandSnapshot(id, displayName, nameKey, ownerRef, worldId, newChunks, subLands,
                Math.addExact(structureRevision, 1), landPolicyRevision, createdAt, updatedAt);
    }

    /**
     * Return a new snapshot with {@code subLand} appended, bumping {@code structureRevision}.
     * The receiver is never modified.
     */
    public LandSnapshot addSubLand(SubLandSnapshot subLand) {
        Objects.requireNonNull(subLand, "subLand");
        List<SubLandSnapshot> next = new ArrayList<>(subLands);
        next.add(subLand);
        return new LandSnapshot(id, displayName, nameKey, ownerRef, worldId, chunks, next,
                Math.addExact(structureRevision, 1), landPolicyRevision, createdAt, updatedAt);
    }

    /**
     * Return a new snapshot with the subland identified by {@code subLandId} removed,
     * bumping {@code structureRevision}. The receiver is never modified.
     */
    public LandSnapshot removeSubLand(SubLandId subLandId) {
        Objects.requireNonNull(subLandId, "subLandId");
        List<SubLandSnapshot> next = new ArrayList<>(subLands);
        next.removeIf(s -> s.id().equals(subLandId));
        return new LandSnapshot(id, displayName, nameKey, ownerRef, worldId, chunks, next,
                Math.addExact(structureRevision, 1), landPolicyRevision, createdAt, updatedAt);
    }

    /**
     * Return a new snapshot whose subland list is replaced by {@code newSubLands},
     * bumping {@code structureRevision}. The receiver is never modified.
     */
    public LandSnapshot replaceSubLands(List<SubLandSnapshot> newSubLands) {
        Objects.requireNonNull(newSubLands, "newSubLands");
        return new LandSnapshot(id, displayName, nameKey, ownerRef, worldId, chunks, newSubLands,
                Math.addExact(structureRevision, 1), landPolicyRevision, createdAt, updatedAt);
    }

    /**
     * Return a new snapshot with the owner replaced, bumping {@code structureRevision}
     * (owner changes are structural per spec §4). The receiver is never modified.
     * Server-owned Land is not transferable: a snapshot whose current owner is
     * {@link OwnerRef.ServerOwnerRef} cannot be changed to a player owner.
     */
    public LandSnapshot withOwner(OwnerRef ownerRef) {
        Objects.requireNonNull(ownerRef, "ownerRef");
        if (this.ownerRef instanceof OwnerRef.ServerOwnerRef
                && ownerRef instanceof OwnerRef.PlayerOwnerRef) {
            throw new IllegalStateException("Server Land is not transferable");
        }
        return new LandSnapshot(id, displayName, nameKey, ownerRef, worldId, chunks, subLands,
                Math.addExact(structureRevision, 1), landPolicyRevision, createdAt, updatedAt);
    }

    /**
     * Return a new snapshot with the display name / name key replaced, bumping
     * {@code landPolicyRevision}. The name key must be the canonical form of the
     * display name (enforced by the canonical constructor). The receiver is never modified.
     */
    public LandSnapshot withName(String displayName, String nameKey) {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(nameKey, "nameKey");
        return new LandSnapshot(id, displayName, nameKey, ownerRef, worldId, chunks, subLands,
                structureRevision, Math.addExact(landPolicyRevision, 1), createdAt, updatedAt);
    }

    /**
     * Return a new snapshot with {@code updatedAt} set. Revisions are unchanged;
     * the timestamp invariant (createdAt <= updatedAt) is re-checked. The receiver
     * is never modified.
     */
    public LandSnapshot withUpdatedAt(Instant updatedAt) {
        Objects.requireNonNull(updatedAt, "updatedAt");
        return new LandSnapshot(id, displayName, nameKey, ownerRef, worldId, chunks, subLands,
                structureRevision, landPolicyRevision, createdAt, updatedAt);
    }
}

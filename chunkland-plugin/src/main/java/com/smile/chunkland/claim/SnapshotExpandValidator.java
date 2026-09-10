package com.smile.chunkland.claim;

import com.smile.chunkland.api.geometry.ChunkGeometry;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Production step-one validator for land expansion over in-memory state only.
 *
 * <p>Checks, in order: the per-world {@code claim-enabled} policy for player
 * land (server-owned land keeps its existing bypass), the required session
 * generation, selection revision and structure tokens against their live
 * sources (any missing, stale or unresolvable token fails closed), target
 * resolution with owner and world agreement, per-delta collision (overlap with
 * the target, adjacency to another land of the same owner, or occupation by a
 * third party), and union connectivity without holes over the target plus the
 * delta. Limit checks stay with the saga's quota reservation; the database
 * stays the final authority through the atomic commit constraints.
 *
 * <p>Geometry uses only {@link ChunkGeometry}: JDK-only analysis over chunk
 * coordinates that never loads a World or Chunk. No SQL, no Economy, no
 * Bukkit access runs here — only the immutable registry snapshot, the
 * selection/config snapshots and the injected revision sources.
 */
public final class SnapshotExpandValidator implements ExpandValidator {

    private final LandRegistryStore registryStore;
    private final ClaimValidator.RevisionSource revisions;
    private final ClaimValidator.SessionGenerationSource generations;
    private final SnapshotClaimValidator.DepthSource depths;
    private final java.util.function.ToLongFunction<OwnerRef> ownerTotalChunks;
    private final ClaimValidator.StructureRevisionSource structures;
    private final WorldClaimPolicy worldPolicy;

    public SnapshotExpandValidator(LandRegistryStore registryStore) {
        this(registryStore, ClaimValidator.RevisionSource.none(),
                ClaimValidator.SessionGenerationSource.none(),
                chunk -> 64, owner -> 0L,
                ClaimValidator.StructureRevisionSource.none());
    }

    public SnapshotExpandValidator(
            LandRegistryStore registryStore,
            ClaimValidator.RevisionSource revisions,
            ClaimValidator.SessionGenerationSource generations,
            SnapshotClaimValidator.DepthSource depths,
            java.util.function.ToLongFunction<OwnerRef> ownerTotalChunks,
            ClaimValidator.StructureRevisionSource structures) {
        this(registryStore, revisions, generations, depths, ownerTotalChunks, structures,
                WorldClaimPolicy.allowAll());
    }

    /**
     * Full constructor with the per-world claim gate. A {@code null} policy
     * fails closed to deny-all so a half-wired validator can never treat an
     * unknown world as enabled.
     */
    public SnapshotExpandValidator(
            LandRegistryStore registryStore,
            ClaimValidator.RevisionSource revisions,
            ClaimValidator.SessionGenerationSource generations,
            SnapshotClaimValidator.DepthSource depths,
            java.util.function.ToLongFunction<OwnerRef> ownerTotalChunks,
            ClaimValidator.StructureRevisionSource structures,
            WorldClaimPolicy worldPolicy) {
        this.registryStore = Objects.requireNonNull(registryStore, "registryStore");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.depths = Objects.requireNonNull(depths, "depths");
        this.ownerTotalChunks = Objects.requireNonNull(ownerTotalChunks, "ownerTotalChunks");
        this.structures = Objects.requireNonNull(structures, "structures");
        this.worldPolicy = worldPolicy == null ? WorldClaimPolicy.denyAll("world.unknown") : worldPolicy;
    }

    @Override
    public ValidatedExpand validate(ExpandRequest request) throws ClaimRejectedException {
        Objects.requireNonNull(request, "request");
        checkWorldPolicy(request);
        checkGeneration(request);
        checkRevision(request);
        LandSnapshot target = resolveTarget(request);
        long expectedStructure = checkStructure(request);
        checkUnion(request, target);
        List<ValidatedExpand.ChunkDetail> details = request.delta().stream()
                .sorted((a, b) -> {
                    int c = Integer.compare(a.chunkX(), b.chunkX());
                    return c != 0 ? c : Integer.compare(a.chunkZ(), b.chunkZ());
                })
                .map(chunk -> new ValidatedExpand.ChunkDetail(chunk, depths.depthFor(chunk)))
                .toList();
        return new ValidatedExpand(request.targetLandId(), target.ownerRef(), request.actorUuid(),
                request.worldId(), target.displayName(), details,
                basisOf(target.ownerRef()), expectedStructure);
    }

    private long basisOf(OwnerRef owner) {
        if (owner instanceof OwnerRef.ServerOwnerRef) {
            return 0L;
        }
        long basis;
        try {
            basis = ownerTotalChunks.applyAsLong(owner);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("owner total source failed", failure);
        }
        if (basis < 0) {
            throw new IllegalStateException("owner total must not be negative");
        }
        return basis;
    }

    private void checkWorldPolicy(ExpandRequest request) {
        if (request.owner() instanceof OwnerRef.ServerOwnerRef) {
            return;
        }
        worldPolicy.checkClaimAllowed(request.worldId());
    }

    private void checkGeneration(ExpandRequest request) {
        Long expected = request.sessionGeneration();
        if (expected == null) {
            throw new ClaimRejectedException("expand.stale");
        }
        OptionalLong current;
        try {
            current = generations.currentGeneration(request.actorUuid());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("generation source failed", failure);
        }
        if (current.isEmpty() || current.getAsLong() != expected.longValue()) {
            throw new ClaimRejectedException("expand.stale");
        }
    }

    private void checkRevision(ExpandRequest request) {
        Long expected = request.selectionRevision();
        if (expected == null) {
            throw new ClaimRejectedException("expand.stale");
        }
        OptionalLong current;
        try {
            current = revisions.currentRevision(request.actorUuid());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("revision source failed", failure);
        }
        if (current.isEmpty() || current.getAsLong() != expected.longValue()) {
            throw new ClaimRejectedException("expand.stale");
        }
    }

    private long checkStructure(ExpandRequest request) {
        Long expected = request.structureRevision();
        if (expected == null) {
            throw new ClaimRejectedException("expand.stale");
        }
        OptionalLong current;
        try {
            current = structures.currentRevision(request.targetLandId());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("structure source failed", failure);
        }
        if (current.isEmpty() || current.getAsLong() < 0) {
            throw new ClaimRejectedException("structure.unavailable");
        }
        if (current.getAsLong() != expected.longValue()) {
            throw new ClaimRejectedException("structure.stale");
        }
        return expected.longValue();
    }

    private LandSnapshot resolveTarget(ExpandRequest request) {
        var snapshot = registryStore.snapshot();
        if (snapshot == null) {
            throw new ClaimRejectedException("expand.unknown_land");
        }
        LandSnapshot target;
        try {
            target = snapshot.land(request.targetLandId());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("runtime snapshot lookup failed", failure);
        }
        if (target == null) {
            throw new ClaimRejectedException("expand.unknown_land");
        }
        if (!request.worldId().equals(target.worldId())) {
            throw new ClaimRejectedException("expand.world_mismatch");
        }
        if (!request.owner().key().equals(target.ownerRef().key())) {
            throw new ClaimRejectedException("expand.owner_mismatch");
        }
        return target;
    }

    private void checkUnion(ExpandRequest request, LandSnapshot target) {
        var snapshot = registryStore.snapshot();
        if (snapshot == null) {
            throw new ClaimRejectedException("expand.unknown_land");
        }
        for (ChunkKey chunk : request.delta()) {
            if (target.chunks().contains(chunk)) {
                throw new ClaimRejectedException("expand.overlap");
            }
            LandId occupying;
            try {
                occupying = snapshot.findLandId(request.worldId(), chunk.chunkX(), chunk.chunkZ());
            } catch (RuntimeException failure) {
                throw new IllegalStateException("runtime snapshot lookup failed", failure);
            }
            if (occupying == null) {
                continue;
            }
            if (occupying.equals(target.id())) {
                throw new ClaimRejectedException("expand.overlap");
            }
            LandSnapshot other;
            try {
                other = snapshot.land(occupying);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("runtime snapshot lookup failed", failure);
            }
            if (other != null && other.ownerRef().key().equals(target.ownerRef().key())) {
                // V1 never merges automatically: expanding into another land
                // of the same owner is rejected with guidance, not merged.
                throw new ClaimRejectedException("expand.merge_unsupported");
            }
            throw new ClaimRejectedException("land.chunk.conflict");
        }
        Set<ChunkKey> union = new HashSet<>(target.chunks().size() + request.delta().size());
        union.addAll(target.chunks());
        union.addAll(request.delta());
        ChunkGeometry geometry;
        try {
            geometry = new ChunkGeometry(union);
        } catch (RuntimeException failure) {
            throw new ClaimRejectedException("expand.disconnected");
        }
        if (!geometry.isConnected()) {
            throw new ClaimRejectedException("expand.disconnected");
        }
        if (!geometry.holes().isEmpty()) {
            throw new ClaimRejectedException("expand.hole");
        }
    }
}

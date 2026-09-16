package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Production step-one validator for whole-land delete over in-memory state
 * only.
 *
 * <p>Checks, in order: the required structure token against its live source
 * (a missing, stale or unresolvable token fails closed), target resolution
 * with owner and world agreement, and a non-empty durable chunk set (the saga
 * refunds the full durable cost basis of every chunk, never the current
 * pricing table). Unlike shrink there is no delta membership, connectivity or
 * SubLand containment check: the whole land goes, SubLands with it.
 *
 * <p>No per-world claim gate lives here: a disabled world forbids new claims
 * but existing lands may still be deleted with a refund. Server-owned land
 * keeps its existing bypass and refunds zero.
 *
 * <p>No SQL, no Economy, no Bukkit access runs here — only the immutable
 * registry snapshot and the injected revision source. The database stays the
 * final authority through the atomic commit constraints.
 */
public final class SnapshotDeleteValidator implements DeleteValidator {

    private final LandRegistryStore registryStore;
    private final ClaimValidator.StructureRevisionSource structures;

    public SnapshotDeleteValidator(LandRegistryStore registryStore) {
        this(registryStore, ClaimValidator.StructureRevisionSource.none());
    }

    public SnapshotDeleteValidator(
            LandRegistryStore registryStore,
            ClaimValidator.StructureRevisionSource structures) {
        this.registryStore = Objects.requireNonNull(registryStore, "registryStore");
        this.structures = Objects.requireNonNull(structures, "structures");
    }

    @Override
    public ValidatedDelete validate(DeleteRequest request) throws ClaimRejectedException {
        Objects.requireNonNull(request, "request");
        long expectedStructure = checkStructure(request);
        LandSnapshot target = resolveTarget(request);
        List<ChunkKey> ordered = new ArrayList<>(target.chunks());
        if (ordered.isEmpty()) {
            throw new ClaimRejectedException("delete.empty_land");
        }
        ordered.sort((a, b) -> {
            int c = Integer.compare(a.chunkX(), b.chunkX());
            return c != 0 ? c : Integer.compare(a.chunkZ(), b.chunkZ());
        });
        return new ValidatedDelete(request.targetLandId(), target.ownerRef(), request.actorUuid(),
                request.worldId(), target.displayName(), List.copyOf(ordered),
                expectedStructure);
    }

    private long checkStructure(DeleteRequest request) {
        Long expected = request.structureRevision();
        if (expected == null) {
            throw new ClaimRejectedException("delete.stale");
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

    private LandSnapshot resolveTarget(DeleteRequest request) {
        var snapshot = registryStore.snapshot();
        if (snapshot == null) {
            throw new ClaimRejectedException("delete.unknown_land");
        }
        LandSnapshot target;
        try {
            target = snapshot.land(request.targetLandId());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("runtime snapshot lookup failed", failure);
        }
        if (target == null) {
            throw new ClaimRejectedException("delete.unknown_land");
        }
        if (!request.worldId().equals(target.worldId())) {
            throw new ClaimRejectedException("delete.world_mismatch");
        }
        if (!request.owner().key().equals(target.ownerRef().key())) {
            throw new ClaimRejectedException("delete.owner_mismatch");
        }
        return target;
    }
}

package com.smile.chunkland.claim;

import com.smile.chunkland.api.geometry.ChunkGeometry;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Production step-one validator for land shrink over in-memory state only.
 *
 * <p>Checks, in order: the required session generation, selection revision
 * and structure tokens against their live sources (any missing, stale or
 * unresolvable token fails closed), target resolution with owner and world
 * agreement, delta membership (every removed chunk must belong to the target
 * land in the same world), the zero remainder (removing the last chunk is
 * rejected towards the delete flow and never writes an empty land), remaining
 * 4-neighbor connectivity (a removal that splits the land is rejected, while
 * a central hole left behind stays legal), and SubLand containment (any
 * SubLand whose chunk projection intersects the delta is reported and the
 * removal is blocked instead of orphaning it).
 *
 * <p>Unlike expansion, there is deliberately no per-world claim gate here:
 * a disabled world forbids new claims but existing lands may still shrink
 * with a refund. Server-owned land keeps its existing bypass and refunds zero.
 *
 * <p>Geometry uses only {@link ChunkGeometry}: JDK-only analysis over chunk
 * coordinates that never loads a World or Chunk. No SQL, no Economy, no
 * Bukkit access runs here — only the immutable registry snapshot, the
 * selection/config snapshots and the injected revision sources. The database
 * stays the final authority through the atomic commit constraints.
 */
public final class SnapshotShrinkValidator implements ShrinkValidator {

    private final LandRegistryStore registryStore;
    private final ClaimValidator.RevisionSource revisions;
    private final ClaimValidator.SessionGenerationSource generations;
    private final ClaimValidator.StructureRevisionSource structures;

    public SnapshotShrinkValidator(LandRegistryStore registryStore) {
        this(registryStore, ClaimValidator.RevisionSource.none(),
                ClaimValidator.SessionGenerationSource.none(),
                ClaimValidator.StructureRevisionSource.none());
    }

    public SnapshotShrinkValidator(
            LandRegistryStore registryStore,
            ClaimValidator.RevisionSource revisions,
            ClaimValidator.SessionGenerationSource generations,
            ClaimValidator.StructureRevisionSource structures) {
        this.registryStore = Objects.requireNonNull(registryStore, "registryStore");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.structures = Objects.requireNonNull(structures, "structures");
    }

    @Override
    public ValidatedShrink validate(ShrinkRequest request) throws ClaimRejectedException {
        Objects.requireNonNull(request, "request");
        checkGeneration(request);
        checkRevision(request);
        LandSnapshot target = resolveTarget(request);
        long expectedStructure = checkStructure(request);
        checkMembership(request, target);
        Set<ChunkKey> remaining = remainingAfter(request, target);
        if (remaining.isEmpty()) {
            throw new ClaimRejectedException("shrink.delete_required");
        }
        checkConnected(request, remaining);
        checkSubLands(request, target);
        List<ChunkKey> ordered = request.delta().stream()
                .sorted((a, b) -> {
                    int c = Integer.compare(a.chunkX(), b.chunkX());
                    return c != 0 ? c : Integer.compare(a.chunkZ(), b.chunkZ());
                })
                .toList();
        return new ValidatedShrink(request.targetLandId(), target.ownerRef(), request.actorUuid(),
                request.worldId(), target.displayName(), ordered, expectedStructure, List.of());
    }

    private void checkGeneration(ShrinkRequest request) {
        Long expected = request.sessionGeneration();
        if (expected == null) {
            throw new ClaimRejectedException("shrink.stale");
        }
        OptionalLong current;
        try {
            current = generations.currentGeneration(request.actorUuid());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("generation source failed", failure);
        }
        if (current.isEmpty() || current.getAsLong() != expected.longValue()) {
            throw new ClaimRejectedException("shrink.stale");
        }
    }

    private void checkRevision(ShrinkRequest request) {
        Long expected = request.selectionRevision();
        if (expected == null) {
            throw new ClaimRejectedException("shrink.stale");
        }
        OptionalLong current;
        try {
            current = revisions.currentRevision(request.actorUuid());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("revision source failed", failure);
        }
        if (current.isEmpty() || current.getAsLong() != expected.longValue()) {
            throw new ClaimRejectedException("shrink.stale");
        }
    }

    private long checkStructure(ShrinkRequest request) {
        Long expected = request.structureRevision();
        if (expected == null) {
            throw new ClaimRejectedException("shrink.stale");
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

    private LandSnapshot resolveTarget(ShrinkRequest request) {
        var snapshot = registryStore.snapshot();
        if (snapshot == null) {
            throw new ClaimRejectedException("shrink.unknown_land");
        }
        LandSnapshot target;
        try {
            target = snapshot.land(request.targetLandId());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("runtime snapshot lookup failed", failure);
        }
        if (target == null) {
            throw new ClaimRejectedException("shrink.unknown_land");
        }
        if (!request.worldId().equals(target.worldId())) {
            throw new ClaimRejectedException("shrink.world_mismatch");
        }
        if (!request.owner().key().equals(target.ownerRef().key())) {
            throw new ClaimRejectedException("shrink.owner_mismatch");
        }
        return target;
    }

    private void checkMembership(ShrinkRequest request, LandSnapshot target) {
        for (ChunkKey chunk : request.delta()) {
            if (!request.worldId().equals(chunk.worldId())) {
                throw new ClaimRejectedException("shrink.world_mismatch");
            }
            if (!target.chunks().contains(chunk)) {
                throw new ClaimRejectedException("shrink.foreign_chunk");
            }
        }
    }

    private static Set<ChunkKey> remainingAfter(ShrinkRequest request, LandSnapshot target) {
        Set<ChunkKey> remaining = new HashSet<>(target.chunks());
        remaining.removeAll(request.delta());
        return remaining;
    }

    private void checkConnected(ShrinkRequest request, Set<ChunkKey> remaining) {
        ChunkGeometry geometry;
        try {
            geometry = new ChunkGeometry(remaining);
        } catch (RuntimeException failure) {
            throw new ClaimRejectedException("shrink.split");
        }
        if (!geometry.isConnected()) {
            throw new ClaimRejectedException("shrink.split");
        }
        // A central hole left behind by the removal is legal and never counts
        // as a split: only the remaining set's own connectivity matters.
    }

    private void checkSubLands(ShrinkRequest request, LandSnapshot target) {
        List<String> affected = new ArrayList<>();
        for (SubLandSnapshot sub : target.subLands()) {
            for (ChunkKey chunk : sub.chunks()) {
                if (request.delta().contains(chunk)) {
                    affected.add(sub.id().value().toString());
                    break;
                }
            }
        }
        if (!affected.isEmpty()) {
            StringBuilder detail = new StringBuilder("affected sublands: ");
            for (int i = 0; i < affected.size(); i++) {
                if (i > 0) {
                    detail.append(',');
                }
                detail.append(affected.get(i));
            }
            throw new ClaimRejectedException("shrink.subland_overlap", detail.toString());
        }
    }

    /** Resolve the target land for command wiring without running validation. */
    LandId targetOf(ShrinkRequest request) {
        return request.targetLandId();
    }
}

package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Production step-one validator over in-memory state only.
 *
 * <p>Checks, in order: the optional session generation against the live
 * source, the optional confirmation revision token against the live source,
 * the optional structure token against the live structure source,
 * four-direction connectivity of the chunk set (diagonals do not count), and
 * collision of every chunk against the published runtime snapshot. Limit
 * checks stay with the saga's quota reservation; the database stays the final
 * authority through the atomic commit constraints.
 */
public final class SnapshotClaimValidator implements ClaimValidator {

    private final LandRegistryStore registryStore;
    private final RevisionSource revisions;
    private final SessionGenerationSource generations;
    private final DepthSource depths;
    private final java.util.function.ToLongFunction<OwnerRef> ownerTotalChunks;
    private final StructureRevisionSource structures;

    /** Per-chunk persisted protection depth for the claim lot. */
    @FunctionalInterface
    public interface DepthSource {
        int depthFor(ChunkKey chunk);
    }

    public SnapshotClaimValidator(LandRegistryStore registryStore) {
        this(registryStore, ClaimValidator.RevisionSource.none(), chunk -> 64, owner -> 0L,
                ClaimValidator.StructureRevisionSource.none());
    }

    public SnapshotClaimValidator(LandRegistryStore registryStore, RevisionSource revisions, DepthSource depths) {
        this(registryStore, revisions, depths, owner -> 0L,
                ClaimValidator.StructureRevisionSource.none());
    }

    public SnapshotClaimValidator(LandRegistryStore registryStore, RevisionSource revisions,
            DepthSource depths, java.util.function.ToLongFunction<OwnerRef> ownerTotalChunks) {
        this(registryStore, revisions, depths, ownerTotalChunks,
                ClaimValidator.StructureRevisionSource.none());
    }

    public SnapshotClaimValidator(LandRegistryStore registryStore, RevisionSource revisions,
            DepthSource depths, java.util.function.ToLongFunction<OwnerRef> ownerTotalChunks,
            StructureRevisionSource structures) {
        this(registryStore, revisions, ClaimValidator.SessionGenerationSource.none(), depths,
                ownerTotalChunks, structures);
    }

    public SnapshotClaimValidator(LandRegistryStore registryStore, RevisionSource revisions,
            SessionGenerationSource generations, DepthSource depths,
            java.util.function.ToLongFunction<OwnerRef> ownerTotalChunks,
            StructureRevisionSource structures) {
        this.registryStore = Objects.requireNonNull(registryStore, "registryStore");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.depths = Objects.requireNonNull(depths, "depths");
        this.ownerTotalChunks = Objects.requireNonNull(ownerTotalChunks, "ownerTotalChunks");
        this.structures = Objects.requireNonNull(structures, "structures");
    }

    @Override
    public ValidatedClaim validate(ClaimRequest request) throws ClaimRejectedException {
        Objects.requireNonNull(request, "request");
        checkGeneration(request);
        checkRevision(request);
        checkStructure(request);
        checkConnected(request.worldId(), request.chunks());
        checkCollision(request);
        List<ValidatedClaim.ChunkDetail> details = request.chunks().stream()
                .sorted((a, b) -> {
                    int c = Integer.compare(a.chunkX(), b.chunkX());
                    return c != 0 ? c : Integer.compare(a.chunkZ(), b.chunkZ());
                })
                .map(chunk -> new ValidatedClaim.ChunkDetail(chunk, depths.depthFor(chunk)))
                .toList();
        return new ValidatedClaim(new LandId(UUID.randomUUID()), request.owner(), request.actorUuid(),
                request.worldId(), request.displayName(), details, basisOf(request.owner()));
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

    private void checkGeneration(ClaimRequest request) {
        Long expected = request.sessionGeneration();
        if (expected == null) {
            return;
        }
        OptionalLong current;
        try {
            current = generations.currentGeneration(request.actorUuid());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("generation source failed", failure);
        }
        if (current.isEmpty() || current.getAsLong() != expected.longValue()) {
            throw new ClaimRejectedException("selection.stale");
        }
    }

    private void checkRevision(ClaimRequest request) {
        Long expected = request.selectionRevision();
        if (expected == null) {
            return;
        }
        OptionalLong current;
        try {
            current = revisions.currentRevision(request.actorUuid());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("revision source failed", failure);
        }
        if (current.isEmpty() || current.getAsLong() != expected.longValue()) {
            throw new ClaimRejectedException("selection.stale");
        }
    }

    private void checkStructure(ClaimRequest request) {
        LandId target = request.targetLandId();
        Long expected = request.structureRevision();
        if (target == null || expected == null) {
            return;
        }
        OptionalLong current;
        try {
            current = structures.currentRevision(target);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("structure source failed", failure);
        }
        if (current.isEmpty() || current.getAsLong() < 0) {
            throw new ClaimRejectedException("structure.unavailable");
        }
        if (current.getAsLong() != expected.longValue()) {
            throw new ClaimRejectedException("structure.stale");
        }
    }

    private static void checkConnected(UUID worldId, Set<ChunkKey> chunks) {
        if (chunks.size() == 1) {
            return;
        }
        Set<Packed> remaining = new HashSet<>();
        for (ChunkKey chunk : chunks) {
            remaining.add(new Packed(chunk.chunkX(), chunk.chunkZ()));
        }
        Set<Packed> frontier = new HashSet<>();
        frontier.add(remaining.iterator().next());
        Set<Packed> visited = new HashSet<>();
        while (!frontier.isEmpty()) {
            Packed next = frontier.iterator().next();
            frontier.remove(next);
            if (!remaining.remove(next) && !visited.add(next)) {
                continue;
            }
            visited.add(next);
            for (int[] step : STEPS) {
                Packed neighbour = new Packed(next.x + step[0], next.z + step[1]);
                if (remaining.contains(neighbour) && !visited.contains(neighbour)) {
                    frontier.add(neighbour);
                }
            }
        }
        if (!remaining.isEmpty()) {
            throw new ClaimRejectedException("selection.disconnected");
        }
    }

    private void checkCollision(ClaimRequest request) {
        var snapshot = registryStore.snapshot();
        if (snapshot == null) {
            return;
        }
        for (ChunkKey chunk : request.chunks()) {
            LandId occupying;
            try {
                occupying = snapshot.findLandId(request.worldId(), chunk.chunkX(), chunk.chunkZ());
            } catch (RuntimeException failure) {
                throw new IllegalStateException("runtime snapshot lookup failed", failure);
            }
            if (occupying != null) {
                throw new ClaimRejectedException("land.chunk.conflict");
            }
        }
    }

    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private record Packed(int x, int z) {
    }
}

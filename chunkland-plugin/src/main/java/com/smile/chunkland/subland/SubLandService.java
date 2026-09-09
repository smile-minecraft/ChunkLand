package com.smile.chunkland.subland;

import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.land.SubLandTopologyValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure SubLand create/update/delete domain seam.
 *
 * <p>Bukkit-free and side-effect free: every method validates against the
 * supplied immutable parent snapshot and either returns the next parent
 * snapshot (with {@code structureRevision} bumped through the
 * {@link LandSnapshot} with-style copy) or throws fail-closed. Persistence,
 * audit, runtime publish and selection cleanup live in
 * {@link SubLandMutationRunner}; this unit never touches SQL, files, or the
 * server.
 *
 * <p>Check order is fixed so callers get the most specific rejection first:
 * geometry shape, parent identity, containment/world, 3D overlap, per-land
 * limit (create only), effective-depth floor with explicit confirmation, then
 * the optimistic structure lock (update/delete) and id presence. A stale
 * structure revision throws before any value is built, so rejected
 * update/delete calls cannot produce a partial side effect downstream.
 */
public final class SubLandService {

    private SubLandService() {
    }

    /**
     * Validate a create candidate and return the next parent snapshot.
     *
     * @param parent live parent snapshot
     * @param candidate precise candidate (must carry a {@code Cuboid})
     * @param existingCount SubLand count observed with the parent snapshot
     * @param maxSublandsPerLand enforced limit (at least the candidate slot must remain)
     * @param effectiveMinY parent effective floor for the candidate footprint
     * @param depthPort explicit depth-extend confirmation seam (fail-closed when denying)
     * @return the parent snapshot with the candidate appended
     * @throws IllegalArgumentException on legacy geometry, parent mismatch,
     *         containment/world failure, or 3D overlap
     * @throws IllegalStateException on duplicate id or a breached limit
     * @throws DepthExtendConfirmationRequired when the candidate extends below
     *         the effective floor without an explicit confirmation
     */
    public static LandSnapshot applyCreate(
            LandSnapshot parent,
            SubLandSnapshot candidate,
            int existingCount,
            int maxSublandsPerLand,
            int effectiveMinY,
            DepthExtensionPort depthPort) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(candidate, "candidate");
        if (existingCount < 0) {
            throw new IllegalArgumentException("existingCount must be >= 0: " + existingCount);
        }
        if (maxSublandsPerLand < 0) {
            throw new IllegalArgumentException("maxSublandsPerLand must be >= 0: " + maxSublandsPerLand);
        }
        requirePrecise(candidate);
        requireParentMatch(parent, candidate);
        SubLandTopologyValidator.validate(parent, candidate);
        if (hasId(parent, candidate.id())) {
            throw new IllegalStateException("SubLand id already exists: " + candidate.id());
        }
        if (existingCount >= maxSublandsPerLand) {
            throw new IllegalStateException(
                    "max-sublands-per-land reached (" + existingCount + "/" + maxSublandsPerLand + ")");
        }
        requireDepthOrConfirmation(parent, candidate, effectiveMinY, depthPort);
        return parent.addSubLand(candidate);
    }

    /**
     * Validate an update candidate and return the next parent snapshot.
     *
     * @param parent live parent snapshot
     * @param candidate replacement precise snapshot (same id as the stored one)
     * @param expectedStructureRevision optimistic lock observed with the confirmation token
     * @param effectiveMinY parent effective floor for the candidate footprint
     * @param depthPort explicit depth-extend confirmation seam (fail-closed when denying)
     * @return the parent snapshot with the stored entry replaced
     * @throws IllegalStateException on a stale structure revision or an unknown id
     */
    public static LandSnapshot applyUpdate(
            LandSnapshot parent,
            SubLandSnapshot candidate,
            long expectedStructureRevision,
            int effectiveMinY,
            DepthExtensionPort depthPort) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(candidate, "candidate");
        requirePrecise(candidate);
        requireParentMatch(parent, candidate);
        requireCurrentStructure(parent, expectedStructureRevision);
        SubLandSnapshot stored = findById(parent, candidate.id());
        if (stored == null) {
            throw new IllegalStateException("unknown SubLand id: " + candidate.id());
        }
        SubLandTopologyValidator.validate(parent, candidate);
        requireDepthOrConfirmation(parent, candidate, effectiveMinY, depthPort);
        List<SubLandSnapshot> next = new ArrayList<>(parent.subLands().size());
        for (SubLandSnapshot sub : parent.subLands()) {
            next.add(sub.id().equals(candidate.id()) ? candidate : sub);
        }
        return parent.replaceSubLands(List.copyOf(next));
    }

    /**
     * Validate a delete and return the next parent snapshot.
     *
     * @param parent live parent snapshot
     * @param target SubLand to remove
     * @param expectedStructureRevision optimistic lock observed with the confirmation token
     * @return the parent snapshot without the target
     * @throws IllegalStateException on a stale structure revision or an unknown id
     */
    public static LandSnapshot applyDelete(
            LandSnapshot parent, SubLandId target, long expectedStructureRevision) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(target, "target");
        requireCurrentStructure(parent, expectedStructureRevision);
        if (findById(parent, target) == null) {
            throw new IllegalStateException("unknown SubLand id: " + target);
        }
        return parent.removeSubLand(target);
    }

    private static void requirePrecise(SubLandSnapshot candidate) {
        if (candidate.cuboid() == null) {
            throw new IllegalArgumentException(
                    "SubLand " + candidate.id() + " must carry precise Cuboid geometry");
        }
    }

    private static void requireParentMatch(LandSnapshot parent, SubLandSnapshot candidate) {
        if (!parent.id().equals(candidate.parentLandId())) {
            throw new IllegalArgumentException(
                    "candidate parentLandId (" + candidate.parentLandId()
                            + ") does not match parent id (" + parent.id() + ")");
        }
    }

    private static void requireDepthOrConfirmation(
            LandSnapshot parent,
            SubLandSnapshot candidate,
            int effectiveMinY,
            DepthExtensionPort depthPort) {
        int requestedMinY = candidate.cuboid().minY();
        if (requestedMinY >= effectiveMinY) {
            return;
        }
        if (depthPort != null
                && depthPort.isDepthExtendConfirmed(parent.id(), effectiveMinY, requestedMinY)) {
            return;
        }
        throw new DepthExtendConfirmationRequired(effectiveMinY, requestedMinY);
    }

    private static void requireCurrentStructure(LandSnapshot parent, long expectedStructureRevision) {
        if (expectedStructureRevision < 0) {
            throw new IllegalArgumentException(
                    "expectedStructureRevision must be >= 0: " + expectedStructureRevision);
        }
        if (parent.structureRevision() != expectedStructureRevision) {
            throw new IllegalStateException(
                    "stale parent structure revision: expected " + expectedStructureRevision
                            + " but live is " + parent.structureRevision());
        }
    }

    private static boolean hasId(LandSnapshot parent, SubLandId id) {
        return findById(parent, id) != null;
    }

    private static SubLandSnapshot findById(LandSnapshot parent, SubLandId id) {
        for (SubLandSnapshot sub : parent.subLands()) {
            if (sub.id().equals(id)) {
                return sub;
            }
        }
        return null;
    }
}

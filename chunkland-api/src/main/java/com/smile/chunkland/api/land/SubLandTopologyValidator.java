package com.smile.chunkland.api.land;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure validation of SubLand topology against a parent Land.
 *
 * <p>This validator is deliberately separate from the immutable snapshots. It
 * checks every chunk in a candidate's X/Z projection for parent containment and
 * checks 3D block intersection for every pair of SubLands. Different worlds do
 * not intersect, while inclusive cuboid boundaries do.
 */
public final class SubLandTopologyValidator {

    private SubLandTopologyValidator() {
        // static utility only
    }

    /** Validate all SubLands in {@code land} for containment and pairwise overlap. */
    public static void validate(LandSnapshot land) {
        Objects.requireNonNull(land, "land");
        List<SubLandSnapshot> subLands = land.subLands();
        for (SubLandSnapshot subLand : subLands) {
            validateContained(land, subLand);
        }
        for (int i = 0; i < subLands.size(); i++) {
            for (int j = i + 1; j < subLands.size(); j++) {
                if (overlaps(subLands.get(i), subLands.get(j))) {
                    throw overlapException(subLands.get(i), subLands.get(j));
                }
            }
        }
    }

    /** Validate one candidate against its parent and the parent's other SubLands. */
    public static void validate(LandSnapshot parent, SubLandSnapshot candidate) {
        validateContained(parent, candidate);
        for (SubLandSnapshot existing : parent.subLands()) {
            if (existing.id().equals(candidate.id())) {
                continue;
            }
            if (overlaps(candidate, existing)) {
                throw overlapException(candidate, existing);
            }
        }
    }

    /** Validate only parent relationship, world, and complete chunk projection containment. */
    public static void validateContained(LandSnapshot parent, SubLandSnapshot candidate) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(candidate, "candidate");
        if (!parent.id().equals(candidate.parentLandId())) {
            throw new IllegalArgumentException(
                    "candidate parentLandId (" + candidate.parentLandId()
                            + ") does not match parent id (" + parent.id() + ")");
        }
        if (!isContained(parent, candidate)) {
            throw new IllegalArgumentException(
                    "candidate SubLand is not fully contained in the parent chunk set");
        }
    }

    /**
     * Validate that {@code candidate} does not overlap any supplied SubLand.
     *
     * <p>The collection is read only; no state is retained by this validator.
     */
    public static void validateNoOverlap(
            SubLandSnapshot candidate, Collection<SubLandSnapshot> existing) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(existing, "existing");
        for (SubLandSnapshot other : existing) {
            Objects.requireNonNull(other, "existing must not contain null");
            if (overlaps(candidate, other)) {
                throw overlapException(candidate, other);
            }
        }
    }

    /** Return whether every projected chunk of {@code candidate} exists in {@code parent}. */
    public static boolean isContained(LandSnapshot parent, SubLandSnapshot candidate) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(candidate, "candidate");
        if (!parent.id().equals(candidate.parentLandId())) {
            return false;
        }
        for (ChunkKey chunk : candidate.chunks()) {
            if (!parent.worldId().equals(chunk.worldId()) || !parent.chunks().contains(chunk)) {
                return false;
            }
        }
        return true;
    }

    /** Return whether two SubLands occupy at least one common block in the same world. */
    public static boolean overlaps(SubLandSnapshot first, SubLandSnapshot second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        UUID firstWorld = worldIdOf(first);
        UUID secondWorld = worldIdOf(second);
        if (firstWorld == null || secondWorld == null || !firstWorld.equals(secondWorld)) {
            return false;
        }
        if (first.cuboid() != null && second.cuboid() != null) {
            return first.cuboid().intersects(second.cuboid());
        }

        if (first.maxBlockY() < second.minBlockY()
                || second.maxBlockY() < first.minBlockY()) {
            return false;
        }
        for (ChunkKey chunk : first.chunks()) {
            if (second.chunks().contains(chunk)) {
                return true;
            }
        }
        return false;
    }

    private static UUID worldIdOf(SubLandSnapshot subLand) {
        for (ChunkKey chunk : subLand.chunks()) {
            return chunk.worldId();
        }
        return null;
    }

    private static IllegalArgumentException overlapException(
            SubLandSnapshot first, SubLandSnapshot second) {
        return new IllegalArgumentException(
                "SubLand " + first.id() + " overlaps SubLand " + second.id());
    }
}

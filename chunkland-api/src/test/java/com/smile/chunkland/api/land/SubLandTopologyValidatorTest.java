package com.smile.chunkland.api.land;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubLandTopologyValidatorTest {

    private static final UUID WORLD = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_WORLD = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void preciseSubLandRetainsCuboidAndCompleteProjection() {
        var parent = new LandId(UUID.randomUUID());
        var cuboid = new Cuboid(-1, -20, 17, 16, 30, 32);
        var subLand = sub(parent, cuboid, WORLD);

        assertTrue(subLand.cuboid().equals(cuboid));
        assertTrue(subLand.chunks().equals(cuboid.coveredChunks(WORLD)));
        assertThrows(UnsupportedOperationException.class, () -> subLand.chunks().clear());
    }

    @Test
    void preciseConstructorRejectsAnInconsistentProjection() {
        var parent = new LandId(UUID.randomUUID());
        var cuboid = new Cuboid(0, 0, 0, 16, 10, 16);

        assertThrows(IllegalArgumentException.class, () -> new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), parent, null, cuboid,
                Set.of(chunk(0, 0))));
    }

    @Test
    void legacyConstructorKeepsNonRectangularChunkSetWithoutInventingACuboid() {
        var chunks = Set.of(chunk(0, 0), chunk(1, 1));
        var subLand = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), new LandId(UUID.randomUUID()), null, 0, 10, chunks);

        assertTrue(subLand.cuboid() == null);
        assertTrue(subLand.chunks().equals(chunks));
    }

    @Test
    void preciseChunkMutationsFailWithoutChangingTheOriginalGeometry() {
        var subLand = sub(new LandId(UUID.randomUUID()), new Cuboid(1, 0, 1, 16, 10, 16), WORLD);
        var added = new ChunkKey(WORLD, 2, 2);

        assertThrows(UnsupportedOperationException.class, () -> subLand.addChunk(added));
        assertThrows(UnsupportedOperationException.class, () -> subLand.removeChunk(new ChunkKey(WORLD, 0, 0)));
        assertThrows(UnsupportedOperationException.class, () -> subLand.replaceChunks(Set.of(added)));
        assertTrue(subLand.cuboid().equals(new Cuboid(1, 0, 1, 16, 10, 16)));
        assertTrue(subLand.chunks().equals(subLand.cuboid().coveredChunks(WORLD)));
    }

    @Test
    void withCuboidRebuildsProjectionAndRetainsIdentityMetadata() {
        var parent = new LandId(UUID.randomUUID());
        var original = sub(parent, new Cuboid(1, 0, 1, 16, 10, 16), WORLD);
        var replacement = new Cuboid(16, -5, 16, 31, 20, 31);

        var updated = original.withCuboid(replacement);

        assertTrue(updated != original);
        assertTrue(updated.id().equals(original.id()));
        assertTrue(updated.parentLandId().equals(parent));
        assertTrue(updated.name() == null);
        assertTrue(updated.cuboid().equals(replacement));
        assertTrue(updated.chunks().equals(replacement.coveredChunks(WORLD)));
        assertTrue(updated.minBlockY() == -5);
        assertTrue(updated.maxBlockY() == 20);
        assertThrows(UnsupportedOperationException.class, () -> updated.chunks().clear());
        assertTrue(original.cuboid().equals(new Cuboid(1, 0, 1, 16, 10, 16)));
    }

    @Test
    void legacyChunkMutationsRemainAvailable() {
        var original = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), new LandId(UUID.randomUUID()), null, 0, 10,
                Set.of(chunk(0, 0)));

        var updated = original.addChunk(new ChunkKey(WORLD, 2, 2));

        assertTrue(updated.chunks().contains(new ChunkKey(WORLD, 2, 2)));
        assertTrue(original.chunks().equals(Set.of(chunk(0, 0))));
    }

    @Test
    void rejectsTwoCornersThatHideAParentHole() {
        var parentChunks = new HashSet<ChunkKey>();
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                if (x != 1 || z != 1) {
                    parentChunks.add(chunk(x, z));
                }
            }
        }
        var parent = land(parentChunks, List.of());
        var candidate = sub(parent.id(), new Cuboid(0, 0, 0, 47, 10, 47), WORLD);

        assertThrows(IllegalArgumentException.class, () -> SubLandTopologyValidator.validate(parent, candidate));
    }

    @Test
    void checksEveryProjectedChunkForContainment() {
        var parent = land(Set.of(chunk(0, 0), chunk(1, 0)), List.of());
        var candidate = sub(parent.id(), new Cuboid(1, 0, 1, 16, 10, 15), WORLD);

        assertDoesNotThrow(() -> SubLandTopologyValidator.validate(parent, candidate));
        assertTrue(SubLandTopologyValidator.isContained(parent, candidate));

        var outside = sub(parent.id(), new Cuboid(1, 0, 1, 32, 10, 15), WORLD);
        assertFalse(SubLandTopologyValidator.isContained(parent, outside));
        assertThrows(IllegalArgumentException.class, () -> SubLandTopologyValidator.validate(parent, outside));
    }

    @Test
    void rejectsRealOverlapIncludingNestedAndInclusiveContact() {
        var parent = land(rectangle(0, 0, 3, 3), List.of());
        var first = sub(parent.id(), new Cuboid(1, 0, 1, 30, 10, 30), WORLD);
        var nested = sub(parent.id(), new Cuboid(4, 2, 4, 20, 8, 20), WORLD);
        var touching = sub(parent.id(), new Cuboid(30, 10, 30, 31, 12, 31), WORLD);

        assertDoesNotThrow(() -> SubLandTopologyValidator.validate(parent, first));
        assertThrows(IllegalArgumentException.class, () -> SubLandTopologyValidator.validateNoOverlap(first, List.of(nested)));
        assertThrows(IllegalArgumentException.class, () -> SubLandTopologyValidator.validateNoOverlap(first, List.of(touching)));
    }

    @Test
    void allowsSameHorizontalProjectionWhenYDoesNotOverlap() {
        var parent = land(rectangle(0, 0, 1, 1), List.of());
        var lower = sub(parent.id(), new Cuboid(0, 0, 0, 31, 10, 31), WORLD);
        var upper = sub(parent.id(), new Cuboid(0, 11, 0, 31, 20, 31), WORLD);

        assertDoesNotThrow(() -> SubLandTopologyValidator.validateNoOverlap(lower, List.of(upper)));
        assertDoesNotThrow(() -> SubLandTopologyValidator.validate(
                land(rectangle(0, 0, 1, 1), List.of(lower, upper), parent.id())));
    }

    @Test
    void differentWorldsDoNotOverlapButCannotBeContainedInThisParent() {
        var parent = land(Set.of(chunk(0, 0)), List.of());
        var foreign = sub(parent.id(), new Cuboid(0, 0, 0, 15, 10, 15), OTHER_WORLD);

        assertFalse(SubLandTopologyValidator.overlaps(
                sub(parent.id(), new Cuboid(0, 0, 0, 15, 10, 15), WORLD), foreign));
        assertThrows(IllegalArgumentException.class, () -> SubLandTopologyValidator.validate(parent, foreign));
    }

    @Test
    void validatesWholeLandAndRejectsOverlappingEntries() {
        var parentId = new LandId(UUID.randomUUID());
        var first = sub(parentId, new Cuboid(0, 0, 0, 15, 10, 15), WORLD);
        var second = sub(parentId, new Cuboid(8, 0, 8, 23, 10, 23), WORLD);
        var land = land(Set.of(chunk(0, 0), chunk(1, 0), chunk(0, 1), chunk(1, 1)), List.of(first, second), parentId);

        assertThrows(IllegalArgumentException.class, () -> SubLandTopologyValidator.validate(land));
    }

    @Test
    void randomizedProjectionContainmentMatchesSetMembership() {
        var random = new Random(0x5EED_0601L);
        for (int iteration = 0; iteration < 100; iteration++) {
            var parentChunks = new HashSet<ChunkKey>();
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    if (random.nextBoolean()) {
                        parentChunks.add(chunk(x, z));
                    }
                }
            }
            var parent = land(parentChunks, List.of());
            int minX = random.nextInt(-48, 48);
            int maxX = minX + random.nextInt(1, 24);
            int minZ = random.nextInt(-48, 48);
            int maxZ = minZ + random.nextInt(1, 24);
            var candidate = sub(parent.id(), new Cuboid(minX, 0, minZ, maxX, 5, maxZ), WORLD);
            boolean expected = parentChunks.containsAll(candidate.chunks());

            assertEqualsByExpectation(expected, parent, candidate);
        }
    }

    private static void assertEqualsByExpectation(
            boolean expected, LandSnapshot parent, SubLandSnapshot candidate) {
        if (expected) {
            assertDoesNotThrow(() -> SubLandTopologyValidator.validate(parent, candidate));
        } else {
            assertThrows(IllegalArgumentException.class, () -> SubLandTopologyValidator.validate(parent, candidate));
        }
    }

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(WORLD, x, z);
    }

    private static Set<ChunkKey> rectangle(int minX, int minZ, int maxX, int maxZ) {
        var result = new HashSet<ChunkKey>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                result.add(chunk(x, z));
            }
        }
        return result;
    }

    private static SubLandSnapshot sub(LandId parent, Cuboid cuboid, UUID world) {
        return new SubLandSnapshot(new SubLandId(UUID.randomUUID()), parent, null, cuboid, world);
    }

    private static LandSnapshot land(Set<ChunkKey> chunks, List<SubLandSnapshot> subLands) {
        return land(chunks, subLands, new LandId(UUID.randomUUID()));
    }

    private static LandSnapshot land(
            Set<ChunkKey> chunks, List<SubLandSnapshot> subLands, LandId id) {
        return new LandSnapshot(
                id, "Home", "home", OwnerRef.server(), WORLD, chunks, new ArrayList<>(subLands),
                1L, 1L, Instant.EPOCH, Instant.EPOCH);
    }
}

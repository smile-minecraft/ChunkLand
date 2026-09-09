package com.smile.chunkland.subland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Domain tests for the SubLand service seam.
 */
class SubLandServiceTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    private static LandSnapshot parentWithChunks(LandId id, Set<ChunkKey> chunks, List<SubLandSnapshot> subs) {
        LandName name = LandName.of("Home");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(OWNER), WORLD, chunks, subs, 7L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static LandSnapshot singleChunkParent(LandId id) {
        return parentWithChunks(id, Set.of(new ChunkKey(WORLD, 0, 0)), List.of());
    }

    @Test
    void createValidCuboidIsContained() {
        LandId parentId = new LandId(UUID.randomUUID());
        LandSnapshot parent = singleChunkParent(parentId);
        SubLandSnapshot candidate = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), parentId, "den",
                new Cuboid(0, 60, 0, 15, 70, 15), WORLD);
        LandSnapshot next = SubLandService.applyCreate(parent, candidate, 0, 16, 50,
                (pid, eff, req) -> false);
        assertEquals(1, next.subLands().size());
        assertEquals(8L, next.structureRevision());
    }

    @Test
    void legacyCuboidFailsClosed() {
        LandId parentId = new LandId(UUID.randomUUID());
        LandSnapshot parent = singleChunkParent(parentId);
        SubLandSnapshot legacy = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), parentId, "legacy",
                60, 70, Set.of(new ChunkKey(WORLD, 0, 0)));
        assertThrows(IllegalArgumentException.class, () ->
                SubLandService.applyCreate(parent, legacy, 0, 16, 50, (pid, eff, req) -> false));
    }

    @Test
    void containmentFailureFailsClosed() {
        LandId parentId = new LandId(UUID.randomUUID());
        LandSnapshot parent = singleChunkParent(parentId);
        SubLandSnapshot outside = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), parentId, "far",
                new Cuboid(16, 60, 16, 31, 70, 31), UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () ->
                SubLandService.applyCreate(parent, outside, 0, 16, 50, (pid, eff, req) -> false));
    }

    @Test
    void overlapFailsClosed() {
        LandId parentId = new LandId(UUID.randomUUID());
        SubLandSnapshot existing = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), parentId, "a",
                new Cuboid(0, 60, 0, 15, 70, 15), WORLD);
        LandSnapshot parent = parentWithChunks(parentId,
                Set.of(new ChunkKey(WORLD, 0, 0)), List.of(existing));
        SubLandSnapshot overlapping = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), parentId, "b",
                new Cuboid(8, 65, 8, 20, 75, 20), WORLD);
        assertThrows(IllegalArgumentException.class, () ->
                SubLandService.applyCreate(parent, overlapping, 1, 16, 50, (pid, eff, req) -> false));
    }

    @Test
    void limitEnforcedAtSixteen() {
        LandId parentId = new LandId(UUID.randomUUID());
        LandSnapshot parent = singleChunkParent(parentId);
        SubLandSnapshot candidate = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), parentId, "extra",
                new Cuboid(0, 60, 0, 15, 70, 15), WORLD);
        assertThrows(IllegalStateException.class, () ->
                SubLandService.applyCreate(parent, candidate, 16, 16, 50, (pid, eff, req) -> false));
    }

    @Test
    void depthBelowEffectiveRequiresConfirmation() {
        LandId parentId = new LandId(UUID.randomUUID());
        LandSnapshot parent = singleChunkParent(parentId);
        SubLandSnapshot deep = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), parentId, "deep",
                new Cuboid(0, 30, 0, 15, 70, 15), WORLD);
        assertThrows(DepthExtendConfirmationRequired.class, () ->
                SubLandService.applyCreate(parent, deep, 0, 16, 50, (pid, eff, req) -> false));
        LandSnapshot next = SubLandService.applyCreate(parent, deep, 0, 16, 50, (pid, eff, req) -> true);
        assertEquals(1, next.subLands().size());
    }

    @Test
    void updateStaleStructureFailsWithoutSideEffect() {
        LandId parentId = new LandId(UUID.randomUUID());
        SubLandId sid = new SubLandId(UUID.randomUUID());
        SubLandSnapshot existing = new SubLandSnapshot(
                sid, parentId, "a", new Cuboid(0, 60, 0, 15, 70, 15), WORLD);
        LandSnapshot parent = parentWithChunks(parentId,
                Set.of(new ChunkKey(WORLD, 0, 0)), List.of(existing));
        SubLandSnapshot candidate = new SubLandSnapshot(
                sid, parentId, "renamed", new Cuboid(0, 60, 0, 15, 70, 15), WORLD);
        assertThrows(IllegalStateException.class, () ->
                SubLandService.applyUpdate(parent, candidate, 999L, 50, (pid, eff, req) -> false));
        assertTrue(parent.subLands().get(0).name().equals("a"));
    }
}

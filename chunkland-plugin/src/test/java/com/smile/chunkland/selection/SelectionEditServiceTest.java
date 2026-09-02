package com.smile.chunkland.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.geometry.BoundarySegment;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.runtime.index.WorldChunkIndex;
import com.smile.chunkland.config.LimitSettings;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SelectionEditServiceTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final LandId TARGET = new LandId(UUID.fromString("00000000-0000-0000-0000-000000000020"));
    private static final LandId OTHER = new LandId(UUID.fromString("00000000-0000-0000-0000-000000000021"));
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void rectangleUsesInclusiveAabbAndFloorDivisionForNegativeBlocks() {
        SelectionEditOutcome outcome = service((world, packed) -> null)
                .selectRectangle(session(SelectionMode.CREATE_LAND, Optional.empty(), Set.of(), Map.of()),
                        new SelectionPoint(WORLD, -1, 64, -1),
                        new SelectionPoint(WORLD, 32, 64, 32));

        assertTrue(outcome.accepted());
        assertEquals(16, outcome.selectedChunks().size());
        assertTrue(outcome.selectedChunks().contains(chunk(-1, -1)));
        assertTrue(outcome.selectedChunks().contains(chunk(2, 2)));
        assertEquals(1, outcome.componentCount());
        assertEquals(16, outcome.boundary().size());
    }

    @Test
    void rectangleAcceptsExactlyThirtyTwoByThirtyTwoAnd1024Chunks() {
        SelectionEditOutcome outcome = service((world, packed) -> null)
                .selectRectangle(session(SelectionMode.CREATE_LAND, Optional.empty(), Set.of(), Map.of()),
                        new SelectionPoint(WORLD, 0, 64, 0),
                        new SelectionPoint(WORLD, 511, 64, 511));

        assertTrue(outcome.accepted());
        assertEquals(1024, outcome.selectedChunks().size());
        assertEquals(1024, outcome.geometry().chunks().size());
    }

    @Test
    void rectangleRejectsSideAndChunkHardLimitsBeforeMaterialisingAnOversizedSet() {
        AtomicInteger lookups = new AtomicInteger();
        SelectionEditService defaultLimits = service((world, packed) -> {
            lookups.incrementAndGet();
            return null;
        });
        SelectionEditOutcome side = defaultLimits.selectRectangle(
                session(SelectionMode.CREATE_LAND, Optional.empty(), Set.of(), Map.of()),
                new SelectionPoint(WORLD, 0, 64, 0),
                new SelectionPoint(WORLD, 512, 64, 0));

        assertFalse(side.accepted());
        assertEquals(SelectionEditReason.SIDE_LIMIT_EXCEEDED, side.reason());
        assertEquals(0, lookups.get());

        SelectionEditService countLimited = new SelectionEditService(
                new LimitSettings(5, 256, 128, 16, 64, 1024),
                (world, packed) -> null);
        SelectionEditOutcome count = countLimited.selectRectangle(
                session(SelectionMode.CREATE_LAND, Optional.empty(), Set.of(), Map.of()),
                new SelectionPoint(WORLD, 0, 64, 0),
                new SelectionPoint(WORLD, 527, 64, 511));

        assertFalse(count.accepted());
        assertEquals(SelectionEditReason.CHUNK_LIMIT_EXCEEDED, count.reason());
    }

    @Test
    void singleChunkAddAndRemoveReconcilePendingChangesInBothDirections() {
        SelectionEditService service = service((world, packed) -> null);
        ChunkKey base = chunk(0, 0);
        ChunkKey added = chunk(1, 0);
        SelectionSession initial = session(SelectionMode.EDIT_SELECTION, Optional.of(TARGET), Set.of(base), Map.of());

        SelectionEditOutcome add = service.addChunk(initial, added);
        assertTrue(add.accepted());
        assertEquals(Set.of(base, added), add.selectedChunks());
        assertEquals(Map.of(added, PendingChange.ADD), add.update().orElseThrow().pendingChanges());

        SelectionSession afterAdd = initial.withSelection(add.update().orElseThrow(), 1, NOW);
        SelectionEditOutcome undoAdd = service.removeChunk(afterAdd, added);
        assertTrue(undoAdd.accepted());
        assertEquals(Set.of(base), undoAdd.selectedChunks());
        assertTrue(undoAdd.update().orElseThrow().pendingChanges().isEmpty());

        SelectionEditOutcome remove = service.removeChunk(initial, base);
        assertTrue(remove.accepted());
        assertEquals(Map.of(base, PendingChange.REMOVE), remove.update().orElseThrow().pendingChanges());
        SelectionSession afterRemove = initial.withSelection(remove.update().orElseThrow(), 1, NOW);
        SelectionEditOutcome undoRemove = service.addChunk(afterRemove, base);
        assertTrue(undoRemove.accepted());
        assertTrue(undoRemove.update().orElseThrow().pendingChanges().isEmpty());
    }

    @Test
    void removingTheLastChunkProducesAnEmptyConnectedSelection() {
        SelectionSession session = session(SelectionMode.EDIT_SELECTION, Optional.of(TARGET),
                Set.of(chunk(0, 0)), Map.of());

        SelectionEditOutcome outcome = service((world, packed) -> null).removeChunk(session, chunk(0, 0));

        assertTrue(outcome.accepted());
        assertTrue(outcome.selectedChunks().isEmpty());
        assertTrue(outcome.connected());
        assertTrue(outcome.boundary().isEmpty());
        assertEquals(Map.of(chunk(0, 0), PendingChange.REMOVE), outcome.update().orElseThrow().pendingChanges());
    }

    @Test
    void removingBridgeIsRejectedButRemovingCenterAndCreatingHoleIsAccepted() {
        SelectionEditService service = service((world, packed) -> null);
        SelectionSession line = session(SelectionMode.EDIT_SELECTION, Optional.of(TARGET),
                Set.of(chunk(0, 0), chunk(1, 0), chunk(2, 0)), Map.of());

        SelectionEditOutcome bridge = service.removeChunk(line, chunk(1, 0));
        assertFalse(bridge.accepted());
        assertEquals(SelectionEditReason.DISCONNECTED, bridge.reason());
        assertEquals(2, bridge.componentCount());
        assertTrue(bridge.update().isEmpty());

        Set<ChunkKey> ring = new HashSet<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                ring.add(chunk(x, z));
            }
        }
        ring.remove(chunk(1, 1));
        SelectionSession solid = session(SelectionMode.EDIT_SELECTION, Optional.of(TARGET),
                Set.of(chunk(0, 0), chunk(1, 0), chunk(2, 0), chunk(0, 1), chunk(1, 1), chunk(2, 1),
                        chunk(0, 2), chunk(1, 2), chunk(2, 2)), Map.of());
        SelectionEditOutcome hole = service.removeChunk(solid, chunk(1, 1));

        assertTrue(hole.accepted());
        assertEquals(Set.of(chunk(1, 1)), hole.holes());
        assertTrue(hole.connected());
        assertEquals(ring, hole.selectedChunks());
    }

    @Test
    void collisionAllowsTargetAndWildernessButRejectsAnotherLand() {
        Map<Long, LandId> index = new HashMap<>();
        index.put(chunk(1, 0).pack(), TARGET);
        index.put(chunk(2, 0).pack(), OTHER);
        SelectionEditService service = service((world, packed) -> index.get(packed));
        SelectionSession session = session(SelectionMode.EDIT_SELECTION, Optional.of(TARGET), Set.of(chunk(0, 0)), Map.of());

        assertTrue(service.addChunk(session, chunk(1, 0)).accepted());
        assertTrue(service.addChunk(session, chunk(-1, 0)).accepted());
        SelectionEditOutcome collision = service.addChunk(session, chunk(2, 0));
        assertFalse(collision.accepted());
        assertEquals(SelectionEditReason.COLLISION, collision.reason());
        assertEquals(Optional.of(chunk(2, 0)), collision.conflictChunk());
    }

    @Test
    void productionIndexAdapterUsesPackedLookupAndKeepsWildernessNull() {
        WorldChunkIndex index = WorldChunkIndex.from(WORLD, Map.of(chunk(1, 0).pack(), TARGET));
        SelectionEditService service = new SelectionEditService(
                new LimitSettings(5, 256, 128, 16, 32, 1024), index);
        SelectionSession session = session(SelectionMode.EDIT_SELECTION, Optional.of(TARGET), Set.of(chunk(0, 0)), Map.of());

        assertTrue(service.addChunk(session, chunk(1, 0)).accepted());
        assertTrue(service.addChunk(session, chunk(-1, 0)).accepted());
    }

    @Test
    void outputAndGeometryAreImmutableSnapshots() {
        Set<ChunkKey> input = new HashSet<>();
        input.add(chunk(0, 0));
        SelectionEditOutcome outcome = service((world, packed) -> null)
                .selectRectangle(session(SelectionMode.CREATE_LAND, Optional.empty(), input, Map.of()),
                        new SelectionPoint(WORLD, 0, 64, 0),
                        new SelectionPoint(WORLD, 15, 64, 15));
        input.clear();

        assertThrows(UnsupportedOperationException.class, () -> outcome.selectedChunks().clear());
        assertThrows(UnsupportedOperationException.class, () -> outcome.boundary().clear());
        assertThrows(UnsupportedOperationException.class, () -> outcome.geometry().chunks().clear());
        assertThrows(UnsupportedOperationException.class, () -> outcome.update().orElseThrow().pendingChanges().clear());
        assertEquals(1, outcome.selectedChunks().size());
        assertTrue(outcome.boundary().stream().allMatch(BoundarySegment.class::isInstance));
    }

    @Test
    void collisionPathUsesOnlyPackedIndexLookupsAndNeverNeedsWorldData() {
        AtomicInteger lookups = new AtomicInteger();
        SelectionEditService service = service((world, packed) -> {
            assertSame(WORLD, world);
            lookups.incrementAndGet();
            return null;
        });

        SelectionEditOutcome outcome = service.selectRectangle(
                session(SelectionMode.CREATE_LAND, Optional.empty(), Set.of(), Map.of()),
                new SelectionPoint(WORLD, 0, 64, 0),
                new SelectionPoint(WORLD, 31, 64, 31));

        assertTrue(outcome.accepted());
        assertEquals(4, lookups.get());
    }

    @Test
    void managerCanUseTheOutcomeReferenceAndStillRejectStaleReferences() {
        SelectionSessionManager manager = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                Clock.fixed(NOW, ZoneOffset.UTC)::instant,
                java.time.Duration.ofMinutes(10));
        SelectionSession initial = session(SelectionMode.EDIT_SELECTION, Optional.of(TARGET), Set.of(chunk(0, 0)), Map.of());
        manager.start(initial);
        SelectionSession current = manager.sessionFor(PLAYER).orElseThrow();

        SelectionEditService service = service((world, packed) -> null);
        SelectionEditOutcome outcome = service.addChunk(current, chunk(1, 0));
        assertSame(current, outcome.expectedSession());
        Optional<SelectionSession> updated = manager.updateSelection(
                PLAYER, outcome.expectedSession(), 0, outcome.update().orElseThrow());

        assertTrue(updated.isPresent());
        assertEquals(1, updated.orElseThrow().selectionRevision());
        assertTrue(manager.updateSelection(PLAYER, current, 0, outcome.update().orElseThrow()).isEmpty());
    }

    private static SelectionEditService service(SelectionLandIndex index) {
        return new SelectionEditService(new LimitSettings(5, 256, 128, 16, 32, 1024), index);
    }

    private static SelectionSession session(SelectionMode mode, Optional<LandId> target,
                                            Set<ChunkKey> selected, Map<ChunkKey, PendingChange> pending) {
        return new SelectionSession(
                PLAYER,
                WORLD,
                mode,
                target,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                selected,
                pending,
                0,
                0,
                NOW,
                NOW);
    }

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(WORLD, x, z);
    }
}

package com.smile.chunkland.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.config.LimitSettings;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A fresh rectangular claim must accept single-chunk trimming without
 * changing workflow: adding an adjacent chunk and removing a selected one
 * run the same range, collision and connectivity checks as the existing
 * edit flow.
 */
class SelectionCreateLandChunkEditTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final LandId OTHER = new LandId(UUID.fromString("00000000-0000-0000-0000-000000000021"));
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void newClaimAcceptsAdjacentAddAndUndo() {
        SelectionEditService service = service((world, packed) -> null);
        SelectionSession initial = session(Set.of(chunk(0, 0)), Map.of());

        SelectionEditOutcome add = service.addChunk(initial, chunk(1, 0));

        assertTrue(add.accepted(), "a new claim must accept an adjacent chunk, got " + add.reason());
        assertEquals(Set.of(chunk(0, 0), chunk(1, 0)), add.selectedChunks());
        assertEquals(Map.of(chunk(1, 0), PendingChange.ADD),
                add.update().orElseThrow().pendingChanges());
    }

    @Test
    void newClaimAcceptsRemovingASelectedChunk() {
        SelectionEditService service = service((world, packed) -> null);
        SelectionSession initial = session(Set.of(chunk(0, 0), chunk(1, 0)), Map.of());

        SelectionEditOutcome remove = service.removeChunk(initial, chunk(1, 0));

        assertTrue(remove.accepted(), "a new claim must accept trimming one chunk, got " + remove.reason());
        assertEquals(Set.of(chunk(0, 0)), remove.selectedChunks());
        assertEquals(Map.of(chunk(1, 0), PendingChange.REMOVE),
                remove.update().orElseThrow().pendingChanges());
    }

    @Test
    void newClaimHandlesNegativeChunkCoordinates() {
        SelectionEditService service = service((world, packed) -> null);
        SelectionSession initial = session(Set.of(chunk(-1, -1)), Map.of());

        SelectionEditOutcome add = service.addChunk(initial, chunk(-2, -1));

        assertTrue(add.accepted(), "negative chunk neighbours must stay connected, got " + add.reason());
        assertTrue(add.selectedChunks().contains(chunk(-2, -1)));
    }

    @Test
    void newClaimStillRejectsCollisions() {
        Map<Long, LandId> index = new HashMap<>();
        index.put(chunk(1, 0).pack(), OTHER);
        SelectionEditService service = service((world, packed) -> index.get(packed));
        SelectionSession initial = session(Set.of(chunk(0, 0)), Map.of());

        SelectionEditOutcome outcome = service.addChunk(initial, chunk(1, 0));

        assertFalse(outcome.accepted());
        assertEquals(SelectionEditReason.COLLISION, outcome.reason());
        assertEquals(Optional.of(chunk(1, 0)), outcome.conflictChunk());
    }

    @Test
    void newClaimStillRejectsDisconnectingRemovals() {
        SelectionEditService service = service((world, packed) -> null);
        SelectionSession initial = session(
                Set.of(chunk(0, 0), chunk(1, 0), chunk(2, 0)), Map.of());

        SelectionEditOutcome outcome = service.removeChunk(initial, chunk(1, 0));

        assertFalse(outcome.accepted());
        assertEquals(SelectionEditReason.DISCONNECTED, outcome.reason());
        assertTrue(outcome.update().isEmpty(), "a rejected edit must carry no update");
        assertEquals(2, outcome.componentCount());
    }

    @Test
    void newClaimCanClearItsLastChunk() {
        SelectionEditService service = service((world, packed) -> null);
        SelectionSession initial = session(Set.of(chunk(0, 0)), Map.of());

        SelectionEditOutcome outcome = service.removeChunk(initial, chunk(0, 0));

        assertTrue(outcome.accepted(), "clearing a new claim must stay accepted, got " + outcome.reason());
        assertTrue(outcome.selectedChunks().isEmpty());
        assertTrue(outcome.connected());
        assertEquals(Map.of(chunk(0, 0), PendingChange.REMOVE),
                outcome.update().orElseThrow().pendingChanges());
    }

    @Test
    void newClaimChunkAddHonoursTheChunkLimit() {
        SelectionEditService limited = new SelectionEditService(
                new LimitSettings(5, 256, 128, 16, 32, 1), (world, packed) -> null);
        SelectionSession initial = session(Set.of(chunk(0, 0)), Map.of());

        SelectionEditOutcome outcome = limited.addChunk(initial, chunk(1, 0));

        assertFalse(outcome.accepted());
        assertEquals(SelectionEditReason.CHUNK_LIMIT_EXCEEDED, outcome.reason());
        assertTrue(outcome.update().isEmpty());
    }

    @Test
    void duplicateAddIsNoChangeWithoutMutatingPending() {
        SelectionEditService service = service((world, packed) -> null);
        SelectionSession initial = session(Set.of(chunk(0, 0)), Map.of());

        SelectionEditOutcome outcome = service.addChunk(initial, chunk(0, 0));

        assertFalse(outcome.accepted());
        assertEquals(SelectionEditReason.NO_CHANGE, outcome.reason());
        assertTrue(outcome.update().isEmpty(), "a duplicate edit must carry no update");
        assertEquals(Set.of(chunk(0, 0)), outcome.expectedSession().selectedChunks());
        assertEquals(Map.of(), outcome.expectedSession().pendingChanges());
    }

    @Test
    void subLandSessionsStillRejectSingleChunkEdits() {
        SelectionEditService service = service((world, packed) -> null);
        SelectionSession initial = new SelectionSession(
                PLAYER, WORLD, SelectionMode.CREATE_SUBLAND, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Set.of(chunk(0, 0)), Map.of(), 0, 0, 0, NOW, NOW);

        SelectionEditOutcome outcome = service.addChunk(initial, chunk(1, 0));

        assertFalse(outcome.accepted());
        assertEquals(SelectionEditReason.WRONG_MODE, outcome.reason());
    }

    private static SelectionEditService service(SelectionLandIndex index) {
        return new SelectionEditService(new LimitSettings(5, 256, 128, 16, 32, 1024), index);
    }

    private static SelectionSession session(Set<ChunkKey> selected, Map<ChunkKey, PendingChange> pending) {
        return new SelectionSession(
                PLAYER, WORLD, SelectionMode.CREATE_LAND, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), selected, pending, 0, 0, 0, NOW, NOW);
    }

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(WORLD, x, z);
    }
}

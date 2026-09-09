package com.smile.chunkland.vertical;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.config.VerticalMode;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.vertical.SnapshotProtectionDepthLookup;
import com.smile.chunkland.runtime.vertical.VerticalDepths;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stored vs effective depth flow: SQLite round-trip, registry carry,
 * PER vs FULL resolution, toggle preservation, legacy fallback, production
 * lookup and the M3-02 proposal seam.
 */
class VerticalDepthFlowTest {

    @TempDir Path temp;

    private static final int WORLD_MIN = -64;
    private static final int WORLD_MAX = 320;

    private LandSnapshot land(UUID world, LandId id, OwnerRef owner, Set<ChunkKey> chunks) {
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        return new LandSnapshot(id, "Home", LandName.normalize("Home"), owner, world,
                chunks, List.of(), 0, 0, now, now);
    }

    @Test
    void storedDepthsRoundTripThroughSqliteToRegistry() {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey a = new ChunkKey(world, 0, 0);
        ChunkKey b = new ChunkKey(world, 1, 0);
        ChunkKey c = new ChunkKey(world, 2, 0);
        Path db = temp.resolve("depths.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            lands.save(land(world, landId, owner, Set.of(a, b, c))).toCompletableFuture().join();
            UUID lot = UUID.randomUUID();
            chunks.addChunk(landId, a, 60, lot, 10L).toCompletableFuture().join();
            chunks.addChunk(landId, b, 25, lot, 10L).toCompletableFuture().join();
            chunks.addChunk(landId, c, -20, lot, 10L).toCompletableFuture().join();

            Map<ChunkKey, Integer> depths = chunks.listDepthsByLand(landId).toCompletableFuture().join();
            assertEquals(Map.of(a, 60, b, 25, c, -20), depths);
            assertNotNull(depths);
            assertThrows(UnsupportedOperationException.class, () -> depths.put(a, 1));

            LandRegistryStore registryStore = new LandRegistryStore();
            RuntimeRegistryRebuilder rebuilder =
                    new RuntimeRegistryRebuilder(lands, registryStore, chunks);
            LandRegistry published = rebuilder.rebuild().toCompletableFuture().join();
            assertSame(published, registryStore.snapshot());
            assertEquals(60, published.storedDepth(a).orElseThrow());
            assertEquals(25, published.storedDepth(b).orElseThrow());
            assertEquals(-20, published.storedDepth(c).orElseThrow());
        }
    }

    @Test
    void nullableLegacyRowFallsBack() {
        // Durable NULL normalizes to the shared fallback; missing registry
        // entries resolve the same way at read time.
        assertEquals(VerticalDepths.LEGACY_STORED_FALLBACK_Y, VerticalDepths.normalizeStored(null));
        assertEquals(40, VerticalDepths.normalizeStored(40));

        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey ck = new ChunkKey(world, 5, 5);
        LandRegistry registry = LandRegistry.from(List.of(land(world, landId, owner, Set.of(ck))));
        assertTrue(registry.storedDepth(ck).isEmpty());
        SnapshotProtectionDepthLookup lookup =
                SnapshotProtectionDepthLookup.fixed(VerticalMode.PER_CHUNK_DEPTH, WORLD_MIN);
        assertEquals(VerticalDepths.LEGACY_STORED_FALLBACK_Y,
                lookup.getEffectiveDepth(ck, registry).orElseThrow());
        assertEquals(VerticalDepths.LEGACY_STORED_FALLBACK_Y,
                lookup.getStoredDepth(ck, registry).orElseThrow());
    }

    @Test
    void effectiveDepthPerVsFullDoesNotTouchStored() {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey a = new ChunkKey(world, 0, 0);
        ChunkKey b = new ChunkKey(world, 1, 0);
        LandSnapshot snap = land(world, landId, owner, Set.of(a, b));
        LandRegistry registry = LandRegistry.fromWithDepths(List.of(snap), Map.of(a, 60, b, 25));

        SnapshotProtectionDepthLookup per =
                SnapshotProtectionDepthLookup.fixed(VerticalMode.PER_CHUNK_DEPTH, WORLD_MIN);
        assertEquals(60, per.getEffectiveDepth(a, registry).orElseThrow());
        assertEquals(25, per.getEffectiveDepth(b, registry).orElseThrow());
        assertEquals(25, per.getProtectionDepth(landId, registry).orElseThrow());

        SnapshotProtectionDepthLookup full =
                SnapshotProtectionDepthLookup.fixed(VerticalMode.FULL_HEIGHT, WORLD_MIN);
        assertEquals(WORLD_MIN, full.getEffectiveDepth(a, registry).orElseThrow());
        assertEquals(WORLD_MIN, full.getEffectiveDepth(b, registry).orElseThrow());
        assertEquals(WORLD_MIN, full.getProtectionDepth(landId, registry).orElseThrow());

        // Stored is mode-independent.
        assertEquals(60, full.getStoredDepth(a, registry).orElseThrow());
        assertEquals(25, full.getStoredDepth(b, registry).orElseThrow());
        assertEquals(60, registry.storedDepth(a).orElseThrow());
    }

    @Test
    void modeToggleRoundTripPreservesStoredAndPayload() {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey a = new ChunkKey(world, 0, 0);
        LandSnapshot snap = land(world, landId, owner, Set.of(a));
        Map<ChunkKey, Integer> stored = Map.of(a, 59);
        LandRegistry registry = LandRegistry.fromWithDepths(List.of(snap), stored);

        SnapshotProtectionDepthLookup per =
                SnapshotProtectionDepthLookup.fixed(VerticalMode.PER_CHUNK_DEPTH, WORLD_MIN);
        SnapshotProtectionDepthLookup full =
                SnapshotProtectionDepthLookup.fixed(VerticalMode.FULL_HEIGHT, WORLD_MIN);
        // PER -> FULL -> PER: effective changes, stored never does.
        assertEquals(59, per.getEffectiveDepth(a, registry).orElseThrow());
        assertEquals(WORLD_MIN, full.getEffectiveDepth(a, registry).orElseThrow());
        assertEquals(59, per.getEffectiveDepth(a, registry).orElseThrow());
        assertEquals(59, registry.storedDepth(a).orElseThrow());

        // Claim payload survives the toggle untouched.
        UUID actor = UUID.randomUUID();
        UUID lot = UUID.randomUUID();
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        OperationPayload payload = OperationPayload.claim(UUID.randomUUID(), actor, world, landId,
                List.of(new OperationPayload.Chunk(a, 59, lot, 100L)), 100L, "test", now, "Home");
        OperationPayload revived = OperationPayload.fromJson(payload.toJson());
        assertEquals(59, revived.chunkSet().get(0).storedMinProtectedY());
    }

    @Test
    void productionLookupUsesSameSnapshotAndMissEmpty() {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey a = new ChunkKey(world, 3, 4);
        LandRegistry published = LandRegistry.fromWithDepths(
                List.of(land(world, landId, owner, Set.of(a))), Map.of(a, 42));
        SnapshotProtectionDepthLookup lookup =
                SnapshotProtectionDepthLookup.fixed(VerticalMode.PER_CHUNK_DEPTH, WORLD_MIN);
        assertEquals(42, lookup.getProtectionDepth(landId, published).orElseThrow());
        assertTrue(lookup.getProtectionDepth(new LandId(UUID.randomUUID()), published).isEmpty());
        ChunkKey wilderness = new ChunkKey(world, 99, 99);
        assertTrue(lookup.getEffectiveDepth(wilderness, published).isEmpty());
        assertTrue(lookup.getStoredDepth(wilderness, published).isEmpty());
    }

    @Test
    void extendProposalWorksUnderBothModesWithoutQueue() {
        // Mode-independent seam for the Auto Extend follow-up: deeper operation proposes, shallower does not.
        Optional<Integer> deeper = VerticalDepths.proposeExtend(59, 20, 5, WORLD_MIN);
        assertEquals(Optional.of(15), deeper);
        assertTrue(VerticalDepths.proposeExtend(15, 20, 5, WORLD_MIN).isEmpty());
        assertTrue(VerticalDepths.proposeExtend(15, 15, 0, WORLD_MIN).isEmpty());
        // World-min clamp.
        assertEquals(Optional.of(WORLD_MIN), VerticalDepths.proposeExtend(0, -100, 5, WORLD_MIN));
        assertThrows(IllegalArgumentException.class,
                () -> VerticalDepths.proposeExtend(59, 20, -1, WORLD_MIN));
        // FULL_HEIGHT still tracks stored: proposal does not consult the mode.
        int stored = 59;
        int effectiveFull = VerticalDepths.effectiveFor(VerticalMode.FULL_HEIGHT, stored, WORLD_MIN);
        assertEquals(WORLD_MIN, effectiveFull);
        assertEquals(Optional.of(15), VerticalDepths.proposeExtend(stored, 20, 5, WORLD_MIN));
    }
}

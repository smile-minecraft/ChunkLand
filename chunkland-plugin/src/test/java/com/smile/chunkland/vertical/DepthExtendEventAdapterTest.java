package com.smile.chunkland.vertical;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.persistence.DepthExtendStore;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.vertical.AutoExtendTrigger;
import com.smile.chunkland.runtime.vertical.DepthExtendEventAdapter;
import com.smile.chunkland.runtime.vertical.DepthExtendService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production trigger adapter and queue lifecycle for Auto Extend.
 *
 * <p>Only legal {@code BLOCK_BREAK}/{@code BLOCK_PLACE} operations with an
 * authorized actor inside a land may propose; the nine silent trigger kinds,
 * unauthorized attempts, wilderness and shallower-than-stored operations
 * never reach the queue. Disable order is stop-accepting, flush, then close
 * persistence, with no executor owned by the queue.
 */
class DepthExtendEventAdapterTest {

    @TempDir Path temp;

    private static DepthExtendEventAdapter adapter(LandRegistry snapshot, int buffer, int worldMin) {
        return new DepthExtendEventAdapter(
                snapshot::findLandId,
                (chunk, land) -> Optional.of(snapshot.storedDepth(chunk)
                        .orElse(com.smile.chunkland.runtime.vertical.VerticalDepths.LEGACY_STORED_FALLBACK_Y)),
                buffer,
                worldMin);
    }

    private static LandRegistry registry(UUID world, LandId landId, OwnerRef owner, ChunkKey chunk, int stored) {
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        LandSnapshot land = new LandSnapshot(landId, "Home", LandName.normalize("Home"), owner, world,
                Set.of(chunk), List.of(), 0, 0, now, now);
        return LandRegistry.fromWithDepths(List.of(land), Map.of(chunk, stored));
    }

    @Test
    void legalBreakProposesRequest() {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        ChunkKey chunk = new ChunkKey(world, 0, 0);
        LandRegistry snapshot = registry(world, landId, OwnerRef.player(UUID.randomUUID()), chunk, 60);
        UUID actor = UUID.randomUUID();

        Optional<com.smile.chunkland.runtime.vertical.DepthExtendRequest> proposal = adapter(snapshot, 2, -64)
                .propose(AutoExtendTrigger.BLOCK_BREAK, actor, true, chunk, 20);

        assertTrue(proposal.isPresent());
        assertEquals(landId, proposal.get().landId());
        assertEquals(18, proposal.get().requestedDepth());
        assertEquals(20, proposal.get().triggeringOperationY());
    }

    @Test
    void silentTriggersUnauthorizedWildernessAndShallowNeverPropose() {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        ChunkKey chunk = new ChunkKey(world, 1, 1);
        LandRegistry snapshot = registry(world, landId, OwnerRef.player(UUID.randomUUID()), chunk, 15);
        ChunkKey wild = new ChunkKey(world, 9, 9);
        UUID actor = UUID.randomUUID();
        DepthExtendEventAdapter adapter = adapter(snapshot, 2, -64);

        AutoExtendTrigger[] silent = {
            AutoExtendTrigger.MOVE_OR_TELEPORT,
            AutoExtendTrigger.MOB_ACTIVITY,
            AutoExtendTrigger.EXPLOSION,
            AutoExtendTrigger.FLUID_FLOW,
            AutoExtendTrigger.FIRE,
            AutoExtendTrigger.FALLING_OR_DROPPED,
            AutoExtendTrigger.PROJECTILE,
            AutoExtendTrigger.PISTON,
            AutoExtendTrigger.UNAUTHORIZED_ATTEMPT,
        };
        for (AutoExtendTrigger trigger : silent) {
            assertTrue(adapter.propose(trigger, actor, true, chunk, -100).isEmpty(),
                    trigger + " must stay silent");
        }
        assertTrue(adapter.propose(AutoExtendTrigger.BLOCK_BREAK, actor, false, chunk, -100).isEmpty(),
                "unauthorized actor must stay silent");
        assertTrue(adapter.propose(AutoExtendTrigger.BLOCK_BREAK, actor, true, wild, -100).isEmpty(),
                "wilderness must stay silent");
        assertTrue(adapter.propose(AutoExtendTrigger.BLOCK_PLACE, actor, true, chunk, 40).isEmpty(),
                "shallower-than-stored operation must stay silent");
    }

    @Test
    void serviceFlushBeforePersistenceCloseKeepsAcceptedWrite() throws Exception {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey chunk = new ChunkKey(world, 2, 2);
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        LandSnapshot land = new LandSnapshot(landId, "Home", LandName.normalize("Home"), owner, world,
                Set.of(chunk), List.of(), 0, 0, now, now);
        Path db = temp.resolve("lifecycle.db");

        PersistenceStore store = PersistenceStore.open(db);
        new SqliteLandRepository(store).save(land).toCompletableFuture().join();
        new SqliteChunkRepository(store).addChunk(landId, chunk, 60, UUID.randomUUID(), 10L)
                .toCompletableFuture().join();
        LandRegistry snapshot = registry(world, landId, owner, chunk, 60);

        DepthExtendService service = DepthExtendService.start(
                new DepthExtendStore(store), Runnable::run, 8, adapter(snapshot, 2, -64), store);
        assertTrue(service.isAccepting());

        Optional<CompletableFuture<com.smile.chunkland.runtime.vertical.DepthWriteResult>> accepted =
                service.onBlockOperation(AutoExtendTrigger.BLOCK_BREAK, UUID.randomUUID(), true, chunk, 20);
        assertTrue(accepted.isPresent());
        assertTrue(accepted.get().get(10, TimeUnit.SECONDS).applied());

        // Disable order: stop accepting, flush the queue, then close persistence.
        service.close();
        assertFalse(service.isAccepting());
        assertTrue(service.onBlockOperation(
                        AutoExtendTrigger.BLOCK_BREAK, UUID.randomUUID(), true, chunk, 10)
                .isEmpty(), "closed service must reject new proposals");
        assertTrue(store.isClosed(), "service close must close persistence after flushing");

        int stored;
        try (PersistenceStore reopened = PersistenceStore.open(db)) {
            stored = new SqliteChunkRepository(reopened).listDepthsByLand(landId)
                    .toCompletableFuture().join().get(chunk);
        }
        assertEquals(18, stored, "accepted extend must survive the stop-flush-close order");
    }
}

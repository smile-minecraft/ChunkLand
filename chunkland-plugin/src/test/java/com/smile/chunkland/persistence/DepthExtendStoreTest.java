package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.runtime.vertical.DepthCasAccumulator;
import com.smile.chunkland.runtime.vertical.DepthExtendRequest;
import com.smile.chunkland.runtime.vertical.DepthPersistenceQueue;
import com.smile.chunkland.runtime.vertical.DepthWriteResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Durable side of Auto Extend: the store applies an atomic minimum to
 * {@code land_chunks.stored_min_protected_y} and records one
 * {@code DEPTH_EXTEND} audit per accepted extend in the same transaction.
 *
 * <p>Shallower proposals, unknown chunks and stale land ids are no-ops
 * without an audit row, so a trailing proposal can never overwrite a deeper
 * persisted value or fabricate history.
 */
class DepthExtendStoreTest {

    @TempDir Path temp;

    private static LandSnapshot land(UUID world, LandId id, OwnerRef owner, Set<ChunkKey> chunks) {
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        return new LandSnapshot(id, "Home", LandName.normalize("Home"), owner, world,
                chunks, List.of(), 0, 0, now, now);
    }

    private static DepthExtendRequest request(ChunkKey chunk, LandId land, int requestedDepth, int operationY) {
        return new DepthExtendRequest(chunk, land, UUID.randomUUID(), requestedDepth, operationY);
    }

    @Test
    void extendPersistsDeeperMinAtomically() {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey chunk = new ChunkKey(world, 0, 0);
        Path db = temp.resolve("extend.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            lands.save(land(world, landId, owner, Set.of(chunk))).toCompletableFuture().join();
            chunks.addChunk(landId, chunk, 60, UUID.randomUUID(), 10L).toCompletableFuture().join();

            DepthExtendStore extends_ = new DepthExtendStore(store);
            DepthWriteResult result =
                    extends_.extend(request(chunk, landId, 15, 20)).toCompletableFuture().join();
            assertTrue(result.applied());
            assertEquals(60, result.beforeStored());
            assertEquals(15, result.afterStored());
            assertEquals(20, result.triggeringOperationY());

            Map<ChunkKey, Integer> depths = chunks.listDepthsByLand(landId).toCompletableFuture().join();
            assertEquals(15, depths.get(chunk));
        }
    }

    @Test
    void shallowProposalNeverOverwritesAndWritesNoAudit() {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey chunk = new ChunkKey(world, 1, 0);
        Path db = temp.resolve("shallow.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            lands.save(land(world, landId, owner, Set.of(chunk))).toCompletableFuture().join();
            chunks.addChunk(landId, chunk, 15, UUID.randomUUID(), 10L).toCompletableFuture().join();

            DepthExtendStore extends_ = new DepthExtendStore(store);
            DepthWriteResult result =
                    extends_.extend(request(chunk, landId, 40, 45)).toCompletableFuture().join();
            assertFalse(result.applied());
            assertEquals(15, result.beforeStored());
            assertEquals(15, result.afterStored());

            assertEquals(15, chunks.listDepthsByLand(landId).toCompletableFuture().join().get(chunk));
            assertTrue(new SqliteAuditRepository(store).findByAction("DEPTH_EXTEND", 10)
                    .toCompletableFuture().join().isEmpty(), "rejected proposal must not fabricate audit");
        }
    }

    @Test
    void acceptedExtendWritesQueryableAuditWithBeforeAfterAndTrigger() {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey chunk = new ChunkKey(world, 2, 0);
        UUID actor = UUID.randomUUID();
        Path db = temp.resolve("audit.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            lands.save(land(world, landId, owner, Set.of(chunk))).toCompletableFuture().join();
            chunks.addChunk(landId, chunk, 60, UUID.randomUUID(), 10L).toCompletableFuture().join();

            DepthExtendStore extends_ = new DepthExtendStore(store);
            extends_.extend(new DepthExtendRequest(chunk, landId, actor, 15, 20))
                    .toCompletableFuture().join();

            SqliteAuditRepository audits = new SqliteAuditRepository(store);
            List<AuditEntry> byAction = audits.findByAction("DEPTH_EXTEND", 10).toCompletableFuture().join();
            assertEquals(1, byAction.size());
            AuditEntry entry = byAction.get(0);
            assertEquals("DEPTH_EXTEND", entry.action());
            assertEquals(landId, entry.landId());
            assertEquals(world, entry.worldId());
            assertEquals(Optional.of(actor), entry.actorOpt());
            assertEquals(Long.valueOf(chunk.pack()), entry.singleChunkPacked());
            assertTrue(entry.beforeJson().contains("60"), "before must record the prior stored depth");
            assertTrue(entry.afterJson().contains("15"), "after must record the extended stored depth");
            assertTrue(entry.metadataJson().contains("20"), "metadata must record the triggering operation Y");

            List<AuditEntry> byLand = audits.findByLand(landId, 10, 0).toCompletableFuture().join();
            assertEquals(1, byLand.size());
            assertEquals(List.of(chunk), byLand.get(0).chunks());
            assertEquals("CL-M3-02", AuditActions.writerTask(entry.action()));
        }
    }

    @Test
    void unknownChunkAndStaleLandAreNoOpsWithoutAudit() {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey chunk = new ChunkKey(world, 4, 4);
        Path db = temp.resolve("noop.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            lands.save(land(world, landId, owner, Set.of(chunk))).toCompletableFuture().join();
            chunks.addChunk(landId, chunk, 60, UUID.randomUUID(), 10L).toCompletableFuture().join();

            DepthExtendStore extends_ = new DepthExtendStore(store);
            ChunkKey wilderness = new ChunkKey(world, 99, 99);
            assertFalse(extends_.extend(request(wilderness, landId, -64, -60))
                    .toCompletableFuture().join().applied());
            assertFalse(extends_.extend(request(chunk, new LandId(UUID.randomUUID()), 10, 15))
                    .toCompletableFuture().join().applied(), "stale land id must fail closed");

            assertEquals(60, chunks.listDepthsByLand(landId).toCompletableFuture().join().get(chunk));
            assertTrue(new SqliteAuditRepository(store).findByAction("DEPTH_EXTEND", 10)
                    .toCompletableFuture().join().isEmpty());
        }
    }

    @Test
    void runtimeCasQueueAndStoredConvergeToSameMin() throws Exception {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey chunk = new ChunkKey(world, 5, 5);
        Path db = temp.resolve("e2e.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            lands.save(land(world, landId, owner, Set.of(chunk))).toCompletableFuture().join();
            chunks.addChunk(landId, chunk, 60, UUID.randomUUID(), 10L).toCompletableFuture().join();

            DepthCasAccumulator accumulator = new DepthCasAccumulator();
            accumulator.seed(chunk, 60);
            int[] proposals = {50, 30, 10, -10, 45, 5};
            int contenders = proposals.length;
            CyclicBarrier start = new CyclicBarrier(contenders);
            ExecutorService exec = Executors.newFixedThreadPool(contenders);
            try {
                List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
                for (int requested : proposals) {
                    futures.add(exec.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        accumulator.propose(chunk, requested);
                        return null;
                    }));
                }
                for (java.util.concurrent.Future<?> future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
            } finally {
                exec.shutdownNow();
                assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
            }
            assertEquals(-10, accumulator.current(chunk).orElseThrow());

            // The queue drains through the production store on an injected
            // executor; disable flushes before the test reads durable state.
            DepthExtendStore extends_ = new DepthExtendStore(store);
            DepthPersistenceQueue queue = new DepthPersistenceQueue(Runnable::run, extends_::extend, 8);
            Optional<CompletableFuture<DepthWriteResult>> accepted = queue.offer(
                    request(chunk, landId, accumulator.current(chunk).orElseThrow(), -5));
            assertTrue(accepted.isPresent());
            assertTrue(accepted.get().get(5, TimeUnit.SECONDS).applied());
            DepthPersistenceQueue.FlushSummary summary = queue.disableAndFlush();
            assertEquals(1, summary.completed());

            int stored = chunks.listDepthsByLand(landId).toCompletableFuture().join().get(chunk);
            assertEquals(-10, stored, "durable depth must match the runtime CAS minimum");
            assertEquals(1, new SqliteAuditRepository(store).findByAction("DEPTH_EXTEND", 10)
                    .toCompletableFuture().join().size());
        }
    }
}

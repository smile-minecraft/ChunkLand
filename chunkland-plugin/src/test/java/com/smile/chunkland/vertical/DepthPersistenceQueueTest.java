package com.smile.chunkland.vertical;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.runtime.vertical.DepthExtendRequest;
import com.smile.chunkland.runtime.vertical.DepthPersistenceQueue;
import com.smile.chunkland.runtime.vertical.DepthStore;
import com.smile.chunkland.runtime.vertical.DepthWriteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Bounded depth persistence queue over an owner-provided executor.
 *
 * <p>The queue never owns a thread: callers inject a direct executor or a
 * suite-owned pool that is shut down in {@code finally}. Disable/close stops
 * new accepts and waits for every accepted write (flush); writer failures
 * stay observable on the per-write future, in the failure list and in the
 * flush summary instead of masquerading as durable.
 */
class DepthPersistenceQueueTest {

    /** In-memory {@link DepthStore} with an optional entry gate and failure injection. */
    static final class FakeStore implements DepthStore {
        final ConcurrentLinkedQueue<DepthExtendRequest> applied = new ConcurrentLinkedQueue<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean gate;
        volatile boolean fail;

        @Override
        public CompletableFuture<DepthWriteResult> apply(DepthExtendRequest request) {
            if (gate) {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "flush test must release the gate");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(interrupted);
                }
            }
            if (fail) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("simulated durable write failure for " + request.chunk()));
            }
            applied.add(request);
            return CompletableFuture.completedFuture(new DepthWriteResult(request.chunk(), request.landId(), true,
                    request.requestedDepth() + 10, request.requestedDepth(), request.triggeringOperationY()));
        }
    }

    private static DepthExtendRequest request(ChunkKey chunk, LandId land, int requestedDepth) {
        return new DepthExtendRequest(chunk, land, UUID.randomUUID(), requestedDepth, requestedDepth + 5);
    }

    @Test
    void acceptedWriteDrainsOnInjectedExecutor() throws Exception {
        FakeStore store = new FakeStore();
        DepthPersistenceQueue queue = new DepthPersistenceQueue(Runnable::run, store, 8);
        UUID world = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        ChunkKey chunk = new ChunkKey(world, 0, 0);

        Optional<CompletableFuture<DepthWriteResult>> accepted = queue.offer(request(chunk, land, 15));
        assertTrue(accepted.isPresent());
        DepthWriteResult result = accepted.get().get(5, TimeUnit.SECONDS);
        assertTrue(result.applied());
        assertEquals(15, result.afterStored());
        assertEquals(1, store.applied.size());
        assertEquals(0, queue.pendingCount());
        assertTrue(queue.failures().isEmpty());

        DepthPersistenceQueue.FlushSummary summary = queue.disableAndFlush();
        assertEquals(1, summary.completed());
        assertEquals(0, summary.failed());
    }

    @Test
    void boundedCapacityRejectsOverflowObservably() throws Exception {
        FakeStore store = new FakeStore();
        store.gate = true;
        ExecutorService exec = Executors.newSingleThreadExecutor();
        DepthPersistenceQueue queue = new DepthPersistenceQueue(exec, store, 1);
        try {
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            Optional<CompletableFuture<DepthWriteResult>> first =
                    queue.offer(request(new ChunkKey(world, 0, 0), land, 10));
            assertTrue(first.isPresent());
            assertTrue(store.entered.await(5, TimeUnit.SECONDS), "first write must reach the gate");

            assertEquals(Optional.empty(), queue.offer(request(new ChunkKey(world, 0, 1), land, 5)),
                    "offer beyond capacity must be rejected, not queued unboundedly");
            assertEquals(1, queue.rejectedFullCount());
            assertEquals(1, queue.pendingCount());

            store.release.countDown();
            DepthWriteResult result = first.get().get(5, TimeUnit.SECONDS);
            assertTrue(result.applied());

            DepthPersistenceQueue.FlushSummary summary = queue.disableAndFlush();
            assertEquals(1, summary.completed());
            assertEquals(0, queue.pendingCount());
        } finally {
            store.release.countDown();
            exec.shutdownNow();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void disableFlushesAcceptedAndRejectsNewOffers() throws Exception {
        FakeStore store = new FakeStore();
        store.gate = true;
        ExecutorService exec = Executors.newSingleThreadExecutor();
        DepthPersistenceQueue queue = new DepthPersistenceQueue(exec, store, 8);
        ExecutorService flusher = Executors.newSingleThreadExecutor();
        try {
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            Optional<CompletableFuture<DepthWriteResult>> accepted =
                    queue.offer(request(new ChunkKey(world, 0, 0), land, 10));
            assertTrue(accepted.isPresent());
            assertTrue(store.entered.await(5, TimeUnit.SECONDS), "write must be in flight before flush");

            Future<DepthPersistenceQueue.FlushSummary> flushing =
                    flusher.submit(queue::disableAndFlush);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!queue.isDisabled() && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertTrue(queue.isDisabled(), "flush starts by disabling new accepts");
            assertEquals(Optional.empty(), queue.offer(request(new ChunkKey(world, 0, 1), land, 5)));
            assertEquals(1, queue.rejectedDisabledCount());

            assertFalse(flushing.isDone(), "flush must wait for the in-flight write, not drop it");
            store.release.countDown();
            DepthPersistenceQueue.FlushSummary summary = flushing.get(5, TimeUnit.SECONDS);
            assertEquals(1, summary.completed());
            assertEquals(0, summary.failed());
            assertTrue(accepted.get().get(5, TimeUnit.SECONDS).applied());
            assertEquals(1, store.applied.size(), "disable must flush every accepted write");
            assertEquals(0, queue.pendingCount());
        } finally {
            store.release.countDown();
            flusher.shutdownNow();
            exec.shutdownNow();
            assertTrue(flusher.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void writerFailureIsObservableAndNeverFakeDurable() throws Exception {
        FakeStore store = new FakeStore();
        store.fail = true;
        DepthPersistenceQueue queue = new DepthPersistenceQueue(Runnable::run, store, 8);
        UUID world = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());

        Optional<CompletableFuture<DepthWriteResult>> accepted =
                queue.offer(request(new ChunkKey(world, 0, 0), land, 10));
        assertTrue(accepted.isPresent());
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> accepted.get().get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(1, queue.failures().size());
        assertEquals(land, queue.failures().get(0).request().landId());

        DepthPersistenceQueue.FlushSummary summary = queue.disableAndFlush();
        assertEquals(0, summary.completed());
        assertEquals(1, summary.failed());
        assertTrue(store.applied.isEmpty(), "failed write must not count as applied");
    }

    @Test
    void closeFlushesAndDisables() throws Exception {
        FakeStore store = new FakeStore();
        DepthPersistenceQueue queue = new DepthPersistenceQueue(Runnable::run, store, 4);
        LandId land = new LandId(UUID.randomUUID());
        UUID world = UUID.randomUUID();
        assertTrue(queue.offer(request(new ChunkKey(world, 0, 0), land, 10)).isPresent());
        assertTrue(queue.offer(request(new ChunkKey(world, 0, 1), land, 5)).isPresent());
        queue.close();
        assertTrue(queue.isDisabled());
        assertEquals(2, store.applied.size());
        assertEquals(Optional.empty(), queue.offer(request(new ChunkKey(world, 0, 2), land, 0)));
    }

    @Test
    void invalidCapacityFailsClosed() {
        FakeStore store = new FakeStore();
        assertThrows(IllegalArgumentException.class,
                () -> new DepthPersistenceQueue(Runnable::run, store, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DepthPersistenceQueue(Runnable::run, store, -3));
        assertThrows(NullPointerException.class,
                () -> new DepthPersistenceQueue(null, store, 4));
        assertThrows(NullPointerException.class,
                () -> new DepthPersistenceQueue(Runnable::run, null, 4));
    }

    /**
     * Concurrent offers against a direct executor: every accepted write
     * completes exactly once and nothing is lost or duplicated.
     */
    @RepeatedTest(100)
    void concurrentOffersNeverLoseAccepted() throws Exception {
        FakeStore store = new FakeStore();
        DepthPersistenceQueue queue = new DepthPersistenceQueue(Runnable::run, store, 64);
        int threads = 8;
        int offersPerThread = 4;
        int total = threads * offersPerThread;
        UUID world = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        AtomicInteger index = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<CompletableFuture<DepthWriteResult>>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(exec.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    List<CompletableFuture<DepthWriteResult>> accepted = new ArrayList<>();
                    for (int i = 0; i < offersPerThread; i++) {
                        int n = index.getAndIncrement();
                        Optional<CompletableFuture<DepthWriteResult>> offer = queue.offer(
                                request(new ChunkKey(world, n, 0), land, 50 - n));
                        assertTrue(offer.isPresent(), "capacity 64 must accept all " + total + " offers");
                        accepted.add(offer.get());
                    }
                    return accepted;
                }));
            }
            int completed = 0;
            for (Future<List<CompletableFuture<DepthWriteResult>>> future : futures) {
                for (CompletableFuture<DepthWriteResult> write : future.get(5, TimeUnit.SECONDS)) {
                    assertTrue(write.get(5, TimeUnit.SECONDS).applied());
                    completed++;
                }
            }
            assertEquals(total, completed);
            assertEquals(total, store.applied.size());
            assertEquals(0, queue.pendingCount());
            assertTrue(queue.failures().isEmpty());
            DepthPersistenceQueue.FlushSummary summary = queue.disableAndFlush();
            assertEquals(total, summary.completed());
        } finally {
            exec.shutdownNow();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}

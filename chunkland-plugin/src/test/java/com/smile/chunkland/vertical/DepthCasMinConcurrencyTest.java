package com.smile.chunkland.vertical;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.runtime.vertical.DepthCasAccumulator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Production CAS accumulator converges concurrent depth proposals to the
 * atomic minimum: no lost update, and a shallower proposal never overwrites
 * an accepted deeper value.
 *
 * <p>Concurrency uses a deterministic start barrier (no sleeps) and repeats
 * 100 times; every repetition builds its own accumulator and shuts down its
 * own executor.
 */
class DepthCasMinConcurrencyTest {

    private static final int STORED = 60;

    /**
     * Eight contenders propose depths from 50 down to the world minimum at
     * the same instant; the accumulator must converge to the deepest value.
     */
    @RepeatedTest(100)
    void concurrentProposalsConvergeToDeepestMin() throws Exception {
        UUID world = UUID.randomUUID();
        ChunkKey chunk = new ChunkKey(world, 0, 0);
        DepthCasAccumulator accumulator = new DepthCasAccumulator();
        accumulator.seed(chunk, STORED);

        int[] proposals = {50, 40, 30, 20, 10, 0, -10, -64};
        int contenders = proposals.length;
        CyclicBarrier start = new CyclicBarrier(contenders);
        ExecutorService exec = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int requested : proposals) {
                futures.add(exec.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return accumulator.propose(chunk, requested);
                }));
            }
            for (Future<Integer> future : futures) {
                int observed = future.get(5, TimeUnit.SECONDS);
                assertTrue(observed >= -64 && observed <= STORED,
                        "observed min must stay within [deepest, seeded], was " + observed);
            }
            assertEquals(-64, accumulator.current(chunk).orElseThrow(),
                    "concurrent proposals must converge to the deepest value");
        } finally {
            exec.shutdownNow();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /**
     * Deep proposals land first (gated by a latch), then shallow proposals
     * race in: the final value must stay at the deep minimum and every
     * shallow caller must observe that minimum rather than its own value.
     */
    @RepeatedTest(100)
    void shallowAfterDeepNeverWins() throws Exception {
        UUID world = UUID.randomUUID();
        ChunkKey chunk = new ChunkKey(world, 3, 7);
        DepthCasAccumulator accumulator = new DepthCasAccumulator();
        accumulator.seed(chunk, STORED);

        int deepThreads = 3;
        int shallowThreads = 3;
        int total = deepThreads + shallowThreads;
        CyclicBarrier start = new CyclicBarrier(total);
        java.util.concurrent.CountDownLatch deepDone = new java.util.concurrent.CountDownLatch(deepThreads);
        ExecutorService exec = Executors.newFixedThreadPool(total);
        try {
            List<Future<Integer>> deepFutures = new ArrayList<>();
            for (int i = 0; i < deepThreads; i++) {
                deepFutures.add(exec.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return accumulator.propose(chunk, -20);
                    } finally {
                        deepDone.countDown();
                    }
                }));
            }
            List<Future<Integer>> shallowFutures = new ArrayList<>();
            for (int i = 0; i < shallowThreads; i++) {
                shallowFutures.add(exec.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    assertTrue(deepDone.await(5, TimeUnit.SECONDS), "deep wave must land first");
                    return accumulator.propose(chunk, 50);
                }));
            }
            for (Future<Integer> future : deepFutures) {
                future.get(5, TimeUnit.SECONDS);
            }
            for (Future<Integer> future : shallowFutures) {
                assertEquals(-20, future.get(5, TimeUnit.SECONDS),
                        "shallow proposal must observe the accepted deeper minimum");
            }
            assertEquals(-20, accumulator.current(chunk).orElseThrow());
        } finally {
            exec.shutdownNow();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void sequentialProposalsTrackMinWithoutLostUpdate() {
        DepthCasAccumulator accumulator = new DepthCasAccumulator();
        ChunkKey chunk = new ChunkKey(UUID.randomUUID(), 1, 1);
        assertTrue(accumulator.current(chunk).isEmpty());
        assertEquals(60, accumulator.seed(chunk, 60));
        assertEquals(40, accumulator.propose(chunk, 40));
        assertEquals(40, accumulator.propose(chunk, 55), "shallower proposal is a no-op");
        assertEquals(40, accumulator.propose(chunk, 40), "duplicate proposal is stable");
        assertEquals(-64, accumulator.propose(chunk, -64));
        assertEquals(-64, accumulator.propose(chunk, 0), "late shallow proposal never wins");
        assertEquals(-64, accumulator.current(chunk).orElseThrow());
    }

    @Test
    void chunksAreIsolatedFromEachOther() {
        DepthCasAccumulator accumulator = new DepthCasAccumulator();
        UUID world = UUID.randomUUID();
        ChunkKey a = new ChunkKey(world, 0, 0);
        ChunkKey b = new ChunkKey(world, 0, 1);
        accumulator.seed(a, 60);
        accumulator.seed(b, 60);
        assertEquals(10, accumulator.propose(a, 10));
        assertEquals(60, accumulator.current(b).orElseThrow());
        assertEquals(10, accumulator.snapshot().get(a));
    }
}

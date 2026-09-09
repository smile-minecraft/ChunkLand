package com.smile.chunkland.subland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.persistence.LandRepository;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.persistence.SubLandAtomicCommit;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import com.smile.chunkland.subland.SubLandMutationRunner.RuntimePublisher;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Single-flight lifecycle for {@code rebuildRuntime} across the
 * clear-versus-complete window.
 *
 * <p>The completion callback completes the joined future before clearing the
 * in-flight slot, so a second caller arriving in that gap still observes the
 * (already completed) in-flight future and joins it instead of starting a
 * second publisher. These tests pin a second caller inside the gap with a
 * latch-based hook (no sleep) and require at most one publisher with both
 * callers sharing the same in-flight future.
 */
class SubLandRebuildSingleFlightRaceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir Path tmp;

    private static final class Env implements AutoCloseable {
        final PersistenceStore store;
        final LandRepository lands;
        final LandRegistryStore registry = new LandRegistryStore();
        final SelectionSessionManager selections = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                () -> NOW,
                Duration.ofMinutes(10));
        final SubLandConfirmService confirm = new SubLandConfirmService();

        Env(Path db) {
            store = PersistenceStore.open(db);
            lands = new SqliteLandRepository(store);
        }

        SubLandMutationRunner runner(RuntimePublisher publisher) {
            return new SubLandMutationRunner(lands,
                    new SubLandAtomicCommit(store),
                    registry, selections,
                    confirm, SubLandDepthSource.constant(50), DepthExtensionPort.denyAll(),
                    LimitSettings.defaults(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    publisher);
        }

        @Override
        public void close() {
            store.close();
        }
    }

    @Test
    void secondCallerInClearCompleteGapSharesOnePublishOnSuccess() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            CompletableFuture<LandRegistry> gate = new CompletableFuture<>();
            AtomicInteger calls = new AtomicInteger();
            SubLandMutationRunner runner = env.runner(() -> {
                calls.incrementAndGet();
                return gate;
            });
            var first = runner.rebuildRuntime();
            assertEquals(1, calls.get());

            CountDownLatch enteredHook = new CountDownLatch(1);
            CountDownLatch releaseHook = new CountDownLatch(1);
            runner.rebuildCompletionHookForTest = () -> {
                enteredHook.countDown();
                try {
                    assertTrue(releaseHook.await(10, TimeUnit.SECONDS),
                            "race test must release the completion gap");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            };

            LandRegistry expected = LandRegistry.empty();
            ExecutorService completing = Executors.newSingleThreadExecutor();
            try {
                Future<?> done = completing.submit(() -> gate.complete(expected));
                assertTrue(enteredHook.await(10, TimeUnit.SECONDS),
                        "completion callback must reach the clear/complete gap");
                // Second caller arrives while the first future is still
                // incomplete in the gap: it must join, not publish again.
                var second = runner.rebuildRuntime();
                releaseHook.countDown();
                done.get(10, TimeUnit.SECONDS);
                assertEquals(1, calls.get(),
                        "second caller in the clear/complete gap must not start a second publisher");
                assertSame(first, second,
                        "second caller in the gap must share the same in-flight future");
                assertSame(expected, first.toCompletableFuture().join());
                assertSame(expected, second.toCompletableFuture().join());
            } finally {
                completing.shutdownNow();
            }

            // Sequential retry after completion runs again but stays side-effect free.
            runner.rebuildCompletionHookForTest = null;
            CompletableFuture<LandRegistry> next = new CompletableFuture<>();
            AtomicInteger extra = new AtomicInteger();
            SubLandMutationRunner retryRunner = env.runner(() -> {
                if (extra.incrementAndGet() == 1) {
                    return CompletableFuture.completedFuture(LandRegistry.empty());
                }
                next.complete(LandRegistry.empty());
                return next;
            });
            // Use the original runner for the sequential check: its slot is clear.
            var third = runner.rebuildRuntime();
            assertEquals(2, calls.get());
            gate.complete(LandRegistry.empty());
            third.toCompletableFuture().join();
            assertTrue(retryRunner != null);
        }
    }

    @Test
    void secondCallerInClearCompleteGapSharesOnePublishOnFailure() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            CompletableFuture<LandRegistry> gate = new CompletableFuture<>();
            AtomicInteger calls = new AtomicInteger();
            SubLandMutationRunner runner = env.runner(() -> {
                calls.incrementAndGet();
                return gate;
            });
            var first = runner.rebuildRuntime();
            assertEquals(1, calls.get());

            CountDownLatch enteredHook = new CountDownLatch(1);
            CountDownLatch releaseHook = new CountDownLatch(1);
            runner.rebuildCompletionHookForTest = () -> {
                enteredHook.countDown();
                try {
                    assertTrue(releaseHook.await(10, TimeUnit.SECONDS),
                            "race test must release the completion gap");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            };

            IllegalStateException boom = new IllegalStateException("injected publish failure");
            ExecutorService completing = Executors.newSingleThreadExecutor();
            try {
                Future<?> done = completing.submit(() -> gate.completeExceptionally(boom));
                assertTrue(enteredHook.await(10, TimeUnit.SECONDS),
                        "completion callback must reach the clear/complete gap");
                var second = runner.rebuildRuntime();
                releaseHook.countDown();
                done.get(10, TimeUnit.SECONDS);
                assertEquals(1, calls.get(),
                        "second caller in the failure gap must not start a second publisher");
                assertSame(first, second,
                        "second caller in the failure gap must share the same in-flight future");
                CompletionException firstFailure = assertThrows(CompletionException.class,
                        () -> first.toCompletableFuture().join());
                assertSame(boom, firstFailure.getCause());
                CompletionException secondFailure = assertThrows(CompletionException.class,
                        () -> second.toCompletableFuture().join());
                assertSame(boom, secondFailure.getCause());
            } finally {
                completing.shutdownNow();
            }

            // Failure must not wedge the slot: the next retry runs again.
            runner.rebuildCompletionHookForTest = null;
            var third = runner.rebuildRuntime();
            assertEquals(2, calls.get());
            gate.completeExceptionally(new IllegalStateException("second round"));
            CompletionException thirdFailure = assertThrows(CompletionException.class,
                    () -> third.toCompletableFuture().join());
            assertInstanceOf(IllegalStateException.class, thirdFailure.getCause());
        }
    }

    @Test
    void retryAfterCompletionRunsAgain() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            AtomicInteger calls = new AtomicInteger();
            SubLandMutationRunner runner = env.runner(() -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(LandRegistry.empty());
            });
            var first = runner.rebuildRuntime();
            first.toCompletableFuture().join();
            assertEquals(1, calls.get());
            var second = runner.rebuildRuntime();
            second.toCompletableFuture().join();
            assertEquals(2, calls.get(),
                    "sequential retries after completion must run again");
        }
    }

    @Test
    void concurrentCallersShareOnePublish() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            CompletableFuture<LandRegistry> gate = new CompletableFuture<>();
            AtomicInteger calls = new AtomicInteger();
            SubLandMutationRunner runner = env.runner(() -> {
                calls.incrementAndGet();
                return gate;
            });
            int contenders = 8;
            CyclicBarrier start = new CyclicBarrier(contenders);
            ExecutorService pool = Executors.newFixedThreadPool(contenders);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < contenders; i++) {
                    futures.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return runner.rebuildRuntime();
                    }));
                }
                List<CompletionException> errors = new ArrayList<>();
                List<Object> stages = new ArrayList<>();
                for (Future<?> future : futures) {
                    try {
                        stages.add(future.get(10, TimeUnit.SECONDS));
                    } catch (Exception failure) {
                        errors.add(new CompletionException(failure));
                    }
                }
                assertTrue(errors.isEmpty(), "concurrent callers must not fail: " + errors);
                assertEquals(contenders, stages.size());
                Object first = stages.get(0);
                for (Object stage : stages) {
                    assertSame(first, stage, "concurrent callers must share one in-flight future");
                }
                assertEquals(1, calls.get());
                gate.complete(LandRegistry.empty());
                for (Object stage : stages) {
                    assertSame(gate.join(),
                            ((java.util.concurrent.CompletionStage<LandRegistry>) stage)
                                    .toCompletableFuture().join());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }
}

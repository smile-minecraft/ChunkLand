package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Red contract for {@link OfflinePlayerResolver}: UUID text and online exact
 * names resolve synchronously without touching the blocking lookup; unknown
 * names are dispatched to the explicit async executor so the region-facing
 * thread never blocks; failures and timeouts fail closed to empty without
 * leaking data.
 */
class OfflinePlayerResolverTest {

    private static final UUID ONLINE_UUID = UUID.randomUUID();
    private static final UUID OFFLINE_UUID = UUID.randomUUID();

    /** Executor that captures tasks so tests drive async completion by hand. */
    private static final class RecordingExecutor implements Executor {
        final List<Runnable> pending = new CopyOnWriteArrayList<>();

        @Override
        public void execute(Runnable task) {
            pending.add(task);
        }

        void runAll() {
            List<Runnable> due = new ArrayList<>(pending);
            pending.clear();
            for (Runnable task : due) {
                task.run();
            }
        }
    }

    private static Function<String, Optional<UUID>> online(Map<String, UUID> known) {
        return name -> Optional.ofNullable(name == null ? null : known.get(name));
    }

    private static OfflinePlayerResolver resolverWith(
            Function<String, Optional<UUID>> onlineIds,
            OfflinePlayerResolver.BlockingLookup offline,
            Executor executor) {
        return new OfflinePlayerResolver(onlineIds, offline, executor);
    }

    @Test
    void uuidTextResolvesWithoutExecutorOrOfflineLookup() {
        AtomicInteger executorTasks = new AtomicInteger(0);
        AtomicInteger offlineCalls = new AtomicInteger(0);
        UUID expected = UUID.randomUUID();
        OfflinePlayerResolver resolver = resolverWith(
                online(Map.of()),
                name -> {
                    offlineCalls.incrementAndGet();
                    return Optional.of(OFFLINE_UUID);
                },
                task -> executorTasks.incrementAndGet());
        Optional<UUID> got = resolver.resolveAsync(expected.toString())
                .toCompletableFuture().join();
        assertEquals(Optional.of(expected), got);
        assertEquals(0, executorTasks.get(), "UUID text must not touch the executor");
        assertEquals(0, offlineCalls.get(), "UUID text must not touch the blocking lookup");
    }

    @Test
    void onlineExactNameResolvesSynchronously() {
        AtomicInteger executorTasks = new AtomicInteger(0);
        AtomicInteger offlineCalls = new AtomicInteger(0);
        OfflinePlayerResolver resolver = resolverWith(
                online(Map.of("Steve", ONLINE_UUID)),
                name -> {
                    offlineCalls.incrementAndGet();
                    return Optional.of(OFFLINE_UUID);
                },
                task -> executorTasks.incrementAndGet());
        CompletionStage<Optional<UUID>> stage = resolver.resolveAsync("Steve");
        assertTrue(stage.toCompletableFuture().isDone(),
                "online hit must complete without waiting for the executor");
        assertEquals(Optional.of(ONLINE_UUID), stage.toCompletableFuture().join());
        assertEquals(0, executorTasks.get(), "online hit must not touch the executor");
        assertEquals(0, offlineCalls.get(), "online hit must not touch the blocking lookup");
    }

    @Test
    void reservedNamesNeverResolveNorTouchExecutor() {
        AtomicInteger executorTasks = new AtomicInteger(0);
        AtomicInteger offlineCalls = new AtomicInteger(0);
        OfflinePlayerResolver resolver = resolverWith(
                online(Map.of("Steve", ONLINE_UUID)),
                name -> {
                    offlineCalls.incrementAndGet();
                    return Optional.of(OFFLINE_UUID);
                },
                task -> executorTasks.incrementAndGet());
        for (String reserved : List.of("EVERYONE", "*", "group(admins)", "  ", "")) {
            Optional<UUID> got = resolver.resolveAsync(reserved)
                    .toCompletableFuture().join();
            assertEquals(Optional.empty(), got, reserved + " must fail closed");
        }
        assertEquals(0, executorTasks.get(), "reserved names must not touch the executor");
        assertEquals(0, offlineCalls.get(), "reserved names must not touch the blocking lookup");
    }

    @Test
    void unknownNameDispatchesToExecutorWithoutBlockingCaller() {
        RecordingExecutor executor = new RecordingExecutor();
        AtomicInteger offlineCalls = new AtomicInteger(0);
        OfflinePlayerResolver resolver = resolverWith(
                online(Map.of()),
                name -> {
                    offlineCalls.incrementAndGet();
                    return Optional.of(OFFLINE_UUID);
                },
                executor);
        CompletionStage<Optional<UUID>> stage = resolver.resolveAsync("OfflineSteve");
        assertEquals(0, offlineCalls.get(),
                "region-facing call must return before the blocking lookup runs");
        assertFalse(stage.toCompletableFuture().isDone(),
                "stage must stay pending until the executor runs the lookup");
        assertEquals(1, executor.pending.size(), "exactly one async lookup must be queued");
        executor.runAll();
        assertEquals(1, offlineCalls.get(), "lookup must run exactly once on the executor");
        assertEquals(Optional.of(OFFLINE_UUID), stage.toCompletableFuture().join());
    }

    @Test
    void offlineLookupRunsOffTheCallerThread() throws Exception {
        AtomicReference<String> lookupThread = new AtomicReference<>();
        OfflinePlayerResolver resolver = resolverWith(
                online(Map.of()),
                name -> {
                    lookupThread.set(Thread.currentThread().getName());
                    return Optional.of(OFFLINE_UUID);
                },
                task -> new Thread(task, "async-resolve").start());
        Optional<UUID> got = resolver.resolveAsync("OfflineSteve")
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(Optional.of(OFFLINE_UUID), got);
        assertEquals("async-resolve", lookupThread.get(),
                "blocking lookup must run on the executor thread, not the caller");
    }

    @Test
    void offlineLookupFailureFailsClosedToEmpty() {
        RecordingExecutor executor = new RecordingExecutor();
        OfflinePlayerResolver resolver = resolverWith(
                online(Map.of()),
                name -> {
                    throw new IllegalStateException("network down");
                },
                executor);
        CompletionStage<Optional<UUID>> stage = resolver.resolveAsync("OfflineSteve");
        executor.runAll();
        assertTrue(stage.toCompletableFuture().isDone());
        assertEquals(Optional.empty(), stage.toCompletableFuture().join(),
                "lookup failure must fail closed, not propagate");
    }

    @Test
    void offlineLookupReturningNullFailsClosedToEmpty() {
        RecordingExecutor executor = new RecordingExecutor();
        OfflinePlayerResolver resolver = resolverWith(
                online(Map.of()),
                name -> null,
                executor);
        CompletionStage<Optional<UUID>> stage = resolver.resolveAsync("OfflineSteve");
        executor.runAll();
        assertEquals(Optional.empty(), stage.toCompletableFuture().join(),
                "null lookup answer must fail closed");
    }

    @Test
    void timeoutFailsClosedWithoutLeaking() throws Exception {
        RecordingExecutor neverRuns = new RecordingExecutor();
        AtomicInteger offlineCalls = new AtomicInteger(0);
        OfflinePlayerResolver resolver = resolverWith(
                online(Map.of()),
                name -> {
                    offlineCalls.incrementAndGet();
                    return Optional.of(OFFLINE_UUID);
                },
                neverRuns);
        CompletionStage<Optional<UUID>> stage =
                resolver.resolveAsync("OfflineSteve", Duration.ofMillis(200));
        Optional<UUID> got = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(Optional.empty(), got, "timed-out resolution must fail closed");
    }

    @Test
    void timeoutExceptionalStageMapsToEmpty() {
        OfflinePlayerResolver resolver = resolverWith(
                online(Map.of()),
                name -> Optional.of(OFFLINE_UUID),
                Runnable::run);
        CompletableFuture<Optional<UUID>> timedOut = new CompletableFuture<>();
        timedOut.completeExceptionally(new java.util.concurrent.TimeoutException("slow"));
        Optional<UUID> got = OfflinePlayerResolver.failClosed(timedOut).toCompletableFuture().join();
        assertEquals(Optional.empty(), got, "timeout failure must fail closed");
    }

    @Test
    void nullInputsFailClosed() {
        OfflinePlayerResolver resolver = resolverWith(
                online(Map.of("Steve", ONLINE_UUID)),
                name -> Optional.of(OFFLINE_UUID),
                Runnable::run);
        assertEquals(Optional.empty(),
                resolver.resolveAsync(null).toCompletableFuture().join());
        assertEquals(Optional.empty(),
                resolver.resolveAsync("   ").toCompletableFuture().join());
    }
}

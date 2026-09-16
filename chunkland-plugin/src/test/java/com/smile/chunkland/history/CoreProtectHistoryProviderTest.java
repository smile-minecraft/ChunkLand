package com.smile.chunkland.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.history.HistoryEntry;
import com.smile.chunkland.api.history.HistoryQuery;
import com.smile.chunkland.api.history.HistoryResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Provider contract: the CoreProtect call runs on the injected executor,
 * never the caller thread; valid rows become a bounded immutable answer;
 * every failure degrades to unavailable without throwing.
 */
class CoreProtectHistoryProviderTest {

    private static HistoryQuery query() {
        return HistoryQuery.bounded(UUID.randomUUID(), "world", 0, 64, 0, 8, 3600, 5);
    }

    private static HistoryEntry entry(int x) {
        return new HistoryEntry(x, 64, 0, "placed", "STONE", 1_700_000_000L);
    }

    @Test
    void validLookupBecomesBoundedImmutableAnswer() throws Exception {
        List<HistoryEntry> rows = new ArrayList<>(List.of(entry(1), entry(2)));
        CoreProtectHistoryProvider provider = new CoreProtectHistoryProvider(
                Runnable::run,
                ignored -> new CoreProtectHistoryProvider.LookupOutcome(rows, true));
        HistoryResult result = provider.query(query())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(result.available());
        assertTrue(result.truncated());
        assertEquals(2, result.entries().size());
        rows.clear();
        assertEquals(2, result.entries().size());
        assertThrows(UnsupportedOperationException.class,
                () -> result.entries().add(entry(3)));
    }

    @Test
    void throwingLookupYieldsUnavailable() throws Exception {
        CoreProtectHistoryProvider provider = new CoreProtectHistoryProvider(
                Runnable::run,
                ignored -> {
                    throw new IllegalStateException("backend down");
                });
        HistoryResult result = provider.query(query())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertFalse(result.available());
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void nullOutcomeYieldsUnavailable() throws Exception {
        CoreProtectHistoryProvider provider =
                new CoreProtectHistoryProvider(Runnable::run, ignored -> null);
        HistoryResult result = provider.query(query())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(result.available());
    }

    @Test
    void nullQueryYieldsUnavailableWithoutTouchingLookup() throws Exception {
        AtomicBoolean lookupRan = new AtomicBoolean(false);
        CoreProtectHistoryProvider provider = new CoreProtectHistoryProvider(
                Runnable::run,
                ignored -> {
                    lookupRan.set(true);
                    return null;
                });
        HistoryResult result = provider.query(null)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(result.available());
        assertFalse(lookupRan.get());
    }

    @Test
    void lookupRunsOnInjectedExecutorNeverCallerThread() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> lookupThread = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "history-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            CoreProtectHistoryProvider provider = new CoreProtectHistoryProvider(
                    executor,
                    ignored -> {
                        lookupThread.set(Thread.currentThread());
                        return new CoreProtectHistoryProvider.LookupOutcome(List.of(), false);
                    });
            HistoryResult result = provider.query(query())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(result.available());
            assertNotNull(lookupThread.get());
            assertEquals("history-test", lookupThread.get().getName());
            assertTrue(lookupThread.get() != caller);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedExecutorYieldsUnavailableWithoutThrowing() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdownNow();
        CoreProtectHistoryProvider provider = new CoreProtectHistoryProvider(
                executor, ignored -> {
                    throw new IllegalStateException("must not run");
                });
        HistoryResult result = provider.query(query())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(result.available());
    }
}

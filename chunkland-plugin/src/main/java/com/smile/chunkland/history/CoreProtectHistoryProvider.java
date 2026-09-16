package com.smile.chunkland.history;

import com.smile.chunkland.api.history.HistoryEntry;
import com.smile.chunkland.api.history.HistoryQuery;
import com.smile.chunkland.api.history.HistoryResult;
import com.smile.chunkland.api.history.WorldHistoryProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Optional backend-backed {@link WorldHistoryProvider}.
 *
 * <p>The immutable {@link HistoryQuery} is captured on the calling thread and
 * the backend {@link Lookup} runs on the injected executor only, so a slow or
 * failing optional lookup can never block a region thread, the inspect
 * summary path or a player command thread. Every degraded state — null
 * query, rejected execution, throwing or null-returning lookup — answers
 * unavailable instead of throwing.
 */
public final class CoreProtectHistoryProvider implements WorldHistoryProvider {

    /**
     * Backend lookup seam. Production performs the reflective optional-API
     * call; tests supply fakes. Runs on the provider executor, never the
     * caller thread, and receives only the already-captured immutable query.
     */
    @FunctionalInterface
    public interface Lookup {
        /**
         * @param query captured immutable query (never null)
         * @return bounded outcome, or null to degrade to unavailable
         */
        LookupOutcome fetch(HistoryQuery query) throws Exception;
    }

    /** Bounded backend answer before it becomes a {@link HistoryResult}. */
    public record LookupOutcome(List<HistoryEntry> entries, boolean truncated) {
        public LookupOutcome {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    private final Executor executor;
    private final Lookup lookup;

    public CoreProtectHistoryProvider(Executor executor, Lookup lookup) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /** Executor seam visible to wiring tests. */
    Executor executorForTest() {
        return executor;
    }

    @Override
    public CompletionStage<HistoryResult> query(HistoryQuery query) {
        if (query == null) {
            return CompletableFuture.completedFuture(HistoryResult.unavailable());
        }
        CompletableFuture<HistoryResult> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    LookupOutcome outcome = lookup.fetch(query);
                    future.complete(toResult(outcome));
                } catch (LinkageError | Exception failure) {
                    future.complete(HistoryResult.unavailable());
                }
            });
        } catch (RuntimeException rejected) {
            future.complete(HistoryResult.unavailable());
        }
        return future;
    }

    private static HistoryResult toResult(LookupOutcome outcome) {
        if (outcome == null) {
            return HistoryResult.unavailable();
        }
        List<HistoryEntry> entries = new ArrayList<>();
        for (HistoryEntry entry : outcome.entries()) {
            if (entry != null) {
                entries.add(entry);
            }
        }
        return HistoryResult.of(entries, outcome.truncated());
    }
}

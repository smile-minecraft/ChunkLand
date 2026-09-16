package com.smile.chunkland.api.history;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Optional world block-history contract.
 *
 * <p>Implementations are loaded externally (the CoreProtect adapter) and must
 * run the backend lookup off the calling thread on an injected executor,
 * answering with a bounded immutable {@link HistoryResult}. A missing backend
 * answers unavailable instead of throwing, so CoreProtect-absent servers keep
 * starting and deciding protection exactly as before.
 *
 * <p>The interface intentionally references neither Bukkit nor any optional
 * backend type, so {@code chunkland-api} stays dependency-free.
 */
public interface WorldHistoryProvider {

    /**
     * Answers one bounded query without blocking the calling thread. A null
     * query, a missing backend or any lookup failure yields an unavailable
     * result — never a thrown exception, never a raw payload.
     */
    CompletionStage<HistoryResult> query(HistoryQuery query);

    /** Whether a live backend backs this provider. */
    default boolean available() {
        return true;
    }

    /** Fail-closed provider for CoreProtect-absent servers. */
    static WorldHistoryProvider empty() {
        return new WorldHistoryProvider() {
            @Override
            public CompletionStage<HistoryResult> query(HistoryQuery query) {
                return CompletableFuture.completedFuture(HistoryResult.unavailable());
            }

            @Override
            public boolean available() {
                return false;
            }
        };
    }
}

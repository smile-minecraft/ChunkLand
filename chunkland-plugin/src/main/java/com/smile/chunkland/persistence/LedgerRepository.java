package com.smile.chunkland.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface LedgerRepository {

    CompletionStage<Void> insert(LedgerEntry entry);

    CompletionStage<Optional<LedgerEntry>> findById(UUID operationId);

    /**
     * Compatibility-only update for legacy repositories whose state vocabulary
     * predates {@link LedgerState}. New code must use the compare-and-set API.
     */
    CompletionStage<Void> updateState(UUID operationId, String newState, Instant updatedAt);

    /**
     * Compatibility overload that reads the current typed state and still
     * performs the final guarded write through {@link #compareAndSetState}.
     */
    default CompletionStage<Void> updateState(UUID operationId, LedgerState newState, Instant updatedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(newState, "newState");
        Objects.requireNonNull(updatedAt, "updatedAt");
        return findById(operationId).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("unknown operation: " + operationId));
            }
            final LedgerState expected;
            try {
                expected = found.get().typedState();
            } catch (IllegalArgumentException malformed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("cannot transition malformed ledger state", malformed));
            }
            return compareAndSetState(operationId, expected, newState, updatedAt);
        });
    }

    /** Atomically changes one typed state when the expected state still matches. */
    CompletionStage<Void> compareAndSetState(
            UUID operationId, LedgerState expected, LedgerState next, Instant updatedAt);

    CompletionStage<List<LedgerEntry>> findByState(String state);

    default CompletionStage<List<LedgerEntry>> findByState(LedgerState state) {
        return findByState(state.name());
    }

    CompletionStage<List<LedgerEntry>> findAll();
}

package com.smile.chunkland.persistence;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Explicit seams for work that cannot run in a SQLite transaction. Production
 * integrations supply Economy lookup/refund and runtime rebuild implementations.
 */
public interface RecoveryHandlers {

    default CompletionStage<PaymentLookup> lookupPayment(LedgerEntry entry) {
        return CompletableFuture.completedFuture(PaymentLookup.unknown());
    }

    default CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
        return CompletableFuture.completedFuture(RefundOutcome.UNKNOWN);
    }

    default CompletionStage<ClaimCommit> replayClaim(LedgerEntry entry, OperationPayload payload) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("claim replay is not configured"));
    }

    default CompletionStage<Void> rebuildRuntime(LedgerEntry entry, OperationPayload payload) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("runtime rebuild is not configured"));
    }

    static RecoveryHandlers none() {
        return new RecoveryHandlers() { };
    }

    static RecoveryHandlers of(
            PaymentLookupFunction lookup,
            RefundFunction refund,
            ClaimReplayFunction replay,
            RuntimeRebuildFunction rebuild) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(refund, "refund");
        Objects.requireNonNull(replay, "replay");
        Objects.requireNonNull(rebuild, "rebuild");
        return new RecoveryHandlers() {
            @Override
            public CompletionStage<PaymentLookup> lookupPayment(LedgerEntry entry) {
                return lookup.apply(entry);
            }

            @Override
            public CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
                return refund.apply(entry);
            }

            @Override
            public CompletionStage<ClaimCommit> replayClaim(LedgerEntry entry, OperationPayload payload) {
                return replay.apply(entry, payload);
            }

            @Override
            public CompletionStage<Void> rebuildRuntime(LedgerEntry entry, OperationPayload payload) {
                return rebuild.apply(entry, payload);
            }
        };
    }

    @FunctionalInterface
    interface PaymentLookupFunction {
        CompletionStage<PaymentLookup> apply(LedgerEntry entry);
    }

    @FunctionalInterface
    interface RefundFunction {
        CompletionStage<RefundOutcome> apply(LedgerEntry entry);
    }

    @FunctionalInterface
    interface ClaimReplayFunction {
        CompletionStage<ClaimCommit> apply(LedgerEntry entry, OperationPayload payload);
    }

    @FunctionalInterface
    interface RuntimeRebuildFunction {
        CompletionStage<Void> apply(LedgerEntry entry, OperationPayload payload);
    }
}

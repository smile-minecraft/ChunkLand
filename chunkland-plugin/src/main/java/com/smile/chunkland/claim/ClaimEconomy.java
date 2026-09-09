package com.smile.chunkland.claim;

import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.RefundOutcome;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Economy seam for the claim saga.
 *
 * <p>{@code charge} must carry {@code operationId} as its idempotency key and
 * must never run inside a SQL transaction; the saga guarantees the ordering.
 * {@code refund} drives step-four compensation and shares the outcome contract
 * of the startup recovery path.
 */
public interface ClaimEconomy {

    /** Result of a single charge attempt. */
    record ChargeResult(boolean success, String diagnosticKey) {
        public static ChargeResult ok() {
            return new ChargeResult(true, null);
        }

        public static ChargeResult failed(String diagnosticKey) {
            Objects.requireNonNull(diagnosticKey, "diagnosticKey");
            return new ChargeResult(false, diagnosticKey);
        }
    }

    CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request,
            com.smile.chunkland.api.money.Money price);

    default CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return CompletableFuture.completedFuture(RefundOutcome.UNKNOWN);
    }

    /** Provider identity recorded on the ledger payload for the refund gate. */
    default String providerId() {
        return "unknown-economy";
    }
}

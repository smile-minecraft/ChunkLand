package com.smile.chunkland.refund;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Fail-closed validation seam for refunds.
 *
 * <p>Implementations read durable land state (owner, chunk membership,
 * per-chunk cost basis) and compute the refund amount from that durable basis
 * only. A rejected request completes with {@link RefundRejectedException};
 * any other failure is treated as an internal validation failure and never
 * moves money.
 */
@FunctionalInterface
public interface RefundValidator {

    CompletionStage<ValidatedRefund> validate(RefundRequest request);

    /** Validator that rejects every request with the given key (useful in tests). */
    static RefundValidator rejecting(String diagnosticKey) {
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return request -> CompletableFuture.failedFuture(new RefundRejectedException(diagnosticKey));
    }
}

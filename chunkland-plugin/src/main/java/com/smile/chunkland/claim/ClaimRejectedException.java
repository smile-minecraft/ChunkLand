package com.smile.chunkland.claim;

import java.util.Objects;

/**
 * Rejection signal from step-one claim revalidation.
 *
 * <p>Carries a stable diagnostic key (never rendered text). The saga maps this
 * to a {@code REJECTED} outcome before any ledger row, charge, or durable write.
 */
public final class ClaimRejectedException extends RuntimeException {

    private final String diagnosticKey;

    public ClaimRejectedException(String diagnosticKey) {
        super(Objects.requireNonNull(diagnosticKey, "diagnosticKey"));
        this.diagnosticKey = diagnosticKey;
    }

    public ClaimRejectedException(String diagnosticKey, String detail) {
        super(Objects.requireNonNull(detail, "detail"));
        this.diagnosticKey = Objects.requireNonNull(diagnosticKey, "diagnosticKey");
    }

    public String diagnosticKey() {
        return diagnosticKey;
    }
}

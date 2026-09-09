package com.smile.chunkland.refund;

import java.util.Objects;

/**
 * Fail-closed validation failure for a refund request.
 *
 * <p>Carries a stable diagnostic key (never rendered text). The saga maps this
 * to a {@code REJECTED} outcome without creating a ledger row, touching the
 * domain, or calling Economy.
 */
public final class RefundRejectedException extends RuntimeException {

    private final String diagnosticKey;

    public RefundRejectedException(String diagnosticKey) {
        super(diagnosticKey);
        this.diagnosticKey = Objects.requireNonNull(diagnosticKey, "diagnosticKey");
    }

    public RefundRejectedException(String diagnosticKey, Throwable cause) {
        super(diagnosticKey, cause);
        this.diagnosticKey = Objects.requireNonNull(diagnosticKey, "diagnosticKey");
    }

    public String diagnosticKey() {
        return diagnosticKey;
    }
}

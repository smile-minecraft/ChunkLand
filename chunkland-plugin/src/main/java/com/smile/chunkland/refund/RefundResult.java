package com.smile.chunkland.refund;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;

/**
 * Immutable outcome of a refund saga execution.
 *
 * <p>{@code diagnosticKey} is a stable message key, never rendered text.
 * {@code NEEDS_RECONCILIATION} means the deposit ran once behind a durably
 * parked execution intent but was not confirmed: the domain is never rolled
 * back and the row is never resent, because a resend could double-credit.
 * {@code COMPENSATION_PENDING} is retained for the command/display mapping
 * but the saga no longer produces it for unconfirmed deposits. {@code DEGRADED}
 * means
 * the money moved and the ledger is {@code COMPENSATED}, but the runtime
 * publish or the final marking did not complete.
 */
public record RefundResult(Status status, LandId landId, Long refundMinorUnits, String diagnosticKey) {

    /** Terminal refund states. */
    public enum Status {
        SUCCESS,
        COMPENSATION_PENDING,
        NEEDS_RECONCILIATION,
        REJECTED,
        FAILED,
        DEGRADED
    }

    public RefundResult {
        Objects.requireNonNull(status, "status");
    }

    public static RefundResult success(LandId landId, long refundMinorUnits) {
        Objects.requireNonNull(landId, "landId");
        return new RefundResult(Status.SUCCESS, landId, refundMinorUnits, null);
    }

    public static RefundResult compensationPending(LandId landId, long refundMinorUnits) {
        return new RefundResult(Status.COMPENSATION_PENDING, landId,
                refundMinorUnits, "refund.compensation_pending");
    }

    public static RefundResult needsReconciliation(LandId landId, Long refundMinorUnits) {
        return new RefundResult(Status.NEEDS_RECONCILIATION, landId,
                refundMinorUnits, "refund.reconciliation");
    }

    public static RefundResult rejected(String diagnosticKey) {
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new RefundResult(Status.REJECTED, null, null, diagnosticKey);
    }

    public static RefundResult failed(String diagnosticKey) {
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new RefundResult(Status.FAILED, null, null, diagnosticKey);
    }

    public static RefundResult degraded(LandId landId, long refundMinorUnits, String diagnosticKey) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new RefundResult(Status.DEGRADED, landId, refundMinorUnits, diagnosticKey);
    }
}

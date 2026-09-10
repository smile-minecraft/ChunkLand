package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;

/**
 * Immutable outcome of a shrink saga execution.
 *
 * <p>{@code diagnosticKey} is a stable message key, never rendered text.
 * {@code COMPENSATION_PENDING} means the domain commit is durable but the
 * deposit is unconfirmed: the domain is never rolled back and the row is
 * retried through the shared compensation contract. {@code DEGRADED} means
 * the money moved and the ledger is {@code COMPENSATED}, but the runtime
 * publish did not complete.
 */
public record ShrinkOutcome(Status status, LandId landId, Long refundMinorUnits, String diagnosticKey) {

    /** Terminal shrink states. */
    public enum Status {
        SUCCESS,
        COMPENSATION_PENDING,
        NEEDS_RECONCILIATION,
        REJECTED,
        FAILED,
        DEGRADED
    }

    public ShrinkOutcome {
        Objects.requireNonNull(status, "status");
    }

    public static ShrinkOutcome success(LandId landId, long refundMinorUnits) {
        Objects.requireNonNull(landId, "landId");
        return new ShrinkOutcome(Status.SUCCESS, landId, refundMinorUnits, null);
    }

    public static ShrinkOutcome compensationPending(LandId landId, long refundMinorUnits) {
        return new ShrinkOutcome(Status.COMPENSATION_PENDING, landId,
                refundMinorUnits, "shrink.compensation_pending");
    }

    public static ShrinkOutcome needsReconciliation(LandId landId, Long refundMinorUnits) {
        return new ShrinkOutcome(Status.NEEDS_RECONCILIATION, landId,
                refundMinorUnits, "shrink.reconciliation");
    }

    public static ShrinkOutcome rejected(String diagnosticKey) {
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new ShrinkOutcome(Status.REJECTED, null, null, diagnosticKey);
    }

    public static ShrinkOutcome failed(String diagnosticKey) {
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new ShrinkOutcome(Status.FAILED, null, null, diagnosticKey);
    }

    public static ShrinkOutcome degraded(LandId landId, long refundMinorUnits, String diagnosticKey) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new ShrinkOutcome(Status.DEGRADED, landId, refundMinorUnits, diagnosticKey);
    }
}

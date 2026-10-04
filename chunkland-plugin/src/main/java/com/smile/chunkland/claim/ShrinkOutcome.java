package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;

/**
 * Immutable outcome of a shrink saga execution.
 *
 * <p>{@code diagnosticKey} is a stable message key, never rendered text.
 * {@code NEEDS_RECONCILIATION} means the deposit ran once behind a durably
 * parked execution intent but was not confirmed: the domain is never rolled
 * back and the row is never resent, because a resend could double-credit.
 * {@code COMPENSATION_PENDING} is retained for the command/display mapping
 * but the saga no longer produces it for unconfirmed deposits. {@code DEGRADED}
 * means
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

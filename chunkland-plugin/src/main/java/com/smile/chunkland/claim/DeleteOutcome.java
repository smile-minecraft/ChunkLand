package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;

/**
 * Immutable outcome of a land-delete saga execution.
 *
 * <p>{@code diagnosticKey} is a stable message key, never rendered text.
 * {@code NEEDS_RECONCILIATION} means the deposit ran once behind a durably
 * parked execution intent but was not confirmed: the delete is never rolled
 * back and the row is never resent, because a resend could double-credit.
 * {@code COMPENSATION_PENDING} is retained for the command/display mapping
 * but the saga no longer produces it for unconfirmed deposits. {@code DEGRADED}
 * means
 * the delete committed durably but the runtime publish did not complete, so
 * no refund was attempted yet; startup recovery completes both.
 */
public record DeleteOutcome(Status status, LandId landId, Long refundMinorUnits, String diagnosticKey) {

    /** Terminal land-delete states. */
    public enum Status {
        SUCCESS,
        COMPENSATION_PENDING,
        NEEDS_RECONCILIATION,
        REJECTED,
        FAILED,
        DEGRADED
    }

    public DeleteOutcome {
        Objects.requireNonNull(status, "status");
    }

    public static DeleteOutcome success(LandId landId, long refundMinorUnits) {
        Objects.requireNonNull(landId, "landId");
        return new DeleteOutcome(Status.SUCCESS, landId, refundMinorUnits, null);
    }

    public static DeleteOutcome compensationPending(LandId landId, long refundMinorUnits) {
        return new DeleteOutcome(Status.COMPENSATION_PENDING, landId,
                refundMinorUnits, "delete.compensation_pending");
    }

    public static DeleteOutcome needsReconciliation(LandId landId, Long refundMinorUnits) {
        return new DeleteOutcome(Status.NEEDS_RECONCILIATION, landId,
                refundMinorUnits, "delete.reconciliation");
    }

    public static DeleteOutcome rejected(String diagnosticKey) {
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new DeleteOutcome(Status.REJECTED, null, null, diagnosticKey);
    }

    public static DeleteOutcome failed(String diagnosticKey) {
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new DeleteOutcome(Status.FAILED, null, null, diagnosticKey);
    }

    public static DeleteOutcome degraded(LandId landId, long refundMinorUnits, String diagnosticKey) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new DeleteOutcome(Status.DEGRADED, landId, refundMinorUnits, diagnosticKey);
    }
}

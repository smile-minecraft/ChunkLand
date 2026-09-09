package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;

/**
 * Immutable outcome of a claim saga execution.
 *
 * <p>{@code diagnosticKey} is a stable message key, never rendered text.
 * {@code DEGRADED} means the domain commit is durable and no refund was issued,
 * but the runtime publish (or the final ledger marking) did not complete; the
 * ledger row stays {@code DOMAIN_COMMITTED} so startup recovery can rebuild the
 * runtime and advance it to {@code ACTIVE}.
 */
public record ClaimOutcome(Status status, LandId landId, String diagnosticKey) {

    /** Terminal claim states. */
    public enum Status {
        SUCCESS,
        REJECTED,
        FAILED,
        DEGRADED
    }

    public ClaimOutcome {
        Objects.requireNonNull(status, "status");
    }

    public static ClaimOutcome success(LandId landId) {
        Objects.requireNonNull(landId, "landId");
        return new ClaimOutcome(Status.SUCCESS, landId, null);
    }

    public static ClaimOutcome rejected(String diagnosticKey) {
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new ClaimOutcome(Status.REJECTED, null, diagnosticKey);
    }

    public static ClaimOutcome failed(String diagnosticKey) {
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new ClaimOutcome(Status.FAILED, null, diagnosticKey);
    }

    public static ClaimOutcome degraded(LandId landId, String diagnosticKey) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new ClaimOutcome(Status.DEGRADED, landId, diagnosticKey);
    }
}

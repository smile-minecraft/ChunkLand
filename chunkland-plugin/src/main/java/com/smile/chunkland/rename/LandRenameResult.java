package com.smile.chunkland.rename;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;

/**
 * Terminal outcome of one land rename.
 *
 * <p>{@code SUCCESS} means the durable row, its audit trail and the runtime
 * publish all landed. {@code DEGRADED} means the durable commit already won
 * but the runtime publish failed, so the live map lags the database until
 * the next rebuild. {@code REJECTED} carries a sender-facing diagnostic key
 * and never wrote anything; {@code FAILED} is the fail-closed bucket for
 * unexpected persistence or wiring errors.
 */
public record LandRenameResult(
        Status status,
        LandId landId,
        String oldDisplayName,
        String newDisplayName,
        String diagnosticKey) {

    /** Terminal states of a rename attempt. */
    public enum Status {
        SUCCESS,
        DEGRADED,
        REJECTED,
        FAILED
    }

    public LandRenameResult {
        Objects.requireNonNull(status, "status");
    }

    /** Durable rename plus a fresh runtime publish. */
    public static LandRenameResult success(LandId landId, String oldDisplayName, String newDisplayName) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(oldDisplayName, "oldDisplayName");
        Objects.requireNonNull(newDisplayName, "newDisplayName");
        return new LandRenameResult(Status.SUCCESS, landId, oldDisplayName, newDisplayName, null);
    }

    /** Durable rename won but the runtime publish failed. */
    public static LandRenameResult degraded(
            LandId landId, String oldDisplayName, String newDisplayName, String diagnosticKey) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(oldDisplayName, "oldDisplayName");
        Objects.requireNonNull(newDisplayName, "newDisplayName");
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new LandRenameResult(Status.DEGRADED, landId, oldDisplayName, newDisplayName, diagnosticKey);
    }

    /** Fail-closed rejection before or inside the mutation; nothing was written. */
    public static LandRenameResult rejected(String diagnosticKey) {
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new LandRenameResult(Status.REJECTED, null, null, null, diagnosticKey);
    }

    /** Unexpected failure; the durable state is unconfirmed, never claimed. */
    public static LandRenameResult failed(String diagnosticKey) {
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return new LandRenameResult(Status.FAILED, null, null, null, diagnosticKey);
    }
}

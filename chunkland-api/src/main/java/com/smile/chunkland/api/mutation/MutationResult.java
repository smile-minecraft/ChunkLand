package com.smile.chunkland.api.mutation;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;

/**
 * Immutable result of a mutation request (spec §85).
 *
 * <p>The API never returns rendered message text: {@link #diagnosticKey()} is a
 * stable message key for the caller to localize, not a localized string (spec
 * §84-1). {@code landId} and {@code diagnosticKey} may be {@code null} depending
 * on the outcome.
 *
 * <p>Thread-safe immutable value object.
 *
 * @since 0.1.0
 */
public record MutationResult(MutationOutcome outcome, LandId landId, String diagnosticKey) {

    public MutationResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    public static MutationResult success(LandId landId) {
        return new MutationResult(MutationOutcome.SUCCESS, landId, null);
    }

    public static MutationResult rejected(String diagnosticKey) {
        return new MutationResult(MutationOutcome.REJECTED, null, diagnosticKey);
    }

    public static MutationResult failed(String diagnosticKey) {
        return new MutationResult(MutationOutcome.FAILED, null, diagnosticKey);
    }
}

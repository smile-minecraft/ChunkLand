package com.smile.chunkland.api.permission;

import java.util.Objects;

/**
 * The immutable outcome of a permission resolution.
 *
 * <p>{@link #outcome()} is the binary decision ({@link PermissionState#ALLOW} or
 * {@link PermissionState#DENY}); {@link #source()} records which
 * {@link DecisionSource} category produced it; {@link #explanation()} is a
 * human-readable, deterministic trace of the layer that resolved the decision.
 *
 * <p>Thread-safe immutable value object.
 */
public record PermissionDecision(PermissionState outcome, DecisionSource source, String explanation) {
    public PermissionDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(explanation, "explanation");
        if (explanation.isBlank()) {
            throw new IllegalArgumentException("explanation must not be blank");
        }
        // The decision is binary: a layer that yields INHERIT must fall through,
        // never surface as the outcome. Only ALLOW / DENY are valid results.
        if (outcome == PermissionState.INHERIT) {
            throw new IllegalArgumentException("outcome must be ALLOW or DENY, not INHERIT");
        }
    }
}

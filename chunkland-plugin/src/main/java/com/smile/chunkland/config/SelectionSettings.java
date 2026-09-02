package com.smile.chunkland.config;

import java.time.Duration;

/** Immutable typed view of {@code config.yml::selection}. */
public record SelectionSettings(int sessionTimeoutSeconds) {
    public static final int DEFAULT_SESSION_TIMEOUT_SECONDS = 600;

    public SelectionSettings {
        if (sessionTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("sessionTimeoutSeconds must be positive: " + sessionTimeoutSeconds);
        }
    }

    public static SelectionSettings defaults() {
        return new SelectionSettings(DEFAULT_SESSION_TIMEOUT_SECONDS);
    }

    public Duration sessionTimeout() {
        try {
            return Duration.ofSeconds(sessionTimeoutSeconds);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("sessionTimeoutSeconds cannot be represented as a Duration", ex);
        }
    }
}

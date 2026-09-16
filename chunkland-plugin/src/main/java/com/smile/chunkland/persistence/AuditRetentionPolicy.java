package com.smile.chunkland.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Interprets the configured {@code retention-days} for audit history.
 *
 * <p>{@code 0} means keep everything: {@link #cutoff} returns empty and no
 * purge may run. A positive value resolves to an event-time cutoff
 * ({@code now - days}); purge deletes rows strictly older than that instant,
 * so a row exactly at the boundary is kept. Negative values are rejected.
 */
public final class AuditRetentionPolicy {

    /** Seconds in one retention day; days are whole calendar units. */
    static final long SECONDS_PER_DAY = 86_400L;

    private AuditRetentionPolicy() {
    }

    /** Whether {@code retentionDays} disables purging entirely. */
    public static boolean retainsForever(int retentionDays) {
        if (retentionDays < 0) {
            throw new IllegalArgumentException("retentionDays must be >= 0: " + retentionDays);
        }
        return retentionDays == 0;
    }

    /**
     * Resolve the event-time cutoff for {@code retentionDays} measured back
     * from {@code now}. Empty means retain forever.
     */
    public static Optional<Instant> cutoff(Instant now, int retentionDays) {
        Objects.requireNonNull(now, "now");
        if (retentionDays < 0) {
            throw new IllegalArgumentException("retentionDays must be >= 0: " + retentionDays);
        }
        if (retentionDays == 0) {
            return Optional.empty();
        }
        return Optional.of(now.minusSeconds(Math.multiplyExact((long) retentionDays, SECONDS_PER_DAY)));
    }
}

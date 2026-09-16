package com.smile.chunkland.config;

import java.util.Objects;

/**
 * Immutable typed view of {@code config.yml::audit}.
 *
 * <p>{@code retention-days} bounds how long audit history is kept
 * (default 180). {@code 0} means retain forever and no purge may run;
 * negative values are rejected at parse time so a bad edit fails closed
 * instead of silently wiping history.
 */
public record AuditSettings(int retentionDays) {

    /** Default history window in days when the section is absent. */
    public static final int DEFAULT_RETENTION_DAYS = 180;

    public AuditSettings {
        if (retentionDays < 0) {
            throw new IllegalArgumentException(
                    "retention-days must be >= 0 (0 means retain forever): " + retentionDays);
        }
    }

    public static AuditSettings defaults() {
        return new AuditSettings(DEFAULT_RETENTION_DAYS);
    }

    /** Whether purging is disabled entirely. */
    public boolean retainsForever() {
        return retentionDays == 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AuditSettings that)) {
            return false;
        }
        return retentionDays == that.retentionDays;
    }

    @Override
    public int hashCode() {
        return Objects.hash(retentionDays);
    }
}

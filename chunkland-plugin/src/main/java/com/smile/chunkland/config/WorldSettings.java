package com.smile.chunkland.config;

import java.util.Objects;

/**
 * Per-world, read-only settings parsed from {@code config.yml::worlds.<name>}.
 *
 * <p>Currently this is intentionally minimal: it carries the
 * {@code claim-enabled} flag required by 企劃書 §64. Subsequent M2 tasks
 * (limits, economy, vertical-mode, etc.) will extend this record; existing
 * fields will not be removed.</p>
 */
public record WorldSettings(boolean claimEnabled) {

    public WorldSettings {
        // No defensive copy is needed — the only field is a primitive boolean.
        // Kept canonical constructor so future record components automatically
        // pick up the same validation pass.
    }

    /** Default settings: claim-enabled = {@code true}. */
    public static WorldSettings defaults() {
        return new WorldSettings(true);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorldSettings other)) return false;
        return claimEnabled == other.claimEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(claimEnabled);
    }
}

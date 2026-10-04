package com.smile.chunkland.config;

import java.util.Objects;

/**
 * Per-world, read-only settings parsed from {@code config.yml::worlds.<name>}.
 *
 * <p>Carries the {@code claim-enabled} flag and the per-world
 * {@code vertical-mode}. {@code verticalMode} only affects
 * effective depth reads; switching it never migrates stored depths.
 * Existing fields are never removed.</p>
 */
public record WorldSettings(boolean claimEnabled, VerticalMode verticalMode) {

    public WorldSettings {
        Objects.requireNonNull(verticalMode, "verticalMode");
    }

    /** Backwards-compatible constructor: defaults to {@code PER_CHUNK_DEPTH}. */
    public WorldSettings(boolean claimEnabled) {
        this(claimEnabled, VerticalMode.defaultMode());
    }

    /** Default settings: claim-enabled = {@code true}, mode = {@code PER_CHUNK_DEPTH}. */
    public static WorldSettings defaults() {
        return new WorldSettings(true, VerticalMode.defaultMode());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorldSettings other)) return false;
        return claimEnabled == other.claimEnabled && verticalMode == other.verticalMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(claimEnabled, verticalMode);
    }
}

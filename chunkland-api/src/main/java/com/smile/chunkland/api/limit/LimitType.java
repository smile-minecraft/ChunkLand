package com.smile.chunkland.api.limit;

/**
 * Typed limit kinds controlled by {@code config.yml::limits} (spec §8).
 *
 * <p>Each type has a stable config key and a default value that must not be
 * changed without updating the typed limits configuration.</p>
 */
public enum LimitType {

    MAX_LANDS_PER_PLAYER("max-lands-per-player", 5),
    MAX_TOTAL_CHUNKS_PER_PLAYER("max-total-chunks-per-player", 256),
    MAX_CHUNKS_PER_LAND("max-chunks-per-land", 128),
    MAX_SUBLANDS_PER_LAND("max-sublands-per-land", 16),
    MAX_SELECTION_SIDE_LENGTH("max-selection-side-length", 32),
    MAX_SELECTION_CHUNKS("max-selection-chunks", 1024);

    private final String configKey;
    private final int defaultValue;

    LimitType(String configKey, int defaultValue) {
        this.configKey = configKey;
        this.defaultValue = defaultValue;
    }

    public String configKey() {
        return configKey;
    }

    public int defaultValue() {
        return defaultValue;
    }
}

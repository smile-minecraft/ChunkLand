package com.smile.chunkland.config;

import com.smile.chunkland.api.limit.LimitType;
import java.util.Objects;

/**
 * Immutable typed view of {@code config.yml::limits} (spec §8).
 *
 * <p>All values are explicit integers, non-negative, with fixed defaults:
 * {@code max-lands-per-player=5}, {@code max-total-chunks-per-player=256},
 * {@code max-chunks-per-land=128}, {@code max-sublands-per-land=16}.
 * Zero is allowed (means the operation is fully disabled for that kind).
 * Negative values are rejected. Values are stored as {@code int} to keep the
 * config contract explicit; callers needing a long should widen explicitly.
 * No double/float or sentinel is used.</p>
 */
public record LimitSettings(
        int maxLandsPerPlayer,
        int maxTotalChunksPerPlayer,
        int maxChunksPerLand,
        int maxSublandsPerLand) {

    public LimitSettings {
        if (maxLandsPerPlayer < 0) {
            throw new IllegalArgumentException("max-lands-per-player must be >= 0: " + maxLandsPerPlayer);
        }
        if (maxTotalChunksPerPlayer < 0) {
            throw new IllegalArgumentException("max-total-chunks-per-player must be >= 0: " + maxTotalChunksPerPlayer);
        }
        if (maxChunksPerLand < 0) {
            throw new IllegalArgumentException("max-chunks-per-land must be >= 0: " + maxChunksPerLand);
        }
        if (maxSublandsPerLand < 0) {
            throw new IllegalArgumentException("max-sublands-per-land must be >= 0: " + maxSublandsPerLand);
        }
    }

    public static LimitSettings defaults() {
        return new LimitSettings(
                LimitType.MAX_LANDS_PER_PLAYER.defaultValue(),
                LimitType.MAX_TOTAL_CHUNKS_PER_PLAYER.defaultValue(),
                LimitType.MAX_CHUNKS_PER_LAND.defaultValue(),
                LimitType.MAX_SUBLANDS_PER_LAND.defaultValue());
    }

    public int valueFor(LimitType type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case MAX_LANDS_PER_PLAYER -> maxLandsPerPlayer;
            case MAX_TOTAL_CHUNKS_PER_PLAYER -> maxTotalChunksPerPlayer;
            case MAX_CHUNKS_PER_LAND -> maxChunksPerLand;
            case MAX_SUBLANDS_PER_LAND -> maxSublandsPerLand;
        };
    }

    public long longValueFor(LimitType type) {
        return valueFor(type);
    }
}

package com.smile.chunkland.api.history;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable capture of one bounded world-history lookup.
 *
 * <p>The handler builds this record on the calling thread from already-read
 * Bukkit state (world identity plus block coordinates), so the background
 * optional lookup only ever sees plain data — never a live server object.
 * Radius, look-back window and result limit are clamped to the documented
 * maxima, so an optional backend can never run an unbounded query.
 */
public record HistoryQuery(UUID worldId,
                           String worldName,
                           int centerX,
                           int centerY,
                           int centerZ,
                           int radiusBlocks,
                           int secondsBack,
                           int maxResults) {

    /** Largest spatial radius a single history lookup may cover, in blocks. */
    public static final int MAX_RADIUS_BLOCKS = 16;

    /** Longest look-back window a single history lookup may cover, in seconds. */
    public static final int MAX_SECONDS_BACK = 7 * 24 * 60 * 60;

    /** Most entries a single history lookup may return. */
    public static final int MAX_RESULTS = 10;

    /** Default radius used when the caller supplies none, in blocks. */
    public static final int DEFAULT_RADIUS_BLOCKS = 8;

    /** Default look-back window used when the caller supplies none, in seconds. */
    public static final int DEFAULT_SECONDS_BACK = 24 * 60 * 60;

    public HistoryQuery {
        Objects.requireNonNull(worldId, "worldId");
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must be non-blank");
        }
        if (radiusBlocks < 1 || radiusBlocks > MAX_RADIUS_BLOCKS) {
            throw new IllegalArgumentException(
                    "radiusBlocks out of range: " + radiusBlocks);
        }
        if (secondsBack < 1 || secondsBack > MAX_SECONDS_BACK) {
            throw new IllegalArgumentException(
                    "secondsBack out of range: " + secondsBack);
        }
        if (maxResults < 1 || maxResults > MAX_RESULTS) {
            throw new IllegalArgumentException(
                    "maxResults out of range: " + maxResults);
        }
    }

    /**
     * Builds a query with every bound clamped into range. Null identity is
     * still rejected: clamping bounds quantities, never identity.
     */
    public static HistoryQuery bounded(UUID worldId,
                                       String worldName,
                                       int centerX,
                                       int centerY,
                                       int centerZ,
                                       int radiusBlocks,
                                       int secondsBack,
                                       int maxResults) {
        Objects.requireNonNull(worldId, "worldId");
        return new HistoryQuery(worldId, worldName,
                centerX, centerY, centerZ,
                clamp(radiusBlocks, MAX_RADIUS_BLOCKS),
                clamp(secondsBack, MAX_SECONDS_BACK),
                clamp(maxResults, MAX_RESULTS));
    }

    private static int clamp(int value, int max) {
        if (value < 1) {
            return 1;
        }
        return Math.min(value, max);
    }
}

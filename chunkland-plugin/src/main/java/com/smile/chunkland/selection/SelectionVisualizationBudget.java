package com.smile.chunkland.selection;

/**
 * Immutable budget that bounds selection particle visualization.
 *
 * <p>Every field is a hard cap enforced per render tick: geometry beyond
 * {@code maxSegments} is dropped (never queued), each tick emits at most
 * {@code maxParticlesPerTick} particles, segments outside
 * {@code renderDistanceBlocks} of the viewer are culled, and ticks are spaced
 * {@code refreshIntervalTicks} apart. The renderer reads the live budget on
 * every tick, so a config reload applies without restarting sessions.
 *
 * <p>These caps bound pure chunk-math work: planning never issues a Highest
 * Block lookup or any other terrain query to stay within budget, it simply
 * drops what does not fit (see {@link SelectionVisualizationGeometry}).
 */
public record SelectionVisualizationBudget(
        int maxSegments,
        int maxParticlesPerTick,
        int renderDistanceBlocks,
        int refreshIntervalTicks) {
    public static final int DEFAULT_MAX_SEGMENTS = 256;
    public static final int MAX_SEGMENTS_LIMIT = 4096;
    public static final int DEFAULT_MAX_PARTICLES_PER_TICK = 128;
    public static final int MAX_PARTICLES_PER_TICK_LIMIT = 1024;
    public static final int DEFAULT_RENDER_DISTANCE_BLOCKS = 48;
    public static final int RENDER_DISTANCE_BLOCKS_LIMIT = 128;
    public static final int MIN_RENDER_DISTANCE_BLOCKS = 8;
    public static final int DEFAULT_REFRESH_INTERVAL_TICKS = 10;
    public static final int REFRESH_INTERVAL_TICKS_LIMIT = 200;

    public SelectionVisualizationBudget {
        if (maxSegments <= 0 || maxSegments > MAX_SEGMENTS_LIMIT) {
            throw new IllegalArgumentException(
                    "maxSegments must be in [1, " + MAX_SEGMENTS_LIMIT + "]: " + maxSegments);
        }
        if (maxParticlesPerTick <= 0 || maxParticlesPerTick > MAX_PARTICLES_PER_TICK_LIMIT) {
            throw new IllegalArgumentException(
                    "maxParticlesPerTick must be in [1, " + MAX_PARTICLES_PER_TICK_LIMIT + "]: "
                            + maxParticlesPerTick);
        }
        if (renderDistanceBlocks < MIN_RENDER_DISTANCE_BLOCKS
                || renderDistanceBlocks > RENDER_DISTANCE_BLOCKS_LIMIT) {
            throw new IllegalArgumentException(
                    "renderDistanceBlocks must be in [" + MIN_RENDER_DISTANCE_BLOCKS + ", "
                            + RENDER_DISTANCE_BLOCKS_LIMIT + "]: " + renderDistanceBlocks);
        }
        if (refreshIntervalTicks <= 0 || refreshIntervalTicks > REFRESH_INTERVAL_TICKS_LIMIT) {
            throw new IllegalArgumentException(
                    "refreshIntervalTicks must be in [1, " + REFRESH_INTERVAL_TICKS_LIMIT + "]: "
                            + refreshIntervalTicks);
        }
    }

    public static SelectionVisualizationBudget defaults() {
        return new SelectionVisualizationBudget(
                DEFAULT_MAX_SEGMENTS,
                DEFAULT_MAX_PARTICLES_PER_TICK,
                DEFAULT_RENDER_DISTANCE_BLOCKS,
                DEFAULT_REFRESH_INTERVAL_TICKS);
    }
}

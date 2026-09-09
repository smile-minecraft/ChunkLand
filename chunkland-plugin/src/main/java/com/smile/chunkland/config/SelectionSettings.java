package com.smile.chunkland.config;

import com.smile.chunkland.selection.SelectionVisualizationBudget;
import java.time.Duration;

/** Immutable typed view of {@code config.yml::selection}. */
public record SelectionSettings(
        int sessionTimeoutSeconds,
        int visualizationMaxSegments,
        int visualizationMaxParticlesPerTick,
        int visualizationRenderDistanceBlocks,
        int visualizationRefreshIntervalTicks) {
    public static final int DEFAULT_SESSION_TIMEOUT_SECONDS = 600;

    public SelectionSettings {
        if (sessionTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("sessionTimeoutSeconds must be positive: " + sessionTimeoutSeconds);
        }
        if (visualizationMaxSegments <= 0
                || visualizationMaxSegments > SelectionVisualizationBudget.MAX_SEGMENTS_LIMIT) {
            throw new IllegalArgumentException("visualizationMaxSegments must be in [1, "
                    + SelectionVisualizationBudget.MAX_SEGMENTS_LIMIT + "]: " + visualizationMaxSegments);
        }
        if (visualizationMaxParticlesPerTick <= 0
                || visualizationMaxParticlesPerTick > SelectionVisualizationBudget.MAX_PARTICLES_PER_TICK_LIMIT) {
            throw new IllegalArgumentException("visualizationMaxParticlesPerTick must be in [1, "
                    + SelectionVisualizationBudget.MAX_PARTICLES_PER_TICK_LIMIT + "]: "
                    + visualizationMaxParticlesPerTick);
        }
        if (visualizationRenderDistanceBlocks < SelectionVisualizationBudget.MIN_RENDER_DISTANCE_BLOCKS
                || visualizationRenderDistanceBlocks > SelectionVisualizationBudget.RENDER_DISTANCE_BLOCKS_LIMIT) {
            throw new IllegalArgumentException("visualizationRenderDistanceBlocks must be in ["
                    + SelectionVisualizationBudget.MIN_RENDER_DISTANCE_BLOCKS + ", "
                    + SelectionVisualizationBudget.RENDER_DISTANCE_BLOCKS_LIMIT + "]: "
                    + visualizationRenderDistanceBlocks);
        }
        if (visualizationRefreshIntervalTicks <= 0
                || visualizationRefreshIntervalTicks > SelectionVisualizationBudget.REFRESH_INTERVAL_TICKS_LIMIT) {
            throw new IllegalArgumentException("visualizationRefreshIntervalTicks must be in [1, "
                    + SelectionVisualizationBudget.REFRESH_INTERVAL_TICKS_LIMIT + "]: "
                    + visualizationRefreshIntervalTicks);
        }
    }

    public static SelectionSettings defaults() {
        return new SelectionSettings(
                DEFAULT_SESSION_TIMEOUT_SECONDS,
                SelectionVisualizationBudget.DEFAULT_MAX_SEGMENTS,
                SelectionVisualizationBudget.DEFAULT_MAX_PARTICLES_PER_TICK,
                SelectionVisualizationBudget.DEFAULT_RENDER_DISTANCE_BLOCKS,
                SelectionVisualizationBudget.DEFAULT_REFRESH_INTERVAL_TICKS);
    }

    public Duration sessionTimeout() {
        try {
            return Duration.ofSeconds(sessionTimeoutSeconds);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("sessionTimeoutSeconds cannot be represented as a Duration", ex);
        }
    }

    /** Live budget view for the visualization renderer; re-read per tick so reloads apply. */
    public SelectionVisualizationBudget visualizationBudget() {
        return new SelectionVisualizationBudget(
                visualizationMaxSegments,
                visualizationMaxParticlesPerTick,
                visualizationRenderDistanceBlocks,
                visualizationRefreshIntervalTicks);
    }
}

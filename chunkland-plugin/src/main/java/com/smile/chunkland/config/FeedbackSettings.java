package com.smile.chunkland.config;

import java.time.Duration;

/**
 * Typed {@code feedback} section: what a denied player sees and how they are
 * moved back.
 *
 * <p>Everything here is cosmetic or comfort tuning. It never decides whether
 * an action is allowed, so a reload that changes it does not invalidate
 * selections or bump any protection state; the new values simply apply to
 * the next deny. Sizes are whole percentages because the config schema reads
 * integers only (100 means the vanilla dust size).
 *
 * @param entryWallEnabled whether the border is drawn on an entry deny
 * @param entryWallRadiusBlocks half-width of the traced border square
 * @param entryWallParticleSizePercent dust size of the border wall
 * @param entryWallPointsPerBlock particles per block along the border
 * @param entryWallCooldownMillis quiet time between two walls per player
 * @param actionMarkEnabled whether a denied block or entity is marked
 * @param actionMarkParticleSizePercent dust size of the mark
 * @param actionMarkCooldownMillis quiet time between two marks per player
 * @param pushOutDistanceBlocks blocks between the border and the landing
 * @param pushOutCooldownMillis quiet time between two push-outs per player
 */
public record FeedbackSettings(
        boolean entryWallEnabled,
        int entryWallRadiusBlocks,
        int entryWallParticleSizePercent,
        int entryWallPointsPerBlock,
        int entryWallCooldownMillis,
        boolean actionMarkEnabled,
        int actionMarkParticleSizePercent,
        int actionMarkCooldownMillis,
        int pushOutDistanceBlocks,
        int pushOutCooldownMillis) {

    public static final int MIN_WALL_RADIUS_BLOCKS = 2;
    public static final int MAX_WALL_RADIUS_BLOCKS = 16;
    public static final int MIN_PARTICLE_SIZE_PERCENT = 25;
    public static final int MAX_PARTICLE_SIZE_PERCENT = 400;
    public static final int MIN_WALL_POINTS_PER_BLOCK = 1;
    public static final int MAX_WALL_POINTS_PER_BLOCK = 4;
    public static final int MIN_COOLDOWN_MILLIS = 100;
    public static final int MAX_COOLDOWN_MILLIS = 60_000;
    public static final int MIN_PUSH_OUT_DISTANCE_BLOCKS = 1;
    public static final int MAX_PUSH_OUT_DISTANCE_BLOCKS = 8;

    public static final int DEFAULT_WALL_RADIUS_BLOCKS = 6;
    public static final int DEFAULT_WALL_PARTICLE_SIZE_PERCENT = 160;
    public static final int DEFAULT_WALL_POINTS_PER_BLOCK = 2;
    public static final int DEFAULT_WALL_COOLDOWN_MILLIS = 2_000;
    public static final int DEFAULT_MARK_PARTICLE_SIZE_PERCENT = 110;
    public static final int DEFAULT_MARK_COOLDOWN_MILLIS = 400;
    public static final int DEFAULT_PUSH_OUT_DISTANCE_BLOCKS = 3;
    public static final int DEFAULT_PUSH_OUT_COOLDOWN_MILLIS = 500;

    public FeedbackSettings {
        requireRange("entryWallRadiusBlocks", entryWallRadiusBlocks,
                MIN_WALL_RADIUS_BLOCKS, MAX_WALL_RADIUS_BLOCKS);
        requireRange("entryWallParticleSizePercent", entryWallParticleSizePercent,
                MIN_PARTICLE_SIZE_PERCENT, MAX_PARTICLE_SIZE_PERCENT);
        requireRange("entryWallPointsPerBlock", entryWallPointsPerBlock,
                MIN_WALL_POINTS_PER_BLOCK, MAX_WALL_POINTS_PER_BLOCK);
        requireRange("entryWallCooldownMillis", entryWallCooldownMillis,
                MIN_COOLDOWN_MILLIS, MAX_COOLDOWN_MILLIS);
        requireRange("actionMarkParticleSizePercent", actionMarkParticleSizePercent,
                MIN_PARTICLE_SIZE_PERCENT, MAX_PARTICLE_SIZE_PERCENT);
        requireRange("actionMarkCooldownMillis", actionMarkCooldownMillis,
                MIN_COOLDOWN_MILLIS, MAX_COOLDOWN_MILLIS);
        requireRange("pushOutDistanceBlocks", pushOutDistanceBlocks,
                MIN_PUSH_OUT_DISTANCE_BLOCKS, MAX_PUSH_OUT_DISTANCE_BLOCKS);
        requireRange("pushOutCooldownMillis", pushOutCooldownMillis,
                MIN_COOLDOWN_MILLIS, MAX_COOLDOWN_MILLIS);
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    name + " must be in [" + min + ", " + max + "]: " + value);
        }
    }

    public static FeedbackSettings defaults() {
        return new FeedbackSettings(
                true,
                DEFAULT_WALL_RADIUS_BLOCKS,
                DEFAULT_WALL_PARTICLE_SIZE_PERCENT,
                DEFAULT_WALL_POINTS_PER_BLOCK,
                DEFAULT_WALL_COOLDOWN_MILLIS,
                true,
                DEFAULT_MARK_PARTICLE_SIZE_PERCENT,
                DEFAULT_MARK_COOLDOWN_MILLIS,
                DEFAULT_PUSH_OUT_DISTANCE_BLOCKS,
                DEFAULT_PUSH_OUT_COOLDOWN_MILLIS);
    }

    /** Dust size of the border wall as the particle API expects it. */
    public float entryWallParticleSize() {
        return entryWallParticleSizePercent / 100.0F;
    }

    /** Dust size of the denied-target mark as the particle API expects it. */
    public float actionMarkParticleSize() {
        return actionMarkParticleSizePercent / 100.0F;
    }

    public Duration entryWallCooldown() {
        return Duration.ofMillis(entryWallCooldownMillis);
    }

    public Duration actionMarkCooldown() {
        return Duration.ofMillis(actionMarkCooldownMillis);
    }

    public Duration pushOutCooldown() {
        return Duration.ofMillis(pushOutCooldownMillis);
    }
}

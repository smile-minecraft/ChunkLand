package com.smile.chunkland.selection;

import java.util.Optional;
import java.util.UUID;

/**
 * Player-scoped particle send seam.
 *
 * <p>The production implementation sends through the operating player's own
 * {@code spawnParticle} overload, so only that player ever sees the
 * visualization — nothing is broadcast, and no terrain, entity, or world query
 * is involved. The viewer pose is the operating player's own location, read on
 * its scheduler thread for render-distance culling and the plane fallback.
 */
public interface SelectionParticleSink {
    /** Viewer position in block coordinates. */
    record ViewerPose(double x, double y, double z) {
    }

    /**
     * @return the operating player's current pose, or empty when the player is
     *         unavailable (the render loop then stops instead of guessing)
     */
    Optional<ViewerPose> viewerOf(UUID playerId);

    /** Send one particle to the operating player only. */
    void emit(UUID playerId, double x, double y, double z);

    /**
     * Send one occupied-preview particle with a semantic colour. The default
     * keeps existing test and integration sinks source-compatible; production
     * sinks map the semantic value to their particle colour.
     */
    default void emit(UUID playerId, double x, double y, double z, SelectionPreviewColor color) {
        emit(playerId, x, y, z);
    }
}

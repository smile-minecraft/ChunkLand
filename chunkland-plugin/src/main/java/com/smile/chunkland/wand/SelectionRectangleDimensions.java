package com.smile.chunkland.wand;

import com.smile.chunkland.api.land.ChunkKey;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure chunk-rectangle size for the selection guidance prompts.
 *
 * <p>The announced A×B size is derived only from the accepted selection's
 * min/max chunk coordinates: A is the chunk-X span, B the chunk-Z span. An
 * empty or missing chunk set has nothing to announce, so it yields empty
 * instead of a guessed size; no world, block, SQL, or network state is read.
 */
public record SelectionRectangleDimensions(int width, int height) {

    public SelectionRectangleDimensions {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("dimensions must be positive: " + width + "×" + height);
        }
    }

    /**
     * @return the min/max span of the accepted chunk set, or empty when the set
     *         holds no chunks (nothing has been accepted to announce).
     */
    public static Optional<SelectionRectangleDimensions> from(Set<ChunkKey> selectedChunks) {
        if (selectedChunks == null || selectedChunks.isEmpty()) {
            return Optional.empty();
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (ChunkKey chunk : selectedChunks) {
            minX = Math.min(minX, chunk.chunkX());
            maxX = Math.max(maxX, chunk.chunkX());
            minZ = Math.min(minZ, chunk.chunkZ());
            maxZ = Math.max(maxZ, chunk.chunkZ());
        }
        return Optional.of(new SelectionRectangleDimensions(maxX - minX + 1, maxZ - minZ + 1));
    }

    /** Message pipeline variables for the {@code <width>}/{@code <height>} placeholders. */
    public Map<String, Object> messageVars() {
        return Map.of("width", width, "height", height);
    }
}

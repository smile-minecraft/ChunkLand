package com.smile.chunkland.message.rejection;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a denied action happened, so the notice can name the land instead of
 * only saying "denied".
 *
 * <p>Coordinates only: the site never holds a world, block or entity handle,
 * so carrying it across the hop to the player thread touches nothing.
 *
 * @param worldId world the denied target sits in; never {@code null}
 * @param blockX block X of the denied target
 * @param blockY block Y of the denied target
 * @param blockZ block Z of the denied target
 */
public record RejectionSite(UUID worldId, int blockX, int blockY, int blockZ) {

    public RejectionSite {
        Objects.requireNonNull(worldId, "worldId");
    }

    /** Chunk X of the denied target. */
    public int chunkX() {
        return blockX >> 4;
    }

    /** Chunk Z of the denied target. */
    public int chunkZ() {
        return blockZ >> 4;
    }
}

package com.smile.chunkland.selection;

import java.util.Objects;
import java.util.UUID;

/** Immutable block position retained by a Selection Session. */
public record SelectionPoint(UUID worldId, int blockX, int blockY, int blockZ) {
    public SelectionPoint {
        Objects.requireNonNull(worldId, "worldId");
    }
}

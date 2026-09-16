package com.smile.chunkland.selection;

import java.util.UUID;

/**
 * Read-only per-chunk land lookup used by the wand occupancy guard.
 *
 * <p>Implementations answer from an already-built immutable LandRegistry
 * snapshot only. They must not load a World, a Chunk or a height map, and
 * must not touch SQL or the network; a missing world or a wilderness chunk
 * returns {@code null}. A failing lookup is expected to throw so the caller
 * can fail closed.
 */
@FunctionalInterface
public interface SelectionLandLookup {

    /** Return the land occupying this chunk, or {@code null} for wilderness. */
    SelectionLandContext landAt(UUID worldId, int chunkX, int chunkZ);

    /** Lookup that reports every chunk as wilderness; used when no registry is wired. */
    static SelectionLandLookup none() {
        return (worldId, chunkX, chunkZ) -> null;
    }
}

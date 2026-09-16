package com.smile.chunkland.selection;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only lookup of a Land's full immutable chunk set, used to preview an
 * occupied land's real boundary.
 *
 * <p>Implementations read an already-built {@code LandRegistry} snapshot only;
 * they never load a World, Chunk or height map and never touch SQL or the
 * network. An empty result means the land is unknown (or the registry is not
 * hydrated), which the caller treats as "no preview" rather than wilderness.
 */
@FunctionalInterface
public interface SelectionLandBoundaryLookup {

    /** The immutable chunk set of {@code landId}, or empty when it cannot be resolved. */
    Optional<Set<ChunkKey>> chunksOf(LandId landId);

    static SelectionLandBoundaryLookup none() {
        return landId -> Optional.empty();
    }
}

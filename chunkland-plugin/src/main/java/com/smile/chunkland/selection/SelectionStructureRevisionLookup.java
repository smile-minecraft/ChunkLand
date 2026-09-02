package com.smile.chunkland.selection;

import com.smile.chunkland.api.land.LandId;
import java.util.OptionalLong;

/** Optional read seam for checking a target Land before accepting an update. */
@FunctionalInterface
public interface SelectionStructureRevisionLookup {
    OptionalLong currentRevision(LandId landId);

    static SelectionStructureRevisionLookup unavailable() {
        return landId -> OptionalLong.empty();
    }
}

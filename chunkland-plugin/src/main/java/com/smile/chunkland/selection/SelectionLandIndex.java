package com.smile.chunkland.selection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.runtime.index.WorldChunkIndex;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only packed-coordinate lookup used by selection collision checks.
 *
 * <p>The interface is deliberately smaller than the runtime index and is also a
 * verification seam: collision checks need an already-built index, never a Bukkit world.</p>
 */
@FunctionalInterface
public interface SelectionLandIndex {
    /** Return the Land at {@code packed}, or {@code null} for Wilderness. */
    LandId landIdAtPacked(UUID worldId, long packed);

    /** Adapt the immutable runtime index without retaining any world object. */
    static SelectionLandIndex from(WorldChunkIndex index) {
        Objects.requireNonNull(index, "index");
        return (worldId, packed) -> {
            Objects.requireNonNull(worldId, "worldId");
            if (!index.worldId().equals(worldId)) {
                throw new IllegalArgumentException("selection lookup world does not match the index");
            }
            return index.landIdAtPacked(packed);
        };
    }
}

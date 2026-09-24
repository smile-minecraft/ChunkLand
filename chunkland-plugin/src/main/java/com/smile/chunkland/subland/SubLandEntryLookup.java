package com.smile.chunkland.subland;

import com.smile.chunkland.api.land.LandSnapshot;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only lookup used by the SubLand command entry point.
 *
 * <p>The adapter answers from the published runtime snapshot only. It keeps
 * Bukkit location objects out of the SubLand domain and makes an unavailable
 * registry fail closed.
 */
@FunctionalInterface
public interface SubLandEntryLookup {

    /** Return the land containing the supplied world and block position. */
    Optional<LandSnapshot> landAt(UUID worldId, int blockX, int blockZ);

    /** Lookup used when the live registry is not wired. */
    static SubLandEntryLookup unavailable() {
        return (worldId, blockX, blockZ) -> Optional.empty();
    }
}

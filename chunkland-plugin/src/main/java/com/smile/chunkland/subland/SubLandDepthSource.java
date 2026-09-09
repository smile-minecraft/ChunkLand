package com.smile.chunkland.subland;

import com.smile.chunkland.api.land.LandSnapshot;

/**
 * Bukkit-free read seam for the parent's effective protection floor.
 *
 * <p>Callers supply the effective minimum protected Y (the runtime
 * interpretation of the persisted depths, never persisted itself) so the
 * domain service never loads a world, a chunk, or a row on its path.
 * Production resolves it from the registry's stored depths plus the vertical
 * mode; tests inject a constant.
 */
@FunctionalInterface
public interface SubLandDepthSource {

    /**
     * Effective minimum protected Y for the given parent snapshot.
     *
     * @param parent already-loaded immutable parent snapshot
     * @return the floor a SubLand minimum Y must stay at or above without confirmation
     */
    int effectiveMinProtectedY(LandSnapshot parent);

    /** Constant seam for tests and single-floor parents. */
    static SubLandDepthSource constant(int effectiveMinY) {
        return parent -> effectiveMinY;
    }
}

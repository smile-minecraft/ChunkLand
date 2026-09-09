package com.smile.chunkland.subland;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;

/**
 * Explicit operator confirmation seam for SubLand depths that would extend
 * below the parent's effective protection floor.
 *
 * <p>Bukkit-free and side-effect free: implementations only record whether the
 * operator explicitly confirmed the depth extension (for example through a
 * follow-up chat prompt). The domain service fails closed with
 * {@link DepthExtendConfirmationRequired} when the candidate's minimum Y is
 * below the effective floor and this port does not confirm. The durable CAS
 * queue itself lives in the M3-02 follow-up and is deliberately not built
 * here.
 */
public interface DepthExtensionPort {

    /**
     * Whether the operator explicitly confirmed extending below the effective floor.
     *
     * @param parentId owning land
     * @param effectiveMinY parent effective minimum protected Y
     * @param requestedMinY candidate cuboid minimum Y (below the floor)
     * @return {@code true} only when an explicit confirmation exists
     */
    boolean isDepthExtendConfirmed(LandId parentId, int effectiveMinY, int requestedMinY);

    /** Deny-everything seam for tests and unwired production paths (fail-closed). */
    static DepthExtensionPort denyAll() {
        return (parentId, effectiveMinY, requestedMinY) -> false;
    }

    /** Allow-everything seam for tests where confirmation was already given. */
    static DepthExtensionPort allowAll() {
        return (parentId, effectiveMinY, requestedMinY) -> {
            Objects.requireNonNull(parentId, "parentId");
            return true;
        };
    }
}

package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.UUID;

/**
 * Builds the cache identity for one decision from immutable views only.
 *
 * <p>The returned key must name the actor, land, covering subland, action
 * and all four epochs plus the structure revision and owner context, all
 * read from the same already-published snapshots the decision itself will
 * use. A {@code null} return means the decision is not cacheable right now
 * (unloaded, unknown or failed sources): the caller then decides without
 * touching the cache and stores nothing.
 *
 * <p>Memory-only like every other hot-path source: no storage, no chunk
 * load, no blocking.
 */
@FunctionalInterface
public interface PermissionDecisionEpochSource {
    PermissionDecisionCache.Key keyFor(UUID actor, LandId landId, SubLandId sublandId,
                                       ProtectionActionType action, LandRegistry snapshot);
}

package com.smile.chunkland.runtime.api;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.UUID;

/**
 * Provides an immutable {@link PermissionContext} for a single permission
 * decision. The implementation reads only from the given already-published
 * immutable {@link LandRegistry} snapshot and must never perform I/O, load a
 * chunk, or touch Bukkit/SQL. The snapshot is the exact same instance the
 * caller observed for land existence, so mixed-version reads are impossible.
 */
@FunctionalInterface
public interface PermissionContextProvider {
    PermissionContext provide(UUID actor, LandId landId, ProtectionActionType action, LandRegistry snapshot);
}

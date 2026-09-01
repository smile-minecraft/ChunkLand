package com.smile.chunkland.runtime.storage;

import java.util.Set;
import java.util.UUID;

/**
 * Injectable world existence check. Backed by the server's world registry in
 * production; faked in tests without touching Bukkit.
 */
public interface WorldCatalog {

    boolean exists(UUID worldId);

    Set<UUID> knownWorlds();

    static WorldCatalog of(Set<UUID> known) {
        Set<UUID> copy = Set.copyOf(known);
        return new WorldCatalog() {
            @Override
            public boolean exists(UUID worldId) {
                return copy.contains(worldId);
            }

            @Override
            public Set<UUID> knownWorlds() {
                return copy;
            }
        };
    }
}

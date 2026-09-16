package com.smile.chunkland.runtime.storage;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable point-in-time view of the server world catalog for orphan
 * decisions.
 *
 * <p>Generation zero means no verified catalog exists yet: the guard has
 * never published, or it was invalidated after a failed read. Such a
 * snapshot must fail every orphan verb closed — it must never be read as
 * "no worlds loaded". Each {@link OrphanWorldGuard#publish} assigns the next
 * generation, so a purge can bind the generation it confirmed with and abort
 * when the catalog moved underneath it.
 */
public record WorldCatalogSnapshot(long generation, Set<UUID> loadedWorlds) {

    public WorldCatalogSnapshot {
        Objects.requireNonNull(loadedWorlds, "loadedWorlds");
        loadedWorlds = Set.copyOf(loadedWorlds);
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
    }

    /** Whether this snapshot carries a verified catalog. */
    public boolean isVerified() {
        return generation > 0;
    }

    /** Whether the world is currently loaded (and therefore not an orphan). */
    public boolean isLoaded(UUID worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return loadedWorlds.contains(worldId);
    }
}

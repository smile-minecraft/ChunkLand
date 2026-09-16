package com.smile.chunkland;

import com.smile.chunkland.runtime.storage.OrphanWorldGuard;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/**
 * Keeps the {@link OrphanWorldGuard} on the live server world catalog.
 *
 * <p>World load/unload only republishes the guard from a freshly copied
 * world list. Any unreadable catalog — a throwing or null supplier, a null
 * or empty list, a null entry, or an unreadable world UUID — invalidates the
 * guard instead, so every orphan verb fails closed rather than mistaking a
 * healthy world for an orphan. Only the guard (memory) is touched here: no
 * SQL, no chunk load, no network. This listener never throws onto the server
 * thread; a failed refresh degrades to invalidation.
 */
public final class OrphanWorldCatalogListener implements Listener {

    private final OrphanWorldGuard guard;
    private final Supplier<List<World>> worlds;

    /**
     * @param guard catalog guard to keep current; never null
     * @param worlds live world list source, read on the event thread; a
     *               {@code null} or failing source invalidates on every event
     */
    public OrphanWorldCatalogListener(OrphanWorldGuard guard, Supplier<List<World>> worlds) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.worlds = worlds;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        refresh();
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        refresh();
    }

    /** Re-reads the catalog; any failure invalidates the guard. Never throws. */
    void refresh() {
        Set<UUID> ids;
        try {
            ids = worlds == null ? null : ChunkLandPlugin.snapshotLoadedWorldIds(worlds);
        } catch (RuntimeException failure) {
            ids = null;
        }
        if (ids == null) {
            guard.invalidate();
        } else {
            guard.publish(ids);
        }
    }

    /**
     * Copies one verified world-UUID set, or {@code null} when the catalog
     * cannot be verified. A partial snapshot is never returned: a single
     * null entry or unreadable world fails the whole copy, because treating
     * it as merely absent would misclassify that healthy world as an orphan.
     * An empty result is likewise unverifiable — a command-running server
     * with zero worlds is not a state a purge may rely on.
     */
    static Set<UUID> copyWorldIds(List<World> worlds) {
        if (worlds == null || worlds.isEmpty()) {
            return null;
        }
        Set<UUID> ids = new HashSet<>();
        for (World world : worlds) {
            if (world == null) {
                return null;
            }
            final UUID uid;
            try {
                uid = world.getUID();
            } catch (RuntimeException unreadable) {
                return null;
            }
            if (uid == null) {
                return null;
            }
            ids.add(uid);
        }
        return ids.isEmpty() ? null : Set.copyOf(ids);
    }
}

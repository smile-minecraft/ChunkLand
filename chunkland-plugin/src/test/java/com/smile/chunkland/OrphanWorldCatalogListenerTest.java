package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.runtime.storage.OrphanWorldGuard;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.junit.jupiter.api.Test;

/**
 * Red contract for the orphan world-catalog listener.
 *
 * <p>World load/unload only republishes the guard from a freshly copied
 * world list. Any unreadable catalog — throwing supplier, null list, empty
 * list, null entry or unreadable world UUID — invalidates the guard so every
 * orphan verb fails closed instead of mistaking a healthy world for an
 * orphan. The listener itself never throws onto the server thread.
 */
class OrphanWorldCatalogListenerTest {

    private static World proxyWorld(UUID uid) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) {
                        return uid;
                    }
                    if (method.getName().equals("getName")) {
                        return "world-" + uid.toString().substring(0, 8);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("toString")) {
                        return "World-proxy";
                    }
                    return null;
                });
    }

    private static World unreadableWorld() {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) {
                        throw new RuntimeException("world handle retired");
                    }
                    if (method.getName().equals("getName")) {
                        return "broken";
                    }
                    return null;
                });
    }

    @Test
    void loadAndUnloadRepublishTheGuard() {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<World> worlds = new ArrayList<>(List.of(proxyWorld(first), proxyWorld(second)));
        OrphanWorldCatalogListener listener = new OrphanWorldCatalogListener(guard, () -> List.copyOf(worlds));

        listener.onWorldLoad(new WorldLoadEvent(proxyWorld(second)));
        assertEquals(1, guard.snapshot().generation());
        assertEquals(Set.of(first, second), guard.snapshot().loadedWorlds());

        worlds.remove(1);
        listener.onWorldUnload(new WorldUnloadEvent(proxyWorld(second)));
        assertEquals(2, guard.snapshot().generation());
        assertEquals(Set.of(first), guard.snapshot().loadedWorlds());
    }

    @Test
    void throwingSupplierInvalidatesInsteadOfThrowing() {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        guard.publish(Set.of(UUID.randomUUID()));
        assertTrue(guard.snapshot().isVerified());
        OrphanWorldCatalogListener listener = new OrphanWorldCatalogListener(guard, () -> {
            throw new RuntimeException("server worlds unreadable");
        });
        listener.onWorldLoad(new WorldLoadEvent(proxyWorld(UUID.randomUUID())));
        assertFalse(guard.snapshot().isVerified());
    }

    @Test
    void nullSupplierInvalidatesInsteadOfThrowing() {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        guard.publish(Set.of(UUID.randomUUID()));
        OrphanWorldCatalogListener listener = new OrphanWorldCatalogListener(guard, null);
        listener.onWorldUnload(new WorldUnloadEvent(proxyWorld(UUID.randomUUID())));
        assertFalse(guard.snapshot().isVerified());
    }

    @Test
    void emptyListInvalidatesRatherThanDeclaringEveryWorldOrphan() {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        guard.publish(Set.of(UUID.randomUUID()));
        OrphanWorldCatalogListener listener =
                new OrphanWorldCatalogListener(guard, List::of);
        listener.onWorldLoad(new WorldLoadEvent(proxyWorld(UUID.randomUUID())));
        assertFalse(guard.snapshot().isVerified());
    }

    @Test
    void nullEntryInvalidatesTheWholeSnapshot() {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        guard.publish(Set.of(UUID.randomUUID()));
        List<World> worlds = new ArrayList<>();
        worlds.add(null);
        OrphanWorldCatalogListener listener =
                new OrphanWorldCatalogListener(guard, () -> worlds);
        listener.onWorldUnload(new WorldUnloadEvent(proxyWorld(UUID.randomUUID())));
        assertFalse(guard.snapshot().isVerified());
    }

    @Test
    void unreadableWorldUuidInvalidatesTheWholeSnapshot() {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        UUID healthy = UUID.randomUUID();
        guard.publish(Set.of(healthy));
        OrphanWorldCatalogListener listener = new OrphanWorldCatalogListener(guard,
                () -> List.of(proxyWorld(healthy), unreadableWorld()));
        listener.onWorldLoad(new WorldLoadEvent(unreadableWorld()));
        assertFalse(guard.snapshot().isVerified());
    }

    @Test
    void copyWorldIdsRejectsEveryUnsafeShape() {
        UUID uid = UUID.randomUUID();
        assertEquals(Set.of(uid),
                OrphanWorldCatalogListener.copyWorldIds(List.of(proxyWorld(uid))));
        assertNull(OrphanWorldCatalogListener.copyWorldIds(null));
        assertNull(OrphanWorldCatalogListener.copyWorldIds(List.of()));
        List<World> withNull = new ArrayList<>();
        withNull.add(null);
        assertNull(OrphanWorldCatalogListener.copyWorldIds(withNull));
        assertNull(OrphanWorldCatalogListener.copyWorldIds(
                List.of(proxyWorld(uid), unreadableWorld())));
    }
}

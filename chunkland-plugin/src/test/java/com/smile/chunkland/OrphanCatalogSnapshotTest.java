package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/**
 * Red contract for the enable-time world-catalog snapshot.
 *
 * <p>A catalog read that fails in any way — throwing supplier, null list,
 * empty list, null entry or an unreadable world UUID — must come back
 * {@code null} so the orphan guard stays unverified and every orphan verb
 * fails closed. It must never come back as an empty set that would
 * misclassify a healthy world as an orphan.
 */
class OrphanCatalogSnapshotTest {

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
                    return null;
                });
    }

    @Test
    void healthyCatalogSnapshotsToItsUuids() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Set<UUID> ids = ChunkLandPlugin.snapshotLoadedWorldIds(
                () -> List.of(proxyWorld(first), proxyWorld(second)));
        assertEquals(Set.of(first, second), ids);
    }

    @Test
    void throwingSupplierSnapshotsToNull() {
        assertNull(ChunkLandPlugin.snapshotLoadedWorldIds(() -> {
            throw new RuntimeException("getWorlds failed");
        }));
    }

    @Test
    void nullSupplierSnapshotsToNull() {
        assertNull(ChunkLandPlugin.snapshotLoadedWorldIds(null));
    }

    @Test
    void nullListSnapshotsToNull() {
        assertNull(ChunkLandPlugin.snapshotLoadedWorldIds(() -> null));
    }

    @Test
    void emptyListSnapshotsToNullRatherThanEmptySuccess() {
        assertNull(ChunkLandPlugin.snapshotLoadedWorldIds(List::of));
    }

    @Test
    void nullEntrySnapshotsToNull() {
        List<World> worlds = new ArrayList<>();
        worlds.add(null);
        assertNull(ChunkLandPlugin.snapshotLoadedWorldIds(() -> worlds));
    }

    @Test
    void unreadableWorldUuidSnapshotsToNull() {
        UUID healthy = UUID.randomUUID();
        World broken = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) {
                        throw new RuntimeException("world handle retired");
                    }
                    return null;
                });
        assertNull(ChunkLandPlugin.snapshotLoadedWorldIds(
                () -> List.of(proxyWorld(healthy), broken)));
    }

    @Test
    void orphanHandlerBuilderNullMatrixStaysFailClosed() {
        assertTrue(ChunkLandPlugin.buildOrphanAdminHandler(null, null, null) == null);
    }
}

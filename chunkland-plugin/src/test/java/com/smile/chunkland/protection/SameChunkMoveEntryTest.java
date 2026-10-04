package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.Test;

/**
 * Same X/Z chunk movement must still meet the ENTRY rule when it crosses a
 * subland boundary. The old chunk-equality short-circuit skipped every
 * in-chunk move, so walking into a block-precise subland (or moving
 * vertically across its height range) never consulted the engine.
 */
class SameChunkMoveEntryTest {

    private static World worldProxy(UUID worldId) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[] {World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUID":
                            return worldId;
                        case "getName":
                            return "world";
                        case "isChunkLoaded":
                            return true;
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "FakeWorld";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                return false;
                            }
                            if (rt == int.class) {
                                return 0;
                            }
                            if (rt == long.class) {
                                return 0L;
                            }
                            return null;
                    }
                });
    }

    private static Player playerProxy(UUID id, World world,
            AtomicReference<Location> teleportedTo) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[] {Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return id;
                        case "getWorld":
                            return world;
                        case "teleport":
                            if (teleportedTo != null && args.length == 1
                                    && args[0] instanceof Location loc) {
                                teleportedTo.set(loc);
                            }
                            return true;
                        case "getName":
                            return "TestPlayer";
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "FakePlayer";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                return false;
                            }
                            if (rt == int.class) {
                                return 0;
                            }
                            if (rt == double.class) {
                                return 0d;
                            }
                            if (rt == float.class) {
                                return 0f;
                            }
                            return null;
                    }
                });
    }

    private static LandSnapshot landWithCuboidSub(UUID worldId, LandId landId, UUID owner,
            SubLandId subId, Cuboid cuboid) {
        SubLandSnapshot sub = new SubLandSnapshot(subId, landId, "den", cuboid, worldId);
        return new LandSnapshot(landId, "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                List.of(sub), 0, 0, Instant.now(), Instant.now());
    }

    private static LandSnapshot landWithHeightSub(UUID worldId, LandId landId, UUID owner,
            SubLandId subId, int minY, int maxY) {
        SubLandSnapshot sub = new SubLandSnapshot(subId, landId, "cellar", minY, maxY,
                Set.of(new ChunkKey(worldId, 0, 0)));
        return new LandSnapshot(landId, "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                List.of(sub), 0, 0, Instant.now(), Instant.now());
    }

    private static ProtectionEngine entryEngine(LandRegistryStore store, PermissionState entry) {
        return new ProtectionEngine(store::snapshot, (actor, landId, action, snapshot) -> {
            if (action != ProtectionActionType.ENTRY) {
                return new PermissionContext(action, false, List.of(),
                        PermissionState.INHERIT, PermissionState.INHERIT);
            }
            return new PermissionContext(action, false, List.of(),
                    entry, PermissionState.INHERIT);
        });
    }

    @Test
    void intraChunkHorizontalMoveIntoDenyingSublandCancels() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landWithCuboidSub(worldId,
                new LandId(UUID.randomUUID()), UUID.randomUUID(),
                new SubLandId(UUID.randomUUID()), new Cuboid(0, 0, 0, 7, 255, 15)))));
        World world = worldProxy(worldId);
        ProtectionListener listener =
                new ProtectionListener(entryEngine(store, PermissionState.DENY));
        Player stranger = playerProxy(UUID.randomUUID(), world, new AtomicReference<>());

        // x=10 sits outside the cuboid (maxX=7), x=5 sits inside; same chunk (0,0).
        PlayerMoveEvent event = new PlayerMoveEvent(stranger,
                new Location(world, 10, 64, 5), new Location(world, 5, 64, 5));
        listener.onPlayerMove(event);

        assertTrue(event.isCancelled(),
                "walking inside one chunk into a denying subland must cancel");
    }

    @Test
    void verticalMoveAcrossSublandHeightBoundaryCancels() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landWithHeightSub(worldId,
                new LandId(UUID.randomUUID()), UUID.randomUUID(),
                new SubLandId(UUID.randomUUID()), 0, 50))));
        World world = worldProxy(worldId);
        ProtectionListener listener =
                new ProtectionListener(entryEngine(store, PermissionState.DENY));
        Player stranger = playerProxy(UUID.randomUUID(), world, new AtomicReference<>());

        // Same X/Z block: y=64 is above the subland (maxY=50), y=40 is inside.
        PlayerMoveEvent event = new PlayerMoveEvent(stranger,
                new Location(world, 5, 64, 5), new Location(world, 5, 40, 5));
        listener.onPlayerMove(event);

        assertTrue(event.isCancelled(),
                "dropping across a subland height boundary must cancel even without leaving the chunk");
    }

    @Test
    void moveInsideSameCoveringIsNotBlocked() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landWithCuboidSub(worldId,
                new LandId(UUID.randomUUID()), UUID.randomUUID(),
                new SubLandId(UUID.randomUUID()), new Cuboid(0, 0, 0, 7, 255, 15)))));
        World world = worldProxy(worldId);
        ProtectionListener listener =
                new ProtectionListener(entryEngine(store, PermissionState.DENY));

        Player outside = playerProxy(UUID.randomUUID(), world, new AtomicReference<>());
        PlayerMoveEvent outsideMove = new PlayerMoveEvent(outside,
                new Location(world, 10, 64, 5), new Location(world, 12, 64, 5));
        listener.onPlayerMove(outsideMove);
        assertFalse(outsideMove.isCancelled(),
                "moving outside every subland must keep the cheap skip even when ENTRY denies");

        Player inside = playerProxy(UUID.randomUUID(), world, new AtomicReference<>());
        PlayerMoveEvent insideMove = new PlayerMoveEvent(inside,
                new Location(world, 2, 64, 5), new Location(world, 3, 64, 5));
        listener.onPlayerMove(insideMove);
        assertFalse(insideMove.isCancelled(),
                "moving inside one subland must not re-check ENTRY on every step");

        Player looking = playerProxy(UUID.randomUUID(), world, new AtomicReference<>());
        PlayerMoveEvent lookAround = new PlayerMoveEvent(looking,
                new Location(world, 5, 64, 5), new Location(world, 5.3, 64.2, 5.7));
        listener.onPlayerMove(lookAround);
        assertFalse(lookAround.isCancelled(),
                "looking around inside one block must never consult ENTRY");
    }

    @Test
    void crossingMoveWithAllowedEntryPasses() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landWithCuboidSub(worldId,
                new LandId(UUID.randomUUID()), UUID.randomUUID(),
                new SubLandId(UUID.randomUUID()), new Cuboid(0, 0, 0, 7, 255, 15)))));
        World world = worldProxy(worldId);
        ProtectionListener listener =
                new ProtectionListener(entryEngine(store, PermissionState.ALLOW));
        Player guest = playerProxy(UUID.randomUUID(), world, new AtomicReference<>());

        PlayerMoveEvent event = new PlayerMoveEvent(guest,
                new Location(world, 10, 64, 5), new Location(world, 5, 64, 5));
        listener.onPlayerMove(event);

        assertFalse(event.isCancelled(), "an allowed ENTRY crossing must pass");
    }

    @Test
    void wildernessSameChunkMovePasses() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.empty());
        World world = worldProxy(worldId);
        ProtectionListener listener =
                new ProtectionListener(entryEngine(store, PermissionState.DENY));
        Player wanderer = playerProxy(UUID.randomUUID(), world, new AtomicReference<>());

        PlayerMoveEvent event = new PlayerMoveEvent(wanderer,
                new Location(world, 900, 64, 900), new Location(world, 902, 64, 900));
        listener.onPlayerMove(event);

        assertFalse(event.isCancelled(), "wilderness movement follows vanilla: never cancel");
    }

    /**
     * Hot-path budget for movement handling: in-process throughput over the
     * three in-chunk shapes (look-around skip, same-covering skip, crossing
     * ENTRY check). Prints mean ns/event for the pre/post comparison; the
     * ceiling is deliberately generous (proxy mocks dominate) so this guards
     * against pathological regressions such as I/O on the move path, not
     * against small constant shifts. No chunk loads, SQL, or blocking I/O
     * happen on any of these paths by construction (snapshot reads only).
     */
    @Test
    void sameChunkMoveStaysWithinHotPathBudget() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landWithCuboidSub(worldId,
                new LandId(UUID.randomUUID()), UUID.randomUUID(),
                new SubLandId(UUID.randomUUID()), new Cuboid(0, 0, 0, 7, 255, 15)))));
        World world = worldProxy(worldId);
        ProtectionListener listener =
                new ProtectionListener(entryEngine(store, PermissionState.ALLOW));
        Player player = playerProxy(UUID.randomUUID(), world, new AtomicReference<>());

        Location lookFrom = new Location(world, 5, 64, 5);
        Location lookTo = new Location(world, 5.3, 64.2, 5.7);
        Location skipFrom = new Location(world, 10, 64, 5);
        Location skipTo = new Location(world, 12, 64, 5);
        Location crossFrom = new Location(world, 10, 64, 5);
        Location crossTo = new Location(world, 5, 64, 5);

        double lookNs = meanNs(listener, player, lookFrom, lookTo);
        double skipNs = meanNs(listener, player, skipFrom, skipTo);
        double crossNs = meanNs(listener, player, crossFrom, crossTo);
        System.out.printf("move-hot-path ns/event: look=%.0f skip=%.0f cross=%.0f%n",
                lookNs, skipNs, crossNs);

        assertTrue(lookNs < 100_000, "look-around path must stay far below 100us/event");
        assertTrue(skipNs < 100_000, "same-covering skip must stay far below 100us/event");
        assertTrue(crossNs < 100_000, "crossing ENTRY check must stay far below 100us/event");
    }

    private static double meanNs(ProtectionListener listener, Player player,
            Location from, Location to) {
        int warmup = 5_000;
        int measured = 20_000;
        for (int i = 0; i < warmup; i++) {
            listener.onPlayerMove(new PlayerMoveEvent(player, from, to));
        }
        long start = System.nanoTime();
        for (int i = 0; i < measured; i++) {
            listener.onPlayerMove(new PlayerMoveEvent(player, from, to));
        }
        return (double) (System.nanoTime() - start) / measured;
    }
}

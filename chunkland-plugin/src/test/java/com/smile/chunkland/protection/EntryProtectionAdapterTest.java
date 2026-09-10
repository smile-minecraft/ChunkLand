package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.selection.SelectionClock;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

/**
 * Entry/transit hardening: every teleport vector checks destination ENTRY,
 * entry denies push out under a throttle without loading chunks, and players
 * banned while already inside are stopped on their next move or teleport.
 */
class EntryProtectionAdapterTest {

    private static final Instant T0 = Instant.parse("2026-09-03T00:00:00Z");

    private static World worldProxy(UUID worldId, Set<Long> loaded, Location spawn) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUID": return worldId;
                        case "getName": return "world";
                        case "isChunkLoaded":
                            int x = (int) args[0];
                            int z = (int) args[1];
                            return loaded.contains(chunkKey(x, z));
                        case "getSpawnLocation": return spawn;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeWorld";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == long.class) return 0L;
                            return null;
                    }
                });
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static Player playerProxy(UUID id, World world,
                                       AtomicReference<Location> teleportedTo) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "getWorld": return world;
                        case "teleport":
                            if (teleportedTo != null && args.length == 1 && args[0] instanceof Location loc) {
                                teleportedTo.set(loc);
                            }
                            return true;
                        case "getName": return "TestPlayer";
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakePlayer";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == double.class) return 0d;
                            if (rt == float.class) return 0f;
                            return null;
                    }
                });
    }

    private static LandSnapshot landAt(UUID worldId, LandId id, UUID owner, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private static ProtectionEngine denyEntryEngine(LandRegistryStore store) {
        return new ProtectionEngine(store::snapshot, (actor, landId, action, snapshot) -> {
            if (action != ProtectionActionType.ENTRY) {
                return new PermissionContext(action, false, List.of(),
                        PermissionState.INHERIT, PermissionState.INHERIT);
            }
            return new PermissionContext(action, false, List.of(),
                    PermissionState.DENY, PermissionState.INHERIT);
        });
    }

    private static EntryProtectionAdapter adapterWith(SelectionClock clock,
                                                      EntryProtectionAdapter.BanLookup bans,
                                                      Set<Long> loaded,
                                                      AtomicReference<Location> teleportedTo,
                                                      AtomicInteger pushOuts) {
        return adapterWith(clock, bans, loaded, teleportedTo, pushOuts, (playerId, at) -> true);
    }

    private static EntryProtectionAdapter adapterWith(SelectionClock clock,
                                                      EntryProtectionAdapter.BanLookup bans,
                                                      Set<Long> loaded,
                                                      AtomicReference<Location> teleportedTo,
                                                      AtomicInteger pushOuts,
                                                      EntryProtectionAdapter.EntryAllowedCheck entryCheck) {
        EntryProtectionAdapter.ChunkLoadedCheck chunks =
                (world, x, z) -> loaded.contains(chunkKey(x, z));
        return new EntryProtectionAdapter(clock, Duration.ofSeconds(3), bans, chunks, entryCheck,
                (player, target) -> {
                    pushOuts.incrementAndGet();
                    teleportedTo.set(target);
                });
    }

    private static EntryProtectionAdapter.EntryAllowedCheck engineEntryCheck(ProtectionEngine engine) {
        return (playerId, at) -> {
            try {
                if (playerId == null || at == null || at.getWorld() == null) {
                    return false;
                }
                return engine.decideAt(playerId, at.getWorld().getUID(),
                        at.getBlockX() >> 4, at.getBlockZ() >> 4,
                        EntryProtectionAdapter.entryAction()).outcome() != PermissionState.DENY;
            } catch (RuntimeException ex) {
                return false;
            }
        };
    }

    @Test
    void everyTeleportCauseRequiresDestinationEntryCheck() {
        var covered = List.of(
                PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
                PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT,
                PlayerTeleportEvent.TeleportCause.PLUGIN,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
                PlayerTeleportEvent.TeleportCause.END_PORTAL);
        for (var cause : covered) {
            assertTrue(EntryProtectionAdapter.requiresDestinationCheck(cause),
                    cause + " must check destination ENTRY, never bypass");
        }
        assertTrue(EntryProtectionAdapter.requiresDestinationCheck(
                PlayerTeleportEvent.TeleportCause.UNKNOWN),
                "unknown causes must also check (fail-closed)");
        assertTrue(EntryProtectionAdapter.requiresDestinationCheck(null),
                "a missing cause must also check (fail-closed)");
    }

    @Test
    void eachCoveredTeleportVectorDeniedAtDestinationCancels() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, new LandId(UUID.randomUUID()), UUID.randomUUID(), 0, 0))));
        World world = worldProxy(worldId, Set.of(), null);
        ProtectionListener listener = new ProtectionListener(denyEntryEngine(store));
        var causes = List.of(
                PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
                PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT,
                PlayerTeleportEvent.TeleportCause.PLUGIN,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
                PlayerTeleportEvent.TeleportCause.END_PORTAL);
        for (var cause : causes) {
            Player stranger = playerProxy(UUID.randomUUID(), world, null);
            PlayerTeleportEvent event = new PlayerTeleportEvent(stranger,
                    new Location(world, 900, 64, 900),
                    new Location(world, 5, 64, 5), cause);
            listener.onPlayerTeleport(event);
            assertTrue(event.isCancelled(), cause + " into a denying land must cancel");
        }
    }

    @Test
    void pushOutIsThrottledPerPlayer() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        AtomicReference<Location> teleportedTo = new AtomicReference<>();
        AtomicInteger pushOuts = new AtomicInteger();
        UUID worldId = UUID.randomUUID();
        World world = worldProxy(worldId, Set.of(chunkKey(56, 56)),
                new Location(worldProxy(worldId, Set.of(), null), 0, 64, 0));
        EntryProtectionAdapter adapter = adapterWith(now::get, null,
                Set.of(chunkKey(56, 56)), teleportedTo, pushOuts);
        UUID playerId = UUID.randomUUID();
        Player player = playerProxy(playerId, world, null);
        Location from = new Location(world, 900, 64, 900);

        assertTrue(adapter.pushOut(player, from), "first deny must push out");
        assertEquals(1, pushOuts.get());
        now.set(T0.plusMillis(500));
        assertFalse(adapter.pushOut(player, from), "rapid re-deny must stay throttled");
        assertEquals(1, pushOuts.get(), "throttled deny must not teleport again");
        now.set(T0.plusSeconds(4));
        assertTrue(adapter.pushOut(player, from), "deny past the window must push out again");
        assertEquals(2, pushOuts.get());
    }

    @Test
    void pushOutNeverLoadsAnUnloadedChunk() {
        AtomicReference<Location> teleportedTo = new AtomicReference<>();
        AtomicInteger pushOuts = new AtomicInteger();
        UUID worldId = UUID.randomUUID();
        World world = worldProxy(worldId, Set.of(), null);
        EntryProtectionAdapter adapter = adapterWith(() -> T0, null,
                Set.of(), teleportedTo, pushOuts);
        Player player = playerProxy(UUID.randomUUID(), world, null);

        assertFalse(adapter.pushOut(player, new Location(world, 900, 64, 900)),
                "no loaded target anywhere: cancel only, never teleport");
        assertEquals(0, pushOuts.get(), "unloaded chunks must never be teleported into");
        assertNull(teleportedTo.get());
    }

    @Test
    void pushOutFallsBackToSpawnWhenOriginChunkIsUnloaded() {
        UUID worldId = UUID.randomUUID();
        World stub = worldProxy(worldId, Set.of(chunkKey(0, 0)), null);
        Location spawn = new Location(stub, 5, 64, 5);
        AtomicReference<Location> spawnHolder = new AtomicReference<>(spawn);
        World spawnedWorld = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUID": return worldId;
                        case "getName": return "world";
                        case "isChunkLoaded":
                            return chunkKey((int) args[0], (int) args[1]) == chunkKey(0, 0);
                        case "getSpawnLocation": return spawnHolder.get();
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeWorld";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == long.class) return 0L;
                            return null;
                    }
                });
        AtomicReference<Location> teleportedTo = new AtomicReference<>();
        AtomicInteger pushOuts = new AtomicInteger();
        EntryProtectionAdapter adapter = adapterWith(() -> T0, null,
                Set.of(chunkKey(0, 0)), teleportedTo, pushOuts);
        Player player = playerProxy(UUID.randomUUID(), spawnedWorld, null);

        assertTrue(adapter.pushOut(player, new Location(spawnedWorld, 900, 64, 900)),
                "origin unloaded but spawn loaded: must push to spawn");
        assertEquals(1, pushOuts.get());
        assertEquals(spawn, teleportedTo.get());
    }

    @Test
    void bannedInsidePlayerIsStoppedOnNextMove() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, new LandId(UUID.randomUUID()), UUID.randomUUID(), 0, 0))));
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0)), new Location(worldProxy(worldId,
                Set.of(), null), 0, 64, 0));
        AtomicReference<Location> teleportedTo = new AtomicReference<>();
        AtomicInteger pushOuts = new AtomicInteger();
        EntryProtectionAdapter.BanLookup bans = (playerId, wid, cx, cz) -> Optional.of(true);
        EntryProtectionAdapter adapter = adapterWith(() -> T0, bans,
                Set.of(chunkKey(0, 0)), teleportedTo, pushOuts);
        ProtectionListener listener =
                new ProtectionListener(denyEntryEngine(store), null, adapter);

        Player banned = playerProxy(UUID.randomUUID(), world, null);
        PlayerMoveEvent sameChunk = new PlayerMoveEvent(banned,
                new Location(world, 5, 64, 5), new Location(world, 6, 64, 6));
        listener.onPlayerMove(sameChunk);
        assertTrue(sameChunk.isCancelled(),
                "banned player already inside must be stopped even without crossing chunks");
    }

    @Test
    void missingBanAnswerFailsClosed() {
        EntryProtectionAdapter emptyAnswer = new EntryProtectionAdapter(() -> T0,
                Duration.ofSeconds(3), (playerId, wid, cx, cz) -> Optional.empty(),
                (world, x, z) -> true, (player, target) -> {});
        EntryProtectionAdapter throwingAnswer = new EntryProtectionAdapter(() -> T0,
                Duration.ofSeconds(3), (playerId, wid, cx, cz) -> {
                    throw new RuntimeException("ban store boom");
                }, (world, x, z) -> true, (player, target) -> {});

        UUID worldId = UUID.randomUUID();
        World world = worldProxy(worldId, Set.of(), null);
        UUID playerId = UUID.randomUUID();
        Location inside = new Location(world, 5, 64, 5);
        assertTrue(emptyAnswer.isBannedInside(playerId, inside),
                "empty ban answer must deny (fail-closed)");
        assertTrue(throwingAnswer.isBannedInside(playerId, inside),
                "throwing ban lookup must deny (fail-closed)");
    }

    @Test
    void unwiredBanLookupSkipsInsideCheck() {
        EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0,
                Duration.ofSeconds(3), null,
                (world, x, z) -> true, (player, target) -> {});
        UUID worldId = UUID.randomUUID();
        World world = worldProxy(worldId, Set.of(), null);
        assertFalse(adapter.isBannedInside(UUID.randomUUID(), new Location(world, 5, 64, 5)),
                "no ban source wired yet: inside check must skip, ENTRY decision still applies");
    }

    @Test
    void productionWiringDeniesBannedInsideFromSnapshot() {
        UUID worldId = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, land, UUID.randomUUID(), 0, 0))));
        UUID bannedId = UUID.randomUUID();
        UUID clearId = UUID.randomUUID();
        LandAuthorisationSnapshot bans = LandAuthorisationSnapshot.copyOf(
                java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of(land, Set.of(bannedId)));
        ProtectionListener listener = new ProtectionListener(denyEntryEngine(store), null,
                new EntryBanLookup(store::snapshot, () -> bans));
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0)), null);

        Player banned = playerProxy(bannedId, world, null);
        PlayerMoveEvent bannedMove = new PlayerMoveEvent(banned,
                new Location(world, 5, 64, 5), new Location(world, 6, 64, 6));
        listener.onPlayerMove(bannedMove);
        assertTrue(bannedMove.isCancelled(),
                "banned player already inside must be stopped even without crossing chunks");

        Player clear = playerProxy(clearId, world, null);
        PlayerMoveEvent clearMove = new PlayerMoveEvent(clear,
                new Location(world, 5, 64, 5), new Location(world, 6, 64, 6));
        listener.onPlayerMove(clearMove);
        assertFalse(clearMove.isCancelled(),
                "unbanned player on a known land must keep moving inside one chunk");
    }

    @Test
    void unwiredEntryCheckFailsClosed() {
        UUID worldId = UUID.randomUUID();
        World world = worldProxy(worldId, Set.of(chunkKey(56, 56)), null);
        EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0,
                Duration.ofSeconds(3), null,
                (w, x, z) -> true, (player, target) -> {
                    throw new AssertionError("cancel-only path must never transport");
                });
        Player player = playerProxy(UUID.randomUUID(), world, null);
        Location from = new Location(world, 900, 64, 900);

        assertTrue(adapter.pushOutTarget(player, from).isEmpty(),
                "no entry-validity probe wired: no candidate verifies, target stays empty");
        assertFalse(adapter.pushOut(player, from),
                "no entry-validity probe wired: push-out stays cancel-only");
    }

    @Test
    void pushOutThrottleAdmitsExactlyOneRacingDeny() throws Exception {
        EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0,
                Duration.ofSeconds(3), null,
                (w, x, z) -> true, (playerId, at) -> true, (player, target) -> {});
        UUID playerId = UUID.randomUUID();
        int racers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch ready = new CountDownLatch(racers);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return adapter.tryAcquirePushOut(playerId);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "all racers must line up first");
            go.countDown();
            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(1, winners,
                    "racing denies for one player must admit exactly one push-out per window");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void entryDenyPushOutMustNotSendBackIntoDeniedLand() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, new LandId(UUID.randomUUID()), UUID.randomUUID(), 0, 0),
                landAt(worldId, new LandId(UUID.randomUUID()), UUID.randomUUID(), 1, 0))));
        Location spawn = new Location(worldProxy(worldId, Set.of(), null), 149, 64, 149);
        World world = worldProxy(worldId,
                Set.of(chunkKey(0, 0), chunkKey(1, 0), chunkKey(9, 9)), spawn);
        AtomicReference<Location> teleportedTo = new AtomicReference<>();
        AtomicInteger pushOuts = new AtomicInteger();
        ProtectionEngine engine = denyEntryEngine(store);
        EntryProtectionAdapter adapter = adapterWith(() -> T0, null,
                Set.of(chunkKey(0, 0), chunkKey(1, 0), chunkKey(9, 9)), teleportedTo, pushOuts,
                engineEntryCheck(engine));
        ProtectionListener listener =
                new ProtectionListener(engine, null, adapter);

        Player stranger = playerProxy(UUID.randomUUID(), world, null);
        Location from = new Location(world, 5, 64, 5);
        PlayerMoveEvent denied = new PlayerMoveEvent(stranger, from, new Location(world, 21, 64, 5));
        listener.onPlayerMove(denied);
        assertTrue(denied.isCancelled(), "ENTRY deny at the destination must cancel");
        assertEquals(spawn, teleportedTo.get(),
                "push-out must land on a valid target (spawn), never back in the denied land");
    }

    @Test
    void bannedInsideWithDeniedSpawnStaysCancelOnly() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, new LandId(UUID.randomUUID()), UUID.randomUUID(), 0, 0))));
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0)),
                new Location(worldProxy(worldId, Set.of(), null), 0, 64, 0));
        Location spawnInsideLand = new Location(world, 5, 64, 5);
        World spawnedWorld = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUID": return worldId;
                        case "getName": return "world";
                        case "isChunkLoaded": return true;
                        case "getSpawnLocation": return spawnInsideLand;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeWorld";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == long.class) return 0L;
                            return null;
                    }
                });
        AtomicReference<Location> teleportedTo = new AtomicReference<>();
        AtomicInteger pushOuts = new AtomicInteger();
        EntryProtectionAdapter.BanLookup bans = (playerId, wid, cx, cz) -> Optional.of(true);
        ProtectionEngine engine = denyEntryEngine(store);
        EntryProtectionAdapter adapter = adapterWith(() -> T0, bans,
                Set.of(chunkKey(0, 0)), teleportedTo, pushOuts, engineEntryCheck(engine));
        ProtectionListener listener =
                new ProtectionListener(engine, null, adapter);

        Player banned = playerProxy(UUID.randomUUID(), spawnedWorld, null);
        PlayerMoveEvent stuck = new PlayerMoveEvent(banned,
                new Location(spawnedWorld, 5, 64, 5), new Location(spawnedWorld, 6, 64, 6));
        listener.onPlayerMove(stuck);
        assertTrue(stuck.isCancelled(), "banned player already inside must be stopped");
        assertNull(teleportedTo.get(),
                "spawn sits inside the denying land: push-out must stay cancel-only, "
                        + "never teleport back into the denied land");
    }
}

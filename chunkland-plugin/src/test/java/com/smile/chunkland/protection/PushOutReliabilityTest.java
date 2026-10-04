package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

/**
 * Push-out reliability on Folia: ejection travels over
 * {@code teleportAsync} (sync {@code Entity#teleport} is broken there), a
 * rescue teleport is never re-cancelled by its own banned-inside stop, and an
 * exhausted candidate list stays fail-closed with a single observable record.
 */
class PushOutReliabilityTest {

    private static final Instant T0 = Instant.parse("2026-09-27T00:00:00Z");

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /** Sink double: records the landing it took and reports the delivery. */
    private static boolean recording(AtomicReference<Location> landing, Location target) {
        landing.set(target);
        return true;
    }

    /** Sink double: never takes a landing. */
    private static boolean alwaysRefuses(Player player, Location target) {
        return false;
    }

    private static World worldProxy(UUID worldId, Set<Long> loaded, Location spawn) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUID": return worldId;
                        case "getName": return "world";
                        case "isChunkLoaded":
                            return loaded.contains(chunkKey((int) args[0], (int) args[1]));
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

    /** Player double: sync teleport is forbidden, async teleport records. */
    private static Player asyncOnlyPlayer(UUID id, World world,
                                          AtomicInteger syncHits,
                                          AtomicReference<Location> asyncTarget) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "getWorld": return world;
                        case "teleport":
                            syncHits.incrementAndGet();
                            throw new AssertionError(
                                    "sync Entity#teleport is broken on Folia, use teleportAsync");
                        case "teleportAsync":
                            if (args.length >= 1 && args[0] instanceof Location loc) {
                                asyncTarget.set(loc);
                            }
                            return CompletableFuture.completedFuture(true);
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

    private static ProtectionEngine allowEntryEngine(LandRegistryStore store) {
        return new ProtectionEngine(store::snapshot, (actor, landId, action, snapshot) ->
                new PermissionContext(action, false, List.of(),
                        action == ProtectionActionType.ENTRY
                                ? PermissionState.ALLOW : PermissionState.INHERIT,
                        PermissionState.INHERIT));
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

    private static ProtectionEngine denyEntryOnLandEngine(LandRegistryStore store) {
        return new ProtectionEngine(store::snapshot, (actor, landId, action, snapshot) -> {
            if (action == ProtectionActionType.ENTRY && landId != null) {
                return new PermissionContext(action, false, List.of(),
                        PermissionState.DENY, PermissionState.INHERIT);
            }
            return new PermissionContext(action, false, List.of(),
                    PermissionState.ALLOW, PermissionState.INHERIT);
        });
    }

    private static LandSnapshot landAt(UUID worldId, LandId id, UUID owner, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private static LandSnapshot landWithCuboidSub(UUID worldId, LandId landId, UUID owner,
                                                  SubLandId subId, Cuboid cuboid) {
        SubLandSnapshot sub = new SubLandSnapshot(subId, landId, "den", cuboid, worldId);
        return new LandSnapshot(landId, "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                List.of(sub), 0, 0, Instant.now(), Instant.now());
    }

    @Test
    void productionPushOutTravelsOverDeferredTeleportAsync() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, new LandId(UUID.randomUUID()), UUID.randomUUID(), 9, 9))));
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0), chunkKey(9, 9)), null);
        ProtectionEngine engine = allowEntryEngine(store);
        EntryProtectionAdapter adapter = ProtectionListener.productionEntryAdapter(
                engine, null, null);

        AtomicInteger syncHits = new AtomicInteger();
        AtomicReference<Location> asyncTarget = new AtomicReference<>();
        Player player = asyncOnlyPlayer(UUID.randomUUID(), world, syncHits, asyncTarget);
        Location from = new Location(world, 5, 64, 5);

        // No plugin means no region thread to defer onto, so the transport
        // refuses and the deny stands; the deferral contract itself is covered
        // by PushOutTransportDeferralTest.
        assertFalse(adapter.pushOut(player, from),
                "an unwired ejection transport must fail closed, never fake a push-out");
        assertEquals(0, syncHits.get(), "push-out must never touch sync Entity#teleport");
        assertNull(asyncTarget.get(), "nothing may travel without a region thread");
    }

    @Test
    void refusedTransportLeavesNoRescuePassBehind() {
        UUID worldId = UUID.randomUUID();
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0)), null);
        EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0,
                Duration.ofSeconds(3), null, (w, x, z) -> true, (playerId, at) -> true,
                PushOutReliabilityTest::alwaysRefuses);
        UUID playerId = UUID.randomUUID();
        Player player = asyncOnlyPlayer(playerId, world,
                new AtomicInteger(), new AtomicReference<>());
        Location from = new Location(world, 5, 64, 5);

        assertFalse(adapter.pushOut(player, from),
                "a refused transport must not read as a successful push-out");
        assertFalse(adapter.consumePushOutPass(playerId, from),
                "a landing that never left the thread must leave no pass: it would waive "
                        + "the origin ban stop for an unrelated later teleport");
    }

    @Test
    void bannedInsidePushOutSurvivesItsOwnTeleportEvent() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, new LandId(UUID.randomUUID()), UUID.randomUUID(), 0, 0))));
        Location spawn = new Location(worldProxy(worldId, Set.of(), null), 1605, 64, 1605);
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0), chunkKey(100, 100)), spawn);
        // Banned only on the home chunk: the ban stop must fire on departure,
        // while the wilderness spawn stays a valid landing.
        EntryProtectionAdapter.BanLookup bans = (playerId, wid, cx, cz) ->
                Optional.of(cx == 0 && cz == 0);
        AtomicReference<Location> transported = new AtomicReference<>();
        EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0,
                Duration.ofSeconds(3), bans, (w, x, z) -> true, (playerId, at) -> true,
                (player, target) -> recording(transported, target));
        ProtectionListener listener =
                new ProtectionListener(allowEntryEngine(store), null, adapter);

        UUID playerId = UUID.randomUUID();
        Player banned = asyncOnlyPlayer(playerId, world, new AtomicInteger(), new AtomicReference<>());
        Location bannedFrom = new Location(world, 5, 64, 5);
        PlayerMoveEvent departure = new PlayerMoveEvent(banned,
                bannedFrom, new Location(world, 6, 64, 6));
        listener.onPlayerMove(departure);
        assertTrue(departure.isCancelled(), "banned player inside must be stopped");
        assertNotNull(transported.get(), "the stop must eject the player");

        // The rescue teleport itself leaves the banned chunk: its own
        // banned-inside stop must recognise it and let it through.
        PlayerTeleportEvent rescue = new PlayerTeleportEvent(banned,
                bannedFrom, transported.get(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        listener.onPlayerTeleport(rescue);
        assertFalse(rescue.isCancelled(),
                "the push-out teleport must not be re-cancelled by its own ban stop");

        // Timing: once landed, the next move starts from a verdict-clean
        // position and must not read as a crossing.
        PlayerMoveEvent afterLanding = new PlayerMoveEvent(banned,
                transported.get(), new Location(world, 1606, 64, 1606));
        listener.onPlayerMove(afterLanding);
        assertFalse(afterLanding.isCancelled(),
                "after a successful push-out the next move must not be misread as a crossing");
    }

    @Test
    void pushOutPassIsSingleUse() {
        UUID worldId = UUID.randomUUID();
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0)), null);
        EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0,
                Duration.ofSeconds(3), null, (w, x, z) -> true, (playerId, at) -> true,
                (player, target) -> true);
        UUID playerId = UUID.randomUUID();
        Player player = asyncOnlyPlayer(playerId, world, new AtomicInteger(), new AtomicReference<>());
        Location from = new Location(world, 5, 64, 5);

        assertTrue(adapter.pushOut(player, from), "first deny must push out");
        assertTrue(adapter.consumePushOutPass(playerId, new Location(world, 5, 64, 5)),
                "the rescue teleport consumes the pass");
        assertFalse(adapter.consumePushOutPass(playerId, new Location(world, 5, 64, 5)),
                "the pass is single-use: a replay without a new push-out stays enforced");
        assertFalse(adapter.consumePushOutPass(playerId, new Location(world, 900, 64, 900)),
                "an unrelated destination never matches the pass");
    }

    @Test
    void exhaustedCandidatesFailClosedWithOneObservableRecord() {
        Logger log = Logger.getLogger(EntryProtectionAdapter.class.getName());
        List<LogRecord> records = new java.util.ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level previous = log.getLevel();
        log.addHandler(capture);
        try {
            UUID worldId = UUID.randomUUID();
            World world = worldProxy(worldId, Set.of(), null);
            AtomicReference<Location> transported = new AtomicReference<>();
            EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0,
                    Duration.ofSeconds(3), null, (w, x, z) -> false, (playerId, at) -> true,
                    (player, target) -> recording(transported, target));
            Player player = asyncOnlyPlayer(UUID.randomUUID(), world,
                    new AtomicInteger(), new AtomicReference<>());

            assertFalse(adapter.pushOut(player, new Location(world, 900, 64, 900)),
                    "no loaded candidate anywhere: cancel only, never teleport");
            assertNull(transported.get(), "no candidate: the player must not be left anywhere");
            assertEquals(1, records.size(),
                    "the rare exhausted-candidate path must leave exactly one observable record");
            assertTrue(records.get(0).getMessage().contains("no push-out target"),
                    "the record must name the exhausted-candidate cause");
        } finally {
            log.removeHandler(capture);
            log.setLevel(previous);
        }
    }

    @Test
    void successfulPushOutLogsNothing() {
        Logger log = Logger.getLogger(EntryProtectionAdapter.class.getName());
        List<LogRecord> records = new java.util.ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(capture);
        try {
            UUID worldId = UUID.randomUUID();
            World world = worldProxy(worldId, Set.of(chunkKey(0, 0)), null);
            EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0,
                    Duration.ofSeconds(3), null, (w, x, z) -> true, (playerId, at) -> true,
                    (player, target) -> true);
            Player player = asyncOnlyPlayer(UUID.randomUUID(), world,
                    new AtomicInteger(), new AtomicReference<>());

            assertTrue(adapter.pushOut(player, new Location(world, 5, 64, 5)));
            assertTrue(records.isEmpty(), "routine push-outs must stay quiet");
        } finally {
            log.removeHandler(capture);
        }
    }

    @Test
    void pushOutRejectsTargetWherePlayerIsBanned() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, new LandId(UUID.randomUUID()), UUID.randomUUID(), 0, 0))));
        Location spawn = new Location(worldProxy(worldId, Set.of(), null), 5, 64, 5);
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0)), spawn);
        EntryProtectionAdapter.BanLookup bans = (playerId, wid, cx, cz) -> Optional.of(true);
        AtomicReference<Location> transported = new AtomicReference<>();
        EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0,
                Duration.ofSeconds(3), bans, (w, x, z) -> true, (playerId, at) -> true,
                (player, target) -> recording(transported, target));
        ProtectionListener listener =
                new ProtectionListener(allowEntryEngine(store), null, adapter);

        Player banned = asyncOnlyPlayer(UUID.randomUUID(), world,
                new AtomicInteger(), new AtomicReference<>());
        PlayerMoveEvent stuck = new PlayerMoveEvent(banned,
                new Location(world, 6, 64, 6), new Location(world, 7, 64, 7));
        listener.onPlayerMove(stuck);

        assertTrue(stuck.isCancelled(), "banned player already inside must be stopped");
        assertNull(transported.get(),
                "every candidate bans the player: push-out must stay cancel-only, "
                        + "never eject into another banned stop");
    }

    @Test
    void sameChunkEntryDenyStillPushesOutAsync() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landWithCuboidSub(worldId,
                new LandId(UUID.randomUUID()), UUID.randomUUID(),
                new SubLandId(UUID.randomUUID()), new Cuboid(0, 0, 0, 7, 255, 15)))));
        Location spawn = new Location(worldProxy(worldId, Set.of(), null), 1605, 64, 1605);
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0), chunkKey(100, 100)), spawn);
        Location from = new Location(world, 10, 64, 5);
        Location to = new Location(world, 5, 64, 5);
        AtomicInteger syncHits = new AtomicInteger();
        AtomicReference<Location> asyncTarget = new AtomicReference<>();
        // Only the pre-entry side stays valid: the deny must eject back there.
        EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0,
                Duration.ofSeconds(3), null, (w, x, z) -> true,
                (playerId, at) -> at.getBlockX() == from.getBlockX()
                        && at.getBlockY() == from.getBlockY()
                        && at.getBlockZ() == from.getBlockZ(),
                (player, target) -> {
                    try {
                        asyncTarget.set(target);
                        return true;
                    } catch (RuntimeException ex) {
                        throw new AssertionError(ex);
                    }
                });
        ProtectionListener listener =
                new ProtectionListener(denyEntryEngine(store), null, adapter);

        Player stranger = asyncOnlyPlayer(UUID.randomUUID(), world, syncHits,
                new AtomicReference<>());
        PlayerMoveEvent crossing = new PlayerMoveEvent(stranger, from, to);
        listener.onPlayerMove(crossing);

        assertTrue(crossing.isCancelled(),
                "walking inside one chunk into a denying subland must still cancel");
        assertEquals(from, asyncTarget.get(),
                "the same-chunk deny must still push out to the pre-entry side");
        assertEquals(0, syncHits.get(), "even the regression path must avoid sync teleport");
    }

    @Test
    void entryDenyPushOutLeavesNoPassAfterRescueArrival() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, new LandId(UUID.randomUUID()), UUID.randomUUID(), 0, 0))));
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0), chunkKey(56, 56)), null);
        // Origin is never banned: this is a pure ENTRY deny, so the rescue
        // lands where the origin ban stop stays silent.
        EntryProtectionAdapter.BanLookup bans = (playerId, wid, cx, cz) -> Optional.of(false);
        AtomicReference<Location> transported = new AtomicReference<>();
        Location from = new Location(world, 900, 64, 900);
        EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0,
                Duration.ofSeconds(3), bans, (w, x, z) -> true,
                (playerId, at) -> at.getBlockX() == from.getBlockX()
                        && at.getBlockZ() == from.getBlockZ(),
                (player, target) -> recording(transported, target));
        ProtectionListener listener =
                new ProtectionListener(denyEntryOnLandEngine(store), null, adapter);

        UUID playerId = UUID.randomUUID();
        Player stranger = asyncOnlyPlayer(playerId, world,
                new AtomicInteger(), new AtomicReference<>());
        PlayerTeleportEvent denied = new PlayerTeleportEvent(stranger,
                from, new Location(world, 5, 64, 5), PlayerTeleportEvent.TeleportCause.PLUGIN);
        listener.onPlayerTeleport(denied);
        assertTrue(denied.isCancelled(), "ENTRY deny at the destination must cancel");
        assertEquals(from, transported.get(),
                "the ENTRY deny must push out to the pre-entry side");

        PlayerTeleportEvent rescue = new PlayerTeleportEvent(stranger,
                from, transported.get(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        listener.onPlayerTeleport(rescue);
        assertFalse(rescue.isCancelled(),
                "the rescue arrival lands ENTRY-allowed and must go through");
        assertFalse(adapter.consumePushOutPass(playerId, transported.get()),
                "the ENTRY-deny rescue arrival must already have spent the pass: "
                        + "no residue may remain behind");
    }

    @Test
    void quitDiscardsPendingPushOutPass() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, new LandId(UUID.randomUUID()), UUID.randomUUID(), 0, 0))));
        World world = worldProxy(worldId, Set.of(chunkKey(56, 56)), null);
        EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0,
                Duration.ofSeconds(3), null, (w, x, z) -> true, (playerId, at) -> true,
                (player, target) -> true);
        ProtectionListener listener =
                new ProtectionListener(allowEntryEngine(store), null, adapter);

        UUID playerId = UUID.randomUUID();
        Player player = asyncOnlyPlayer(playerId, world,
                new AtomicInteger(), new AtomicReference<>());
        Location from = new Location(world, 900, 64, 900);
        assertTrue(adapter.pushOut(player, from), "first deny must push out");

        listener.onPlayerQuit(new PlayerQuitEvent(player, "quit",
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertFalse(adapter.consumePushOutPass(playerId, from),
                "quit must discard the pending pass: a rejoining player starts clean");
    }

    @Test
    void repeatedExhaustedPushOutLogsOncePerWindow() {
        Logger log = Logger.getLogger(EntryProtectionAdapter.class.getName());
        List<LogRecord> records = new java.util.ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(capture);
        try {
            AtomicReference<Instant> now = new AtomicReference<>(T0);
            UUID worldId = UUID.randomUUID();
            World world = worldProxy(worldId, Set.of(), null);
            AtomicReference<Location> transported = new AtomicReference<>();
            EntryProtectionAdapter adapter = new EntryProtectionAdapter(now::get,
                    Duration.ofSeconds(3), null, (w, x, z) -> false, (playerId, at) -> true,
                    (player, target) -> recording(transported, target));
            Player player = asyncOnlyPlayer(UUID.randomUUID(), world,
                    new AtomicInteger(), new AtomicReference<>());
            Location stuck = new Location(world, 900, 64, 900);

            assertFalse(adapter.pushOut(player, stuck), "no candidate: cancel only");
            assertFalse(adapter.pushOut(player, stuck),
                    "same window: still cancel only");
            assertEquals(1, records.size(),
                    "repeated no-candidate denials inside one throttle window "
                            + "must leave exactly one record");
            assertNull(transported.get(), "no candidate: the player must not be left anywhere");

            now.set(T0.plusSeconds(4));
            assertFalse(adapter.pushOut(player, stuck), "next window: still cancel only");
            assertEquals(2, records.size(),
                    "a new window may record the still-stuck player once more");
        } finally {
            log.removeHandler(capture);
        }
    }

    @Test
    void unloadedBanSnapshotKeepsPushOutCancelOnly() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, new LandId(UUID.randomUUID()), UUID.randomUUID(), 0, 0))));
        Location spawn = new Location(worldProxy(worldId, Set.of(), null), 5, 64, 5);
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0)), spawn);
        // An unloaded ban snapshot answers unknown on known lands (empty),
        // exactly like EntryBanLookup before its first durable load.
        EntryProtectionAdapter.BanLookup unloadedBans =
                (playerId, wid, cx, cz) -> Optional.empty();
        AtomicReference<Location> transported = new AtomicReference<>();
        EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0,
                Duration.ofSeconds(3), unloadedBans, (w, x, z) -> true, (playerId, at) -> true,
                (player, target) -> recording(transported, target));
        ProtectionListener listener =
                new ProtectionListener(allowEntryEngine(store), null, adapter);

        Player player = asyncOnlyPlayer(UUID.randomUUID(), world,
                new AtomicInteger(), new AtomicReference<>());
        PlayerMoveEvent stuck = new PlayerMoveEvent(player,
                new Location(world, 5, 64, 5), new Location(world, 6, 64, 6));
        listener.onPlayerMove(stuck);

        assertTrue(stuck.isCancelled(),
                "unknown ban state must stop movement (fail-closed)");
        assertNull(transported.get(),
                "unknown ban state excludes every known-land candidate: push-out "
                        + "stays cancel-only until the snapshot loads, never ejecting "
                        + "into an unverifiable ban state");
    }
}

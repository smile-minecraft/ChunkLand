package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * The push-out transport must not move the player while the deny event is
 * still being dispatched.
 *
 * <p>Paper fires {@code PlayerMoveEvent} from inside
 * {@code ServerGamePacketListenerImpl#handleMovePlayer} and, when the event
 * comes back cancelled, immediately restores the pre-move position with
 * {@code internalTeleport(from)}. On the player's own region thread
 * {@code teleportAsync} resolves inline through the same-region fast path, so
 * an ejection issued from the handler is applied and then silently undone in
 * the same call: the player never moves, nothing throws, and nothing is
 * logged. {@code /tp} is unaffected because it is issued outside that
 * dispatch.
 *
 * <p>So the production transport defers the teleport onto the player's Folia
 * thread for a later tick, and reports a refusal the platform made instead of
 * dropping it silently.
 */
class PushOutTransportDeferralTest {

    private static final String TRANSPORT_LOGGER =
            "com.smile.chunkland.protection.EntryProtectionAdapter";

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static World worldProxy(UUID worldId, Set<Long> loaded) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUID": return worldId;
                        case "getName": return "world";
                        case "isChunkLoaded":
                            return loaded.contains(chunkKey((int) args[0], (int) args[1]));
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

    private static Plugin pluginProxy() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class[]{Plugin.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getName": return "ChunkLand";
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakePlugin";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                    }
                });
    }

    private static ScheduledTask scheduledTaskProxy() {
        return (ScheduledTask) Proxy.newProxyInstance(ScheduledTask.class.getClassLoader(),
                new Class[]{ScheduledTask.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isRepeatingTask": return false;
                        case "isCancelled": return false;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeScheduledTask";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                    }
                });
    }

    /** One submitted region-scheduler task, kept unexecuted until the test runs it. */
    private record Submission(Consumer<ScheduledTask> task, long delayTicks) {
    }

    /** Player double whose entity scheduler records submissions instead of running them. */
    private static final class DeferredPlayer {

        private final UUID id;
        private final World world;
        private final List<Submission> submissions = new ArrayList<>();
        private final List<Location> asyncTargets = new ArrayList<>();
        private final AtomicInteger syncHits = new AtomicInteger();
        private CompletableFuture<Boolean> transport;
        private boolean schedulerRetires;

        private DeferredPlayer(UUID id, World world) {
            this.id = id;
            this.world = world;
            this.transport = CompletableFuture.completedFuture(Boolean.TRUE);
        }

        private Player proxy() {
            EntityScheduler scheduler = (EntityScheduler) Proxy.newProxyInstance(
                    EntityScheduler.class.getClassLoader(),
                    new Class[]{EntityScheduler.class},
                    (sched, method, args) -> {
                        if (!method.getName().equals("runDelayed")) {
                            if (method.getReturnType() == boolean.class) return false;
                            return null;
                        }
                        if (schedulerRetires) {
                            return null;
                        }
                        submissions.add(new Submission((Consumer<ScheduledTask>) args[1],
                                (Long) args[3]));
                        return scheduledTaskProxy();
                    });
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                    new Class[]{Player.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getUniqueId": return id;
                            case "getWorld": return world;
                            case "getScheduler": return scheduler;
                            case "teleport":
                                syncHits.incrementAndGet();
                                throw new AssertionError(
                                        "sync Entity#teleport is broken on Folia, use teleportAsync");
                            case "teleportAsync":
                                if (args.length >= 1 && args[0] instanceof Location loc) {
                                    asyncTargets.add(loc);
                                }
                                return transport;
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

        private void runSubmittedTasks() {
            List<Submission> pending = List.copyOf(submissions);
            submissions.clear();
            for (Submission submission : pending) {
                submission.task().accept(scheduledTaskProxy());
            }
        }
    }

    private static ProtectionEngine allowEntryEngine(LandRegistryStore store) {
        return new ProtectionEngine(store::snapshot, (actor, landId, action, snapshot) ->
                new PermissionContext(action, false, List.of(),
                        action == ProtectionActionType.ENTRY
                                ? PermissionState.ALLOW : PermissionState.INHERIT,
                        PermissionState.INHERIT));
    }

    private static LandSnapshot landAt(UUID worldId, LandId id, UUID owner,
                                       int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private static LandRegistryStore storeWithLandAt(UUID worldId, int chunkX, int chunkZ) {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, new LandId(UUID.randomUUID()), UUID.randomUUID(),
                        chunkX, chunkZ))));
        return store;
    }

    /** Captures warnings the push-out subsystem emits for one assertion. */
    private static List<LogRecord> captureTransportWarnings(Runnable body) {
        Logger logger = Logger.getLogger(TRANSPORT_LOGGER);
        List<LogRecord> captured = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record != null && record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    captured.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            body.run();
        } finally {
            logger.removeHandler(handler);
        }
        return captured;
    }

    @Test
    void productionPushOutMustNotMoveThePlayerInsideTheDeniedMoveDispatch() {
        UUID worldId = UUID.randomUUID();
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0)));
        EntryProtectionAdapter adapter = ProtectionListener.productionEntryAdapter(
                allowEntryEngine(storeWithLandAt(worldId, 9, 9)), null, pluginProxy());
        DeferredPlayer player = new DeferredPlayer(UUID.randomUUID(), world);
        Player proxy = player.proxy();
        Location from = new Location(world, 5, 64, 5);

        adapter.pushOut(proxy, from);

        assertEquals(List.of(), player.asyncTargets,
                "the ejection must not run inline: Paper restores the pre-move position "
                        + "right after a cancelled PlayerMoveEvent returns, which silently "
                        + "undoes an inline teleport");
        assertEquals(0, player.syncHits.get(), "push-out must never touch sync Entity#teleport");
    }

    @Test
    void deferredPushOutTeleportsOnThePlayerThreadAfterTheDispatch() {
        UUID worldId = UUID.randomUUID();
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0)));
        EntryProtectionAdapter adapter = ProtectionListener.productionEntryAdapter(
                allowEntryEngine(storeWithLandAt(worldId, 9, 9)), null, pluginProxy());
        DeferredPlayer player = new DeferredPlayer(UUID.randomUUID(), world);
        Player proxy = player.proxy();
        Location from = new Location(world, 5, 64, 5);

        assertTrue(adapter.pushOut(proxy, from), "a loaded, ENTRY-allowed origin must push out");
        assertEquals(1, player.submissions.size(),
                "the ejection must be handed to the player's own region thread");
        assertTrue(player.submissions.getFirst().delayTicks() >= 1L,
                "the ejection must land on a later tick than the cancelled deny event");

        player.runSubmittedTasks();

        assertEquals(List.of(from), player.asyncTargets,
                "the deferred hop must travel over teleportAsync to the validated landing");
        assertEquals(0, player.syncHits.get(), "push-out must never touch sync Entity#teleport");
    }

    @Test
    void refusedTransportIsReportedInsteadOfDroppedSilently() {
        UUID worldId = UUID.randomUUID();
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0)));
        EntryProtectionAdapter adapter = ProtectionListener.productionEntryAdapter(
                allowEntryEngine(storeWithLandAt(worldId, 9, 9)), null, pluginProxy());
        DeferredPlayer player = new DeferredPlayer(UUID.randomUUID(), world);
        Player proxy = player.proxy();
        Location from = new Location(world, 5, 64, 5);

        player.transport = CompletableFuture.completedFuture(Boolean.FALSE);
        List<LogRecord> warnings = captureTransportWarnings(() -> {
            assertTrue(adapter.pushOut(proxy, from), "the hop itself was accepted");
            player.runSubmittedTasks();
        });

        assertEquals(1, player.asyncTargets.size(), "the deferred hop must still have run");
        assertTrue(warnings.stream().anyMatch(record -> record.getMessage().contains("push-out")),
                "a refused push-out teleport must leave an observable record, got: " + warnings);
    }

    @Test
    void exceptionalTransportIsReportedWithItsCause() {
        UUID worldId = UUID.randomUUID();
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0)));
        EntryProtectionAdapter adapter = ProtectionListener.productionEntryAdapter(
                allowEntryEngine(storeWithLandAt(worldId, 9, 9)), null, pluginProxy());
        DeferredPlayer player = new DeferredPlayer(UUID.randomUUID(), world);
        Player proxy = player.proxy();
        Location from = new Location(world, 5, 64, 5);
        CompletableFuture<Boolean> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("boom"));

        player.transport = failed;
        List<LogRecord> warnings = captureTransportWarnings(() -> {
            adapter.pushOut(proxy, from);
            player.runSubmittedTasks();
        });

        assertNotNull(warnings.stream()
                        .filter(record -> record.getMessage().contains("push-out"))
                        .findFirst()
                        .orElse(null),
                "a failed push-out teleport must leave an observable record, got: " + warnings);
    }

    @Test
    void retiredRegionThreadIsRefusedNotAcceptedAsSuccess() {
        UUID worldId = UUID.randomUUID();
        World world = worldProxy(worldId, Set.of(chunkKey(0, 0)));
        EntryProtectionAdapter adapter = ProtectionListener.productionEntryAdapter(
                allowEntryEngine(storeWithLandAt(worldId, 9, 9)), null, pluginProxy());
        DeferredPlayer player = new DeferredPlayer(UUID.randomUUID(), world);
        player.schedulerRetires = true;
        Player proxy = player.proxy();
        Location from = new Location(world, 5, 64, 5);
        List<LogRecord> warnings = captureTransportWarnings(
                () -> assertFalse(adapter.pushOut(proxy, from),
                        "a retired entity scheduler must not read as a successful push-out"));

        assertEquals(List.of(), player.asyncTargets, "nothing may travel");
        assertTrue(warnings.stream().anyMatch(record -> record.getMessage().contains("push-out")),
                "a refused push-out hop must leave an observable record, got: " + warnings);
    }
}
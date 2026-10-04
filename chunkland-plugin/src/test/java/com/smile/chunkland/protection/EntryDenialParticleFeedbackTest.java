package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.selection.SelectionClock;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

class EntryDenialParticleFeedbackTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    private static final class FakeClock implements SelectionClock {
        private final AtomicReference<Instant> now = new AtomicReference<>(T0);

        @Override
        public Instant now() {
            return now.get();
        }

        void advance(Duration delta) {
            now.set(now.get().plus(delta));
        }
    }

    private static World world(UUID worldId) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> worldId;
                    case "getName" -> "world";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeWorld";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Player player(UUID id) {
        return player(id, null);
    }

    private static Player player(UUID id, World world) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getWorld" -> world;
                    case "getName" -> "TestPlayer";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakePlayer";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Block block(World world, int x, int y, int z) {
        Location location = new Location(world, x, y, z);
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
                new Class[]{Block.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getLocation" -> location;
                    case "getWorld" -> world;
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeBlock";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        return null;
    }

    private static ProtectionEngine engine(LandRegistryStore store, boolean entryAllowed) {
        return new ProtectionEngine(store::snapshot, (actor, landId, action, snapshot) -> {
            PermissionState state = action == ProtectionActionType.ENTRY
                    ? (entryAllowed ? PermissionState.ALLOW : PermissionState.DENY)
                    : PermissionState.DENY;
            return new PermissionContext(action, false, List.of(), state,
                    PermissionState.INHERIT);
        });
    }

    private static LandRegistryStore store(UUID worldId) {
        LandRegistryStore store = new LandRegistryStore();
        UUID owner = UUID.randomUUID();
        store.publish(LandRegistry.from(List.of(new LandSnapshot(
                new LandId(UUID.randomUUID()), "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, 0, 0)), List.of(), 0, 0,
                T0, T0))));
        return store;
    }

    private static final class Captured {
        final AtomicInteger sends = new AtomicInteger();
        final AtomicInteger pushOuts = new AtomicInteger();
        final AtomicReference<Player> player = new AtomicReference<>();
        final AtomicReference<Location> location = new AtomicReference<>();
        final FakeClock clock = new FakeClock();
    }

    private static ProtectionListener listener(Captured captured, ProtectionEngine engine,
                                               EntryProtectionAdapter adapter) {
        EntryDenialParticleFeedback feedback = new EntryDenialParticleFeedback(
                captured.clock, EntryDenialParticleFeedback.DEFAULT_COOLDOWN,
                PlayerScheduler.direct(), (player, location) -> {
                    captured.sends.incrementAndGet();
                    captured.player.set(player);
                    captured.location.set(location);
                });
        return new ProtectionListener(engine, null, adapter, feedback);
    }

    private static EntryProtectionAdapter adapter(World world, AtomicInteger pushOuts) {
        return new EntryProtectionAdapter(() -> T0, Duration.ofSeconds(3), null,
                (ignoredWorld, x, z) -> true, (playerId, at) -> true,
                (player, target) -> counted(pushOuts));
    }

    /** Sink double: counts the eject and reports the delivery. */
    private static boolean counted(AtomicInteger pushOuts) {
        pushOuts.incrementAndGet();
        return true;
    }

    private static int actualTrackedCount(EntryDenialParticleFeedback feedback) {
        try {
            Field field = EntryDenialParticleFeedback.class.getDeclaredField("lastSent");
            field.setAccessible(true);
            return ((java.util.Map<?, ?>) field.get(feedback)).size();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void expiredEntriesAreSweptWithoutChangingThrottleWindow() {
        Captured captured = new Captured();
        World world = world(UUID.randomUUID());
        EntryDenialParticleFeedback feedback = new EntryDenialParticleFeedback(
                captured.clock, EntryDenialParticleFeedback.DEFAULT_COOLDOWN,
                PlayerScheduler.direct(), (player, location) -> captured.sends.incrementAndGet());
        feedback.show(player(UUID.randomUUID(), world), new Location(world, 5, 64, 5));
        feedback.show(player(UUID.randomUUID(), world), new Location(world, 5, 64, 5));
        assertEquals(2, actualTrackedCount(feedback));

        captured.clock.advance(EntryDenialParticleFeedback.DEFAULT_RETENTION.plusSeconds(1));
        feedback.show(player(UUID.randomUUID(), world), new Location(world, 5, 64, 5));

        assertEquals(1, actualTrackedCount(feedback));
    }

    @Test
    void trackedStateIsBounded() {
        Captured captured = new Captured();
        World world = world(UUID.randomUUID());
        EntryDenialParticleFeedback feedback = new EntryDenialParticleFeedback(
                captured.clock, EntryDenialParticleFeedback.DEFAULT_COOLDOWN,
                PlayerScheduler.direct(), (player, location) -> captured.sends.incrementAndGet());
        for (int i = 0; i <= EntryDenialParticleFeedback.MAX_ENTRIES; i++) {
            feedback.show(player(UUID.randomUUID(), world), new Location(world, 5, 64, 5));
        }

        assertTrue(actualTrackedCount(feedback) <= EntryDenialParticleFeedback.MAX_ENTRIES);
    }

    @Test
    void forgetRemovesOnlyTheTargetPlayerAndAllowsImmediateRetry() {
        Captured captured = new Captured();
        World world = world(UUID.randomUUID());
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Player first = player(firstId, world);
        Player second = player(secondId, world);
        EntryDenialParticleFeedback feedback = new EntryDenialParticleFeedback(
                captured.clock, EntryDenialParticleFeedback.DEFAULT_COOLDOWN,
                PlayerScheduler.direct(), (player, location) -> captured.sends.incrementAndGet());
        feedback.show(first, new Location(world, 5, 64, 5));
        feedback.show(second, new Location(world, 5, 64, 5));
        feedback.forget(firstId);
        feedback.show(first, new Location(world, 5, 64, 5));

        assertEquals(3, captured.sends.get());
        assertEquals(2, actualTrackedCount(feedback));
    }

    @Test
    void quitHandlerForgetsTheDepartingPlayer() throws Exception {
        Captured captured = new Captured();
        World world = world(UUID.randomUUID());
        UUID playerId = UUID.randomUUID();
        Player actor = player(playerId, world);
        EntryDenialParticleFeedback feedback = new EntryDenialParticleFeedback(
                captured.clock, EntryDenialParticleFeedback.DEFAULT_COOLDOWN,
                PlayerScheduler.direct(), (player, location) -> captured.sends.incrementAndGet());
        ProtectionListener listener = new ProtectionListener(
                engine(store(UUID.randomUUID()), false), null,
                adapter(world, captured.pushOuts), feedback);
        feedback.show(actor, new Location(world, 5, 64, 5));
        listener.getClass().getDeclaredMethod("onPlayerQuit", PlayerQuitEvent.class)
                .invoke(listener, new PlayerQuitEvent(actor, "test"));
        feedback.show(actor, new Location(world, 5, 64, 5));

        assertEquals(2, captured.sends.get());
    }

    @Test
    void deniedLocationInAnotherWorldDoesNotSendParticles() {
        Captured captured = new Captured();
        UUID playerWorldId = UUID.randomUUID();
        World playerWorld = world(playerWorldId);
        World destinationWorld = world(UUID.randomUUID());
        EntryDenialParticleFeedback feedback = new EntryDenialParticleFeedback(
                captured.clock, EntryDenialParticleFeedback.DEFAULT_COOLDOWN,
                PlayerScheduler.direct(), (player, location) -> captured.sends.incrementAndGet());

        feedback.show(player(UUID.randomUUID(), playerWorld),
                new Location(destinationWorld, 5, 64, 5));

        assertEquals(0, captured.sends.get());
    }

    @Test
    void deniedMoveShowsAtTheDeniedDestinationOnlyToTheActor() {
        Captured captured = new Captured();
        UUID worldId = UUID.randomUUID();
        World world = world(worldId);
        Player actor = player(UUID.randomUUID(), world);
        ProtectionListener listener = listener(captured, engine(store(worldId), false),
                adapter(world, captured.pushOuts));
        PlayerMoveEvent event = new PlayerMoveEvent(actor,
                new Location(world, 900, 64, 900), new Location(world, 5, 64, 5));

        listener.onPlayerMove(event);

        assertTrue(event.isCancelled());
        assertEquals(1, captured.sends.get());
        assertEquals(1, captured.pushOuts.get());
        assertSame(actor, captured.player.get());
        assertEquals(new Location(world, 5, 64, 5), captured.location.get());
    }

    @Test
    void deniedTeleportShowsAtTheDeniedDestination() {
        Captured captured = new Captured();
        UUID worldId = UUID.randomUUID();
        World world = world(worldId);
        Player actor = player(UUID.randomUUID(), world);
        ProtectionListener listener = listener(captured, engine(store(worldId), false),
                adapter(world, captured.pushOuts));
        PlayerTeleportEvent event = new PlayerTeleportEvent(actor,
                new Location(world, 900, 64, 900), new Location(world, 5, 64, 5),
                PlayerTeleportEvent.TeleportCause.PLUGIN);

        listener.onPlayerTeleport(event);

        assertTrue(event.isCancelled());
        assertEquals(1, captured.sends.get());
        assertEquals(1, captured.pushOuts.get());
        assertSame(actor, captured.player.get());
        assertEquals(new Location(world, 5, 64, 5), captured.location.get());
    }

    @Test
    void allowedEntryAndOtherDeniedActionsDoNotShowParticles() {
        Captured captured = new Captured();
        UUID worldId = UUID.randomUUID();
        World world = world(worldId);
        Player actor = player(UUID.randomUUID(), world);
        ProtectionListener listener = listener(captured, engine(store(worldId), true),
                adapter(world, captured.pushOuts));

        listener.onPlayerMove(new PlayerMoveEvent(actor,
                new Location(world, 900, 64, 900), new Location(world, 5, 64, 5)));
        BlockBreakEvent other = new BlockBreakEvent(block(world, 5, 64, 5), actor);
        listener.onBlockBreak(other);

        assertTrue(other.isCancelled());
        assertEquals(0, captured.sends.get());
    }

    @Test
    void bannedInsideDoesNotShowEntryParticles() {
        Captured captured = new Captured();
        UUID worldId = UUID.randomUUID();
        World world = world(worldId);
        Player actor = player(UUID.randomUUID(), world);
        EntryProtectionAdapter banned = new EntryProtectionAdapter(
                captured.clock, Duration.ofSeconds(3), (id, worldIdAt, x, z) -> Optional.of(true),
                (ignoredWorld, x, z) -> true, (id, at) -> true,
                (player, target) -> counted(captured.pushOuts));
        ProtectionListener listener = listener(captured, engine(store(worldId), false), banned);
        PlayerMoveEvent event = new PlayerMoveEvent(actor,
                new Location(world, 5, 64, 5), new Location(world, 6, 64, 5));

        listener.onPlayerMove(event);

        assertTrue(event.isCancelled());
        assertEquals(0, captured.sends.get());
        assertEquals(0, captured.pushOuts.get());
    }

    @Test
    void particleSchedulingFailureDoesNotChangeMoveEnforcement() {
        Captured captured = new Captured();
        UUID worldId = UUID.randomUUID();
        World world = world(worldId);
        Player actor = player(UUID.randomUUID(), world);
        EntryDenialParticleFeedback feedback = new EntryDenialParticleFeedback(
                captured.clock, EntryDenialParticleFeedback.DEFAULT_COOLDOWN,
                (player, task) -> { throw new IllegalStateException("scheduler failed"); },
                (player, location) -> captured.sends.incrementAndGet());
        ProtectionListener listener = new ProtectionListener(
                engine(store(worldId), false), null, adapter(world, captured.pushOuts), feedback);
        PlayerMoveEvent event = new PlayerMoveEvent(actor,
                new Location(world, 900, 64, 900), new Location(world, 5, 64, 5));

        listener.onPlayerMove(event);

        assertTrue(event.isCancelled());
        assertEquals(1, captured.pushOuts.get());
        assertEquals(0, captured.sends.get());
    }

    @Test
    void particleSenderFailureDoesNotChangeMoveEnforcement() {
        Captured captured = new Captured();
        UUID worldId = UUID.randomUUID();
        World world = world(worldId);
        Player actor = player(UUID.randomUUID(), world);
        EntryDenialParticleFeedback feedback = new EntryDenialParticleFeedback(
                captured.clock, EntryDenialParticleFeedback.DEFAULT_COOLDOWN,
                PlayerScheduler.direct(), (player, location) -> {
                    throw new IllegalStateException("sender failed");
                });
        ProtectionListener listener = new ProtectionListener(
                engine(store(worldId), false), null, adapter(world, captured.pushOuts), feedback);
        PlayerMoveEvent event = new PlayerMoveEvent(actor,
                new Location(world, 900, 64, 900), new Location(world, 5, 64, 5));

        listener.onPlayerMove(event);

        assertTrue(event.isCancelled());
        assertEquals(1, captured.pushOuts.get());
    }

    @Test
    void feedbackThrottlesPerPlayerAndReopensAfterWindow() {
        Captured captured = new Captured();
        World world = world(UUID.randomUUID());
        Player first = player(UUID.randomUUID(), world);
        Player second = player(UUID.randomUUID(), world);
        EntryDenialParticleFeedback feedback = new EntryDenialParticleFeedback(
                captured.clock, Duration.ofSeconds(2), PlayerScheduler.direct(),
                (player, location) -> captured.sends.incrementAndGet());

        feedback.show(first, new Location(world, 5, 64, 5));
        feedback.show(first, new Location(world, 6, 64, 5));
        feedback.show(second, new Location(world, 5, 64, 5));
        assertEquals(2, captured.sends.get());
        captured.clock.advance(Duration.ofSeconds(2));
        feedback.show(first, new Location(world, 5, 64, 5));
        assertEquals(3, captured.sends.get());
    }

    @Test
    void senderAndSchedulerFailuresStaySilent() {
        Captured captured = new Captured();
        World world = world(UUID.randomUUID());
        Player actor = player(UUID.randomUUID(), world);
        EntryDenialParticleFeedback senderFailure = new EntryDenialParticleFeedback(
                captured.clock, EntryDenialParticleFeedback.DEFAULT_COOLDOWN,
                PlayerScheduler.direct(), (player, location) -> {
                    throw new IllegalStateException("sender failed");
                });
        assertDoesNotThrow(() -> senderFailure.show(actor, new Location(world, 5, 64, 5)));

        EntryDenialParticleFeedback schedulerFailure = new EntryDenialParticleFeedback(
                captured.clock, EntryDenialParticleFeedback.DEFAULT_COOLDOWN,
                (player, task) -> { throw new IllegalStateException("scheduler failed"); },
                (player, location) -> captured.sends.incrementAndGet());
        assertDoesNotThrow(() -> schedulerFailure.show(actor, new Location(world, 5, 64, 5)));

        assertDoesNotThrow(() -> senderFailure.show(actor, null));
        assertEquals(0, captured.sends.get());
    }
}

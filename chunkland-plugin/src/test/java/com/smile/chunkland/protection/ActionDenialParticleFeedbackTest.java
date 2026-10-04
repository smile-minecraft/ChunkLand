package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.selection.SelectionClock;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;

class ActionDenialParticleFeedbackTest {

    private static final Instant T0 = Instant.parse("2026-10-04T00:00:00Z");

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

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        return null;
    }

    private static World world(UUID worldId) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> worldId;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeWorld";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Player player(UUID id, World world) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getWorld" -> world;
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

    private static ProtectionEngine denyingEngine(UUID worldId) {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(new LandSnapshot(
                new LandId(UUID.randomUUID()), "TestLand", "testland",
                OwnerRef.player(UUID.randomUUID()), worldId,
                Set.of(new ChunkKey(worldId, 0, 0)), List.of(), 0, 0, T0, T0))));
        return new ProtectionEngine(store::snapshot, (actor, landId, action, snapshot) ->
                new PermissionContext(action, false, List.of(), PermissionState.DENY,
                        PermissionState.INHERIT));
    }

    @Test
    void deniedBlockBreakMarksExactlyThatBlockForThePlayer() {
        UUID worldId = UUID.randomUUID();
        World world = world(worldId);
        Player actor = player(UUID.randomUUID(), world);
        List<ActionDenialParticleFeedback.Mark> marks = new CopyOnWriteArrayList<>();
        ActionDenialParticleFeedback feedback = new ActionDenialParticleFeedback(
                new FakeClock(), ActionDenialParticleFeedback.DEFAULT_COOLDOWN,
                PlayerScheduler.direct(), (p, mark) -> marks.add(mark));
        ProtectionListener listener = new ProtectionListener(denyingEngine(worldId), null,
                null, null, feedback);
        BlockBreakEvent event = new BlockBreakEvent(block(world, 5, 64, 7), actor);

        listener.onBlockBreak(event);

        assertTrue(event.isCancelled());
        assertEquals(List.of(new ActionDenialParticleFeedback.Mark(worldId, 5, 64, 7, true)),
                marks);
    }

    @Test
    void allowedBlockBreakLeavesNoMark() {
        UUID worldId = UUID.randomUUID();
        World world = world(worldId);
        Player actor = player(UUID.randomUUID(), world);
        List<ActionDenialParticleFeedback.Mark> marks = new CopyOnWriteArrayList<>();
        ActionDenialParticleFeedback feedback = new ActionDenialParticleFeedback(
                new FakeClock(), ActionDenialParticleFeedback.DEFAULT_COOLDOWN,
                PlayerScheduler.direct(), (p, mark) -> marks.add(mark));
        ProtectionListener listener = new ProtectionListener(denyingEngine(worldId), null,
                null, null, feedback);
        // Chunk (5, 5) is wilderness.
        BlockBreakEvent event = new BlockBreakEvent(block(world, 85, 64, 85), actor);

        listener.onBlockBreak(event);

        assertFalse(event.isCancelled());
        assertTrue(marks.isEmpty());
    }

    @Test
    void marksAreThrottledPerPlayerAndReopenAfterTheWindow() {
        FakeClock clock = new FakeClock();
        UUID worldId = UUID.randomUUID();
        World world = world(worldId);
        Player first = player(UUID.randomUUID(), world);
        Player second = player(UUID.randomUUID(), world);
        List<ActionDenialParticleFeedback.Mark> marks = new CopyOnWriteArrayList<>();
        ActionDenialParticleFeedback feedback = new ActionDenialParticleFeedback(
                clock, Duration.ofMillis(400), PlayerScheduler.direct(),
                (p, mark) -> marks.add(mark));

        feedback.showBlock(first, worldId, 1, 64, 1);
        feedback.showBlock(first, worldId, 2, 64, 2);
        feedback.showSpot(second, worldId, 1.5, 64.0, 1.5);
        assertEquals(2, marks.size(), "one mark per player inside the window");
        assertFalse(marks.get(1).block(), "an entity position is a spot, not a block cell");

        clock.advance(Duration.ofMillis(400));
        feedback.showBlock(first, worldId, 2, 64, 2);
        assertEquals(3, marks.size());

        feedback.forget(first.getUniqueId());
        feedback.showBlock(first, worldId, 3, 64, 3);
        assertEquals(4, marks.size(), "a forgotten player starts a fresh window");
    }

    @Test
    void senderAndSchedulerFailuresNeverReachEnforcement() {
        UUID worldId = UUID.randomUUID();
        World world = world(worldId);
        Player actor = player(UUID.randomUUID(), world);
        ActionDenialParticleFeedback senderFailure = new ActionDenialParticleFeedback(
                new FakeClock(), ActionDenialParticleFeedback.DEFAULT_COOLDOWN,
                PlayerScheduler.direct(), (p, mark) -> {
                    throw new IllegalStateException("sender failed");
                });
        ActionDenialParticleFeedback schedulerFailure = new ActionDenialParticleFeedback(
                new FakeClock(), ActionDenialParticleFeedback.DEFAULT_COOLDOWN,
                (p, task) -> {
                    throw new IllegalStateException("scheduler failed");
                }, (p, mark) -> { });
        for (ActionDenialParticleFeedback feedback : List.of(senderFailure, schedulerFailure)) {
            ProtectionListener listener = new ProtectionListener(denyingEngine(worldId), null,
                    null, null, feedback);
            BlockBreakEvent event = new BlockBreakEvent(block(world, 5, 64, 7), actor);
            assertDoesNotThrow(() -> listener.onBlockBreak(event));
            assertTrue(event.isCancelled(), "a feedback failure must not lift the deny");
        }
        assertDoesNotThrow(() -> senderFailure.showBlock(null, worldId, 0, 0, 0));
        assertDoesNotThrow(() -> senderFailure.showBlock(actor, null, 0, 0, 0));
    }
}

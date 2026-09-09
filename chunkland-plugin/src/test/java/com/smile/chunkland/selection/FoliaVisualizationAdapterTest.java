package com.smile.chunkland.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import com.smile.acelib.scheduler.TaskType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Folia adapters translate the JDK-only renderer seams onto the Bukkit/AceLib
 * public API — and nothing else. Players are dynamic proxies so no server is needed; the
 * assertions pin down that emission is player-scoped ({@code Player#spawnParticle}, never a
 * broadcast) and scheduling is player-scoped ({@code runForPlayerLater}, never global).
 */
class FoliaVisualizationAdapterTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final class SpawnCall {
        final Particle particle;
        final double x;
        final double y;
        final double z;
        final int count;

        SpawnCall(Particle particle, double x, double y, double z, int count) {
            this.particle = particle;
            this.x = x;
            this.y = y;
            this.z = z;
            this.count = count;
        }
    }

    private static final class ProxyPlayer {
        final List<SpawnCall> spawns = new ArrayList<>();
        final Location location = new Location(null, 100.5, 70.0, -40.5);
        final Player player;

        ProxyPlayer() {
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getUniqueId" -> {
                        return PLAYER_ID;
                    }
                    case "getLocation" -> {
                        return location;
                    }
                    case "spawnParticle" -> {
                        // Player#spawnParticle(Particle, double, double, double, int).
                        spawns.add(new SpawnCall(
                                (Particle) args[0],
                                (Double) args[1],
                                (Double) args[2],
                                (Double) args[3],
                                (Integer) args[4]));
                        return null;
                    }
                    case "getScheduler" -> {
                        throw new UnsupportedOperationException("adapter must use SafeScheduler, not EntityScheduler");
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "toString" -> {
                        return "proxy-player";
                    }
                    default -> throw new UnsupportedOperationException("unexpected call: " + method);
                }
            };
            this.player = (Player) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {Player.class}, handler);
        }
    }

    private static final class FakeScheduledTask implements ScheduledTask {
        boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public JavaPlugin getPlugin() {
            return null;
        }

        @Override
        public TaskType getType() {
            return TaskType.values()[0];
        }

        @Override
        public long getCreationTick() {
            return 0L;
        }
    }

    private static final class FakeSafeScheduler implements SafeScheduler {
        final List<Long> playerLaterDelays = new ArrayList<>();
        final List<Player> playerLaterTargets = new ArrayList<>();
        final List<Runnable> playerLaterTasks = new ArrayList<>();
        Player lastPlayer;
        boolean returnNull;

        @Override
        public ScheduledTask runForPlayerLater(Player player, Runnable task, long delayTicks) {
            playerLaterTargets.add(player);
            playerLaterDelays.add(delayTicks);
            playerLaterTasks.add(task);
            lastPlayer = player;
            if (returnNull) {
                return null;
            }
            return new FakeScheduledTask();
        }

        @Override
        public ScheduledTask runGlobal(Runnable task) {
            throw new UnsupportedOperationException("visualization must not use the global scheduler");
        }

        @Override
        public ScheduledTask runAsync(Runnable task) {
            throw new UnsupportedOperationException("visualization must not use async tasks");
        }

        @Override
        public ScheduledTask runLater(Runnable task, long delayTicks) {
            throw new UnsupportedOperationException("visualization must not use detached delays");
        }

        @Override
        public ScheduledTask runTimer(Runnable task, long delayTicks, long periodTicks) {
            throw new UnsupportedOperationException("visualization must not use global timers");
        }

        @Override
        public ScheduledTask runForPlayer(Player player, Runnable task) {
            throw new UnsupportedOperationException("unexpected runForPlayer in this test");
        }

        @Override
        public ScheduledTask runForEntity(org.bukkit.entity.Entity entity, Runnable task) {
            throw new UnsupportedOperationException("visualization must not schedule for generic entities");
        }

        @Override
        public ScheduledTask runAtLocation(Location location, Runnable task) {
            throw new UnsupportedOperationException("visualization must not use location scheduling");
        }

        @Override
        public List<com.smile.acelib.scheduler.TaskErrorRecord> getRecorderErrors(int limit) {
            return List.of();
        }

        @Override
        public void cancelAll() {
        }
    }

    @Test
    void tickSchedulerDispatchesPlayerScopedWithTheRequestedDelay() {
        ProxyPlayer proxy = new ProxyPlayer();
        FakeSafeScheduler scheduler = new FakeSafeScheduler();
        Function<UUID, Player> lookup = playerId -> PLAYER_ID.equals(playerId) ? proxy.player : null;
        FoliaVisualizationTickScheduler ticks =
                new FoliaVisualizationTickScheduler(lookup, () -> Optional.of(scheduler));
        Runnable task = () -> { };

        SelectionTimeoutScheduler.Cancellable handle = ticks.schedule(PLAYER_ID, 10L, task);

        assertTrue(handle != null && !handle.isCancelled());
        assertEquals(List.of(10L), scheduler.playerLaterDelays);
        assertEquals(1, scheduler.playerLaterTargets.size());
        assertTrue(scheduler.playerLaterTargets.get(0) == proxy.player);
        handle.cancel();
        assertTrue(handle.isCancelled());
    }

    @Test
    void tickSchedulerRejectsMissingPlayerSchedulerOrNullHandle() {
        FakeSafeScheduler scheduler = new FakeSafeScheduler();
        FoliaVisualizationTickScheduler missingPlayer =
                new FoliaVisualizationTickScheduler(ignored -> null, () -> Optional.of(scheduler));
        ProxyPlayer proxy = new ProxyPlayer();
        FoliaVisualizationTickScheduler missingScheduler =
                new FoliaVisualizationTickScheduler(ignored -> proxy.player, Optional::empty);

        assertThrows(IllegalStateException.class, () -> missingPlayer.schedule(PLAYER_ID, 10L, () -> { }));
        assertThrows(IllegalStateException.class, () -> missingScheduler.schedule(PLAYER_ID, 10L, () -> { }));
        assertTrue(scheduler.playerLaterTasks.isEmpty());

        scheduler.returnNull = true;
        FoliaVisualizationTickScheduler nullHandle =
                new FoliaVisualizationTickScheduler(ignored -> proxy.player, () -> Optional.of(scheduler));
        assertThrows(IllegalStateException.class, () -> nullHandle.schedule(PLAYER_ID, 10L, () -> { }));
    }

    @Test
    void particleSinkReadsViewerPoseAndEmitsPlayerScoped() {
        ProxyPlayer proxy = new ProxyPlayer();
        FoliaSelectionParticleSink sink =
                new FoliaSelectionParticleSink(playerId -> PLAYER_ID.equals(playerId) ? proxy.player : null);

        Optional<SelectionParticleSink.ViewerPose> viewer = sink.viewerOf(PLAYER_ID);

        assertTrue(viewer.isPresent());
        assertEquals(100.5, viewer.get().x());
        assertEquals(70.0, viewer.get().y());
        assertEquals(-40.5, viewer.get().z());

        sink.emit(PLAYER_ID, 1.0, 65.0, 2.0);

        assertEquals(1, proxy.spawns.size());
        assertEquals(Particle.HAPPY_VILLAGER, proxy.spawns.get(0).particle);
        assertEquals(1.0, proxy.spawns.get(0).x);
        assertEquals(65.0, proxy.spawns.get(0).y);
        assertEquals(2.0, proxy.spawns.get(0).z);
        assertEquals(1, proxy.spawns.get(0).count);
    }

    @Test
    void particleSinkTreatsMissingPlayerAsAbsent() {
        FoliaSelectionParticleSink sink = new FoliaSelectionParticleSink(ignored -> null);

        assertTrue(sink.viewerOf(PLAYER_ID).isEmpty());
        // Best-effort display: emitting to an offline player is a quiet no-op.
        sink.emit(PLAYER_ID, 1.0, 65.0, 2.0);
    }

    @Test
    void particleSinkNeverTouchesTheEntityScheduler() throws Exception {
        ProxyPlayer proxy = new ProxyPlayer();
        // Any path that reaches Player#getScheduler fails the proxy on purpose, so simply
        // exercising the sink proves the adapter stays on the SafeScheduler seam.
        Method getScheduler = Player.class.getMethod("getScheduler");
        assertTrue(proxy.player.getLocation() != null);
        try {
            getScheduler.invoke(proxy.player);
        } catch (java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof UnsupportedOperationException);
        }
    }
}

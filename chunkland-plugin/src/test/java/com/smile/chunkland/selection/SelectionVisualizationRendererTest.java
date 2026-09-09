package com.smile.chunkland.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SelectionVisualizationRendererTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static SelectionSession sessionWithChunks(Set<ChunkKey> chunks) {
        return new SelectionSession(
                PLAYER,
                WORLD,
                SelectionMode.CREATE_LAND,
                Optional.empty(),
                Optional.empty(),
                Optional.of(new SelectionPoint(WORLD, 0, 64, 0)),
                Optional.of(new SelectionPoint(WORLD, 15, 64, 15)),
                chunks,
                Map.of(),
                0,
                0,
                0,
                NOW,
                NOW);
    }

    private static final class Fixture {
        final FakeTickScheduler ticks = new FakeTickScheduler();
        final FakeSink sink = new FakeSink();
        final SelectionVisualizationRenderer renderer;

        Fixture() {
            sink.viewer = new SelectionParticleSink.ViewerPose(8.0, 70.0, 8.0);
            renderer = new SelectionVisualizationRenderer(
                    ticks, sink, SelectionVisualizationBudget::defaults);
        }
    }

    private static final class FakeTickScheduler implements VisualizationTickScheduler {
        final List<Long> delays = new ArrayList<>();
        final List<FakeHandle> handles = new ArrayList<>();
        boolean throwOnSchedule;

        @Override
        public SelectionTimeoutScheduler.Cancellable schedule(UUID playerId, long delayTicks, Runnable task) {
            if (throwOnSchedule) {
                throw new IllegalStateException("schedule failed");
            }
            delays.add(delayTicks);
            FakeHandle handle = new FakeHandle(task);
            handles.add(handle);
            return handle;
        }

        FakeHandle latest() {
            return handles.get(handles.size() - 1);
        }

        int activeCount() {
            return (int) handles.stream().filter(handle -> !handle.cancelled).count();
        }
    }

    private static final class FakeHandle implements SelectionTimeoutScheduler.Cancellable {
        private final Runnable task;
        boolean cancelled;

        FakeHandle(Runnable task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        void fire() {
            task.run();
        }
    }

    private static final class FakeSink implements SelectionParticleSink {
        ViewerPose viewer;
        final List<double[]> emitted = new ArrayList<>();
        boolean throwOnEmit;

        @Override
        public Optional<ViewerPose> viewerOf(UUID playerId) {
            return Optional.ofNullable(viewer);
        }

        @Override
        public void emit(UUID playerId, double x, double y, double z) {
            if (throwOnEmit) {
                throw new IllegalStateException("emit failed");
            }
            emitted.add(new double[] {x, y, z});
        }
    }

    @Test
    void startSchedulesPlayerScopedLoopWithBudgetInterval() {
        Fixture fixture = new Fixture();

        fixture.renderer.start(sessionWithChunks(Set.of(new ChunkKey(WORLD, 0, 0))));

        assertTrue(fixture.renderer.isActive(PLAYER));
        assertEquals(1, fixture.ticks.handles.size());
        assertEquals(List.of(10L), fixture.ticks.delays);
    }

    @Test
    void tickEmitsOnlyTheOperatingPlayerPointsWithinBudget() {
        Fixture fixture = new Fixture();
        fixture.renderer.start(sessionWithChunks(Set.of(new ChunkKey(WORLD, 0, 0))));

        fixture.ticks.latest().fire();

        // One chunk renders 16 samples; the default per-tick budget caps emission at 128,
        // so a single chunk fits in one tick and every point goes to the same player.
        assertEquals(16, fixture.sink.emitted.size());
        for (double[] point : fixture.sink.emitted) {
            assertEquals(65.0, point[1]);
        }
        // The loop reschedules itself for the next refresh interval.
        assertEquals(2, fixture.ticks.handles.size());
        assertTrue(fixture.renderer.isActive(PLAYER));
    }

    @Test
    void tickWindowRotatesAcrossTicks() {
        Fixture fixture = new Fixture();
        SelectionVisualizationRenderer smallBudget = new SelectionVisualizationRenderer(
                fixture.ticks, fixture.sink, () -> new SelectionVisualizationBudget(256, 6, 48, 10));
        smallBudget.start(sessionWithChunks(Set.of(new ChunkKey(WORLD, 0, 0))));

        fixture.ticks.latest().fire();
        List<double[]> firstTick = List.copyOf(fixture.sink.emitted);
        fixture.sink.emitted.clear();
        fixture.ticks.latest().fire();
        List<double[]> secondTick = List.copyOf(fixture.sink.emitted);

        assertEquals(6, firstTick.size());
        assertEquals(6, secondTick.size());
        assertFalse(firstTick.toString().equals(secondTick.toString()));
    }

    @Test
    void restartReplacesThePreviousTaskWithoutOrphans() {
        Fixture fixture = new Fixture();
        fixture.renderer.start(sessionWithChunks(Set.of(new ChunkKey(WORLD, 0, 0))));
        FakeHandle first = fixture.ticks.latest();

        fixture.renderer.refresh(sessionWithChunks(Set.of(new ChunkKey(WORLD, 1, 1))));

        assertTrue(first.cancelled);
        assertEquals(1, fixture.ticks.activeCount());
        assertTrue(fixture.renderer.isActive(PLAYER));
        fixture.ticks.latest().fire();
        // The refreshed session renders the new chunk: x coordinates live in [16, 32].
        assertFalse(fixture.sink.emitted.isEmpty());
        for (double[] point : fixture.sink.emitted) {
            assertTrue(point[0] >= 16.0 && point[0] <= 32.0, "x near chunk 1: " + point[0]);
        }
    }

    @Test
    void staleTickAfterStopEmitsNothingAndDoesNotReschedule() {
        Fixture fixture = new Fixture();
        fixture.renderer.start(sessionWithChunks(Set.of(new ChunkKey(WORLD, 0, 0))));
        FakeHandle handle = fixture.ticks.latest();
        fixture.renderer.stop(PLAYER);
        int scheduled = fixture.ticks.handles.size();

        handle.fire();

        assertTrue(fixture.sink.emitted.isEmpty());
        assertEquals(scheduled, fixture.ticks.handles.size());
        assertFalse(fixture.renderer.isActive(PLAYER));
    }

    @Test
    void stopIsIdempotent() {
        Fixture fixture = new Fixture();
        fixture.renderer.start(sessionWithChunks(Set.of(new ChunkKey(WORLD, 0, 0))));

        fixture.renderer.stop(PLAYER);
        fixture.renderer.stop(PLAYER);

        assertFalse(fixture.renderer.isActive(PLAYER));
        assertEquals(0, fixture.ticks.activeCount());
    }

    @Test
    void missingViewerStopsTheLoopWithoutRescheduling() {
        Fixture fixture = new Fixture();
        fixture.renderer.start(sessionWithChunks(Set.of(new ChunkKey(WORLD, 0, 0))));
        fixture.sink.viewer = null;
        int scheduled = fixture.ticks.handles.size();

        fixture.ticks.latest().fire();

        assertTrue(fixture.sink.emitted.isEmpty());
        assertEquals(scheduled, fixture.ticks.handles.size());
        assertFalse(fixture.renderer.isActive(PLAYER));
    }

    @Test
    void emitFailureStopsTheLoopFailClosed() {
        Fixture fixture = new Fixture();
        fixture.renderer.start(sessionWithChunks(Set.of(new ChunkKey(WORLD, 0, 0))));
        fixture.sink.throwOnEmit = true;
        int scheduled = fixture.ticks.handles.size();

        fixture.ticks.latest().fire();

        assertEquals(scheduled, fixture.ticks.handles.size());
        assertFalse(fixture.renderer.isActive(PLAYER));
    }

    @Test
    void scheduleFailureRemovesTheEntryAndPropagates() {
        Fixture fixture = new Fixture();
        fixture.ticks.throwOnSchedule = true;

        assertThrows(IllegalStateException.class,
                () -> fixture.renderer.start(sessionWithChunks(Set.of(new ChunkKey(WORLD, 0, 0)))));

        assertFalse(fixture.renderer.isActive(PLAYER));
    }

    @Test
    void defaultControllerIsDormant() {
        SelectionVisualizationTaskController noop = SelectionVisualizationTaskController.noop();

        // The stop seam never throws, even for unknown players; start/refresh/isActive
        // stay dormant so managers can call them unconditionally.
        noop.stop(PLAYER);
        noop.start(sessionWithChunks(Set.of(new ChunkKey(WORLD, 0, 0))));
        noop.refresh(sessionWithChunks(Set.of(new ChunkKey(WORLD, 0, 0))));
        assertFalse(noop.isActive(PLAYER));
    }

    // ---------- Manager lifecycle integration ----------

    private static final class RecordingVisualization implements SelectionVisualizationTaskController {
        final List<String> events = new ArrayList<>();
        final Map<UUID, SelectionSession> started = new HashMap<>();
        boolean throwOnStart;

        @Override
        public void start(SelectionSession session) {
            if (throwOnStart) {
                throw new IllegalStateException("visualization start failed");
            }
            events.add("visual.start");
            started.put(session.playerId(), session);
        }

        @Override
        public void refresh(SelectionSession session) {
            events.add("visual.refresh");
            started.put(session.playerId(), session);
        }

        @Override
        public void stop(UUID playerId) {
            events.add("visual.stop");
            started.remove(playerId);
        }
    }

    private static final class LifecycleFixture {
        final FakeScheduler scheduler = new FakeScheduler();
        final RecordingVisualization visualization = new RecordingVisualization();
        final List<SelectionNotification> notifications = new ArrayList<>();
        Instant now = NOW;
        final SelectionSessionManager manager = new SelectionSessionManager(
                scheduler,
                visualization,
                notifications::add,
                () -> now,
                Duration.ofMinutes(10),
                ignored -> Optional.of("world"),
                SelectionStructureRevisionLookup.unavailable());

        SelectionSession session() {
            return SelectionSession.initial(
                    PLAYER,
                    WORLD,
                    SelectionMode.CREATE_LAND,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new SelectionPoint(WORLD, 0, 64, 0)),
                    Optional.of(new SelectionPoint(WORLD, 15, 64, 15)),
                    0,
                    now);
        }
    }

    private static final class FakeScheduler implements SelectionTimeoutScheduler {
        final List<FakeTimeoutHandle> handles = new ArrayList<>();

        @Override
        public Cancellable schedule(UUID playerId, Duration delay, Runnable task) {
            FakeTimeoutHandle handle = new FakeTimeoutHandle(task);
            handles.add(handle);
            return handle;
        }

        void fireLatest() {
            handles.get(handles.size() - 1).task.run();
        }
    }

    private static final class FakeTimeoutHandle implements SelectionTimeoutScheduler.Cancellable {
        private final Runnable task;
        boolean cancelled;

        FakeTimeoutHandle(Runnable task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    @Test
    void managerStartsVisualizationOnSessionStartAndRefreshesOnUpdate() {
        LifecycleFixture fixture = new LifecycleFixture();

        SelectionSession stamped = fixture.manager.start(fixture.session());
        assertEquals(List.of("visual.start"), fixture.visualization.events);
        assertEquals(stamped, fixture.visualization.started.get(PLAYER));

        SelectionSession updated = fixture.manager.updateSelection(
                PLAYER,
                stamped,
                new SelectionUpdate(
                        stamped.pointA(), stamped.pointB(), Set.of(new ChunkKey(WORLD, 2, 3)), Map.of()))
                .orElseThrow();

        assertEquals(List.of("visual.start", "visual.refresh"), fixture.visualization.events);
        assertEquals(updated, fixture.visualization.started.get(PLAYER));
    }

    @Test
    void managerStopsVisualizationOnEveryCleanupPath() {
        for (String path : List.of("cancel", "quit", "world-change", "timeout", "disable", "replace")) {
            LifecycleFixture fixture = new LifecycleFixture();
            SelectionSession stamped = fixture.manager.start(fixture.session());
            fixture.visualization.events.clear();

            switch (path) {
                case "cancel" -> fixture.manager.cancel(PLAYER);
                case "quit" -> fixture.manager.onPlayerQuit(PLAYER);
                case "world-change" -> fixture.manager.onWorldChange(PLAYER);
                case "timeout" -> fixture.scheduler.fireLatest();
                case "disable" -> fixture.manager.disable();
                case "replace" -> {
                    SelectionSession replacement = fixture.manager.start(fixture.session());
                    assertEquals(stamped.sessionGeneration() + 1, replacement.sessionGeneration());
                }
                default -> throw new AssertionError(path);
            }

            assertTrue(fixture.manager.sessionFor(PLAYER).isEmpty()
                    || path.equals("replace") && fixture.manager.sessionFor(PLAYER).isPresent(), path);
            assertTrue(fixture.visualization.events.contains("visual.stop"), path);
            if (path.equals("replace")) {
                assertEquals(List.of("visual.stop", "visual.start"), fixture.visualization.events, path);
            } else {
                assertEquals(List.of("visual.stop"), fixture.visualization.events, path);
            }
        }
    }

    @Test
    void visualizationStartFailureKeepsTheSessionButStopsTheRender() {
        LifecycleFixture fixture = new LifecycleFixture();
        fixture.visualization.throwOnStart = true;

        SelectionSession stamped = fixture.manager.start(fixture.session());

        // Display is best-effort: selection data wins, the broken render is stopped.
        assertEquals(stamped, fixture.manager.sessionFor(PLAYER).orElseThrow());
        assertEquals(List.of("visual.stop"), fixture.visualization.events);
    }

    @Test
    void visualizationUsesLiveBudgetPerTick() {
        Fixture fixture = new Fixture();
        List<SelectionVisualizationBudget> budgets = new ArrayList<>(
                List.of(new SelectionVisualizationBudget(256, 6, 48, 10)));
        Supplier<SelectionVisualizationBudget> live = () -> budgets.get(budgets.size() - 1);
        SelectionVisualizationRenderer renderer =
                new SelectionVisualizationRenderer(fixture.ticks, fixture.sink, live);
        renderer.start(sessionWithChunks(Set.of(new ChunkKey(WORLD, 0, 0))));

        fixture.ticks.latest().fire();
        assertEquals(6, fixture.sink.emitted.size());

        // A reload that raises the per-tick budget applies to the running loop.
        fixture.sink.emitted.clear();
        budgets.add(new SelectionVisualizationBudget(256, 128, 48, 10));
        fixture.ticks.latest().fire();
        assertEquals(16, fixture.sink.emitted.size());
    }
}

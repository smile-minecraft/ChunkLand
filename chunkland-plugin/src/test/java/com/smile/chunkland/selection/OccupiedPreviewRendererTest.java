package com.smile.chunkland.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The occupied-land preview is a separate player-scoped loop that renders an
 * explicit immutable chunk set with its own sink, so it never replaces the
 * active selection renderer and never touches selection state.
 */
class OccupiedPreviewRendererTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000010");

    private static final class FakeTickScheduler implements VisualizationTickScheduler {
        final List<Long> delays = new ArrayList<>();
        final List<FakeHandle> handles = new ArrayList<>();

        @Override
        public SelectionTimeoutScheduler.Cancellable schedule(UUID playerId, long delayTicks, Runnable task) {
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
        ViewerPose viewer = new ViewerPose(8.0, 70.0, 8.0);
        final List<double[]> emitted = new ArrayList<>();

        @Override
        public Optional<ViewerPose> viewerOf(UUID playerId) {
            return Optional.ofNullable(viewer);
        }

        @Override
        public void emit(UUID playerId, double x, double y, double z) {
            emitted.add(new double[] {x, y, z});
        }
    }

    private static final class Fixture {
        final FakeTickScheduler ticks = new FakeTickScheduler();
        final FakeSink sink = new FakeSink();
        final OccupiedPreviewRenderer renderer =
                new OccupiedPreviewRenderer(ticks, sink, SelectionVisualizationBudget::defaults);
    }

    @Test
    void showSchedulesPlayerScopedLoopAtTheBudgetInterval() {
        Fixture fixture = new Fixture();

        fixture.renderer.show(PLAYER, Set.of(new ChunkKey(WORLD, 0, 0)), 66.0);

        assertTrue(fixture.renderer.isActive(PLAYER));
        assertEquals(List.of(10L), fixture.ticks.delays);
    }

    @Test
    void tickOutlinesTheActualChunkSetOnTheGivenPlane() {
        Fixture fixture = new Fixture();
        // An irregular land: two diagonal chunks outline four corners, not a solid square.
        fixture.renderer.show(PLAYER, Set.of(new ChunkKey(WORLD, 0, 0), new ChunkKey(WORLD, 2, 1)), 66.0);

        fixture.ticks.latest().fire();

        assertFalse(fixture.sink.emitted.isEmpty());
        for (double[] point : fixture.sink.emitted) {
            assertTrue(point[1] >= 64.0 && point[1] <= 68.0,
                    "the preview column stays around the requested plane: " + point[1]);
        }
        // The shared geometry path expands each sample to base-2..base+2 (base 66.0 here).
        assertTrue(fixture.sink.emitted.stream().anyMatch(point -> point[1] == 64.0),
                "column reaches base-2");
        assertTrue(fixture.sink.emitted.stream().anyMatch(point -> point[1] == 68.0),
                "column reaches base+2");
        // The outline reaches the diagonal chunk's far corner (chunk 2 -> x in [32, 48]).
        assertTrue(fixture.sink.emitted.stream().anyMatch(point -> point[0] >= 32.0 && point[0] <= 48.0));
        assertEquals(2, fixture.ticks.handles.size(), "the loop reschedules itself");
    }

    @Test
    void showReplacesThePreviousLoopWithoutOrphans() {
        Fixture fixture = new Fixture();
        fixture.renderer.show(PLAYER, Set.of(new ChunkKey(WORLD, 0, 0)), 66.0);
        FakeHandle first = fixture.ticks.latest();

        fixture.renderer.show(PLAYER, Set.of(new ChunkKey(WORLD, 1, 1)), 67.0);

        assertTrue(first.cancelled);
        assertEquals(1, fixture.ticks.activeCount());
        assertTrue(fixture.renderer.isActive(PLAYER));
    }

    @Test
    void showWithEmptyChunksStopsInsteadOfRenderingNothing() {
        Fixture fixture = new Fixture();
        fixture.renderer.show(PLAYER, Set.of(new ChunkKey(WORLD, 0, 0)), 66.0);

        fixture.renderer.show(PLAYER, Set.of(), 66.0);

        assertFalse(fixture.renderer.isActive(PLAYER));
        assertEquals(0, fixture.ticks.activeCount());
    }

    @Test
    void stopCancelsTheLoopAndStaleTickEmitsNothing() {
        Fixture fixture = new Fixture();
        fixture.renderer.show(PLAYER, Set.of(new ChunkKey(WORLD, 0, 0)), 66.0);
        FakeHandle handle = fixture.ticks.latest();
        int scheduled = fixture.ticks.handles.size();

        fixture.renderer.stop(PLAYER);
        handle.fire();

        assertTrue(fixture.sink.emitted.isEmpty());
        assertEquals(scheduled, fixture.ticks.handles.size());
        assertFalse(fixture.renderer.isActive(PLAYER));
    }

    @Test
    void stopAllClearsEveryPlayerLoop() {
        Fixture fixture = new Fixture();
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        fixture.renderer.show(PLAYER, Set.of(new ChunkKey(WORLD, 0, 0)), 66.0);
        fixture.renderer.show(other, Set.of(new ChunkKey(WORLD, 1, 1)), 66.0);

        fixture.renderer.stopAll();

        assertFalse(fixture.renderer.isActive(PLAYER));
        assertFalse(fixture.renderer.isActive(other));
        assertEquals(0, fixture.ticks.activeCount());
    }
}

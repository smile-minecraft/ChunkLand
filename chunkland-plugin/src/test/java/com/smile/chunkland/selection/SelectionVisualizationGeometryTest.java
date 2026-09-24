package com.smile.chunkland.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SelectionVisualizationGeometryTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static final SelectionVisualizationBudget GENEROUS =
            new SelectionVisualizationBudget(256, 128, 48, 10);

    private static SelectionSession sessionWith(Set<ChunkKey> chunks, Optional<SelectionPoint> a, Optional<SelectionPoint> b) {
        return new SelectionSession(
                PLAYER,
                WORLD,
                SelectionMode.CREATE_LAND,
                Optional.empty(),
                Optional.empty(),
                a,
                b,
                chunks,
                Map.of(),
                0,
                0,
                0,
                NOW,
                NOW);
    }

    private static SelectionSession singleChunk(int y) {
        return sessionWith(
                Set.of(new ChunkKey(WORLD, 0, 0)),
                Optional.of(new SelectionPoint(WORLD, 0, y, 0)),
                Optional.of(new SelectionPoint(WORLD, 15, y, 15)));
    }

    @Test
    void singleChunkYieldsFourSegmentsAndFivePointColumnsPerSample() {
        SelectionVisualizationGeometry.Frame frame =
                SelectionVisualizationGeometry.plan(singleChunk(64), GENEROUS, 8.0, 70.0, 8.0);

        assertEquals(4, frame.segmentCount());
        assertEquals(false, frame.truncated());
        assertEquals(160, frame.points().size(), "eight samples on each of four edges, five points per column");
        // Base is 68.0 (point+1 clamped into the viewer band); each sample expands to base-2..base+2.
        for (int index = 0; index < frame.points().size(); index += 5) {
            SelectionVisualizationGeometry.Point base = frame.points().get(index + 2);
            double x = base.x();
            double z = base.z();
            assertEquals(68.0, base.y(), "column centre is the unchanged base plane");
            for (int dy = -2; dy <= 2; dy++) {
                SelectionVisualizationGeometry.Point columnPoint = frame.points().get(index + dy + 2);
                assertEquals(x, columnPoint.x(), "column shares x at index " + index);
                assertEquals(z, columnPoint.z(), "column shares z at index " + index);
                assertEquals(68.0 + dy, columnPoint.y(), 1e-9, "column spacing is 1.0 covering base+-2");
            }
            assertTrue(x >= 0.0 && x <= 16.0, "x in chunk: " + base);
            assertTrue(z >= 0.0 && z <= 16.0, "z in chunk: " + base);
        }
    }

    @Test
    void segmentCapTruncatesDeterministicallyInSortedOrder() {
        SelectionVisualizationBudget capped = new SelectionVisualizationBudget(2, 128, 48, 10);

        SelectionVisualizationGeometry.Frame first =
                SelectionVisualizationGeometry.plan(singleChunk(64), capped, 8.0, 70.0, 8.0);
        SelectionVisualizationGeometry.Frame second =
                SelectionVisualizationGeometry.plan(singleChunk(64), capped, 8.0, 70.0, 8.0);

        assertEquals(2, first.segmentCount());
        assertEquals(true, first.truncated());
        assertEquals(80, first.points().size());
        assertEquals(first.points(), second.points());
        // Sorted order keeps NORTH then EAST: the first column sits on the north edge (z == 0).
        SelectionVisualizationGeometry.Point head = first.points().get(2);
        assertEquals(1.0, head.x());
        assertEquals(68.0, head.y());
        assertEquals(0.0, head.z());
    }

    @Test
    void sharedEdgesAreNotEmittedTwice() {
        SelectionSession twoChunks = sessionWith(
                Set.of(new ChunkKey(WORLD, 0, 0), new ChunkKey(WORLD, 1, 0)),
                Optional.of(new SelectionPoint(WORLD, 0, 64, 0)),
                Optional.of(new SelectionPoint(WORLD, 31, 64, 15)));

        SelectionVisualizationGeometry.Frame frame =
                SelectionVisualizationGeometry.plan(twoChunks, GENEROUS, 16.0, 70.0, 8.0);

        assertEquals(6, frame.segmentCount());
        assertEquals(false, frame.truncated());
        assertEquals(240, frame.points().size());
    }

    @Test
    void renderDistanceCullsFarSegments() {
        SelectionVisualizationGeometry.Frame frame = SelectionVisualizationGeometry.plan(
                singleChunk(64), GENEROUS, 1000.0, 70.0, 1000.0);

        assertEquals(0, frame.segmentCount());
        assertEquals(false, frame.truncated());
        assertTrue(frame.points().isEmpty());
    }

    @Test
    void emptySelectionRendersNothing() {
        SelectionSession empty = sessionWith(
                Set.of(),
                Optional.of(new SelectionPoint(WORLD, 0, 64, 0)),
                Optional.of(new SelectionPoint(WORLD, 15, 64, 15)));

        SelectionVisualizationGeometry.Frame frame =
                SelectionVisualizationGeometry.plan(empty, GENEROUS, 8.0, 70.0, 8.0);

        assertEquals(0, frame.segmentCount());
        assertTrue(frame.points().isEmpty());
    }

    @Test
    void planeFallsBackToViewerHeightWithoutPoints() {
        SelectionSession chunkOnly = sessionWith(Set.of(new ChunkKey(WORLD, 0, 0)), Optional.empty(), Optional.empty());

        SelectionVisualizationGeometry.Frame frame =
                SelectionVisualizationGeometry.plan(chunkOnly, GENEROUS, 8.0, 70.5, 8.0);

        assertEquals(4, frame.segmentCount());
        for (int index = 0; index < frame.points().size(); index += 5) {
            for (int dy = -2; dy <= 2; dy++) {
                assertEquals(70.5 + dy, frame.points().get(index + dy + 2).y(), 1e-9);
            }
        }
    }

    @Test
    void planeStaysInsideTheViewerBandWhenPointsAreWithinIt() {
        SelectionSession level = sessionWith(
                Set.of(new ChunkKey(WORLD, 0, 0)),
                Optional.of(new SelectionPoint(WORLD, 0, 69, 0)),
                Optional.of(new SelectionPoint(WORLD, 15, 69, 15)));

        SelectionVisualizationGeometry.Frame frame =
                SelectionVisualizationGeometry.plan(level, GENEROUS, 8.0, 70.0, 8.0);

        for (int index = 0; index < frame.points().size(); index += 5) {
            for (int dy = -2; dy <= 2; dy++) {
                assertEquals(70.0 + dy, frame.points().get(index + dy + 2).y(), 1e-9,
                        "point+1 inside the band stays the column centre");
            }
        }
    }

    @Test
    void lowClickClampsPlaneUpTowardTheViewer() {
        SelectionVisualizationGeometry.Frame frame =
                SelectionVisualizationGeometry.plan(singleChunk(10), GENEROUS, 8.0, 70.0, 8.0);

        double expected = 70.0 - SelectionVisualizationGeometry.PLANE_BELOW_VIEWER;
        for (int index = 0; index < frame.points().size(); index += 5) {
            for (int dy = -2; dy <= 2; dy++) {
                assertEquals(expected + dy, frame.points().get(index + dy + 2).y(), 1e-9,
                        "a cave click must not drop the column out of sight");
            }
        }
    }

    @Test
    void highClickClampsPlaneDownTowardTheViewer() {
        SelectionVisualizationGeometry.Frame frame =
                SelectionVisualizationGeometry.plan(singleChunk(200), GENEROUS, 8.0, 70.0, 8.0);

        double expected = 70.0 + SelectionVisualizationGeometry.PLANE_ABOVE_VIEWER;
        for (int index = 0; index < frame.points().size(); index += 5) {
            for (int dy = -2; dy <= 2; dy++) {
                assertEquals(expected + dy, frame.points().get(index + dy + 2).y(), 1e-9,
                        "a sky click must not raise the column out of sight");
            }
        }
    }

    @Test
    void perTickWindowRotatesDeterministically() {
        SelectionVisualizationGeometry.Frame frame =
                SelectionVisualizationGeometry.plan(singleChunk(64), GENEROUS, 8.0, 70.0, 8.0);
        List<SelectionVisualizationGeometry.Point> all = frame.points();
        int size = all.size();

        List<SelectionVisualizationGeometry.Point> tick0 = frame.window(0, 6);
        List<SelectionVisualizationGeometry.Point> tick1 = frame.window(1, 6);
        List<SelectionVisualizationGeometry.Point> tick2 = frame.window(2, 6);

        assertEquals(6, tick0.size());
        assertEquals(6, tick1.size());
        assertEquals(6, tick2.size());
        assertEquals(all.subList(0, 6), tick0);
        assertEquals(all.subList(6, 12), tick1);
        // 160 points with a budget of 6: the third tick continues without wrapping yet.
        assertEquals(
                List.of(all.get(12), all.get(13), all.get(14), all.get(15), all.get(16), all.get(17)),
                tick2);
        // A full cycle realigns once the window start returns to zero.
        assertEquals(tick0, frame.window(size / 2, 6));
        // Extremely large tick indices wrap without overflow.
        assertEquals(frame.window(Math.floorMod(Long.MAX_VALUE, (long) size), 6), frame.window(Long.MAX_VALUE, 6));
    }

    @Test
    void windowReturnsEverythingWhenUnderBudget() {
        SelectionVisualizationGeometry.Frame frame =
                SelectionVisualizationGeometry.plan(singleChunk(64), GENEROUS, 8.0, 70.0, 8.0);

        assertEquals(frame.points(), frame.window(3, 256));
        assertEquals(frame.points(), frame.window(0, frame.points().size()));
    }
}

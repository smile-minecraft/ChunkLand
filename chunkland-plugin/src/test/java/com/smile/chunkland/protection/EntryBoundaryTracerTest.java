package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EntryBoundaryTracerTest {

    @Test
    void straightBorderYieldsOneSegmentPerBlockOnTheBorderPlane() {
        // Everything at x >= 16 is denied: a chunk border running along Z.
        List<EntryBoundaryTracer.Edge> edges = EntryBoundaryTracer.trace(16, 5, 4,
                (x, z) -> x >= 16);

        assertEquals(9, edges.size(), "one segment per sampled block along the border");
        for (EntryBoundaryTracer.Edge edge : edges) {
            assertEquals(16.0D, edge.x1());
            assertEquals(16.0D, edge.x2());
            assertEquals(1.0D, edge.z2() - edge.z1(), "segments are one block long");
        }
    }

    @Test
    void cornerIsFollowedOnBothAxes() {
        // Denied quadrant x >= 0 && z >= 0: the outline turns a corner at the origin.
        List<EntryBoundaryTracer.Edge> edges = EntryBoundaryTracer.trace(0, 0, 2,
                (x, z) -> x >= 0 && z >= 0);

        long alongZ = edges.stream().filter(e -> e.x1() == e.x2()).count();
        long alongX = edges.stream().filter(e -> e.z1() == e.z2()).count();
        assertEquals(3, alongZ, "the x = 0 side covers z in [0, 3)");
        assertEquals(3, alongX, "the z = 0 side covers x in [0, 3)");
        assertTrue(edges.stream().allMatch(e -> e.x1() >= 0 && e.z1() >= 0),
                "no segment may be drawn away from the denied quadrant");
    }

    @Test
    void uniformNeighbourhoodHasNoBorder() {
        assertTrue(EntryBoundaryTracer.trace(0, 0, 6, (x, z) -> true).isEmpty());
        assertTrue(EntryBoundaryTracer.trace(0, 0, 6, (x, z) -> false).isEmpty());
    }

    @Test
    void failingProbeAndBadRadiusStayEmpty() {
        assertTrue(EntryBoundaryTracer.trace(0, 0, 6, (x, z) -> {
            throw new IllegalStateException("snapshot gone");
        }).isEmpty());
        assertTrue(EntryBoundaryTracer.trace(0, 0, 0, (x, z) -> x > 0).isEmpty());
    }
}

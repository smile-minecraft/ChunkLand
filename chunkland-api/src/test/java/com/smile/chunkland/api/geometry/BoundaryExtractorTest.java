package com.smile.chunkland.api.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Red phase + property-based + performance tests for {@link BoundaryExtractor}.
 *
 * <p>An exposed edge is one orthogonal side of a chunk whose neighbor is NOT in the
 * set. Shared edges (both neighbors present) are emitted zero times; diagonal contact
 * never creates or removes a segment. The extractor is O(n) and order-independent, and
 * returns an immutable set.
 */
class BoundaryExtractorTest {

    private static final UUID WORLD = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WORLD_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final long SEED_A = 0x5EED_0001L;
    private static final long SEED_B = 0x5EED_0002L;
    private static final long SEED_C = 0x5EED_0003L;
    private static final long SEED_D = 0x5EED_0004L;

    private static ChunkKey k(int x, int z) {
        return new ChunkKey(WORLD, x, z);
    }

    private static Set<ChunkKey> set(ChunkKey... keys) {
        return Set.of(keys);
    }

    // ---- Red: empty ----
    @Test
    void emptySetHasNoBoundary() {
        assertTrue(BoundaryExtractor.extract(set()).isEmpty());
    }

    // ---- Red: singleton exposes all four edges ----
    @Test
    void singletonExposesFourEdges() {
        var b = BoundaryExtractor.extract(set(k(0, 0)));
        assertEquals(4, b.size());
        for (Direction d : Direction.values()) {
            assertTrue(b.contains(new BoundarySegment(k(0, 0), d)), "missing edge " + d);
        }
    }

    // ---- Red: two adjacent chunks share an edge -> not emitted ----
    @Test
    void adjacentPairShareNoEdge() {
        var b = BoundaryExtractor.extract(set(k(0, 0), k(1, 0)));
        // each chunk keeps its 3 non-shared edges: 6 total.
        assertEquals(6, b.size());
        // the shared edge (E of (0,0) == W of (1,0)) must be absent.
        assertFalse(b.contains(new BoundarySegment(k(0, 0), Direction.EAST)));
        assertFalse(b.contains(new BoundarySegment(k(1, 0), Direction.WEST)));
        // the three exposed sides of (0,0) are present.
        assertTrue(b.contains(new BoundarySegment(k(0, 0), Direction.WEST)));
        assertTrue(b.contains(new BoundarySegment(k(0, 0), Direction.NORTH)));
        assertTrue(b.contains(new BoundarySegment(k(0, 0), Direction.SOUTH)));
    }

    // ---- Red: diagonal-only contact -> each keeps all four edges ----
    @Test
    void diagonalOnlyKeepsAllEdges() {
        var b = BoundaryExtractor.extract(set(k(0, 0), k(1, 1)));
        assertEquals(8, b.size());
        for (ChunkKey c : set(k(0, 0), k(1, 1))) {
            for (Direction d : Direction.values()) {
                assertTrue(b.contains(new BoundarySegment(c, d)), "missing edge " + c + " " + d);
            }
        }
    }

    // ---- Red: L shape (hand-computed, Minecraft +z = south) ----
    @Test
    void lShapeBoundary() {
        // (0,0),(1,0),(0,1): 3 cells, 2 shared edges -> 8 exposed edges.
        // (0,0) is occupied on EAST (x+1 -> (1,0)) and SOUTH (z+1 -> (0,1)),
        // so it exposes WEST and NORTH only.
        var b = BoundaryExtractor.extract(set(k(0, 0), k(1, 0), k(0, 1)));
        assertEquals(8, b.size());
        assertTrue(b.contains(new BoundarySegment(k(0, 0), Direction.NORTH)));
        assertTrue(b.contains(new BoundarySegment(k(0, 0), Direction.WEST)));
        assertFalse(b.contains(new BoundarySegment(k(0, 0), Direction.EAST)));
        assertFalse(b.contains(new BoundarySegment(k(0, 0), Direction.SOUTH)));
    }

    // ---- Red: solid 2x2 rectangle -> perimeter of 8 (Minecraft +z = south) ----
    @Test
    void solidRectanglePerimeter() {
        var b = BoundaryExtractor.extract(set(
                k(0, 0), k(1, 0), k(0, 1), k(1, 1)));
        assertEquals(8, b.size());
        // interior shared edges absent: (0,0)-(1,0) is EAST/WEST, (0,0)-(0,1) is
        // SOUTH/NORTH, (1,0)-(1,1) is SOUTH/NORTH, (0,1)-(1,1) is EAST/WEST.
        assertFalse(b.contains(new BoundarySegment(k(0, 0), Direction.EAST)));
        assertFalse(b.contains(new BoundarySegment(k(0, 0), Direction.SOUTH)));
        assertFalse(b.contains(new BoundarySegment(k(1, 0), Direction.WEST)));
        assertFalse(b.contains(new BoundarySegment(k(0, 1), Direction.NORTH)));
    }

    // ---- Red: input iteration order must not affect the result ----
    @Test
    void orderIndependence() {
        var chunks = set(k(0, 0), k(1, 0), k(0, 1), k(2, 2), k(-3, 4));
        var a = BoundaryExtractor.extract(chunks);
        var list = new ArrayList<>(chunks);
        Collections.shuffle(list, new Random(SEED_A));
        var b = BoundaryExtractor.extract(new HashSet<>(list));
        assertEquals(a, b);
    }

    // ---- Red: mixed worlds rejected ----
    @Test
    void mixedWorldsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BoundaryExtractor.extract(Set.of(
                        new ChunkKey(WORLD, 0, 0), new ChunkKey(WORLD_B, 1, 1))));
    }

    // ---- Red: negative coordinates behave like positive ones ----
    @Test
    void negativeCoordinatesBoundary() {
        var b = BoundaryExtractor.extract(set(k(-1, -1), k(-2, -1)));
        // shared edge between (-1,-1) and (-2,-1) is absent; 6 exposed.
        assertEquals(6, b.size());
        assertFalse(b.contains(new BoundarySegment(k(-1, -1), Direction.WEST)));
        assertFalse(b.contains(new BoundarySegment(k(-2, -1), Direction.EAST)));
    }

    // ---- Red: int extreme coordinates must not overflow / wrap ----
    @Test
    void extremeAdjacentNoWrap() {
        // (MAX,0) and (MAX-1,0) are true neighbors; their shared edge is absent.
        var b = BoundaryExtractor.extract(set(
                k(Integer.MAX_VALUE, 0), k(Integer.MAX_VALUE - 1, 0)));
        assertEquals(6, b.size());
        assertFalse(b.contains(new BoundarySegment(k(Integer.MAX_VALUE, 0), Direction.WEST)));
        assertFalse(b.contains(new BoundarySegment(k(Integer.MAX_VALUE - 1, 0), Direction.EAST)));
    }

    @Test
    void extremeEdgeFacesWorldBoundary() {
        // (MAX,0) has no valid EAST neighbor (would be MAX+1) -> edge emitted,
        // and sideChunk() is null rather than a wrapped coordinate.
        var b = BoundaryExtractor.extract(set(k(Integer.MAX_VALUE, 0)));
        assertEquals(4, b.size());
        var east = new BoundarySegment(k(Integer.MAX_VALUE, 0), Direction.EAST);
        assertTrue(b.contains(east));
        assertEquals(null, east.sideChunk());
    }

    @Test
    void extremeMinEdgeFacesWorldBoundary() {
        var b = BoundaryExtractor.extract(set(k(Integer.MIN_VALUE, 0)));
        assertEquals(4, b.size());
        var west = new BoundarySegment(k(Integer.MIN_VALUE, 0), Direction.WEST);
        assertTrue(b.contains(west));
        assertEquals(null, west.sideChunk());
    }

    // ---- Red: Direction matches Minecraft convention (x east, +z south) ----
    @Test
    void directionMatchesMinecraftConvention() {
        assertEquals(0, Direction.NORTH.dx());
        assertEquals(-1, Direction.NORTH.dz()); // north is toward -z
        assertEquals(1, Direction.EAST.dx());
        assertEquals(0, Direction.EAST.dz());
        assertEquals(0, Direction.SOUTH.dx());
        assertEquals(1, Direction.SOUTH.dz());  // south is toward +z
        assertEquals(-1, Direction.WEST.dx());
        assertEquals(0, Direction.WEST.dz());
        // opposite() faces back across the shared edge.
        assertEquals(Direction.SOUTH, Direction.NORTH.opposite());
        assertEquals(Direction.NORTH, Direction.SOUTH.opposite());
        assertEquals(Direction.WEST, Direction.EAST.opposite());
        assertEquals(Direction.EAST, Direction.WEST.opposite());
    }

    // ---- Red: edgeEndpoints geometry for a normal chunk ----
    @Test
    void edgeEndpointsNormalChunk() {
        // chunk (0,0) occupies [0,1) x [0,1); each face is the unit side toward d.
        assertArrayEquals(new long[] {0, 0, 1, 0}, new BoundarySegment(k(0, 0), Direction.NORTH).edgeEndpoints());
        assertArrayEquals(new long[] {1, 0, 1, 1}, new BoundarySegment(k(0, 0), Direction.EAST).edgeEndpoints());
        assertArrayEquals(new long[] {0, 1, 1, 1}, new BoundarySegment(k(0, 0), Direction.SOUTH).edgeEndpoints());
        assertArrayEquals(new long[] {0, 0, 0, 1}, new BoundarySegment(k(0, 0), Direction.WEST).edgeEndpoints());
    }

    // ---- Red: edgeEndpoints must not overflow at int extremes ----
    @Test
    void edgeEndpointsNoOverflowAtMax() {
        long max = Integer.MAX_VALUE;
        // EAST face at x+1: x+1 must be MAX+1, not a wrapped negative.
        assertArrayEquals(new long[] {max + 1, 0, max + 1, 1},
                new BoundarySegment(k(Integer.MAX_VALUE, 0), Direction.EAST).edgeEndpoints());
        // SOUTH face at z+1: z+1 must be MAX+1.
        assertArrayEquals(new long[] {max, max + 1, max + 1, max + 1},
                new BoundarySegment(k(Integer.MAX_VALUE, Integer.MAX_VALUE), Direction.SOUTH).edgeEndpoints());
        // NORTH face uses x+1 on the far x edge.
        assertArrayEquals(new long[] {max, 0, max + 1, 0},
                new BoundarySegment(k(Integer.MAX_VALUE, 0), Direction.NORTH).edgeEndpoints());
        // WEST face uses z+1 on the far z edge.
        assertArrayEquals(new long[] {0, max, 0, max + 1},
                new BoundarySegment(k(0, Integer.MAX_VALUE), Direction.WEST).edgeEndpoints());
        // every endpoint stays a valid (non-wrapped) long coordinate.
        for (Direction d : Direction.values()) {
            long[] e = new BoundarySegment(k(Integer.MAX_VALUE, Integer.MAX_VALUE), d).edgeEndpoints();
            for (long v : e) assertTrue(v > Integer.MAX_VALUE || v >= Integer.MIN_VALUE);
        }
    }

    // ---- Red: edgeEndpoints at int MIN still safe ----
    @Test
    void edgeEndpointsNoUnderflowAtMin() {
        long min = Integer.MIN_VALUE;
        // WEST face at x = min (no x-1 needed); z+1 is fine.
        assertArrayEquals(new long[] {min, 0, min, 1},
                new BoundarySegment(k(Integer.MIN_VALUE, 0), Direction.WEST).edgeEndpoints());
        // NORTH face at z = min; x+1 is fine.
        assertArrayEquals(new long[] {0, min, 1, min},
                new BoundarySegment(k(0, Integer.MIN_VALUE), Direction.NORTH).edgeEndpoints());
    }

    // ---- Red: returned set is immutable ----
    @Test
    void resultIsImmutable() {
        var b = BoundaryExtractor.extract(set(k(0, 0)));
        assertThrows(UnsupportedOperationException.class,
                () -> b.add(new BoundarySegment(k(0, 0), Direction.NORTH)));
    }

    // ---- Property-based: oracle over (chunk, direction) ----
    @Test
    void propertyMatchesIndependentOracle() {
        var rng = new Random(SEED_B);
        for (int i = 0; i < 400; i++) {
            int w = 2 + rng.nextInt(10);
            int h = 2 + rng.nextInt(10);
            double density = 0.2 + rng.nextDouble() * 0.7;
            var chunks = generate(rng, w, h, density);
            var packed = new HashSet<Long>();
            for (var c : chunks) packed.add(c.pack());
            var actual = BoundaryExtractor.extract(chunks);

            // Build the expected set with an independent oracle: a segment (c,d)
            // exists iff the neighbor across d is null (world boundary) or absent.
            var expected = new HashSet<BoundarySegment>();
            for (var c : chunks) {
                for (Direction d : Direction.values()) {
                    Long nb = oracleNeighbor(c.chunkX(), c.chunkZ(), d);
                    if (nb == null || !packed.contains(nb)) {
                        expected.add(new BoundarySegment(c, d));
                    }
                }
            }
            assertEquals(expected, actual, "boundary mismatch at iter " + i);

            // uniqueness: no segment appears twice (set semantics guarantee it, but
            // assert the count equals the number of distinct (chunk,direction) pairs).
            assertEquals(expected.size(), actual.size(), "duplicate segments at iter " + i);

            // immutability of the returned collection (probe with a fixed chunk so the
            // assertion holds even when this random set happened to be empty).
            assertThrows(UnsupportedOperationException.class,
                    () -> actual.add(new BoundarySegment(k(0, 0), Direction.NORTH)));
        }
    }

    // ---- Property-based: order independence across many seeds ----
    @Test
    void propertyOrderIndependence() {
        var rng = new Random(SEED_C);
        for (int i = 0; i < 100; i++) {
            var chunks = generate(rng, 2 + rng.nextInt(10), 2 + rng.nextInt(10), 0.5);
            var a = BoundaryExtractor.extract(chunks);
            var list = new ArrayList<>(chunks);
            Collections.shuffle(list, rng);
            var b = BoundaryExtractor.extract(new HashSet<>(list));
            assertEquals(a, b, "order changed result at iter " + i);
        }
    }

    // ---- Performance / structural test at ~1024 chunks ----
    @Test
    void perf1024ChunksMillisecondScale() {
        // 32x32 = 1024 fully filled grid: a closed block, no exposed edges.
        var full = new HashSet<ChunkKey>();
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                full.add(k(x, z));
            }
        }
        long start = System.nanoTime();
        var b = BoundaryExtractor.extract(full);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // A solid 32x32 block has exactly the 4*32 = 128 perimeter edges.
        assertEquals(128, b.size());
        assertTrue(elapsedMs < 2000, "1024-chunk boundary took " + elapsedMs + "ms (expected < 2000ms)");
        System.out.println("[BoundaryExtractorTest] 1024-chunk full-grid boundary: " + elapsedMs + " ms");
    }

    @Test
    void perf1024SparseStillLinear() {
        // Exactly 1024 chunks drawn from a 64x64 grid (4096 cells): must stay O(n).
        // We shuffle the full grid and take the first 1024 so the count is exact and
        // the set is a realistic scattered selection rather than a density estimate.
        var rng = new Random(SEED_D);
        var all = new ArrayList<ChunkKey>();
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                all.add(k(x, z));
            }
        }
        Collections.shuffle(all, rng);
        var sparse = new HashSet<>(all.subList(0, 1024));
        assertEquals(1024, sparse.size());
        long start = System.nanoTime();
        var b = BoundaryExtractor.extract(sparse);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 2000, "1024-chunk sparse boundary took " + elapsedMs + "ms (expected < 2000ms)");
        System.out.println("[BoundaryExtractorTest] 1024-chunk sparse boundary: " + elapsedMs + " ms, segments=" + b.size());
    }

    // ---- helpers ----

    private static Long oracleNeighbor(int x, int z, Direction d) {
        long nx = (long) x + d.dx();
        long nz = (long) z + d.dz();
        if (nx < Integer.MIN_VALUE || nx > Integer.MAX_VALUE
                || nz < Integer.MIN_VALUE || nz > Integer.MAX_VALUE) {
            return null;
        }
        return ((long) (int) nx << 32) | ((int) nz & 0xFFFFFFFFL);
    }

    private static Set<ChunkKey> generate(Random rng, int gridW, int gridH, double density) {
        var out = new HashSet<ChunkKey>();
        for (int x = 0; x < gridW; x++) {
            for (int z = 0; z < gridH; z++) {
                if (rng.nextDouble() < density) out.add(k(x, z));
            }
        }
        return out;
    }
}

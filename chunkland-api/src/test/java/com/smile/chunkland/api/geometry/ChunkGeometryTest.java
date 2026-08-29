package com.smile.chunkland.api.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Red phase + property-based + performance tests for {@link ChunkGeometry}.
 *
 * <p>Connectivity is 4-neighbor (N/E/S/W). Diagonal-only contact is NOT
 * connectivity. Holes are empty cells fully enclosed by the set (not reachable
 * from the bounding-box exterior). Bridge removal increases the component count.
 */
class ChunkGeometryTest {

    private static final UUID WORLD = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WORLD_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final long SEED = 0x5EED_1234L;

    private static ChunkKey k(int x, int z) {
        return new ChunkKey(WORLD, x, z);
    }

    private static Set<ChunkKey> set(ChunkKey... keys) {
        return Set.of(keys);
    }

    // ---- Red: basic connectivity / disconnection ----

    @Test
    void emptySetHasZeroComponentsAndIsConnected() {
        var g = new ChunkGeometry(set());
        assertEquals(0, g.componentCount());
        assertTrue(g.isConnected());
        assertTrue(g.holes().isEmpty());
        assertTrue(g.bridges().isEmpty());
    }

    @Test
    void singletonIsOneConnectedComponent() {
        var g = new ChunkGeometry(set(k(0, 0)));
        assertEquals(1, g.componentCount());
        assertTrue(g.isConnected());
        assertTrue(g.holes().isEmpty());
        assertTrue(g.bridges().isEmpty());
    }

    @Test
    void fourNeighborLineIsConnected() {
        var g = new ChunkGeometry(set(k(0, 0), k(1, 0), k(2, 0), k(3, 0)));
        assertEquals(1, g.componentCount());
        assertTrue(g.isConnected());
    }

    @Test
    void disconnectedPairAreTwoComponents() {
        var g = new ChunkGeometry(set(k(0, 0), k(5, 5)));
        assertEquals(2, g.componentCount());
        assertFalse(g.isConnected());
    }

    // ---- Red: diagonal-only is NOT connected ----

    @Test
    void diagonalOnlyContactIsNotConnected() {
        var g = new ChunkGeometry(set(k(0, 0), k(1, 1)));
        assertEquals(2, g.componentCount());
        assertFalse(g.isConnected());
        // No hole: both empty diagonal neighbors connect to the exterior.
        assertTrue(g.holes().isEmpty());
    }

    @Test
    void diagonalChainStaysDisconnected() {
        // (0,0)-(1,1)-(2,0) touch only on diagonals -> three components.
        var g = new ChunkGeometry(set(k(0, 0), k(1, 1), k(2, 0)));
        assertEquals(3, g.componentCount());
        assertFalse(g.isConnected());
    }

    // ---- Red: holes ----

    @Test
    void enclosedRingCreatesOneHole() {
        // 3x3 ring missing the center (1,1).
        var ring = new HashSet<ChunkKey>();
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                if (x == 1 && z == 1) continue;
                ring.add(k(x, z));
            }
        }
        var g = new ChunkGeometry(ring);
        assertEquals(1, g.componentCount());
        assertTrue(g.isConnected());
        assertEquals(set(k(1, 1)), g.holes());
    }

    @Test
    void uShapeHasNoHoleBecauseItOpensToExterior() {
        // U shape: bottom row + two sides, open at top -> interior connects outside.
        var u = new HashSet<ChunkKey>();
        for (int x = 0; x <= 2; x++) u.add(k(x, 0));
        u.add(k(0, 1));
        u.add(k(2, 1));
        var g = new ChunkGeometry(u);
        assertTrue(g.holes().isEmpty());
    }

    @Test
    void boundaryEmptyIsNotAHole() {
        // Single chunk: the empty cells around it all reach the exterior.
        var g = new ChunkGeometry(set(k(10, 10)));
        assertTrue(g.holes().isEmpty());
    }

    // ---- Red: bridge removal ----

    @Test
    void middleOfLineIsBridge() {
        var g = new ChunkGeometry(set(k(0, 0), k(1, 0), k(2, 0)));
        assertEquals(set(k(1, 0)), g.bridges());
        assertTrue(g.isBridge(k(1, 0)));
        assertFalse(g.isBridge(k(0, 0)));
    }

    @Test
    void removingBridgeSplitsComponents() {
        var original = new ChunkGeometry(set(k(0, 0), k(1, 0), k(2, 0)));
        int before = original.componentCount();
        var without = new ChunkGeometry(set(k(0, 0), k(2, 0)));
        assertTrue(without.componentCount() > before);
    }

    @Test
    void fullBlockHasNoBridge() {
        var block = new HashSet<ChunkKey>();
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                block.add(k(x, z));
            }
        }
        var g = new ChunkGeometry(block);
        assertTrue(g.bridges().isEmpty());
    }

    // ---- Red: mixed worlds rejected ----

    @Test
    void mixedWorldsRejected() {
        var other = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkGeometry(Set.of(new ChunkKey(WORLD, 0, 0), new ChunkKey(other, 1, 1))));
    }

    // ---- Red: int extreme coordinates must not overflow / wrap ----

    @Test
    void extremeNonAdjacentNotMerged() {
        // Two chunks at the MAX edge but two apart: not 4-neighbors, and the
        // +1 neighbor of (MAX,0) must not wrap to a real coordinate. No overflow.
        var g = new ChunkGeometry(set(k(Integer.MAX_VALUE, 0), k(Integer.MAX_VALUE - 2, 0)));
        assertEquals(2, g.componentCount());
        assertFalse(g.isConnected());
    }

    @Test
    void fullRangeSetFailsFast() {
        // A set containing both (MAX,0) and (MIN,0) would let a wrapped +1
        // neighbor of (MAX,0) falsely reach (MIN,0). Such a set spans the full
        // int range, so hole analysis must fail fast rather than merge them or
        // loop. This is the safe behavior that prevents the wrap-merge bug.
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkGeometry(set(k(Integer.MAX_VALUE, 0), k(Integer.MIN_VALUE, 0))));
    }

    @Test
    void extremeAdjacentCoordsAreConnected() {
        // (MAX-1,0) and (MAX,0) differ by exactly one in x -> true neighbors.
        var g = new ChunkGeometry(set(k(Integer.MAX_VALUE - 1, 0), k(Integer.MAX_VALUE, 0)));
        assertEquals(1, g.componentCount());
        assertTrue(g.isConnected());
    }

    @Test
    void extremeBridgeAtMaxEdge() {
        var g = new ChunkGeometry(set(
                k(Integer.MAX_VALUE - 2, 0), k(Integer.MAX_VALUE - 1, 0), k(Integer.MAX_VALUE, 0)));
        assertEquals(set(k(Integer.MAX_VALUE - 1, 0)), g.bridges());
    }

    @Test
    void extremeHoleCompletesWithoutHang() {
        // 3x3 ring near the max edge (but not touching it, so the test's own
        // iteration stays in range); the center is enclosed.
        int c = Integer.MAX_VALUE - 2;
        var ring = new HashSet<ChunkKey>();
        for (int x = c - 1; x <= c + 1; x++) {
            for (int z = c - 1; z <= c + 1; z++) {
                if (x == c && z == c) continue;
                ring.add(k(x, z));
            }
        }
        var g = new ChunkGeometry(ring);
        assertEquals(1, g.componentCount());
        assertEquals(set(k(c, c)), g.holes());
    }

    @Test
    void extremeHoleTouchingWorldEdge() {
        // Ring whose right column sits on x = MAX_VALUE; the frame would overflow
        // without clamping. The center must still be detected as a hole. Built
        // with overflow-safe iteration because the upper bound is the int max.
        int c = Integer.MAX_VALUE - 1;
        int xTo = Integer.MAX_VALUE;
        int zTo = c + 1;
        var ring = new HashSet<ChunkKey>();
        for (int x = c - 1; ; ) {
            for (int z = c - 1; ; ) {
                if (!(x == c && z == c)) ring.add(k(x, z));
                if (z == zTo) break;
                z++;
            }
            if (x == xTo) break;
            x++;
        }
        var g = new ChunkGeometry(ring);
        assertEquals(set(k(c, c)), g.holes());
    }

    @Test
    void hugeBoundingBoxFailsFastOnHoles() {
        // Two chunks at opposite int extremes -> bounding box spans the full
        // range. Construction must fail fast (not loop or OOM, and not only
        // when holes() is later called), so the exception is asserted at the
        // constructor itself.
        var wide = Set.of(k(0, 0), k(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new ChunkGeometry(wide));
    }

    // ---- Red: isBridge must respect worldId ----

    @Test
    void isBridgeRejectsOtherWorld() {
        var g = new ChunkGeometry(set(k(0, 0), k(1, 0), k(2, 0)));
        var otherWorldChunk = new ChunkKey(WORLD_B, 1, 0); // same x/z, different world
        assertThrows(IllegalArgumentException.class, () -> g.isBridge(otherWorldChunk));
    }

    @Test
    void isBridgeSameWorldStillCorrect() {
        var g = new ChunkGeometry(set(k(0, 0), k(1, 0), k(2, 0)));
        assertTrue(g.isBridge(k(1, 0)));
        assertFalse(g.isBridge(k(0, 0)));
        // A same-x/z chunk in another world must not be treated as the bridge.
        assertThrows(IllegalArgumentException.class, () -> g.isBridge(new ChunkKey(WORLD_B, 1, 0)));
    }

    // ---- Property-based / generative tests (fixed seed) ----

    /** Reference flood fill of empty cells from the bounding-box frame. */
    private static Set<Long> referenceExterior(Set<Long> packed) {
        if (packed.isEmpty()) return Set.of();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (long p : packed) {
            int x = (int) (p >>> 32), z = (int) (p & 0xFFFFFFFFL);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        int fMinX = minX - 1, fMaxX = maxX + 1, fMinZ = minZ - 1, fMaxZ = maxZ + 1;
        Set<Long> exterior = new HashSet<>();
        var queue = new java.util.ArrayDeque<Long>();
        for (int x = fMinX; x <= fMaxX; x++) {
            seed(x, fMinZ, packed, exterior, queue, fMinX, fMaxX, fMinZ, fMaxZ);
            seed(x, fMaxZ, packed, exterior, queue, fMinX, fMaxX, fMinZ, fMaxZ);
        }
        for (int z = fMinZ; z <= fMaxZ; z++) {
            seed(fMinX, z, packed, exterior, queue, fMinX, fMaxX, fMinZ, fMaxZ);
            seed(fMaxX, z, packed, exterior, queue, fMinX, fMaxX, fMinZ, fMaxZ);
        }
        while (!queue.isEmpty()) {
            long cur = queue.poll();
            if (!exterior.add(cur)) continue;
            for (int d = 0; d < 4; d++) {
                long nb = neighbor(cur, DX[d], DZ[d]);
                int nx = (int) (nb >>> 32), nz = (int) (nb & 0xFFFFFFFFL);
                if (nx < fMinX || nx > fMaxX || nz < fMinZ || nz > fMaxZ) continue;
                if (packed.contains(nb) || exterior.contains(nb)) continue;
                queue.add(nb);
            }
        }
        return exterior;
    }

    private static void seed(int x, int z, Set<Long> packed, Set<Long> exterior,
            java.util.ArrayDeque<Long> queue, int fMinX, int fMaxX, int fMinZ, int fMaxZ) {
        long p = pack(x, z);
        if (packed.contains(p) || exterior.contains(p)) return;
        queue.add(p);
    }

    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DZ = {1, 0, -1, 0};

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static long neighbor(long p, int dx, int dz) {
        int x = (int) (p >>> 32) + dx;
        int z = (int) (p & 0xFFFFFFFFL) + dz;
        return pack(x, z);
    }

    /** Deterministic generator: random subset of a bounded grid. */
    private static Set<ChunkKey> generate(Random rng, int gridW, int gridH, double density) {
        var out = new HashSet<ChunkKey>();
        for (int x = 0; x < gridW; x++) {
            for (int z = 0; z < gridH; z++) {
                if (rng.nextDouble() < density) out.add(k(x, z));
            }
        }
        return out;
    }

    @Test
    void propertyConnectivityAndComponents() {
        var rng = new Random(SEED);
        for (int i = 0; i < 300; i++) {
            int w = 2 + rng.nextInt(8);
            int h = 2 + rng.nextInt(8);
            double density = 0.3 + rng.nextDouble() * 0.6;
            var chunks = generate(rng, w, h, density);
            var g = new ChunkGeometry(chunks);

            // componentCount matches components list
            var comps = g.components();
            assertEquals(g.componentCount(), comps.size(), "component count mismatch at iter " + i);

            // union of components == original set
            var union = new HashSet<ChunkKey>();
            for (var c : comps) union.addAll(c);
            assertEquals(new HashSet<>(chunks), union, "components must partition the set at iter " + i);

            // components are pairwise disjoint and each is internally connected
            for (int a = 0; a < comps.size(); a++) {
                for (int b = a + 1; b < comps.size(); b++) {
                    assertTrue(Collections.disjoint(comps.get(a), comps.get(b)),
                            "components must be disjoint at iter " + i);
                }
                assertTrue(isConnected(comps.get(a)), "each component must be connected at iter " + i);
            }

            // no two distinct components are 4-adjacent (otherwise they would merge)
            for (int a = 0; a < comps.size(); a++) {
                for (int b = a + 1; b < comps.size(); b++) {
                    assertFalse(areAdjacent(comps.get(a), comps.get(b)),
                            "adjacent components must have merged at iter " + i);
                }
            }
        }
    }

    private static boolean isConnected(Set<ChunkKey> comp) {
        if (comp.size() <= 1) return true;
        var packed = new HashSet<Long>();
        for (var c : comp) packed.add(c.pack());
        var seen = new HashSet<Long>();
        var stack = new java.util.ArrayDeque<Long>();
        long start = comp.iterator().next().pack();
        stack.push(start);
        seen.add(start);
        while (!stack.isEmpty()) {
            long cur = stack.pop();
            for (int d = 0; d < 4; d++) {
                long nb = neighbor(cur, DX[d], DZ[d]);
                if (packed.contains(nb) && seen.add(nb)) stack.push(nb);
            }
        }
        return seen.size() == packed.size();
    }

    private static boolean areAdjacent(Set<ChunkKey> a, Set<ChunkKey> b) {
        for (var ca : a) {
            for (int d = 0; d < 4; d++) {
                long nb = neighbor(ca.pack(), DX[d], DZ[d]);
                for (var cb : b) {
                    if (cb.pack() == nb) return true;
                }
            }
        }
        return false;
    }

    @Test
    void propertyHoleClassificationMatchesReference() {
        var rng = new Random(SEED);
        for (int i = 0; i < 300; i++) {
            int w = 2 + rng.nextInt(8);
            int h = 2 + rng.nextInt(8);
            double density = 0.3 + rng.nextDouble() * 0.6;
            var chunks = generate(rng, w, h, density);
            var g = new ChunkGeometry(chunks);

            var packed = new HashSet<Long>();
            for (var c : chunks) packed.add(c.pack());
            var exterior = referenceExterior(packed);

            // holes must be exactly the inner empty cells not in exterior
            var expected = new HashSet<Long>();
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (long p : packed) {
                int x = (int) (p >>> 32), z = (int) (p & 0xFFFFFFFFL);
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
            if (!packed.isEmpty()) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        long p = pack(x, z);
                        if (!packed.contains(p) && !exterior.contains(p)) expected.add(p);
                    }
                }
            }
            var actual = new HashSet<Long>();
            for (var hk : g.holes()) actual.add(hk.pack());
            assertEquals(expected, actual, "hole mismatch at iter " + i);
        }
    }

    @Test
    void propertyBridgeRemoval() {
        var rng = new Random(SEED);
        for (int i = 0; i < 200; i++) {
            int w = 2 + rng.nextInt(7);
            int h = 2 + rng.nextInt(7);
            double density = 0.4 + rng.nextDouble() * 0.5;
            var chunks = generate(rng, w, h, density);
            var g = new ChunkGeometry(chunks);
            int base = g.componentCount();

            for (var c : chunks) {
                var without = new HashSet<ChunkKey>(chunks);
                without.remove(c);
                int after = new ChunkGeometry(without).componentCount();
                // removing one chunk can only drop the count by at most 1 (when it was
                // an isolated singleton component); it can never drop by more.
                assertTrue(after >= base - 1, "removal dropped components by more than 1 at iter " + i);
                // bridge iff removal increased component count
                assertEquals(after > base, g.isBridge(c), "bridge mismatch for " + c + " at iter " + i);
            }
        }
    }

    @Test
    void propertyOrderIndependence() {
        var rng = new Random(SEED);
        for (int i = 0; i < 100; i++) {
            int w = 2 + rng.nextInt(8);
            int h = 2 + rng.nextInt(8);
            var chunks = generate(rng, w, h, 0.5);
            var ordered = new ChunkGeometry(chunks);

            var shuffled = new ArrayList<>(chunks);
            Collections.shuffle(shuffled, rng);
            var gShuffled = new ChunkGeometry(new HashSet<>(shuffled));

            assertEquals(ordered.componentCount(), gShuffled.componentCount());
            assertEquals(ordered.holes(), gShuffled.holes());
            assertEquals(ordered.bridges(), gShuffled.bridges());
        }
    }

    // ---- Performance / structural test at ~1024 chunks ----

    @Test
    void perf1024ChunksMillisecondScale() {
        // 32x32 = 1024 fully filled grid: one component, no holes, no bridges.
        var full = new HashSet<ChunkKey>();
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                full.add(k(x, z));
            }
        }
        long start = System.nanoTime();
        var g = new ChunkGeometry(full);
        int comps = g.componentCount();
        var holes = g.holes();
        var bridges = g.bridges();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, comps);
        assertTrue(holes.isEmpty());
        assertTrue(bridges.isEmpty());
        // Loose structural/perf gate: catches gross regressions / accidental
        // quadratic blowups. Not a hardware SLA.
        assertTrue(elapsedMs < 2000, "1024-chunk judgment took " + elapsedMs + "ms (expected < 2000ms)");
        System.out.println("[ChunkGeometryTest] 1024-chunk full-grid judgment: " + elapsedMs + " ms");
    }

    @Test
    void perf1024WithHoleAndBridge() {
        // 32x32 minus center -> one component, one hole, no bridges.
        var grid = new HashSet<ChunkKey>();
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                if (x == 16 && z == 16) continue;
                grid.add(k(x, z));
            }
        }
        long start = System.nanoTime();
        var g = new ChunkGeometry(grid);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, g.componentCount());
        assertEquals(set(k(16, 16)), g.holes());
        assertTrue(g.bridges().isEmpty());
        assertTrue(elapsedMs < 2000, "1024-chunk with hole took " + elapsedMs + "ms (expected < 2000ms)");
        System.out.println("[ChunkGeometryTest] 1024-chunk with-hole judgment: " + elapsedMs + " ms");
    }
}

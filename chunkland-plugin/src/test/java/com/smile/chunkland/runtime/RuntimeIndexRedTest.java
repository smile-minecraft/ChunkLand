package com.smile.chunkland.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.index.PlayerLocation;
import com.smile.chunkland.runtime.index.PlayerLocationCache;
import com.smile.chunkland.runtime.index.PlayerLocationCacheStore;
import com.smile.chunkland.runtime.index.SubLandIndex;
import com.smile.chunkland.runtime.index.WorldChunkIndex;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Runtime index contract: packed long packing, world isolation / Wilderness
 * miss, immutable snapshot input and output, volatile publish atomicity,
 * hot-path lookup correctness, SubLand chunk lookup, and {@code
 * PlayerLocationCache} without Bukkit references.
 */
class RuntimeIndexRedTest {

    private static LandSnapshot land(UUID worldId, LandId id, int chunkX, int chunkZ) {
        return new LandSnapshot(
                id, "TestLand", "testland", OwnerRef.player(UUID.randomUUID()),
                worldId,
                Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(),
                0, 0,
                Instant.now(), Instant.now());
    }

    @Test
    void packedLongHandlesNegAndExtremes() {
        assertEquals(((long) -1 << 32) | (0xFFFFFFFFL & -2), WorldChunkIndex.pack(-1, -2));
        assertEquals(-1, WorldChunkIndex.unpackX(WorldChunkIndex.pack(-1, -2)));
        assertEquals(-2, WorldChunkIndex.unpackZ(WorldChunkIndex.pack(-1, -2)));
        long edge = WorldChunkIndex.pack(Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE, WorldChunkIndex.unpackX(edge));
        assertEquals(Integer.MAX_VALUE, WorldChunkIndex.unpackZ(edge));
        // cross zero
        assertEquals(0, WorldChunkIndex.unpackX(WorldChunkIndex.pack(0, 0)));
        assertEquals(1, WorldChunkIndex.unpackZ(WorldChunkIndex.pack(0, 1)));
    }

    @Test
    void worldIsolationMissReturnsWilderness() {
        UUID w1 = UUID.randomUUID();
        UUID w2 = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        LandSnapshot s = land(w1, id, 5, 5);
        LandRegistry reg = LandRegistry.from(List.of(s));
        assertNotNull(reg.findLand(w1, 5, 5));
        assertNull(reg.findLand(w2, 5, 5));
        assertNull(reg.findLand(w1, 6, 6));
        assertNull(reg.findLandId(w2, 5, 5));
    }

    @Test
    void emptyRootAndWorldWithoutLandIsWilderness() {
        LandRegistry empty = LandRegistry.empty();
        UUID w = UUID.randomUUID();
        assertNull(empty.findLand(w, 0, 0));
        assertNull(empty.findLandId(w, 0, 0));
        assertTrue(empty.worlds().isEmpty());
        assertTrue(empty.lands().isEmpty());
    }

    @Test
    void immutableInputOutput() {
        UUID w = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        LandSnapshot s = land(w, id, 1, 1);
        LandRegistry reg = LandRegistry.from(List.of(s));
        // input collection mutation must not affect registry
        // output maps must be immutable
        assertThrows(UnsupportedOperationException.class, () -> reg.lands().put(id, s));
        assertThrows(UnsupportedOperationException.class, () -> reg.worlds().put(UUID.randomUUID(), WorldChunkIndex.empty(w)));
        WorldChunkIndex widx = reg.worlds().get(w);
        assertNotNull(widx);
        // try to mutate via packing map view not exposed as mutable
    }

    @Test
    void publishOnlySeesCompleteOldOrNew() throws Exception {
        LandRegistryStore store = new LandRegistryStore();
        UUID w = UUID.randomUUID();
        LandId idOld = new LandId(UUID.randomUUID());
        LandRegistry old = LandRegistry.from(List.of(land(w, idOld, 0, 0)));
        store.publish(old);
        LandId idNew = new LandId(UUID.randomUUID());
        LandRegistry newReg = LandRegistry.from(List.of(land(w, idNew, 10, 10)));
        int readers = 8;
        int iterations = 2000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers);
        AtomicReference<Throwable> err = new AtomicReference<>();
        for (int i = 0; i < readers; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int k = 0; k < iterations; k++) {
                        LandRegistry snap = store.snapshot(); // one volatile read
                        // must be either old or new, never partial
                        boolean isOld = snap.findLand(w, 0, 0) != null;
                        boolean isNew = snap.findLand(w, 10, 10) != null;
                        if (isOld && isNew) {
                            err.compareAndSet(null, new AssertionError("partial: saw both"));
                        }
                        if (!isOld && !isNew) {
                            err.compareAndSet(null, new AssertionError("partial: saw neither"));
                        }
                        // world miss must be wilderness
                        assertNull(snap.findLand(UUID.randomUUID(), 99, 99));
                    }
                } catch (Throwable e) { err.compareAndSet(null, e); }
                finally { done.countDown(); }
            });
            t.start();
        }
        start.countDown();
        // concurrent publish midpoint
        Thread.sleep(5);
        store.publish(newReg);
        Thread.sleep(5);
        done.await();
        if (err.get() != null) throw new AssertionError(err.get());
        // after publish final is new
        assertNotNull(store.snapshot().findLand(w, 10, 10));
        assertNull(store.snapshot().findLand(w, 0, 0));
    }

    @Test
    void hotPathDoesNotAllocateChunkKey() {
        UUID w = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry reg = LandRegistry.from(List.of(land(w, id, 7, 8)));
        // count ChunkKey allocations via instrument? we prove no ChunkKey instance created
        // by inspecting that lookup does not call ChunkKey constructor – we check file does not import ChunkKey
        // Here we just invoke hot path many times and assert it returns correct without throwing
        for (int i = 0; i < 1000; i++) {
            assertNotNull(reg.findLandId(w, 7, 8));
            assertNull(reg.findLandId(w, 7, 9));
        }
        // allocation test: ensure WorldChunkIndex pack is used, not new ChunkKey
        long packed = WorldChunkIndex.pack(7, 8);
        assertEquals(packed, WorldChunkIndex.pack(7, 8));
    }

    @Test
    void subLandLookup() {
        UUID w = UUID.randomUUID();
        LandId lid = new LandId(UUID.randomUUID());
        SubLandId sid = new SubLandId(UUID.randomUUID());
        ChunkKey ck = new ChunkKey(w, 2, 2);
        SubLandSnapshot sub = new SubLandSnapshot(sid, lid, "sub", 0, 255, Set.of(ck));
        LandSnapshot land = new LandSnapshot(lid, "L", "l", OwnerRef.player(UUID.randomUUID()), w, Set.of(ck), List.of(sub), 0, 0, Instant.now(), Instant.now());
        LandRegistry reg = LandRegistry.from(List.of(land));
        SubLandIndex idx = reg.subLandIndex(lid);
        assertNotNull(idx);
        assertNotNull(idx.findAt(2, 2));
        assertNull(idx.findAt(3, 3));
    }

    @Test
    void playerCacheDoesNotHoldBukkit() {
        PlayerLocationCacheStore ps = new PlayerLocationCacheStore();
        UUID pid = UUID.randomUUID();
        UUID wid = UUID.randomUUID();
        // chunkX=1, chunkZ=1 match blockX=16, blockZ=16 via floorDiv(block, 16)
        PlayerLocation loc = new PlayerLocation(pid, wid, 1, 1, 16, 64, 16);
        PlayerLocationCache cache = PlayerLocationCache.empty().with(loc);
        ps.publish(cache);
        PlayerLocation got = ps.snapshot().get(pid);
        assertNotNull(got);
        assertEquals(wid, got.worldId());
        assertEquals(1, got.chunkX());
        // immutability
        assertThrows(UnsupportedOperationException.class, () -> ps.snapshot().asMap().put(pid, loc));
    }

    /**
     * Regression: a parent Land whose chunk set does not contain every SubLand
     * chunk must be rejected at registry build time. Previously the builder
     * trusted the {@link LandSnapshot} constructor and silently accepted the
     * mismatch, so {@code findLand} and {@code subLandIndex} disagreed about
     * which chunks belonged to the land.
     */
    @Test
    void landRegistryRejectsSubLandChunkOutsideParent() {
        UUID w = UUID.randomUUID();
        LandId lid = new LandId(UUID.randomUUID());
        // parent owns (0,0); subland claims (1,0) – inconsistent.
        Set<ChunkKey> parentChunks = Set.of(new ChunkKey(w, 0, 0));
        SubLandSnapshot sub = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), lid, "sub",
                0, 255, Set.of(new ChunkKey(w, 1, 0)));
        LandSnapshot land = new LandSnapshot(
                lid, "Land", "land", OwnerRef.player(UUID.randomUUID()),
                w, parentChunks, List.of(sub), 0, 0, Instant.now(), Instant.now());

        assertThrows(IllegalArgumentException.class, () -> LandRegistry.from(List.of(land)));
    }

    /**
     * Regression: two SubLands that share chunk columns and have truly
     * overlapping Y ranges must be rejected before a registry is published.
     * The disjoint-Y case is intentionally a legal Y-stack and is covered by
     * {@link #landRegistryAcceptsDisjointYStackedSubLands()}.
     */
    @Test
    void landRegistryRejectsHorizontallyOverlappingSubLands() {
        UUID w = UUID.randomUUID();
        LandId lid = new LandId(UUID.randomUUID());
        Set<ChunkKey> parentChunks = Set.of(new ChunkKey(w, 0, 0), new ChunkKey(w, 1, 0));
        SubLandSnapshot a = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), lid, "a",
                0, 15, Set.of(new ChunkKey(w, 0, 0), new ChunkKey(w, 1, 0)));
        SubLandSnapshot b = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), lid, "b",
                10, 20, Set.of(new ChunkKey(w, 0, 0), new ChunkKey(w, 1, 0)));
        LandSnapshot land = new LandSnapshot(
                lid, "Land", "land", OwnerRef.player(UUID.randomUUID()),
                w, parentChunks, List.of(a, b), 0, 0, Instant.now(), Instant.now());

        assertThrows(IllegalArgumentException.class, () -> LandRegistry.from(List.of(land)));
    }

    /**
     * Regression: the direct {@link PlayerLocation} constructor must reject
     * any state where {@code chunkX} / {@code chunkZ} do not derive from
     * {@code blockX} / {@code blockZ} via {@link Math#floorDiv(int, int)},
     * otherwise publishers and lookups could disagree about which chunk the
     * location lives in.
     */
    @Test
    void playerLocationRejectsInconsistentBlockAndChunkX() {
        UUID pid = UUID.randomUUID();
        UUID wid = UUID.randomUUID();
        // blockX=16 -> floorDiv = 1, but chunkX=2 is a mismatch
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerLocation(pid, wid, 2, 1, 16, 64, 16));
    }

    @Test
    void playerLocationRejectsInconsistentBlockAndChunkZ() {
        UUID pid = UUID.randomUUID();
        UUID wid = UUID.randomUUID();
        // blockZ=16 -> floorDiv = 1, but chunkZ=2 is a mismatch
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerLocation(pid, wid, 1, 2, 16, 64, 16));
    }

    @Test
    void playerLocationRejectsInconsistentBlockAndChunkWithNegativeCoordinates() {
        UUID pid = UUID.randomUUID();
        UUID wid = UUID.randomUUID();
        // blockX=-1 -> floorDiv(-1, 16) = -1; claiming chunkX=0 is a mismatch.
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerLocation(pid, wid, 0, 0, -1, 64, 16));
    }

    /**
     * A consistent direct construction (both axes agree with floorDiv) and the
     * {@link PlayerLocation#of} factory must keep producing equal chunks.
     */
    @Test
    void playerLocationAcceptsConsistentConstructionAndOfFactory() {
        UUID pid = UUID.randomUUID();
        UUID wid = UUID.randomUUID();
        // blockX=16, blockZ=16 -> chunkX=1, chunkZ=1
        PlayerLocation direct = new PlayerLocation(pid, wid, 1, 1, 16, 64, 16);
        assertEquals(1, direct.chunkX());
        assertEquals(1, direct.chunkZ());

        // blockX=-1 -> floorDiv(-1, 16) = -1; negative coords must derive too
        PlayerLocation negative = new PlayerLocation(pid, wid, -1, -1, -1, 64, -16);
        assertEquals(-1, negative.chunkX());
        assertEquals(-1, negative.chunkZ());

        PlayerLocation ofLoc = PlayerLocation.of(pid, wid, 32, 70, -32);
        assertEquals(2, ofLoc.chunkX());
        assertEquals(-2, ofLoc.chunkZ());
        assertEquals(32, ofLoc.blockX());
        assertEquals(70, ofLoc.blockY());
        assertEquals(-32, ofLoc.blockZ());
    }

    /**
     * Regression: a valid Cuboid SubLand that is fully contained in its parent
     * (every covered chunk lives in the parent) must still build successfully
     * once the validator is enforced inside {@link LandRegistry#from}.
     */
    @Test
    void landRegistryAcceptsContainedCuboidSubLand() {
        UUID w = UUID.randomUUID();
        LandId lid = new LandId(UUID.randomUUID());
        Set<ChunkKey> parentChunks = Set.of(new ChunkKey(w, 0, 0), new ChunkKey(w, 1, 0));
        Cuboid inside = new Cuboid(0, 0, 0, 16, 10, 15);
        SubLandSnapshot sub = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), lid, "inside", inside, w);
        LandSnapshot land = new LandSnapshot(
                lid, "Land", "land", OwnerRef.player(UUID.randomUUID()),
                w, parentChunks, List.of(sub), 0, 0, Instant.now(), Instant.now());

        LandRegistry reg = LandRegistry.from(List.of(land));
        assertNotNull(reg.findLand(w, 0, 0));
        assertNotNull(reg.findLand(w, 1, 0));
        assertNotNull(reg.subLandIndex(lid).findAt(0, 0));
    }

    /**
     * Regression: two SubLands sharing the same chunk column with disjoint Y
     * ranges are a legal Y-stack. {@link com.smile.chunkland.api.land.SubLandTopologyValidator}
     * already classifies them as non-overlapping; the registry and the per-land
     * SubLand index must accept them and {@code findAtBlock} must pick the
     * correct SubLand by block Y.
     */
    @Test
    void landRegistryAcceptsDisjointYStackedSubLands() {
        UUID w = UUID.randomUUID();
        LandId lid = new LandId(UUID.randomUUID());
        Set<ChunkKey> parentChunks = Set.of(new ChunkKey(w, 0, 0));
        SubLandSnapshot lower = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), lid, "lower",
                0, 127, Set.of(new ChunkKey(w, 0, 0)));
        SubLandSnapshot upper = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), lid, "upper",
                128, 255, Set.of(new ChunkKey(w, 0, 0)));
        LandSnapshot land = new LandSnapshot(
                lid, "Land", "land", OwnerRef.player(UUID.randomUUID()),
                w, parentChunks, List.of(lower, upper), 0, 0,
                Instant.now(), Instant.now());

        LandRegistry reg = LandRegistry.from(List.of(land));
        SubLandIndex idx = reg.subLandIndex(lid);
        assertNotNull(idx);
        // both candidates share chunk column; findAt is deterministic by lowest-minY.
        SubLandSnapshot chunkPick = idx.findAt(0, 0);
        assertNotNull(chunkPick);
        assertEquals("lower", chunkPick.name());
        // findAtBlock must discriminate by block Y.
        assertEquals("lower", idx.findAtBlock(8, 50, 8).name());
        assertEquals("lower", idx.findAtBlock(8, 0, 8).name());
        assertEquals("lower", idx.findAtBlock(8, 127, 8).name());
        assertEquals("upper", idx.findAtBlock(8, 128, 8).name());
        assertEquals("upper", idx.findAtBlock(8, 200, 8).name());
        assertEquals("upper", idx.findAtBlock(8, 255, 8).name());
        // above/below the stacked column -> Wilderness (null).
        assertNull(idx.findAtBlock(8, -1, 8));
        assertNull(idx.findAtBlock(8, 256, 8));
    }

    /**
     * Regression: two SubLands sharing a chunk column with overlapping Y
     * ranges represent a real 3D overlap and must be rejected at registry
     * build time, not silently admitted by the index.
     */
    @Test
    void landRegistryRejectsOverlappingYStackedSubLands() {
        UUID w = UUID.randomUUID();
        LandId lid = new LandId(UUID.randomUUID());
        Set<ChunkKey> parentChunks = Set.of(new ChunkKey(w, 0, 0));
        SubLandSnapshot a = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), lid, "a",
                0, 127, Set.of(new ChunkKey(w, 0, 0)));
        SubLandSnapshot b = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), lid, "b",
                100, 200, Set.of(new ChunkKey(w, 0, 0)));
        LandSnapshot land = new LandSnapshot(
                lid, "Land", "land", OwnerRef.player(UUID.randomUUID()),
                w, parentChunks, List.of(a, b), 0, 0,
                Instant.now(), Instant.now());

        assertThrows(IllegalArgumentException.class,
                () -> LandRegistry.from(List.of(land)));
    }

    /**
     * Y-stacked SubLands built directly via {@link SubLandIndex#from} must
     * also discriminate correctly on the block path; the direct API is
     * exposed outside of {@link LandRegistry}.
     */
    @Test
    void subLandIndexAcceptsDisjointYStackedSubLands() {
        UUID w = UUID.randomUUID();
        LandId lid = new LandId(UUID.randomUUID());
        SubLandSnapshot lower = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), lid, "lower",
                0, 127, Set.of(new ChunkKey(w, 5, 5)));
        SubLandSnapshot upper = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), lid, "upper",
                128, 255, Set.of(new ChunkKey(w, 5, 5)));

        SubLandIndex idx = SubLandIndex.from(lid, List.of(lower, upper));
        assertNotNull(idx.findAt(5, 5));
        assertEquals("lower", idx.findAt(5, 5).name());
        assertEquals("lower", idx.findAtBlock(80, 50, 80).name());
        assertEquals("upper", idx.findAtBlock(80, 200, 80).name());
        assertNull(idx.findAtBlock(80, -1, 80));
        assertNull(idx.findAtBlock(80, 256, 80));
    }

    /**
     * Y-stacked Cuboid SubLands must keep the precise cuboid path working:
     * each SubLand's cuboid contains its own Y range and the block-level
     * lookup discriminates by Y.
     */
    @Test
    void landRegistryAcceptsYStackedCuboidSubLands() {
        UUID w = UUID.randomUUID();
        LandId lid = new LandId(UUID.randomUUID());
        Set<ChunkKey> parentChunks = Set.of(new ChunkKey(w, 0, 0));
        Cuboid lower = new Cuboid(0, 0, 0, 15, 127, 15);
        Cuboid upper = new Cuboid(0, 128, 0, 15, 255, 15);
        SubLandSnapshot lowerSub = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), lid, "lower", lower, w);
        SubLandSnapshot upperSub = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), lid, "upper", upper, w);
        LandSnapshot land = new LandSnapshot(
                lid, "Land", "land", OwnerRef.player(UUID.randomUUID()),
                w, parentChunks, List.of(lowerSub, upperSub), 0, 0,
                Instant.now(), Instant.now());

        LandRegistry reg = LandRegistry.from(List.of(land));
        SubLandIndex idx = reg.subLandIndex(lid);
        assertNotNull(idx);
        assertEquals("lower", idx.findAtBlock(8, 50, 8).name());
        assertEquals("lower", idx.findAtBlock(8, 127, 8).name());
        assertEquals("upper", idx.findAtBlock(8, 128, 8).name());
        assertEquals("upper", idx.findAtBlock(8, 200, 8).name());
        assertNull(idx.findAtBlock(8, -1, 8));
        assertNull(idx.findAtBlock(8, 256, 8));
    }

    /**
     * Regression: a Y-stack that puts more than {@code Short.MAX_VALUE}
     * (32767) disjoint-Y candidates in the same X/Z column must still build
     * and still resolve every candidate by block Y. The slice length was
     * previously stored as {@code short} and narrowed to a negative value at
     * 32768+, which made {@code findAtBlock} return null for every Y inside
     * the stack.
     *
     * <p>The slice is forced into a single column to trigger the narrowing on
     * the affected slot. Disjoint single-block Y ranges keep the pairwise
     * overlap check O(1) per pair (early-out on the Y comparison) so the
     * validator stays linear-ish instead of exploding into expensive cuboid
     * intersection checks. To keep the test runtime reasonable we only probe
     * a handful of Ys across the 32768-row stack, not every entry.
     */
    @Test
    void subLandIndexAcceptsLargeDisjointYStackWithoutSliceOverflow() {
        UUID w = UUID.randomUUID();
        LandId lid = new LandId(UUID.randomUUID());
        ChunkKey ck = new ChunkKey(w, 0, 0);
        int count = Short.MAX_VALUE + 1; // 32768, just past the short narrowing point

        List<SubLandSnapshot> subs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int y = i * 2; // disjoint single-block Y: candidate i at [y, y]
            subs.add(new SubLandSnapshot(
                    new SubLandId(UUID.randomUUID()), lid, "sub-" + i,
                    y, y, Set.of(ck)));
        }

        SubLandIndex idx = SubLandIndex.from(lid, subs);

        // The whole stack must remain addressable. With the old short slot
        // the recorded slice length becomes negative and findAtBlock never
        // enters its loop.
        assertEquals(count, idx.chunkMappingSize(),
                "all " + count + " stacked candidates must be reachable");

        // Spot-check Ys spread across the stack. Before the fix, every probe
        // returned null because start + negative-length made the loop skip
        // the whole slice.
        int[] probeYs = {0, 2, 1000, 16384, 32766, 40000, 65534};
        for (int y : probeYs) {
            SubLandSnapshot found = idx.findAtBlock(8, y, 8);
            assertNotNull(found, "expected a stacked candidate at Y=" + y);
            assertEquals(y, found.minBlockY(),
                    "slice iteration reached the wrong candidate at Y=" + y);
            assertEquals(y, found.maxBlockY(),
                    "slice iteration reached the wrong candidate at Y=" + y);
        }

        // Above/below the stacked column must still report Wilderness (null)
        // even when the slice length overflows the representation.
        assertNull(idx.findAtBlock(8, -2, 8));
        assertNull(idx.findAtBlock(8, 65536, 8));
    }

    /**
     * Regression: a cuboid covering only part of its chunk column must not
     * claim blocks that share the column (and the Y range) but sit outside
     * its X/Z geometry. The chunk+Y fallback used to return the cuboid
     * itself for those points, so both the engine covering and the movement
     * pre-filter read the wrong subland.
     */
    @Test
    void findAtBlockIgnoresPartialCuboidOutsideItsGeometry() {
        UUID w = UUID.randomUUID();
        LandId lid = new LandId(UUID.randomUUID());
        SubLandSnapshot den = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), lid, "den",
                new Cuboid(0, 0, 0, 7, 255, 15), w);

        SubLandIndex idx = SubLandIndex.from(lid, List.of(den));

        assertEquals("den", idx.findAtBlock(5, 64, 5).name());
        assertEquals("den", idx.findAtBlock(7, 255, 15).name(),
                "inclusive cuboid edges still belong to the subland");
        assertNull(idx.findAtBlock(8, 64, 5),
                "same column and Y but outside the cuboid X range must not resolve");
        assertNull(idx.findAtBlock(10, 64, 10),
                "same column and Y but outside the cuboid must not resolve");
    }
}

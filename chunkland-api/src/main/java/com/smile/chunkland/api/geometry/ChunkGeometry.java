package com.smile.chunkland.api.geometry;

import com.smile.chunkland.api.land.ChunkKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable 4-neighbor geometry analysis over a set of chunk coordinates.
 *
 * <p>Connectivity is defined by the four orthogonal neighbors (N/E/S/W). Two
 * chunks are connected only when they share an edge; diagonal contact (a corner
 * touch) is explicitly NOT connectivity. This matches the domain rule that a
 * chunk is selected, split, or deleted only along shared edges.
 *
 * <p>Hole semantics (infinite world): the exterior is the infinite connected
 * region of empty cells. A hole is a maximal finite region of empty cells that
 * cannot reach the exterior. Concretely, flood-fill the empty cells starting
 * from the frame just outside the set's bounding box (those cells are empty and
 * connect to infinity); every empty cell inside the bounding box that the flood
 * never reaches is a hole. Consequences:
 * <ul>
 *   <li>An empty cell on the boundary of the set's extent is exterior, not a hole.</li>
 *   <li>A U-shaped set has no hole because its interior opens to the exterior.</li>
 *   <li>Only a fully enclosed ring produces a hole.</li>
 * </ul>
 *
 * <p>A bridge is a chunk whose removal increases the number of connected
 * components. Removing a non-bridge never increases the component count.
 *
 * <p>All returned collections are immutable snapshots; the instance never
 * exposes a mutable internal collection. The analysis performs no I/O and never
 * loads a chunk, and depends only on the JDK.
 */
public final class ChunkGeometry {

    /** Orthogonal neighbor offsets: N, E, S, W. */
    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DZ = {1, 0, -1, 0};

    /**
     * Above this many cells, hole analysis is rejected rather than enumerating
     * the whole bounding box (which would be pathological for a near-full
     * int-coordinate range, and whose area overflows a long). 1024-chunk sets
     * are far below this; selection/split/delete on such extreme spans is out
     * of scope and fails fast with a clear message instead of hanging or OOM.
     */
    private static final long MAX_HOLE_ENUMERATION_CELLS = 50_000_000L;

    private final UUID worldId;
    private final Set<Long> packed;

    private final List<Set<ChunkKey>> components;
    private final Set<ChunkKey> holes;
    private final Set<ChunkKey> bridges;

    /**
     * Build the geometry for a single-world chunk set.
     *
     * <p>Construction eagerly computes components, holes, and bridges, so any
     * rejection happens here in the constructor rather than when a result
     * accessor is later called.
     *
     * @throws NullPointerException     if {@code chunks} or any element is null
     * @throws IllegalArgumentException if {@code chunks} mixes more than one world id,
     *                                  or its bounding box spans more than
     *                                  {@code MAX_HOLE_ENUMERATION_CELLS} (50,000,000)
     *                                  cells. The latter is a deliberate, explicit limit:
     *                                  enumerating holes over a near-full int-coordinate
     *                                  range would be pathological (the area itself
     *                                  overflows a long) and would hang or OOM, so such
     *                                  sets are rejected up front instead of failing later
     *                                  inside {@link #holes()}.
     */
    public ChunkGeometry(Set<ChunkKey> chunks) {
        Objects.requireNonNull(chunks, "chunks");
        UUID wid = null;
        Set<Long> pk = new HashSet<>(chunks.size());
        for (ChunkKey key : chunks) {
            Objects.requireNonNull(key, "chunks must not contain null");
            if (wid == null) {
                wid = key.worldId();
            } else if (!wid.equals(key.worldId())) {
                throw new IllegalArgumentException(
                        "ChunkGeometry requires a single worldId; found mixed worlds");
            }
            pk.add(key.pack());
        }
        this.worldId = wid;
        this.packed = Collections.unmodifiableSet(pk);

        List<Set<Long>> rawComponents = computeComponents(pk);
        this.components = Collections.unmodifiableList(toChunkKeyComponents(rawComponents));
        this.holes = Collections.unmodifiableSet(toChunkKeys(computeHoles()));
        this.bridges = Collections.unmodifiableSet(toChunkKeys(computeBridges(rawComponents)));
    }

    /** Immutable copy of the input chunk set (empty when constructed from none). */
    public Set<ChunkKey> chunks() {
        return toChunkKeys(packed);
    }

    /** Number of 4-connected components (0 for an empty set). */
    public int componentCount() {
        return components.size();
    }

    /** Whether the set forms at most one 4-connected component. */
    public boolean isConnected() {
        return components.size() <= 1;
    }

    /** Components as an immutable list of immutable chunk sets. */
    public List<Set<ChunkKey>> components() {
        return components;
    }

    /** Empty cells fully enclosed by the set, as immutable chunk coordinates. */
    public Set<ChunkKey> holes() {
        return holes;
    }

    /** Chunks whose removal would split the set, as an immutable set. */
    public Set<ChunkKey> bridges() {
        return bridges;
    }

    /**
     * Whether {@code chunk} is a bridge (its removal increases component count).
     *
     * <p>The chunk must belong to this geometry's world. A {@code null} chunk is
     * rejected; a chunk from a different world is rejected with
     * {@link IllegalArgumentException} rather than silently matched by x/z, since
     * the same coordinates in another world are a different chunk. An empty
     * geometry (no world) has no bridges and always returns {@code false}.
     */
    public boolean isBridge(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        if (worldId == null) {
            return false; // empty geometry has no bridges
        }
        if (!worldId.equals(chunk.worldId())) {
            throw new IllegalArgumentException(
                    "chunk worldId does not match this geometry's worldId");
        }
        return bridges.contains(chunk);
    }

    // ---- internals ----

    private List<Set<ChunkKey>> toChunkKeyComponents(List<Set<Long>> raw) {
        List<Set<ChunkKey>> out = new ArrayList<>(raw.size());
        for (Set<Long> comp : raw) {
            out.add(toChunkKeys(comp));
        }
        return out;
    }

    private Set<ChunkKey> toChunkKeys(Set<Long> raw) {
        if (worldId == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<ChunkKey> out = new HashSet<>(raw.size());
        for (long p : raw) {
            out.add(ChunkKey.unpack(worldId, p));
        }
        return Set.copyOf(out);
    }

    private static List<Set<Long>> computeComponents(Set<Long> set) {
        Set<Long> remaining = new HashSet<>(set);
        List<Set<Long>> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            long start = remaining.iterator().next();
            Set<Long> comp = new HashSet<>();
            Deque<Long> stack = new ArrayDeque<>();
            stack.push(start);
            remaining.remove(start);
            while (!stack.isEmpty()) {
                long cur = stack.pop();
                comp.add(cur);
                for (int d = 0; d < 4; d++) {
                    Long nb = neighbor(cur, DX[d], DZ[d]);
                    if (nb != null && set.contains(nb) && remaining.remove(nb)) {
                        stack.push(nb);
                    }
                }
            }
            result.add(comp);
        }
        return result;
    }

    private Set<Long> computeHoles() {
        if (packed.isEmpty()) {
            return Set.of();
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (long p : packed) {
            int x = (int) (p >>> 32), z = (int) (p & 0xFFFFFFFFL);
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }

        // Fail fast on a bounding box too large to enumerate safely. The full
        // int-coordinate range would make the O(area) scan pathological (and the
        // area itself overflows a long), so reject up front with a clear message.
        long w = (long) maxX - minX + 1;
        long h = (long) maxZ - minZ + 1;
        if (w > MAX_HOLE_ENUMERATION_CELLS
                || h > MAX_HOLE_ENUMERATION_CELLS
                || w * h > MAX_HOLE_ENUMERATION_CELLS) {
            throw new IllegalArgumentException(
                    "chunk set bounding box too large to analyze for holes ("
                            + w + " x " + h + " cells); reduce the set or analyze a sub-region");
        }

        // Frame just outside the bounding box, clamped to the valid int range so
        // the arithmetic never overflows. When the set already touches an int
        // extreme, the frame coincides with the world edge, which is correctly
        // treated as exterior.
        int fMinX = decClamped(minX);
        int fMaxX = incClamped(maxX);
        int fMinZ = decClamped(minZ);
        int fMaxZ = incClamped(maxZ);

        // Flood the exterior (empty cells reachable from the bounding-box frame).
        // The frame loops use overflow-safe iteration: when the bound is at the
        // int extreme, a plain `x++` would wrap and never terminate.
        Set<Long> exterior = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        seedColumn(fMinZ, fMinX, fMaxX, exterior, queue);
        seedColumn(fMaxZ, fMinX, fMaxX, exterior, queue);
        seedRow(fMinX, fMinZ, fMaxZ, exterior, queue);
        seedRow(fMaxX, fMinZ, fMaxZ, exterior, queue);
        while (!queue.isEmpty()) {
            long cur = queue.poll();
            if (!exterior.add(cur)) continue;
            for (int d = 0; d < 4; d++) {
                Long nb = neighbor(cur, DX[d], DZ[d]);
                if (nb == null) continue;
                int nx = (int) (nb >>> 32), nz = (int) (nb & 0xFFFFFFFFL);
                if (nx < fMinX || nx > fMaxX || nz < fMinZ || nz > fMaxZ) continue;
                if (packed.contains(nb) || exterior.contains(nb)) continue;
                queue.add(nb);
            }
        }

        // Holes = empty cells inside the bounding box not reached by the exterior.
        // Same overflow-safe iteration over the (possibly extreme) bounding box.
        Set<Long> holes = new HashSet<>();
        for (int x = minX; ; ) {
            for (int z = minZ; ; ) {
                long p = pack(x, z);
                if (!packed.contains(p) && !exterior.contains(p)) {
                    holes.add(p);
                }
                if (z == maxZ) break;
                z++;
            }
            if (x == maxX) break;
            x++;
        }
        return holes.isEmpty() ? Set.of() : Set.copyOf(holes);
    }

    /** Seed the exterior along a column at fixed z, iterating x in [xFrom, xTo]. */
    private void seedColumn(int zFixed, int xFrom, int xTo, Set<Long> exterior, Deque<Long> queue) {
        for (int x = xFrom; ; ) {
            seedExterior(x, zFixed, exterior, queue);
            if (x == xTo) break;
            x++;
        }
    }

    /** Seed the exterior along a row at fixed x, iterating z in [zFrom, zTo]. */
    private void seedRow(int xFixed, int zFrom, int zTo, Set<Long> exterior, Deque<Long> queue) {
        for (int z = zFrom; ; ) {
            seedExterior(xFixed, z, exterior, queue);
            if (z == zTo) break;
            z++;
        }
    }

    /** Decrement, clamped at the minimum valid coordinate (no underflow). */
    private static int decClamped(int v) {
        return v == Integer.MIN_VALUE ? Integer.MIN_VALUE : v - 1;
    }

    /** Increment, clamped at the maximum valid coordinate (no overflow). */
    private static int incClamped(int v) {
        return v == Integer.MAX_VALUE ? Integer.MAX_VALUE : v + 1;
    }

    private void seedExterior(int x, int z, Set<Long> exterior, Deque<Long> queue) {
        long p = pack(x, z);
        if (packed.contains(p) || exterior.contains(p)) return;
        queue.add(p);
    }

    /**
     * Bridges are articulation points of the 4-neighbor graph: removing one
     * increases the component count. Computed in O(n) per component via Tarjan's
     * lowlink rule rather than by O(n^2) removal trials, so a 1024-chunk set is
     * judged in milliseconds.
     */
    private Set<Long> computeBridges(List<Set<Long>> baseComponents) {
        Set<Long> bridges = new HashSet<>();
        for (Set<Long> comp : baseComponents) {
            if (comp.size() <= 2) continue; // singleton or pair never splits on removal
            findArticulationPoints(comp, bridges);
        }
        return bridges.isEmpty() ? Set.of() : Set.copyOf(bridges);
    }

    private void findArticulationPoints(Set<Long> comp, Set<Long> out) {
        Map<Long, Integer> disc = new HashMap<>();
        Map<Long, Integer> low = new HashMap<>();
        int[] time = {0};
        for (long root : comp) {
            if (disc.containsKey(root)) continue;
            Deque<Frame> stack = new ArrayDeque<>();
            stack.push(new Frame(root, null));
            int rootChildren = 0;
            while (!stack.isEmpty()) {
                Frame f = stack.peek();
                if (!f.visited) {
                    f.visited = true;
                    disc.put(f.u, ++time[0]);
                    low.put(f.u, time[0]);
                    f.it = neighborIterator(f.u, comp);
                }
                if (f.it != null && f.it.hasNext()) {
                    long v = f.it.next();
                    if (f.parent != null && v == f.parent) continue;
                    if (!disc.containsKey(v)) {
                        if (f.parent == null) rootChildren++;
                        stack.push(new Frame(v, f.u));
                    } else {
                        // back edge to an ancestor
                        low.put(f.u, Math.min(low.get(f.u), disc.get(v)));
                    }
                } else {
                    stack.pop();
                    if (f.parent != null) {
                        low.put(f.parent, Math.min(low.get(f.parent), low.get(f.u)));
                        if (f.parent != root && low.get(f.u) >= disc.get(f.parent)) {
                            out.add(f.parent);
                        }
                    } else if (rootChildren > 1) {
                        out.add(f.u);
                    }
                }
            }
        }
    }

    private Iterator<Long> neighborIterator(long u, Set<Long> comp) {
        List<Long> ns = new ArrayList<>(4);
        for (int d = 0; d < 4; d++) {
            Long nb = neighbor(u, DX[d], DZ[d]);
            if (nb != null && comp.contains(nb)) ns.add(nb);
        }
        return ns.iterator();
    }

    private static final class Frame {
        final long u;
        final Long parent;
        boolean visited;
        Iterator<Long> it;

        Frame(long u, Long parent) {
            this.u = u;
            this.parent = parent;
        }
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Orthogonal neighbor of the packed coordinate, or {@code null} when the
     * neighbor would fall outside the valid {@code int} chunk-coordinate range.
     * Coordinates are computed in {@code long} and range-checked before any
     * {@code long -> int} conversion, so {@code Integer.MAX_VALUE + 1} and
     * {@code Integer.MIN_VALUE - 1} never wrap and are never mistaken for a
     * valid (and possibly occupied) coordinate. No magic sentinel is used.
     */
    private static Long neighbor(long p, int dx, int dz) {
        int x = (int) (p >>> 32);
        int z = (int) (p & 0xFFFFFFFFL);
        long nx = (long) x + dx;
        long nz = (long) z + dz;
        if (nx < Integer.MIN_VALUE || nx > Integer.MAX_VALUE
                || nz < Integer.MIN_VALUE || nz > Integer.MAX_VALUE) {
            return null;
        }
        return pack((int) nx, (int) nz);
    }
}

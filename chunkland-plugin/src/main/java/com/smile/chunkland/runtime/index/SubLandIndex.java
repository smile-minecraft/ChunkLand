package com.smile.chunkland.runtime.index;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.land.SubLandTopologyValidator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable index of sublands belonging to a single parent Land.
 *
 * <p>Multiple SubLands may share the same X/Z chunk column when their Y
 * ranges do not overlap (a Y-stack). Each chunk column therefore maps to a
 * small immutable slice of {@link SubLandSnapshot} entries, sorted by
 * {@code (minBlockY, id)}. {@link #findAt(int, int)} is deterministic and
 * returns the lowest-minY sub (ties broken by id). {@link #findAtBlock(int,
 * int, int)} iterates that slice to discriminate by block Y.
 *
 * <p>Duplicate {@link SubLandId}s, parent-id mismatch, and true 3D overlap
 * (same chunk + overlapping Y ranges) are rejected at construction; the 3D
 * overlap rule is delegated to {@link SubLandTopologyValidator#overlaps} so
 * the index never contradicts the domain rule.
 *
 * <p>Hot path uses packed {@code long} keys only; no {@code ChunkKey},
 * Bukkit, SQL or I/O is referenced.
 */
public final class SubLandIndex {

    private final LandId parentLandId;
    private final List<SubLandSnapshot> subLands;
    private final Map<SubLandId, SubLandSnapshot> byId;

    // Open-addressed hash table mapping packed chunk -> slice in flatCandidates.
    private final long[] keys;
    private final int[] offsets;   // start index in flatCandidates (valid only when occupied)
    // slice length is int (not short): legal same-X/Z disjoint-Y stacks can
    // produce more than Short.MAX_VALUE candidates in a single column, and
    // a narrowing overflow used to make findAtBlock skip the whole slice.
    private final int[] lengths;
    private final boolean[] occupied;
    private final int mask;
    private final SubLandSnapshot[] flatCandidates;

    private SubLandIndex(LandId parentLandId,
                         List<SubLandSnapshot> subLands,
                         long[] keys,
                         int[] offsets,
                         int[] lengths,
                         boolean[] occupied,
                         int mask,
                         SubLandSnapshot[] flatCandidates,
                         Map<SubLandId, SubLandSnapshot> byId) {
        this.parentLandId = parentLandId;
        this.subLands = subLands;
        this.keys = keys;
        this.offsets = offsets;
        this.lengths = lengths;
        this.occupied = occupied;
        this.mask = mask;
        this.flatCandidates = flatCandidates;
        this.byId = byId;
    }

    public static SubLandIndex empty(LandId parentLandId) {
        Objects.requireNonNull(parentLandId, "parentLandId");
        return new SubLandIndex(parentLandId, List.of(),
                new long[0], new int[0], new int[0], new boolean[0], 0,
                new SubLandSnapshot[0], Map.of());
    }

    public static SubLandIndex from(LandId parentLandId, Collection<SubLandSnapshot> subs) {
        Objects.requireNonNull(parentLandId, "parentLandId");
        Objects.requireNonNull(subs, "subs");
        if (subs.isEmpty()) return empty(parentLandId);
        List<SubLandSnapshot> copy = List.copyOf(subs);

        // 1. parent-id match
        for (SubLandSnapshot s : copy) {
            if (!parentLandId.equals(s.parentLandId())) {
                throw new IllegalArgumentException(
                        "sub parent mismatch: " + s.parentLandId() + " vs " + parentLandId);
            }
        }

        // 2. duplicate id
        Map<SubLandId, SubLandSnapshot> byId = new HashMap<>();
        for (SubLandSnapshot s : copy) {
            if (byId.putIfAbsent(s.id(), s) != null) {
                throw new IllegalArgumentException("duplicate sub id " + s.id());
            }
        }

        // 3. Group SubLands by packed chunk; reject same-chunk + overlapping Y (3D overlap)
        // via the single domain rule in SubLandTopologyValidator.
        Map<Long, List<SubLandSnapshot>> byPacked = new HashMap<>();
        for (SubLandSnapshot s : copy) {
            for (ChunkKey ck : s.chunks()) {
                long packed = WorldChunkIndex.pack(ck.chunkX(), ck.chunkZ());
                byPacked.computeIfAbsent(packed, k -> new ArrayList<>(2)).add(s);
            }
        }
        // Sort each per-chunk slice deterministically and check pairwise overlap.
        for (Map.Entry<Long, List<SubLandSnapshot>> entry : byPacked.entrySet()) {
            List<SubLandSnapshot> list = entry.getValue();
            if (list.size() < 2) continue;
            list.sort(SUB_ORDER);
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    if (SubLandTopologyValidator.overlaps(list.get(i), list.get(j))) {
                        throw new IllegalArgumentException(
                                "overlapping sub candidates at packed chunk "
                                        + entry.getKey() + " for subs "
                                        + list.get(i).id() + " and " + list.get(j).id());
                    }
                }
            }
        }

        // 4. Lay out candidates into flat arrays indexed by the hash table.
        Map<SubLandId, SubLandSnapshot> byIdUnmod = Collections.unmodifiableMap(byId);
        int chunkCount = byPacked.size();
        if (chunkCount == 0) {
            return new SubLandIndex(parentLandId, copy,
                    new long[0], new int[0], new int[0], new boolean[0], 0,
                    new SubLandSnapshot[0], byIdUnmod);
        }
        int totalEntries = 0;
        for (List<SubLandSnapshot> list : byPacked.values()) totalEntries += list.size();
        int capacity = tableCapacity(chunkCount);
        long[] keys = new long[capacity];
        int[] offsets = new int[capacity];
        int[] lengths = new int[capacity];
        boolean[] occ = new boolean[capacity];
        int mask = capacity - 1;
        SubLandSnapshot[] flat = new SubLandSnapshot[totalEntries];
        int cursor = 0;

        // Iterate packed keys in deterministic order so the resulting table is
        // reproducible across rebuilds from identical inputs.
        List<Long> orderedKeys = new ArrayList<>(byPacked.keySet());
        Collections.sort(orderedKeys);
        for (long packed : orderedKeys) {
            List<SubLandSnapshot> list = byPacked.get(packed);
            list.sort(SUB_ORDER);
            int idx = probe(packed, mask, keys, occ);
            keys[idx] = packed;
            offsets[idx] = cursor;
            lengths[idx] = list.size();
            occ[idx] = true;
            for (int i = 0; i < list.size(); i++) {
                flat[cursor++] = list.get(i);
            }
        }

        return new SubLandIndex(parentLandId, copy, keys, offsets, lengths, occ, mask,
                flat, byIdUnmod);
    }

    public LandId parentLandId() { return parentLandId; }

    public List<SubLandSnapshot> subLands() { return subLands; }

    /** Total number of (chunk, sub) entries across every chunk column. */
    public int chunkMappingSize() { return flatCandidates.length; }

    /**
     * Hot path chunk -> subland (null if none). No {@code ChunkKey}.
     *
     * <p>Deterministic: when multiple SubLands stack in the same X/Z column
     * with disjoint Y ranges, the lowest-minY entry (ties broken by
     * {@link SubLandId}) is returned. Callers that need block-level precision
     * must use {@link #findAtBlock(int, int, int)} instead.
     */
    public SubLandSnapshot findAt(int chunkX, int chunkZ) {
        if (flatCandidates.length == 0) return null;
        long packed = WorldChunkIndex.pack(chunkX, chunkZ);
        int idx = hash(packed) & mask;
        while (occupied[idx]) {
            if (keys[idx] == packed) {
                return flatCandidates[offsets[idx]];
            }
            idx = (idx + 1) & mask;
        }
        return null;
    }

    /** Lookup by SubLandId (null if absent). */
    public SubLandSnapshot findById(SubLandId id) {
        Objects.requireNonNull(id, "id");
        return byId.get(id);
    }

    /**
     * Precise block position lookup within the parent land. If any SubLand
     * has precise geometry, tests cuboid containment first. Otherwise inspects
     * the chunk column pointed at by the block and discriminates by block Y.
     * Candidates with precise geometry already failed containment above, so
     * the column fallback only considers chunk-based SubLands: a partial
     * cuboid never covers its whole column. Returns {@code null} if no
     * SubLand covers the position.
     */
    public SubLandSnapshot findAtBlock(int blockX, int blockY, int blockZ) {
        if (subLands.isEmpty()) return null;
        // precise cuboid path
        for (SubLandSnapshot s : subLands) {
            if (s.cuboid() != null) {
                var c = s.cuboid();
                if (blockX >= c.minX() && blockX <= c.maxX()
                        && blockY >= c.minY() && blockY <= c.maxY()
                        && blockZ >= c.minZ() && blockZ <= c.maxZ()) {
                    return s;
                }
            }
        }
        // chunk + Y fallback: discriminate among every candidate in the column.
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        if (flatCandidates.length == 0) return null;
        long packed = WorldChunkIndex.pack(chunkX, chunkZ);
        int idx = hash(packed) & mask;
        while (occupied[idx]) {
            if (keys[idx] == packed) {
                int start = offsets[idx];
                int end = start + lengths[idx];
                for (int i = start; i < end; i++) {
                    SubLandSnapshot s = flatCandidates[i];
                    if (s.cuboid() != null) {
                        continue;
                    }
                    if (blockY >= s.minBlockY() && blockY <= s.maxBlockY()) {
                        return s;
                    }
                }
                return null;
            }
            idx = (idx + 1) & mask;
        }
        return null;
    }

    private static final Comparator<SubLandSnapshot> SUB_ORDER =
            Comparator.comparingInt(SubLandSnapshot::minBlockY)
                    .thenComparing(s -> s.id().value(), Comparator.naturalOrder());

    private static int hash(long packed) {
        int h = (int) (packed ^ (packed >>> 32));
        h ^= (h >>> 16);
        return h;
    }

    private static int probe(long packed, int mask, long[] keys, boolean[] occ) {
        int idx = hash(packed) & mask;
        while (occ[idx]) {
            if (keys[idx] == packed) return idx;
            idx = (idx + 1) & mask;
        }
        return idx;
    }

    private static int tableCapacity(int needed) {
        if (needed == 0) return 0;
        int cap = 1;
        while (cap < needed * 2) cap <<= 1;
        if (cap < 4) cap = 4;
        return cap;
    }

    // Visible for tests / defensive inspection of the immutable layout.
    int internalCapacity() { return keys.length; }

    boolean internalIsOccupied(int slot) { return occupied[slot]; }

    SubLandSnapshot internalFlatAt(int index) { return flatCandidates[index]; }
}

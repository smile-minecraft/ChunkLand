package com.smile.chunkland.runtime.index;

import com.smile.chunkland.api.land.LandId;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable per-world chunk to land mapping using packed long keys.
 *
 * <p>Hot path {@link #landIdAt(int,int)} packs coordinates with
 * {@code ((long)chunkX<<32)|(chunkZ & 0xFFFFFFFFL)} and probes a primitive
 * table without allocating {@code ChunkKey}, streams or temporary collections.
 * No Bukkit, SQL or I/O is referenced.
 */
public final class WorldChunkIndex {

    private final UUID worldId;
    private final long[] keys;
    private final LandId[] values;
    private final boolean[] occupied;
    private final int mask;
    private final int size;

    private WorldChunkIndex(UUID worldId, long[] keys, LandId[] values, boolean[] occupied, int mask, int size) {
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.keys = keys;
        this.values = values;
        this.occupied = occupied;
        this.mask = mask;
        this.size = size;
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >>> 32);
    }

    public static int unpackZ(long packed) {
        return (int) packed;
    }

    public static WorldChunkIndex empty(UUID worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return new WorldChunkIndex(worldId, new long[0], new LandId[0], new boolean[0], 0, 0);
    }

    public static WorldChunkIndex from(UUID worldId, Map<Long, LandId> input) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(input, "input");
        if (input.isEmpty()) return empty(worldId);
        int needed = input.size();
        int capacity = tableCapacity(needed);
        long[] keys = new long[capacity];
        LandId[] values = new LandId[capacity];
        boolean[] occ = new boolean[capacity];
        int mask = capacity - 1;
        for (Map.Entry<Long, LandId> e : input.entrySet()) {
            Objects.requireNonNull(e.getKey(), "key");
            Objects.requireNonNull(e.getValue(), "value");
            long packed = e.getKey();
            int idx = probe(packed, mask, keys, occ);
            if (occ[idx]) {
                throw new IllegalArgumentException("duplicate packed key " + packed);
            }
            keys[idx] = packed;
            values[idx] = e.getValue();
            occ[idx] = true;
        }
        // defensive: iterate again to ensure no null values
        return new WorldChunkIndex(worldId, keys, values, occ, mask, needed);
    }

    static WorldChunkIndex fromEntries(UUID worldId, Set<Map.Entry<Long, LandId>> entries) {
        if (entries.isEmpty()) return empty(worldId);
        Map<Long, LandId> m = new java.util.HashMap<>();
        for (Map.Entry<Long, LandId> e : entries) m.put(e.getKey(), e.getValue());
        return from(worldId, m);
    }

    public UUID worldId() { return worldId; }

    public int size() { return size; }

    public boolean isEmpty() { return size == 0; }

    /** Hot path: no allocation, no ChunkKey. Returns null for wilderness. */
    public LandId landIdAt(int chunkX, int chunkZ) {
        return landIdAtPacked(pack(chunkX, chunkZ));
    }

    /** Hot path with already packed key. Returns null for wilderness. */
    public LandId landIdAtPacked(long packed) {
        if (size == 0) return null;
        int idx = hash(packed) & mask;
        while (occupied[idx]) {
            if (keys[idx] == packed) return values[idx];
            idx = (idx + 1) & mask;
        }
        return null;
    }

    /** Unmodifiable view of packed keys (snapshot). Allocates only for the view, not on hot path. */
    public Set<Long> packedKeys() {
        if (size == 0) return Collections.emptySet();
        Set<Long> s = new HashSet<>(size);
        for (int i = 0; i < keys.length; i++) if (occupied[i]) s.add(keys[i]);
        return Collections.unmodifiableSet(s);
    }

    private static int hash(long packed) {
        // spread similar to HashMap
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
        int cap = 1;
        // load factor 0.5 -> cap >= needed*2
        while (cap < needed * 2) cap <<= 1;
        if (cap < 4) cap = 4;
        return cap;
    }
}

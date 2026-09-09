package com.smile.chunkland.runtime.index;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.land.SubLandTopologyValidator;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable snapshot of all lands.
 *
 * <p>Read path holds a single volatile reference to this snapshot.
 * {@code worldId} is the isolation boundary; a lookup never crosses worlds.
 * Miss and world-without-land immediately return {@code null} (Wilderness / empty).
 *
 * <p>No Bukkit, SQL, I/O or {@code ChunkKey} allocation on hot path. All
 * collections exposed are unmodifiable; inputs are defensively copied.
 */
public final class LandRegistry {

    private final Map<UUID, WorldChunkIndex> worlds;
    private final Map<LandId, LandSnapshot> lands;
    private final Map<LandId, SubLandIndex> subLandIndexes;
    private final Map<ChunkKey, Integer> chunkDepths;

    private LandRegistry(Map<UUID, WorldChunkIndex> worlds,
                         Map<LandId, LandSnapshot> lands,
                         Map<LandId, SubLandIndex> subLandIndexes) {
        this(worlds, lands, subLandIndexes, Map.of());
    }

    private LandRegistry(Map<UUID, WorldChunkIndex> worlds,
                         Map<LandId, LandSnapshot> lands,
                         Map<LandId, SubLandIndex> subLandIndexes,
                         Map<ChunkKey, Integer> chunkDepths) {
        this.worlds = worlds;
        this.lands = lands;
        this.subLandIndexes = subLandIndexes;
        this.chunkDepths = chunkDepths;
    }

    public static LandRegistry empty() {
        return new LandRegistry(Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * Build an immutable registry from a collection of snapshots.
     * Each snapshot's world/chunk set is validated; overlapping chunk keys
     * across different lands in the same world are rejected. Every snapshot's
     * SubLands are checked by {@link SubLandTopologyValidator} before the
     * per-land chunk index and the per-land SubLand index are built, so the
     * resulting lookup tables never disagree about which chunks belong to a
     * land.
     */
    public static LandRegistry from(Collection<LandSnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots");
        if (snapshots.isEmpty()) return empty();
        List<LandSnapshot> copy = List.copyOf(snapshots);

        Map<LandId, LandSnapshot> landsById = new HashMap<>(copy.size());
        Map<UUID, Map<Long, LandId>> worldToPacked = new HashMap<>();
        Map<LandId, SubLandIndex> subIndexes = new HashMap<>();

        for (LandSnapshot s : copy) {
            Objects.requireNonNull(s, "snapshot");
            if (landsById.putIfAbsent(s.id(), s) != null) {
                throw new IllegalArgumentException("duplicate land id " + s.id());
            }
            // Validate parent containment, world, and pairwise SubLand overlap
            // before any per-land lookup tables are built. Without this gate,
            // the chunk map would claim only the parent's chunks while the
            // SubLand index silently kept chunks outside the parent's
            // projection, producing contradictory land / SubLand lookups.
            SubLandTopologyValidator.validate(s);
            // build world chunk map
            Map<Long, LandId> packedMap = worldToPacked.computeIfAbsent(s.worldId(), k -> new HashMap<>());
            for (var ck : s.chunks()) {
                long packed = WorldChunkIndex.pack(ck.chunkX(), ck.chunkZ());
                LandId prev = packedMap.putIfAbsent(packed, s.id());
                if (prev != null && !prev.equals(s.id())) {
                    throw new IllegalArgumentException("chunk collision at packed " + packed + " world " + s.worldId()
                            + " between " + prev + " and " + s.id());
                }
            }
            // subland index for this land
            SubLandIndex sIdx = SubLandIndex.from(s.id(), s.subLands());
            subIndexes.put(s.id(), sIdx);
        }

        Map<UUID, WorldChunkIndex> worlds = new HashMap<>(worldToPacked.size());
        for (Map.Entry<UUID, Map<Long, LandId>> e : worldToPacked.entrySet()) {
            worlds.put(e.getKey(), WorldChunkIndex.from(e.getKey(), e.getValue()));
        }

        return new LandRegistry(
                Collections.unmodifiableMap(worlds),
                Collections.unmodifiableMap(new HashMap<>(landsById)),
                Collections.unmodifiableMap(subIndexes),
                Map.of());
    }

    /**
     * Build a registry with authoritative per-chunk stored depths.
     *
     * <p>{@code storedDepths} maps each claimed chunk to its durable
     * {@code storedMinProtectedY}. Entries for unknown chunks are ignored so a
     * stale depth row can never create a phantom claim; chunks without an
     * entry resolve through the legacy fallback at read time. The map is
     * defensively copied; mode switching only changes effective reads, never
     * this table.
     */
    public static LandRegistry fromWithDepths(
            Collection<LandSnapshot> snapshots, Map<ChunkKey, Integer> storedDepths) {
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(storedDepths, "storedDepths");
        LandRegistry base = from(snapshots);
        if (storedDepths.isEmpty()) {
            return base;
        }
        Map<ChunkKey, Integer> filtered = new HashMap<>(storedDepths.size());
        for (Map.Entry<ChunkKey, Integer> entry : storedDepths.entrySet()) {
            ChunkKey key = Objects.requireNonNull(entry.getKey(), "storedDepths key");
            Integer value = Objects.requireNonNull(entry.getValue(), "storedDepths value");
            LandId owner = base.findLandId(key.worldId(), key.chunkX(), key.chunkZ());
            if (owner == null) {
                continue;
            }
            filtered.put(key, value);
        }
        return new LandRegistry(
                base.worlds, base.lands, base.subLandIndexes, Collections.unmodifiableMap(filtered));
    }

    public Map<UUID, WorldChunkIndex> worlds() { return worlds; }

    public Map<LandId, LandSnapshot> lands() { return lands; }

    public Map<LandId, SubLandIndex> subLandIndexes() { return subLandIndexes; }

    public boolean isEmpty() { return lands.isEmpty(); }

    public WorldChunkIndex worldIndex(UUID worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return worlds.get(worldId);
    }

    public SubLandIndex subLandIndex(LandId landId) {
        Objects.requireNonNull(landId, "landId");
        return subLandIndexes.get(landId);
    }

    /** Hot path: world isolation, packed long, no ChunkKey. Returns null for wilderness. */
    public LandId findLandId(UUID worldId, int chunkX, int chunkZ) {
        Objects.requireNonNull(worldId, "worldId");
        WorldChunkIndex wi = worlds.get(worldId);
        if (wi == null) return null;
        return wi.landIdAt(chunkX, chunkZ);
    }

    /** Hot path: world isolation, packed long, no ChunkKey. Returns null for wilderness. */
    public LandSnapshot findLand(UUID worldId, int chunkX, int chunkZ) {
        LandId id = findLandId(worldId, chunkX, chunkZ);
        if (id == null) return null;
        return lands.get(id);
    }

    /** Hot path with pre-packed key. */
    public LandId findLandIdPacked(UUID worldId, long packed) {
        Objects.requireNonNull(worldId, "worldId");
        WorldChunkIndex wi = worlds.get(worldId);
        if (wi == null) return null;
        return wi.landIdAtPacked(packed);
    }

    public LandSnapshot findLandPacked(UUID worldId, long packed) {
        LandId id = findLandIdPacked(worldId, packed);
        if (id == null) return null;
        return lands.get(id);
    }

    public LandSnapshot land(LandId id) {
        Objects.requireNonNull(id, "id");
        return lands.get(id);
    }

    /**
     * Authoritative per-chunk stored depths keyed by {@link ChunkKey}.
     * Never {@code null}; missing chunks resolve through the legacy fallback
     * at read time. Unmodifiable.
     */
    public Map<ChunkKey, Integer> chunkDepths() { return chunkDepths; }

    /**
     * Stored depth for one chunk, or empty when the chunk is wilderness or the
     * depth row is absent (caller applies the legacy fallback).
     */
    public java.util.Optional<Integer> storedDepth(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        if (findLandId(chunk.worldId(), chunk.chunkX(), chunk.chunkZ()) == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(chunkDepths.get(chunk));
    }
}

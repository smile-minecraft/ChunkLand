package com.smile.chunkland.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of the parsed {@code config.yml}.
 *
 * <p>This snapshot is the canonical, validation-passed view of the ChunkLand
 * configuration. It is the value type referenced by
 * {@link ConfigService#current()} and republished on every successful reload.
 * All collections are defensively copied on construction; no mutator exists.</p>
 *
 * <p>Two epoch counters travel with the snapshot:</p>
 * <ul>
 *   <li>{@code globalPolicyEpoch} — bumped on every successful reload so global
 *       default changes (企劃書 §33.1) and any cache key keyed on it become
 *       stale on reload.</li>
 *   <li>{@code worldPolicyEpochs} — per-world counter. The map is the union of
 *       worlds known to the previous and the new snapshot; every entry is
 *       bumped together on a successful reload so cache entries tied to a world
 *       become stale even if that world is dropped from the new config.</li>
 * </ul>
 *
 * <p>The {@code worlds} map holds the typed per-world settings
 * ({@link WorldSettings}) keyed by world name. Names are case-sensitive.</p>
 */
public final class ChunkLandConfig {

    private final Map<String, WorldSettings> worlds;
    private final LimitSettings limits;
    private final long globalPolicyEpoch;
    private final Map<String, Long> worldPolicyEpochs;

    public ChunkLandConfig(Map<String, WorldSettings> worlds,
                           long globalPolicyEpoch,
                           Map<String, Long> worldPolicyEpochs) {
        this(worlds, LimitSettings.defaults(), globalPolicyEpoch, worldPolicyEpochs);
    }

    public ChunkLandConfig(Map<String, WorldSettings> worlds,
                           LimitSettings limits,
                           long globalPolicyEpoch,
                           Map<String, Long> worldPolicyEpochs) {
        Objects.requireNonNull(worlds, "worlds");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(worldPolicyEpochs, "worldPolicyEpochs");
        if (globalPolicyEpoch < 0) {
            throw new IllegalArgumentException(
                    "globalPolicyEpoch must be non-negative: " + globalPolicyEpoch);
        }
        Map<String, WorldSettings> defensiveWorlds = new HashMap<>(worlds.size());
        for (Map.Entry<String, WorldSettings> e : worlds.entrySet()) {
            String name = Objects.requireNonNull(e.getKey(), "world name");
            WorldSettings settings = Objects.requireNonNull(e.getValue(),
                    "world settings for " + name);
            if (name.isBlank()) {
                throw new IllegalArgumentException("world name must not be blank");
            }
            defensiveWorlds.put(name, settings);
        }
        Map<String, Long> defensiveEpochs = new HashMap<>(worldPolicyEpochs.size());
        for (Map.Entry<String, Long> e : worldPolicyEpochs.entrySet()) {
            String name = Objects.requireNonNull(e.getKey(), "worldPolicyEpoch key");
            Long epoch = Objects.requireNonNull(e.getValue(),
                    "worldPolicyEpoch value for " + name);
            if (epoch < 0) {
                throw new IllegalArgumentException(
                        "worldPolicyEpoch must be non-negative for " + name + ": " + epoch);
            }
            defensiveEpochs.put(name, epoch);
        }
        this.worlds = Collections.unmodifiableMap(defensiveWorlds);
        this.limits = limits;
        this.globalPolicyEpoch = globalPolicyEpoch;
        this.worldPolicyEpochs = Collections.unmodifiableMap(defensiveEpochs);
    }

    /** Default snapshot: no worlds, both epoch counters at zero. */
    public static ChunkLandConfig defaults() {
        return new ChunkLandConfig(Map.of(), LimitSettings.defaults(), 0L, Map.of());
    }

    public Map<String, WorldSettings> worlds() {
        return worlds;
    }

    public LimitSettings limits() {
        return limits;
    }

    public long globalPolicyEpoch() {
        return globalPolicyEpoch;
    }

    public Map<String, Long> worldPolicyEpochs() {
        return worldPolicyEpochs;
    }

    /** Lookup helper: returns the epoch for {@code worldName} or {@code null}. */
    public Long worldPolicyEpoch(String worldName) {
        return worldPolicyEpochs.get(worldName);
    }

    public WorldSettings worldSettings(String worldName) {
        return worlds.get(worldName);
    }

    public Set<String> worldNames() {
        return worlds.keySet();
    }

    /**
     * Return a new snapshot with {@code newWorlds} substituted. The epoch
     * counters are preserved so this helper can be used during validation
     * without accidentally bumping them; the {@link ConfigService} is the only
     * place that produces epoch-bumped snapshots.
     */
    public ChunkLandConfig withWorlds(Map<String, WorldSettings> newWorlds) {
        Objects.requireNonNull(newWorlds, "newWorlds");
        return new ChunkLandConfig(newWorlds, limits, globalPolicyEpoch, worldPolicyEpochs);
    }

    public ChunkLandConfig withLimits(LimitSettings newLimits) {
        Objects.requireNonNull(newLimits, "newLimits");
        return new ChunkLandConfig(worlds, newLimits, globalPolicyEpoch, worldPolicyEpochs);
    }

    /**
     * Return a new snapshot that is identical to this one except every epoch
     * has been incremented by one. The {@code worldPolicyEpochs} map is the
     * union of this snapshot's known worlds and the {@code nextWorlds}'s
     * known worlds, so removed worlds still see a bumped counter and any
     * cache key tied to them becomes stale.
     */
    public ChunkLandConfig withEpochsBumped(Map<String, WorldSettings> nextWorlds) {
        Objects.requireNonNull(nextWorlds, "nextWorlds");
        // Compute the union of worlds known to either the previous snapshot
        // or the incoming payload, then bump each exactly once. Worlds that
        // appear in both still get a single bump; worlds that disappear
        // still get a bump so any cache key tied to them becomes stale.
        Set<String> union = new LinkedHashSet<>(this.worldPolicyEpochs.keySet());
        union.addAll(nextWorlds.keySet());
        Map<String, Long> bumped = new HashMap<>(union.size());
        for (String name : union) {
            long previousValue = this.worldPolicyEpochs.getOrDefault(name, 0L);
            bumped.put(name, Math.addExact(previousValue, 1L));
        }
        return new ChunkLandConfig(
                nextWorlds,
                this.limits,
                Math.addExact(this.globalPolicyEpoch, 1L),
                bumped);
    }

    public ChunkLandConfig withEpochsBumped(Map<String, WorldSettings> nextWorlds, LimitSettings nextLimits) {
        Objects.requireNonNull(nextWorlds, "nextWorlds");
        Objects.requireNonNull(nextLimits, "nextLimits");
        Set<String> union = new LinkedHashSet<>(this.worldPolicyEpochs.keySet());
        union.addAll(nextWorlds.keySet());
        Map<String, Long> bumped = new HashMap<>(union.size());
        for (String name : union) {
            long previousValue = this.worldPolicyEpochs.getOrDefault(name, 0L);
            bumped.put(name, Math.addExact(previousValue, 1L));
        }
        return new ChunkLandConfig(
                nextWorlds,
                nextLimits,
                Math.addExact(this.globalPolicyEpoch, 1L),
                bumped);
    }
}

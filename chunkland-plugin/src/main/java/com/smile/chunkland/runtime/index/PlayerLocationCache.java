package com.smile.chunkland.runtime.index;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable snapshot of player locations.
 *
 * <p>Publish builds a new snapshot and swaps the volatile holder; readers hold a single
 * immutable snapshot for the entire lookup. No Bukkit objects are retained.
 */
public final class PlayerLocationCache {

    private final Map<UUID, PlayerLocation> byPlayer;

    private PlayerLocationCache(Map<UUID, PlayerLocation> byPlayer) {
        this.byPlayer = byPlayer;
    }

    public static PlayerLocationCache empty() {
        return new PlayerLocationCache(Map.of());
    }

    public static PlayerLocationCache from(Map<UUID, PlayerLocation> input) {
        Objects.requireNonNull(input, "input");
        if (input.isEmpty()) return empty();
        Map<UUID, PlayerLocation> copy = new HashMap<>(input.size());
        for (Map.Entry<UUID, PlayerLocation> e : input.entrySet()) {
            Objects.requireNonNull(e.getKey(), "key");
            Objects.requireNonNull(e.getValue(), "value");
            if (!e.getKey().equals(e.getValue().playerId())) {
                throw new IllegalArgumentException("key " + e.getKey() + " != location.playerId " + e.getValue().playerId());
            }
            copy.put(e.getKey(), e.getValue());
        }
        return new PlayerLocationCache(Collections.unmodifiableMap(copy));
    }

    /** Returns null if absent (wilderness/unknown). No allocation on miss. */
    public PlayerLocation get(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return byPlayer.get(playerId);
    }

    public Set<UUID> playerIds() { return byPlayer.keySet(); }

    public Map<UUID, PlayerLocation> asMap() { return byPlayer; }

    public int size() { return byPlayer.size(); }

    public boolean isEmpty() { return byPlayer.isEmpty(); }

    /** Returns new immutable snapshot with entry added/replaced. */
    public PlayerLocationCache with(PlayerLocation loc) {
        Objects.requireNonNull(loc, "loc");
        Map<UUID, PlayerLocation> next = new HashMap<>(byPlayer);
        next.put(loc.playerId(), loc);
        return new PlayerLocationCache(Collections.unmodifiableMap(next));
    }

    /** Returns new immutable snapshot with entry removed (or same if absent). */
    public PlayerLocationCache without(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!byPlayer.containsKey(playerId)) return this;
        Map<UUID, PlayerLocation> next = new HashMap<>(byPlayer);
        next.remove(playerId);
        if (next.isEmpty()) return empty();
        return new PlayerLocationCache(Collections.unmodifiableMap(next));
    }
}

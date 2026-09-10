package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable view of the durable land authorisation layers: direct player
 * grants per land plus the land default per action.
 *
 * <p>Instances are published through a volatile holder, so readers observe
 * one complete version and previously captured references keep answering
 * with their own values. Accessors only read immutable maps and never touch
 * storage, the server, or the network. Missing data answers empty or
 * {@code INHERIT} so decisions fall through fail-closed.
 */
public final class LandAuthorisationSnapshot {

    private final Map<LandId, Map<UUID, Set<ProtectionActionType>>> direct;
    private final Map<LandId, Map<ProtectionActionType, PermissionState>> defaults;

    private LandAuthorisationSnapshot(
            Map<LandId, Map<UUID, Set<ProtectionActionType>>> direct,
            Map<LandId, Map<ProtectionActionType, PermissionState>> defaults) {
        this.direct = direct;
        this.defaults = defaults;
    }

    /** Empty snapshot: no bindings, every land default {@code INHERIT}. */
    public static LandAuthorisationSnapshot empty() {
        return new LandAuthorisationSnapshot(Map.of(), Map.of());
    }

    /**
     * Defensive copy of both layers; {@code null} layers read as empty.
     * Every nested map and set is copied into an unmodifiable view, so later
     * changes to the inputs can never leak into the snapshot.
     */
    public static LandAuthorisationSnapshot copyOf(
            Map<LandId, Map<UUID, Set<ProtectionActionType>>> direct,
            Map<LandId, Map<ProtectionActionType, PermissionState>> defaults) {
        return new LandAuthorisationSnapshot(copyDirect(direct), copyDefaults(defaults));
    }

    /**
     * Whitelisted actions the actor is directly granted on the land. Never
     * null and unmodifiable; empty when the actor holds no binding there.
     */
    public Set<ProtectionActionType> directAllows(UUID actor, LandId landId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Map<UUID, Set<ProtectionActionType>> byPlayer = direct.get(landId);
        if (byPlayer == null) {
            return Set.of();
        }
        Set<ProtectionActionType> allows = byPlayer.get(actor);
        return allows == null ? Set.of() : allows;
    }

    /**
     * Durable land default for one action, or {@code INHERIT} when unset.
     * Never null.
     */
    public PermissionState landDefault(LandId landId, ProtectionActionType action) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(action, "action");
        Map<ProtectionActionType, PermissionState> byAction = defaults.get(landId);
        if (byAction == null) {
            return PermissionState.INHERIT;
        }
        return byAction.getOrDefault(action, PermissionState.INHERIT);
    }

    private static Map<LandId, Map<UUID, Set<ProtectionActionType>>> copyDirect(
            Map<LandId, Map<UUID, Set<ProtectionActionType>>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<LandId, Map<UUID, Set<ProtectionActionType>>> copy = new HashMap<>(source.size());
        for (Map.Entry<LandId, Map<UUID, Set<ProtectionActionType>>> entry : source.entrySet()) {
            LandId landId = Objects.requireNonNull(entry.getKey(), "land key");
            Map<UUID, Set<ProtectionActionType>> byPlayer = Objects.requireNonNull(
                    entry.getValue(), "direct grants for " + landId);
            Map<UUID, Set<ProtectionActionType>> players = new HashMap<>(byPlayer.size());
            for (Map.Entry<UUID, Set<ProtectionActionType>> player : byPlayer.entrySet()) {
                UUID actor = Objects.requireNonNull(player.getKey(), "actor key");
                Set<ProtectionActionType> allows = Objects.requireNonNull(
                        player.getValue(), "direct grants for " + actor);
                players.put(actor, Set.copyOf(allows));
            }
            copy.put(landId, Collections.unmodifiableMap(players));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<LandId, Map<ProtectionActionType, PermissionState>> copyDefaults(
            Map<LandId, Map<ProtectionActionType, PermissionState>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<LandId, Map<ProtectionActionType, PermissionState>> copy = new HashMap<>(source.size());
        for (Map.Entry<LandId, Map<ProtectionActionType, PermissionState>> entry : source.entrySet()) {
            LandId landId = Objects.requireNonNull(entry.getKey(), "land key");
            Map<ProtectionActionType, PermissionState> byAction = Objects.requireNonNull(
                    entry.getValue(), "land defaults for " + landId);
            EnumMap<ProtectionActionType, PermissionState> states =
                    new EnumMap<>(ProtectionActionType.class);
            for (Map.Entry<ProtectionActionType, PermissionState> state : byAction.entrySet()) {
                states.put(Objects.requireNonNull(state.getKey(), "action key"),
                        Objects.requireNonNull(state.getValue(), "default state"));
            }
            copy.put(landId, Collections.unmodifiableMap(states));
        }
        return Collections.unmodifiableMap(copy);
    }
}

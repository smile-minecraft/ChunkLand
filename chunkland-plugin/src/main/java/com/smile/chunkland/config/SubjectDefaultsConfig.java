package com.smile.chunkland.config;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Name-keyed subject default payload parsed from
 * {@code config.yml::subject-defaults}.
 *
 * <p>This is the {@code SUBJECT_PERMISSION} namespace only: each entry answers
 * "may this actor act?" for one {@link ProtectionActionType}. It never falls
 * back to rule defaults — subject and rule defaults are different typed maps
 * on purpose, so a key from one namespace can never decide the other chain.
 *
 * <p>The {@code worlds} map is keyed by world <em>name</em> exactly as written
 * in the config (case-sensitive, matching the existing {@code worlds} map).
 * Names are resolved to world UUIDs once at load/bootstrap by the permission
 * defaults snapshot; the hot path only reads UUID-keyed immutable maps.
 *
 * <p>An absent entry and an explicit {@code INHERIT} entry mean the same
 * thing: fall through to the next resolver layer. The schema drops
 * {@code INHERIT} entries at parse time, so this record only carries explicit
 * {@code ALLOW}/{@code DENY} values.
 */
public record SubjectDefaultsConfig(
        Map<ProtectionActionType, PermissionState> global,
        Map<String, Map<ProtectionActionType, PermissionState>> worlds) {

    public SubjectDefaultsConfig {
        Objects.requireNonNull(global, "global");
        Objects.requireNonNull(worlds, "worlds");
        EnumMap<ProtectionActionType, PermissionState> defensiveGlobal =
                new EnumMap<>(ProtectionActionType.class);
        for (Map.Entry<ProtectionActionType, PermissionState> entry : global.entrySet()) {
            ProtectionActionType action = Objects.requireNonNull(entry.getKey(), "global action");
            PermissionState state = Objects.requireNonNull(
                    entry.getValue(), "global default for " + action);
            defensiveGlobal.put(action, state);
        }
        global = Collections.unmodifiableMap(defensiveGlobal);
        Map<String, Map<ProtectionActionType, PermissionState>> defensiveWorlds =
                new LinkedHashMap<>(worlds.size());
        for (Map.Entry<String, Map<ProtectionActionType, PermissionState>> entry : worlds.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "world name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("world name must not be blank");
            }
            Map<ProtectionActionType, PermissionState> states = Objects.requireNonNull(
                    entry.getValue(), "subject defaults for world " + name);
            EnumMap<ProtectionActionType, PermissionState> copy =
                    new EnumMap<>(ProtectionActionType.class);
            for (Map.Entry<ProtectionActionType, PermissionState> state : states.entrySet()) {
                ProtectionActionType action = Objects.requireNonNull(
                        state.getKey(), "subject action for world " + name);
                PermissionState value = Objects.requireNonNull(
                        state.getValue(), "subject default for world " + name + " action " + action);
                copy.put(action, value);
            }
            defensiveWorlds.put(name, Collections.unmodifiableMap(copy));
        }
        worlds = Collections.unmodifiableMap(defensiveWorlds);
    }

    /** Empty payload: every subject default layer stays {@code INHERIT}. */
    public static SubjectDefaultsConfig empty() {
        return new SubjectDefaultsConfig(Map.of(), Map.of());
    }
}

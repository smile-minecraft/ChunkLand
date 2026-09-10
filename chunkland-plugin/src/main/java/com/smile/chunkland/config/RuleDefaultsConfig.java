package com.smile.chunkland.config;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Name-keyed rule default payload parsed from
 * {@code config.yml::rule-defaults}.
 *
 * <p>This is the {@code LAND_RULE} namespace only: each entry answers "can
 * this world mechanic happen here?" for one {@link LandRuleType}. It never
 * falls back to subject defaults — subject and rule defaults are different
 * typed maps on purpose, so a key from one namespace can never decide the
 * other chain.
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
public record RuleDefaultsConfig(
        Map<LandRuleType, PermissionState> global,
        Map<String, Map<LandRuleType, PermissionState>> worlds) {

    public RuleDefaultsConfig {
        Objects.requireNonNull(global, "global");
        Objects.requireNonNull(worlds, "worlds");
        EnumMap<LandRuleType, PermissionState> defensiveGlobal =
                new EnumMap<>(LandRuleType.class);
        for (Map.Entry<LandRuleType, PermissionState> entry : global.entrySet()) {
            LandRuleType rule = Objects.requireNonNull(entry.getKey(), "global rule");
            PermissionState state = Objects.requireNonNull(
                    entry.getValue(), "global default for " + rule);
            defensiveGlobal.put(rule, state);
        }
        global = Collections.unmodifiableMap(defensiveGlobal);
        Map<String, Map<LandRuleType, PermissionState>> defensiveWorlds =
                new LinkedHashMap<>(worlds.size());
        for (Map.Entry<String, Map<LandRuleType, PermissionState>> entry : worlds.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "world name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("world name must not be blank");
            }
            Map<LandRuleType, PermissionState> states = Objects.requireNonNull(
                    entry.getValue(), "rule defaults for world " + name);
            EnumMap<LandRuleType, PermissionState> copy =
                    new EnumMap<>(LandRuleType.class);
            for (Map.Entry<LandRuleType, PermissionState> state : states.entrySet()) {
                LandRuleType rule = Objects.requireNonNull(
                        state.getKey(), "rule for world " + name);
                PermissionState value = Objects.requireNonNull(
                        state.getValue(), "rule default for world " + name + " rule " + rule);
                copy.put(rule, value);
            }
            defensiveWorlds.put(name, Collections.unmodifiableMap(copy));
        }
        worlds = Collections.unmodifiableMap(defensiveWorlds);
    }

    /** Empty payload: every rule default layer stays {@code INHERIT}. */
    public static RuleDefaultsConfig empty() {
        return new RuleDefaultsConfig(Map.of(), Map.of());
    }
}

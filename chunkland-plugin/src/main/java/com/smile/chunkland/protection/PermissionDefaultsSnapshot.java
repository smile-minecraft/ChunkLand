package com.smile.chunkland.protection;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.RuleDefaultsConfig;
import com.smile.chunkland.config.SubjectDefaultsConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Immutable, UUID-keyed view of the config world/global permission defaults.
 *
 * <p>The config file writes world entries by world <em>name</em>;
 * {@link #resolve} translates those names to world UUIDs exactly once at
 * load/bootstrap through the given lookup and caches the result. The hot
 * path (the accessors below) performs only immutable map reads: no Bukkit,
 * no SQL, no network, no blocking.
 *
 * <p>Subject and rule defaults stay in separate typed maps from parse time
 * to this snapshot, so one namespace can never answer for the other. Unknown
 * or unresolvable world entries are reported through the warning sink and
 * left out of the snapshot (never fail-closed into a wrong world, never
 * silently merged). Two names that resolve to the same UUID are handled in
 * sorted name order: the first claim wins and later collisions warn instead
 * of silently overwriting it.
 */
public record PermissionDefaultsSnapshot(
        Map<ProtectionActionType, PermissionState> subjectGlobal,
        Map<UUID, Map<ProtectionActionType, PermissionState>> subjectWorlds,
        Map<LandRuleType, PermissionState> ruleGlobal,
        Map<UUID, Map<LandRuleType, PermissionState>> ruleWorlds) {

    public PermissionDefaultsSnapshot {
        Objects.requireNonNull(subjectGlobal, "subjectGlobal");
        Objects.requireNonNull(subjectWorlds, "subjectWorlds");
        Objects.requireNonNull(ruleGlobal, "ruleGlobal");
        Objects.requireNonNull(ruleWorlds, "ruleWorlds");
        EnumMap<ProtectionActionType, PermissionState> defensiveSubjectGlobal =
                new EnumMap<>(ProtectionActionType.class);
        defensiveSubjectGlobal.putAll(subjectGlobal);
        subjectGlobal = Collections.unmodifiableMap(defensiveSubjectGlobal);
        subjectWorlds = copyWorldMap(subjectWorlds, "subject world");
        EnumMap<LandRuleType, PermissionState> defensiveRuleGlobal =
                new EnumMap<>(LandRuleType.class);
        defensiveRuleGlobal.putAll(ruleGlobal);
        ruleGlobal = Collections.unmodifiableMap(defensiveRuleGlobal);
        ruleWorlds = copyWorldMap(ruleWorlds, "rule world");
    }

    /** Empty snapshot: every default layer reads {@code INHERIT}. */
    public static PermissionDefaultsSnapshot empty() {
        return new PermissionDefaultsSnapshot(Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * Resolve the name-keyed defaults payloads of {@code config} into an
     * immutable UUID-keyed snapshot.
     *
     * @param config    live config snapshot; {@code null} resolves to {@link #empty()}
     * @param worldIds  world name to world UUID; an empty, {@code null} or
     *                  throwing result marks that entry unknown
     * @param warnings  sink for unknown/orphan/colliding entries; {@code null} drops them
     */
    public static PermissionDefaultsSnapshot resolve(
            ChunkLandConfig config,
            Function<String, Optional<UUID>> worldIds,
            Consumer<String> warnings) {
        if (config == null) {
            return empty();
        }
        SubjectDefaultsConfig subjects = config.subjectDefaults();
        RuleDefaultsConfig rules = config.ruleDefaults();
        Map<ProtectionActionType, PermissionState> subjectGlobal =
                subjects == null ? Map.of() : subjects.global();
        Map<LandRuleType, PermissionState> ruleGlobal =
                rules == null ? Map.of() : rules.global();
        Map<UUID, Map<ProtectionActionType, PermissionState>> subjectWorlds =
                resolveWorlds(subjects == null ? Map.of() : subjects.worlds(),
                        worldIds, warnings, "subject-defaults");
        Map<UUID, Map<LandRuleType, PermissionState>> ruleWorlds =
                resolveWorlds(rules == null ? Map.of() : rules.worlds(),
                        worldIds, warnings, "rule-defaults");
        return new PermissionDefaultsSnapshot(subjectGlobal, subjectWorlds, ruleGlobal, ruleWorlds);
    }

    /** Subject world default for one action, or {@code INHERIT} when unset. */
    public PermissionState subjectWorldDefault(UUID worldId, ProtectionActionType action) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(action, "action");
        Map<ProtectionActionType, PermissionState> states = subjectWorlds.get(worldId);
        if (states == null) {
            return PermissionState.INHERIT;
        }
        return states.getOrDefault(action, PermissionState.INHERIT);
    }

    /** Subject global default for one action, or {@code INHERIT} when unset. */
    public PermissionState subjectGlobalDefault(ProtectionActionType action) {
        Objects.requireNonNull(action, "action");
        return subjectGlobal.getOrDefault(action, PermissionState.INHERIT);
    }

    /** Rule world default for one rule, or {@code INHERIT} when unset. */
    public PermissionState ruleWorldDefault(UUID worldId, LandRuleType rule) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(rule, "rule");
        Map<LandRuleType, PermissionState> states = ruleWorlds.get(worldId);
        if (states == null) {
            return PermissionState.INHERIT;
        }
        return states.getOrDefault(rule, PermissionState.INHERIT);
    }

    /** Rule global default for one rule, or {@code INHERIT} when unset. */
    public PermissionState ruleGlobalDefault(LandRuleType rule) {
        Objects.requireNonNull(rule, "rule");
        return ruleGlobal.getOrDefault(rule, PermissionState.INHERIT);
    }

    private static <E extends Enum<E>> Map<UUID, Map<E, PermissionState>> resolveWorlds(
            Map<String, Map<E, PermissionState>> byName,
            Function<String, Optional<UUID>> worldIds,
            Consumer<String> warnings,
            String section) {
        Map<UUID, Map<E, PermissionState>> out = new HashMap<>();
        if (byName == null || byName.isEmpty()) {
            return Map.of();
        }
        Consumer<String> sink = warnings != null ? warnings : ignored -> {
        };
        // Sort by name so resolution is deterministic regardless of the map
        // implementation that carried the parsed payload: the
        // lexicographically smallest name claims a UUID first, and later
        // collisions warn instead of silently overwriting it.
        List<Map.Entry<String, Map<E, PermissionState>>> entries =
                new ArrayList<>(byName.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, Map<E, PermissionState>> entry : entries) {
            String name = entry.getKey();
            Optional<UUID> resolved;
            try {
                resolved = worldIds == null ? Optional.empty() : worldIds.apply(name);
            } catch (RuntimeException failure) {
                resolved = Optional.empty();
            }
            if (resolved == null || resolved.isEmpty()) {
                sink.accept("ChunkLand " + section + ": unknown world '" + name
                        + "'; entry ignored until the world is known");
                continue;
            }
            UUID worldId = resolved.get();
            if (out.containsKey(worldId)) {
                sink.accept("ChunkLand " + section + ": world entry '" + name
                        + "' resolves to an already-configured world; entry ignored");
                continue;
            }
            Map<E, PermissionState> states = entry.getValue();
            out.put(worldId, states == null ? Map.of() : Map.copyOf(states));
        }
        return out;
    }

    private static <E extends Enum<E>> Map<UUID, Map<E, PermissionState>> copyWorldMap(
            Map<UUID, Map<E, PermissionState>> source, String label) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<E, PermissionState>> copy = new HashMap<>(source.size());
        for (Map.Entry<UUID, Map<E, PermissionState>> entry : source.entrySet()) {
            UUID worldId = Objects.requireNonNull(entry.getKey(), label + " key");
            Map<E, PermissionState> states = Objects.requireNonNull(
                    entry.getValue(), label + " states for " + worldId);
            copy.put(worldId, Collections.unmodifiableMap(new HashMap<>(states)));
        }
        return Collections.unmodifiableMap(copy);
    }
}

package com.smile.chunkland.runtime.rule;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.runtime.api.LandRuleLookup;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Memory-only {@link LandRuleLookup} for the eleven environment rules.
 *
 * <p>Resolution order inside one call: Land rule → World default → Global
 * default → built-in default. There is no subject dimension, so no per-layer
 * aggregation happens. {@code INHERIT} (or a missing entry) falls through to
 * the next layer; the built-in default is always concrete, so a known land
 * never resolves to {@code INHERIT} from here.
 *
 * <p>SubLand gap: this interface carries no block position, so the service
 * cannot pick which SubLand rule applies and does not consult SubLand rules.
 * The caller places the returned state on the land-rule layer; once a
 * position-aware lookup exists, SubLand rules belong on the subland-rule
 * layer above it. Unknown lands yield {@code Optional.empty()}
 * per the interface contract, which the provider turns into fail-closed
 * {@code DENY}.
 *
 * <p>Owner neutrality: the service never sees the actor, so the owner
 * guarantee cannot apply here by construction — the owner is bound by the
 * same effective rule as anyone else.
 *
 * <p>Hot-path rules: immutable snapshots copied at construction, map reads
 * only, no blocking, no SQL/Economy/chunk load.
 */
public final class LandRuleService implements LandRuleLookup {

    private static final Map<LandRuleType, PermissionState> BUILT_IN = builtIn();

    private final Map<LandId, EnumMap<LandRuleType, PermissionState>> landRules;
    private final Map<UUID, EnumMap<LandRuleType, PermissionState>> worldDefaults;
    private final EnumMap<LandRuleType, PermissionState> globalDefaults;

    /**
     * @param landRules     per-land overrides; {@code null} means none
     * @param worldDefaults per-world (keyed by world UUID) defaults; {@code null} means none
     * @param globalDefaults global defaults; {@code null} means none
     */
    public LandRuleService(Map<LandId, Map<LandRuleType, PermissionState>> landRules,
                           Map<UUID, Map<LandRuleType, PermissionState>> worldDefaults,
                           Map<LandRuleType, PermissionState> globalDefaults) {
        this.landRules = copyByKey(landRules);
        this.worldDefaults = copyByKey(worldDefaults);
        this.globalDefaults = copyStates(globalDefaults);
    }

    /** Fail-closed service: no overrides, only the built-in defaults. */
    public static LandRuleService defaults() {
        return new LandRuleService(Map.of(), Map.of(), Map.of());
    }

    /**
     * Service whose world/global layers come from the config defaults
     * snapshot. Land rules stay empty (no durable rule source is wired yet),
     * so unknown lands still resolve through world/global/built-in defaults
     * and missing lands still yield {@code Optional.empty()}.
     *
     * @param snapshot UUID-keyed config defaults; {@code null} behaves like
     *                 {@link #defaults()}
     */
    public static LandRuleService fromRuleSnapshot(
            com.smile.chunkland.protection.PermissionDefaultsSnapshot snapshot) {
        if (snapshot == null) {
            return defaults();
        }
        return new LandRuleService(Map.of(), snapshot.ruleWorlds(), snapshot.ruleGlobal());
    }

    /**
     * The built-in default per rule, used when no Land / World / Global layer
     * sets a value. Protective rules default to {@code DENY}; passive mob
     * spawning is harmless ambience and defaults to {@code ALLOW} to preserve
     * the vanilla feel inside lands.
     */
    public static Map<LandRuleType, PermissionState> builtInDefaults() {
        return Collections.unmodifiableMap(new EnumMap<>(BUILT_IN));
    }

    @Override
    public Optional<PermissionState> getRule(LandId landId, LandRuleType rule, LandRegistry snapshot) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(snapshot, "snapshot");
        LandSnapshot land = snapshot.land(landId);
        if (land == null) {
            return Optional.empty();
        }
        PermissionState found = explicit(landRules.get(landId), rule);
        if (found == null) {
            found = explicit(worldDefaults.get(land.worldId()), rule);
        }
        if (found == null) {
            found = explicit(globalDefaults, rule);
        }
        if (found == null) {
            found = BUILT_IN.get(rule);
        }
        return Optional.of(found);
    }

    private static PermissionState explicit(Map<LandRuleType, PermissionState> states, LandRuleType rule) {
        if (states == null) {
            return null;
        }
        PermissionState state = states.get(rule);
        return state == null || state == PermissionState.INHERIT ? null : state;
    }

    private static <K> Map<K, EnumMap<LandRuleType, PermissionState>> copyByKey(
            Map<K, Map<LandRuleType, PermissionState>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<K, EnumMap<LandRuleType, PermissionState>> copy = new HashMap<>(source.size());
        for (Map.Entry<K, Map<LandRuleType, PermissionState>> entry : source.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "rule map key");
            copy.put(entry.getKey(), copyStates(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static EnumMap<LandRuleType, PermissionState> copyStates(
            Map<LandRuleType, PermissionState> source) {
        EnumMap<LandRuleType, PermissionState> copy = new EnumMap<>(LandRuleType.class);
        if (source != null) {
            for (Map.Entry<LandRuleType, PermissionState> entry : source.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private static Map<LandRuleType, PermissionState> builtIn() {
        EnumMap<LandRuleType, PermissionState> defaults = new EnumMap<>(LandRuleType.class);
        defaults.put(LandRuleType.PVP, PermissionState.DENY);
        defaults.put(LandRuleType.EXPLOSION_TERRAIN, PermissionState.DENY);
        defaults.put(LandRuleType.EXPLOSION_ENTITY, PermissionState.DENY);
        defaults.put(LandRuleType.FIRE_SPREAD, PermissionState.DENY);
        defaults.put(LandRuleType.FIRE_BURN, PermissionState.DENY);
        defaults.put(LandRuleType.MOB_GRIEFING, PermissionState.DENY);
        // Mechanics that stay inside one land follow vanilla; the handlers
        // judge anything crossing a land boundary under the directional
        // actions instead, which never read these rules.
        defaults.put(LandRuleType.FLUID_FLOW, PermissionState.ALLOW);
        defaults.put(LandRuleType.PISTON, PermissionState.ALLOW);
        defaults.put(LandRuleType.HOPPER_TRANSFER, PermissionState.ALLOW);
        defaults.put(LandRuleType.HOSTILE_MOB_SPAWN, PermissionState.ALLOW);
        defaults.put(LandRuleType.PASSIVE_MOB_SPAWN, PermissionState.ALLOW);
        return Collections.unmodifiableMap(defaults);
    }
}

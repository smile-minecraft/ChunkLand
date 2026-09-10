package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.runtime.api.LandRuleLookup;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Snapshot-backed {@link PermissionContextProvider} for live enforcement.
 *
 * <p>Ownership comes from the land snapshot itself, so the owner guarantee for
 * {@code SUBJECT_PERMISSION} actions is real: the snapshot owner acts freely,
 * strangers fall through to bindings and defaults. Subject bindings and
 * defaults arrive through {@link SubjectPermissionLookup}; environment rules
 * arrive through {@link LandRuleLookup}. Either lookup may be absent (passed
 * as {@code null}, meaning no source is wired yet): the corresponding layers
 * stay {@code INHERIT} and the resolver ends at its implicit {@code DENY}.
 *
 * <p>Rule mapping is by action, not by actor: a {@code LAND_RULE} action reads
 * the effective rule for its mapped {@link LandRuleType} and places it on the
 * land-rule layer. The owner guarantee never applies there, so the owner is
 * bound by environment rules like anyone else. A lookup that throws, returns
 * {@code null}, or yields empty propagates as an exception or {@code INHERIT},
 * and the engine turns both into {@code DENY} (fail-closed).
 *
 * <p>Namespace routing: subject defaults and rule defaults are separate
 * typed namespaces from parse time on. The shared world/global context
 * layers always carry the <em>subject</em> namespace; for {@code LAND_RULE}
 * actions they are forced to {@code INHERIT} so a subject key can never
 * decide the rule chain. Rule world/global defaults instead arrive
 * pre-resolved inside the {@link LandRuleLookup} result (for example a
 * {@code LandRuleService} built from the rule namespace) and sit on the
 * land-rule layer, so a rule key can never decide the subject chain either.
 * {@code COMBINED} actions keep the subject layers and read the effective
 * rule from the land-rule layer: the rule half stops there for known lands
 * (the rule service always returns a concrete state), so it never reaches
 * the subject-carrying layers.</p>
 *
 * <p>Hot-path rules: memory-only snapshot and lookup reads, no blocking, no
 * cross-region calls, no storage access.
 */
public final class SnapshotPermissionContextProvider implements PermissionContextProvider {

    private static final Map<ProtectionActionType, LandRuleType> RULE_BY_ACTION = ruleTable();

    private final LandRuleLookup ruleLookup;
    private final SubjectPermissionLookup subjectLookup;

    /**
     * @param ruleLookup    source for environment rules; {@code null} means no
     *                      rule source is wired yet (every rule layer stays
     *                      {@code INHERIT} and therefore denies)
     * @param subjectLookup source for subject bindings and defaults;
     *                      {@code null} means no grant source is wired yet
     */
    public SnapshotPermissionContextProvider(LandRuleLookup ruleLookup,
                                             SubjectPermissionLookup subjectLookup) {
        this.ruleLookup = ruleLookup != null
                ? ruleLookup
                : (landId, rule, snapshot) -> Optional.empty();
        this.subjectLookup = subjectLookup != null ? subjectLookup : SubjectPermissionLookup.empty();
    }

    @Override
    public PermissionContext provide(UUID actor, LandId landId,
                                     ProtectionActionType action, LandRegistry snapshot) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(snapshot, "snapshot");
        LandSnapshot land = snapshot.land(landId);
        if (land == null) {
            return PermissionContext.builder(action).build();
        }
        boolean owner = land.ownerRef() instanceof OwnerRef.PlayerOwnerRef player
                && player.uuid().equals(actor);
        SubjectPermissionLookup.Grant grants =
                SubjectPermissionLookup.requireNonNullGrant(
                        subjectLookup.grants(actor, landId, action, snapshot));
        // LAND_RULE actions must not observe the subject namespace: their
        // world/global layers stay INHERIT and the rule chain decides purely
        // from the pre-resolved land-rule layer (which already folds the rule
        // namespace's land -> world -> global -> built-in order).
        boolean ruleAction = action.decisionSource() == DecisionSource.LAND_RULE;
        return PermissionContext.builder(action)
                .isOwner(owner)
                .landBindings(grants.landBindings())
                .landDefault(grants.landDefault())
                .worldDefault(ruleAction ? PermissionState.INHERIT : grants.worldDefault())
                .globalDefault(ruleAction ? PermissionState.INHERIT : grants.globalDefault())
                .landRule(ruleState(landId, action, snapshot))
                .build();
    }

    private PermissionState ruleState(LandId landId, ProtectionActionType action, LandRegistry snapshot) {
        LandRuleType rule = RULE_BY_ACTION.get(action);
        if (rule == null) {
            return PermissionState.INHERIT;
        }
        Optional<PermissionState> found = ruleLookup.getRule(landId, rule, snapshot);
        if (found == null || found.isEmpty() || found.get() == null) {
            return PermissionState.INHERIT;
        }
        return found.get();
    }

    private static Map<ProtectionActionType, LandRuleType> ruleTable() {
        Map<ProtectionActionType, LandRuleType> table = new EnumMap<>(ProtectionActionType.class);
        table.put(ProtectionActionType.PLAYER_DAMAGE_PLAYER, LandRuleType.PVP);
        table.put(ProtectionActionType.PISTON_MOVE, LandRuleType.PISTON);
        table.put(ProtectionActionType.FLUID_FLOW, LandRuleType.FLUID_FLOW);
        table.put(ProtectionActionType.HOPPER_TRANSFER, LandRuleType.HOPPER_TRANSFER);
        table.put(ProtectionActionType.FIRE_SPREAD, LandRuleType.FIRE_SPREAD);
        table.put(ProtectionActionType.FIRE_BURN, LandRuleType.FIRE_BURN);
        table.put(ProtectionActionType.EXPLOSION_TERRAIN, LandRuleType.EXPLOSION_TERRAIN);
        table.put(ProtectionActionType.EXPLOSION_ENTITY, LandRuleType.EXPLOSION_ENTITY);
        table.put(ProtectionActionType.MOB_GRIEFING, LandRuleType.MOB_GRIEFING);
        table.put(ProtectionActionType.HOSTILE_MOB_SPAWN, LandRuleType.HOSTILE_MOB_SPAWN);
        table.put(ProtectionActionType.PASSIVE_MOB_SPAWN, LandRuleType.PASSIVE_MOB_SPAWN);
        // Cross-boundary actions reuse their source rule; no new rule type.
        // A dispenser is an ownerless mechanic whose cross-boundary effect is
        // grief-like, so it reads MOB_GRIEFING (the closest existing rule).
        table.put(ProtectionActionType.BLOCK_MOVE_IN, LandRuleType.PISTON);
        table.put(ProtectionActionType.BLOCK_MOVE_OUT, LandRuleType.PISTON);
        table.put(ProtectionActionType.FLUID_ENTER, LandRuleType.FLUID_FLOW);
        table.put(ProtectionActionType.FLUID_EXIT, LandRuleType.FLUID_FLOW);
        table.put(ProtectionActionType.ITEM_TRANSFER_IN, LandRuleType.HOPPER_TRANSFER);
        table.put(ProtectionActionType.ITEM_TRANSFER_OUT, LandRuleType.HOPPER_TRANSFER);
        table.put(ProtectionActionType.DISPENSER_CROSS_BOUNDARY, LandRuleType.MOB_GRIEFING);
        return Collections.unmodifiableMap(table);
    }
}

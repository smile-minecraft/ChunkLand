package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.runtime.api.LandRuleLookup;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.SubLandIndex;
import com.smile.chunkland.runtime.rule.LandRuleService;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

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
 *
 * <p>Atomic config views: the legacy constructor wires two independent
 * lookups that each re-read the config source, so a reload between the
 * subject read and the rule read can mix generations inside one decision.
 * Production wiring must use {@link #atomic(Supplier, Supplier)}, which
 * captures one {@link PermissionDefaultsCache.ConfigView} per
 * {@code provide()} call and derives both halves from it.
 *
 * <p>The atomic provider also serves as the decision-cache epoch source
 * (see {@link #keyFor}): it captures the same one view plus one durable
 * snapshot and folds their epochs, the land revisions and the owner
 * context into a full cache key. The legacy provider has no epochs and
 * always answers {@code null} there, so decisions through it bypass the
 * cache exactly like before.
 */
public final class SnapshotPermissionContextProvider implements PermissionContextProvider,
        PermissionDecisionEpochSource {

    private static final Map<ProtectionActionType, LandRuleType> RULE_BY_ACTION = ruleTable();
    private static final Set<ProtectionActionType> BOUNDARY_ACTIONS = boundaryActions();

    private final LandRuleLookup ruleLookup;
    private final SubjectPermissionLookup subjectLookup;
    private final Supplier<PermissionDefaultsCache.ConfigView> views;
    private final Supplier<LandAuthorisationSnapshot> landAuths;

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
        this.views = null;
        this.landAuths = null;
    }

    private SnapshotPermissionContextProvider(Supplier<PermissionDefaultsCache.ConfigView> views,
                                              Supplier<LandAuthorisationSnapshot> landAuths) {
        this.ruleLookup = (landId, rule, snapshot) -> Optional.empty();
        this.subjectLookup = SubjectPermissionLookup.empty();
        this.views = views;
        this.landAuths = landAuths;
    }

    /**
     * Atomic provider over one config view per decision.
     *
     * <p>Each {@code provide()} call reads {@code views} exactly once and
     * derives both the subject and rule layers from that single view, paired
     * with one durable land-layer read. A {@code null} or failing view
     * source degrades to empty layers (fail-closed). The returned provider
     * holds only the suppliers, so reloads apply to new decisions without
     * rebuilding consumers and in-flight decisions finish on their captured
     * view.
     */
    public static SnapshotPermissionContextProvider atomic(
            Supplier<PermissionDefaultsCache.ConfigView> views,
            Supplier<LandAuthorisationSnapshot> landAuths) {
        return new SnapshotPermissionContextProvider(views, landAuths);
    }

    @Override
    public PermissionContext provide(UUID actor, LandId landId,
                                     ProtectionActionType action, LandRegistry snapshot) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(snapshot, "snapshot");
        if (views != null) {
            return provideAtomic(actor, landId, action, snapshot);
        }
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
        if (BOUNDARY_ACTIONS.contains(action)) {
            return PermissionState.DENY;
        }
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

    /**
     * One-view decision: the volatile config view is read exactly once, then
     * both halves derive from it. The durable land layers add one more read
     * of an already-immutable snapshot; config reloads never interleave
     * between the subject and rule halves.
     */
    private PermissionContext provideAtomic(UUID actor, LandId landId,
                                            ProtectionActionType action, LandRegistry snapshot) {
        LandSnapshot land = snapshot.land(landId);
        if (land == null) {
            return PermissionContext.builder(action).build();
        }
        PermissionDefaultsCache.ConfigView view = capturedView();
        LandAuthorisationSnapshot landAuth = capturedLandAuth();
        if (landAuth == null) {
            landAuth = LandAuthorisationSnapshot.empty();
        }
        return buildAtomicContext(actor, landId, land, action, snapshot, view, landAuth);
    }

    /**
     * Builds the land-chain context from one already-captured generation.
     * Both {@link #provideAtomic} and {@link #provideAtBlockAtomic} share
     * this so the land half and the subland half can never observe different
     * config or durable generations inside one decision.
     */
    private PermissionContext buildAtomicContext(UUID actor, LandId landId, LandSnapshot land,
            ProtectionActionType action, LandRegistry snapshot,
            PermissionDefaultsCache.ConfigView view, LandAuthorisationSnapshot landAuth) {
        PermissionDefaultsSnapshot defaults = null;
        LandRuleService rules = null;
        if (view != null) {
            try {
                defaults = view.snapshot();
            } catch (RuntimeException failure) {
                defaults = null;
            }
            try {
                rules = view.rules();
            } catch (RuntimeException failure) {
                rules = null;
            }
        }
        SubjectPermissionLookup.Grant grants;
        if (defaults == null) {
            grants = SubjectPermissionLookup.Grant.empty();
        } else {
            PermissionDefaultsSnapshot fixedDefaults = defaults;
            LandAuthorisationSnapshot fixedAuth = landAuth;
            grants = new ConfigSubjectPermissionLookup(
                    () -> fixedDefaults, () -> fixedAuth).grants(actor, landId, action, snapshot);
            if (grants == null) {
                grants = SubjectPermissionLookup.Grant.empty();
            }
        }
        boolean owner = land.ownerRef() instanceof OwnerRef.PlayerOwnerRef player
                && player.uuid().equals(actor);
        boolean ruleAction = action.decisionSource() == DecisionSource.LAND_RULE;
        return PermissionContext.builder(action)
                .isOwner(owner)
                .landBindings(grants.landBindings())
                .landDefault(grants.landDefault())
                .worldDefault(ruleAction ? PermissionState.INHERIT : grants.worldDefault())
                .globalDefault(ruleAction ? PermissionState.INHERIT : grants.globalDefault())
                .landRule(ruleStateAtomic(rules, landId, action, snapshot))
                .build();
    }

    /**
     * Position-aware decision input: the subland covering the block position
     * contributes its subject layers ahead of the land chain, so an explicit
     * subland binding or default decides first. Outside every subland — or
     * when the index lookup fails — the subland layers stay {@code INHERIT}
     * and the decision falls back to the land chain below.
     *
     * <p>Memory-only like {@link #provide}: one registry index read plus the
     * same snapshot lookups, no blocking, no storage access.
     */
    public PermissionContext provideAtBlock(UUID actor, LandId landId,
            int blockX, int blockY, int blockZ,
            ProtectionActionType action, LandRegistry snapshot) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(snapshot, "snapshot");
        if (views != null) {
            return provideAtBlockAtomic(actor, landId, blockX, blockY, blockZ, action, snapshot);
        }
        PermissionContext landOnly = provide(actor, landId, action, snapshot);
        if (snapshot.land(landId) == null) {
            return landOnly;
        }
        SubjectPermissionLookup.SublandGrant sub = sublandGrantAt(
                subjectLookup, actor, landId, blockX, blockY, blockZ, action, snapshot);
        return PermissionContext.builder(action)
                .isOwner(landOnly.isOwner())
                .adminBypass(landOnly.adminBypass())
                .subLandBindings(sub.bindings())
                .subLandDefault(sub.sublandDefault())
                .landBindings(landOnly.landBindings())
                .landDefault(landOnly.landDefault())
                .worldDefault(landOnly.worldDefault())
                .globalDefault(landOnly.globalDefault())
                .subLandRule(landOnly.subLandRule())
                .landRule(landOnly.landRule())
                .build();
    }

    private PermissionContext provideAtBlockAtomic(UUID actor, LandId landId,
            int blockX, int blockY, int blockZ,
            ProtectionActionType action, LandRegistry snapshot) {
        LandSnapshot land = snapshot.land(landId);
        if (land == null) {
            return PermissionContext.builder(action).build();
        }
        // One decision holds one generation: capture the volatile view and
        // the durable auth once, then derive both the land chain and the
        // subland layers from those same immutable values.
        PermissionDefaultsCache.ConfigView view = capturedView();
        LandAuthorisationSnapshot landAuth = capturedLandAuth();
        if (landAuth == null) {
            landAuth = LandAuthorisationSnapshot.empty();
        }
        PermissionContext landOnly = buildAtomicContext(actor, landId, land, action, snapshot, view, landAuth);
        PermissionDefaultsSnapshot fixedDefaults;
        try {
            fixedDefaults = view == null ? null : view.snapshot();
        } catch (RuntimeException failure) {
            fixedDefaults = null;
        }
        PermissionDefaultsSnapshot subDefaults =
                fixedDefaults == null ? PermissionDefaultsSnapshot.empty() : fixedDefaults;
        LandAuthorisationSnapshot fixedAuth = landAuth;
        SubjectPermissionLookup fixed = new ConfigSubjectPermissionLookup(
                () -> subDefaults,
                () -> fixedAuth);
        SubjectPermissionLookup.SublandGrant sub = sublandGrantAt(
                fixed, actor, landId, blockX, blockY, blockZ, action, snapshot);
        return PermissionContext.builder(action)
                .isOwner(landOnly.isOwner())
                .adminBypass(landOnly.adminBypass())
                .subLandBindings(sub.bindings())
                .subLandDefault(sub.sublandDefault())
                .landBindings(landOnly.landBindings())
                .landDefault(landOnly.landDefault())
                .worldDefault(landOnly.worldDefault())
                .globalDefault(landOnly.globalDefault())
                .subLandRule(landOnly.subLandRule())
                .landRule(landOnly.landRule())
                .build();
    }

    private static SubjectPermissionLookup.SublandGrant sublandGrantAt(
            SubjectPermissionLookup lookup, UUID actor, LandId landId,
            int blockX, int blockY, int blockZ,
            ProtectionActionType action, LandRegistry snapshot) {
        SubLandId covering = coveringSubland(snapshot, landId, blockX, blockY, blockZ);
        try {
            SubjectPermissionLookup.SublandGrant grant =
                    lookup.sublandGrants(actor, landId, covering, action, snapshot);
            return grant == null
                    ? SubjectPermissionLookup.SublandGrant.empty()
                    : grant;
        } catch (RuntimeException failure) {
            return SubjectPermissionLookup.SublandGrant.empty();
        }
    }

    private static SubLandId coveringSubland(LandRegistry snapshot, LandId landId,
            int blockX, int blockY, int blockZ) {
        try {
            SubLandIndex index = snapshot.subLandIndex(landId);
            if (index == null) {
                return null;
            }
            SubLandSnapshot covering = index.findAtBlock(blockX, blockY, blockZ);
            return covering == null ? null : covering.id();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private PermissionDefaultsCache.ConfigView capturedView() {
        try {
            return views == null ? null : views.get();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private LandAuthorisationSnapshot capturedLandAuth() {
        try {
            return landAuths == null ? null : landAuths.get();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Cache identity for one decision, or {@code null} when it must bypass
     * the cache. The legacy provider (no atomic views) always answers
     * {@code null}. The atomic path captures one config view and one
     * durable snapshot — the same generations {@link #provide} would read —
     * and returns {@code null} for unloaded, unknown-land or failed
     * sources so those decisions stay uncached and fail closed.
     */
    @Override
    public PermissionDecisionCache.Key keyFor(UUID actor, LandId landId, SubLandId sublandId,
            ProtectionActionType action, LandRegistry snapshot) {
        if (actor == null || landId == null || action == null || snapshot == null) {
            return null;
        }
        if (views == null) {
            return null;
        }
        PermissionDefaultsCache.ConfigView view = capturedView();
        LandAuthorisationSnapshot landAuth = capturedLandAuth();
        if (view == null || landAuth == null || !landAuth.loaded()) {
            return null;
        }
        LandSnapshot land;
        try {
            land = snapshot.land(landId);
        } catch (RuntimeException failure) {
            return null;
        }
        if (land == null) {
            return null;
        }
        boolean owner = land.ownerRef() instanceof OwnerRef.PlayerOwnerRef player
                && player.uuid().equals(actor);
        return new PermissionDecisionCache.Key(actor, landId, sublandId, land.worldId(), action,
                view.globalPolicyEpoch(), view.worldPolicyEpoch(land.worldId()),
                land.landPolicyRevision(), land.structureRevision(),
                landAuth.ownerAclEpoch(), true, owner, false);
    }

    private PermissionState ruleStateAtomic(LandRuleService rules, LandId landId,
                                            ProtectionActionType action, LandRegistry snapshot) {
        if (BOUNDARY_ACTIONS.contains(action)) {
            // A land boundary is a wall for ownerless mechanics: the in-land
            // rule decides what happens inside, never what crosses in or out.
            return PermissionState.DENY;
        }
        LandRuleType rule = RULE_BY_ACTION.get(action);
        if (rule == null || rules == null) {
            return PermissionState.INHERIT;
        }
        Optional<PermissionState> found = rules.getRule(landId, rule, snapshot);
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
        // A dispenser is an ownerless mechanic whose cross-boundary effect is
        // grief-like, so it reads MOB_GRIEFING (the closest existing rule).
        // The six directional piston/fluid/hopper actions are deliberately
        // absent: see BOUNDARY_ACTIONS.
        table.put(ProtectionActionType.DISPENSER_CROSS_BOUNDARY, LandRuleType.MOB_GRIEFING);
        return Collections.unmodifiableMap(table);
    }

    /**
     * Directional actions for a piston, fluid or hopper reaching across a
     * land boundary. They resolve to {@code DENY} without reading a rule, so
     * opening a mechanic inside a land never opens the border with it.
     */
    private static Set<ProtectionActionType> boundaryActions() {
        return Collections.unmodifiableSet(EnumSet.of(
                ProtectionActionType.BLOCK_MOVE_IN,
                ProtectionActionType.BLOCK_MOVE_OUT,
                ProtectionActionType.FLUID_ENTER,
                ProtectionActionType.FLUID_EXIT,
                ProtectionActionType.ITEM_TRANSFER_IN,
                ProtectionActionType.ITEM_TRANSFER_OUT));
    }
}

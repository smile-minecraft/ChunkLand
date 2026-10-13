package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.rule.LandRuleView;
import com.smile.chunkland.rule.LandRuleViewService;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.rule.LandRuleService;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The display triple must agree with live enforcement: for every rule,
 * the view's effective value equals the protection engine's decision.
 * Three shapes pin the chain — an explicit land DENY, an explicit land
 * ALLOW, and full inheritance down to the built-in default — so a future
 * change on either side that silently forks the resolution shows up here.
 */
class RuleViewEngineParityTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final UUID worldId = UUID.randomUUID();
    private final LandId landId = new LandId(UUID.randomUUID());
    private final UUID owner = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final LandRegistry registry = LandRegistry.from(List.of(
            new LandSnapshot(landId, "Home", "home", OwnerRef.player(owner),
                    worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                    List.of(), 0, 0, NOW, NOW)));

    private static ProtectionActionType actionFor(LandRuleType rule) {
        return switch (rule) {
            case PVP -> ProtectionActionType.PLAYER_DAMAGE_PLAYER;
            case EXPLOSION_TERRAIN -> ProtectionActionType.EXPLOSION_TERRAIN;
            case EXPLOSION_ENTITY -> ProtectionActionType.EXPLOSION_ENTITY;
            case FIRE_SPREAD -> ProtectionActionType.FIRE_SPREAD;
            case FIRE_BURN -> ProtectionActionType.FIRE_BURN;
            case MOB_GRIEFING -> ProtectionActionType.MOB_GRIEFING;
            case FLUID_FLOW -> ProtectionActionType.FLUID_FLOW;
            case PISTON -> ProtectionActionType.PISTON_MOVE;
            case HOPPER_TRANSFER -> ProtectionActionType.HOPPER_TRANSFER;
            case HOSTILE_MOB_SPAWN -> ProtectionActionType.HOSTILE_MOB_SPAWN;
            case PASSIVE_MOB_SPAWN -> ProtectionActionType.PASSIVE_MOB_SPAWN;
        };
    }

    private static PermissionDefaultsCache.ConfigView emptyView() {
        PermissionDefaultsSnapshot snapshot = PermissionDefaultsSnapshot.empty();
        return new PermissionDefaultsCache.ConfigView(snapshot,
                LandRuleService.fromRuleSnapshot(snapshot), 0L, Map.of());
    }

    private ProtectionEngine engine(Map<LandRuleType, PermissionState> rules) {
        EnumMap<LandRuleType, PermissionState> states = new EnumMap<>(LandRuleType.class);
        states.putAll(rules);
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.empty()
                .withDirect(Map.of(), Map.of(), Map.of(),
                        Map.of(landId, Map.copyOf(states)),
                        Map.of(landId, 0L));
        LandRegistryStore store = new LandRegistryStore();
        store.publish(registry);
        PermissionDefaultsCache.ConfigView view = emptyView();
        return new ProtectionEngine(store::snapshot,
                SnapshotPermissionContextProvider.atomic(() -> view, () -> auth));
    }

    private List<LandRuleView> views(Map<LandRuleType, PermissionState> rules) {
        EnumMap<LandRuleType, PermissionState> states = new EnumMap<>(LandRuleType.class);
        states.putAll(rules);
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.empty()
                .withDirect(Map.of(), Map.of(), Map.of(),
                        Map.of(landId, Map.copyOf(states)),
                        Map.of(landId, 0L));
        return LandRuleViewService.all(landId, registry, auth,
                PermissionDefaultsSnapshot.empty()).orElseThrow();
    }

    private static LandRuleView only(List<LandRuleView> rows, LandRuleType rule) {
        return rows.stream().filter(view -> view.rule() == rule).findFirst()
                .orElseThrow(() -> new AssertionError("no view for " + rule));
    }

    private void assertParity(Map<LandRuleType, PermissionState> rules,
            LandRuleView.Source expectedSource) {
        ProtectionEngine engine = engine(rules);
        List<LandRuleView> rows = views(rules);
        for (LandRuleType rule : LandRuleType.values()) {
            LandRuleView view = only(rows, rule);
            PermissionState decided = engine.decide(stranger, landId, actionFor(rule))
                    .outcome();
            assertEquals(decided, view.effective(),
                    rule + ": the displayed effective value must equal the engine decision");
            assertEquals(expectedSource, view.source(),
                    rule + ": the source must read " + expectedSource);
        }
    }

    @Test
    void explicitLandDenyMatchesTheEngine() {
        EnumMap<LandRuleType, PermissionState> rules = new EnumMap<>(LandRuleType.class);
        for (LandRuleType rule : LandRuleType.values()) {
            rules.put(rule, PermissionState.DENY);
        }
        assertParity(rules, LandRuleView.Source.LAND);
    }

    @Test
    void explicitLandAllowMatchesTheEngine() {
        EnumMap<LandRuleType, PermissionState> rules = new EnumMap<>(LandRuleType.class);
        for (LandRuleType rule : LandRuleType.values()) {
            rules.put(rule, PermissionState.ALLOW);
        }
        assertParity(rules, LandRuleView.Source.LAND);
    }

    @ParameterizedTest
    @EnumSource(LandRuleType.class)
    void fullInheritanceMatchesTheBuiltInEngineDecision(LandRuleType rule) {
        ProtectionEngine engine = engine(Map.of());
        List<LandRuleView> rows = views(Map.of());
        LandRuleView view = only(rows, rule);
        assertEquals(PermissionState.INHERIT, view.explicit(),
                rule + ": nothing is set on the land");
        assertEquals(LandRuleService.builtInDefaults().get(rule), view.effective(),
                rule + ": the effective value must be the built-in default");
        assertEquals(LandRuleView.Source.BUILT_IN, view.source(),
                rule + ": an inherited value must not read as a land override");
        assertEquals(engine.decide(stranger, landId, actionFor(rule)).outcome(),
                view.effective(),
                rule + ": the displayed effective value must equal the engine decision");
    }

    @Test
    void dispenserAcrossTheBorderFollowsTheSameMobGriefingRule() {
        EnumMap<LandRuleType, PermissionState> rules = new EnumMap<>(LandRuleType.class);
        rules.put(LandRuleType.MOB_GRIEFING, PermissionState.ALLOW);
        ProtectionEngine engine = engine(rules);
        List<LandRuleView> rows = views(rules);
        assertEquals(engine.decide(stranger, landId,
                ProtectionActionType.DISPENSER_CROSS_BOUNDARY).outcome(),
                only(rows, LandRuleType.MOB_GRIEFING).effective(),
                "the dispenser action reads the same rule the view shows");
    }
}

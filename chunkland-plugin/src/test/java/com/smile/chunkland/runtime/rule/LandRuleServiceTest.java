package com.smile.chunkland.runtime.rule;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.protection.ProtectionEngine;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Red-first contract for the Land Rule service.
 *
 * <p>Resolution order: Land rule → World default → Global default → built-in
 * default. SubLand rules are not addressable through {@code LandRuleLookup}
 * (no position travels with the call); that layer stays a documented gap.
 */
class LandRuleServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static LandSnapshot land(UUID worldId, LandId id, UUID owner) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                List.of(), 0, 0, NOW, NOW);
    }

    private static LandRegistry registry(UUID worldId, LandId id, UUID owner) {
        return LandRegistry.from(List.of(land(worldId, id, owner)));
    }

    private static Map<LandId, Map<LandRuleType, PermissionState>> landRules(
            LandId id, LandRuleType rule, PermissionState state) {
        return Map.of(id, Map.of(rule, state));
    }

    // --- 十二種（十一種 rule + FARMLAND_TRAMPLE 除外）各有解析 ---

    @ParameterizedTest
    @EnumSource(LandRuleType.class)
    void landLevelAllowAndDenyResolve(LandRuleType rule) {
        UUID worldId = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistry snapshot = registry(worldId, id, owner);

        var denying = new LandRuleService(landRules(id, rule, PermissionState.DENY),
                Map.of(), Map.of());
        assertEquals(Optional.of(PermissionState.DENY), denying.getRule(id, rule, snapshot),
                "land DENY must win for " + rule);

        var allowing = new LandRuleService(landRules(id, rule, PermissionState.ALLOW),
                Map.of(), Map.of());
        assertEquals(Optional.of(PermissionState.ALLOW), allowing.getRule(id, rule, snapshot),
                "land ALLOW must win for " + rule);
    }

    // --- 空間層級歸約：下層未設值向上繼承 ---

    @Test
    void landBeatsWorldBeatsGlobal() {
        UUID worldId = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = registry(worldId, id, UUID.randomUUID());
        var rule = LandRuleType.PISTON;

        var service = new LandRuleService(
                landRules(id, rule, PermissionState.DENY),
                Map.of(worldId, Map.of(rule, PermissionState.ALLOW)),
                Map.of(rule, PermissionState.ALLOW));
        assertEquals(Optional.of(PermissionState.DENY), service.getRule(id, rule, snapshot));

        var worldWins = new LandRuleService(
                Map.of(),
                Map.of(worldId, Map.of(rule, PermissionState.ALLOW)),
                Map.of(rule, PermissionState.DENY));
        assertEquals(Optional.of(PermissionState.ALLOW), worldWins.getRule(id, rule, snapshot));

        var globalWins = new LandRuleService(
                Map.of(), Map.of(), Map.of(rule, PermissionState.DENY));
        assertEquals(Optional.of(PermissionState.DENY), globalWins.getRule(id, rule, snapshot));
    }

    @Test
    void inheritFallsThroughToNextLayer() {
        UUID worldId = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = registry(worldId, id, UUID.randomUUID());
        var rule = LandRuleType.FLUID_FLOW;

        var service = new LandRuleService(
                landRules(id, rule, PermissionState.INHERIT),
                Map.of(worldId, Map.of(rule, PermissionState.INHERIT)),
                Map.of(rule, PermissionState.ALLOW));
        assertEquals(Optional.of(PermissionState.ALLOW), service.getRule(id, rule, snapshot),
                "INHERIT at every layer must fall through to the next layer");
    }

    @Test
    void topLevelDefaultsApplyWhenNothingIsSet() {
        UUID worldId = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = registry(worldId, id, UUID.randomUUID());
        var service = LandRuleService.defaults();
        for (LandRuleType rule : LandRuleType.values()) {
            Optional<PermissionState> found = service.getRule(id, rule, snapshot);
            assertTrue(found.isPresent(), "built-in default must exist for " + rule);
            assertNotEquals(PermissionState.INHERIT, found.get(),
                    "built-in default must be concrete for " + rule);
        }
        assertEquals(Optional.of(PermissionState.DENY),
                service.getRule(id, LandRuleType.PVP, snapshot));
        assertEquals(Optional.of(PermissionState.ALLOW),
                service.getRule(id, LandRuleType.PASSIVE_MOB_SPAWN, snapshot));
    }

    @Test
    void unknownLandReturnsEmpty() {
        var service = LandRuleService.defaults();
        assertTrue(service.getRule(new LandId(UUID.randomUUID()),
                LandRuleType.PVP, LandRegistry.empty()).isEmpty());
    }

    // --- Owner 保證對 rule 完全無效 ---

    @Test
    void ownerIsBoundByLandRulesLikeAnyoneElse() {
        UUID worldId = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(registry(worldId, id, owner));

        var denying = new LandRuleService(
                landRules(id, LandRuleType.PVP, PermissionState.DENY), Map.of(), Map.of());
        var denyEngine = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(denying, null));
        assertEquals(PermissionState.DENY,
                denyEngine.decide(owner, id, ProtectionActionType.PLAYER_DAMAGE_PLAYER).outcome(),
                "owner must still be DENY when the PVP rule is closed");

        var allowing = new LandRuleService(
                landRules(id, LandRuleType.PVP, PermissionState.ALLOW), Map.of(), Map.of());
        var allowEngine = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(allowing, null));
        assertEquals(PermissionState.ALLOW,
                allowEngine.decide(owner, id, ProtectionActionType.PLAYER_DAMAGE_PLAYER).outcome(),
                "owner follows the rule ALLOW like anyone else (no owner bypass)");
    }

    // --- 關閉後果描述子齊備 ---

    @ParameterizedTest
    @EnumSource(LandRuleType.class)
    void everyRuleHasDenyConsequence(LandRuleType rule) {
        String text = LandRuleConsequences.denyConsequence(rule);
        assertNotNull(text, "consequence must exist for " + rule);
        assertFalse(text.isBlank(), "consequence must not be blank for " + rule);
    }

    @Test
    void pistonConsequenceWarnsAboutAutomation() {
        assertTrue(LandRuleConsequences.denyConsequence(LandRuleType.PISTON).contains("活塞"),
                "PISTON consequence must name the affected mechanic");
    }

    // --- 工廠可用性 ---

    @Test
    void defaultsFactoryToleratesNullMaps() {
        var service = new LandRuleService(null, null, null);
        UUID worldId = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        assertEquals(Optional.of(PermissionState.DENY),
                service.getRule(id, LandRuleType.FIRE_SPREAD, registry(worldId, id, UUID.randomUUID())));
    }

    @Test
    void builtInDefaultsAreVanillaForMechanicsAndClosedForDestruction() {
        Map<LandRuleType, PermissionState> defaults = LandRuleService.builtInDefaults();
        for (LandRuleType vanilla : List.of(LandRuleType.FLUID_FLOW, LandRuleType.PISTON,
                LandRuleType.HOPPER_TRANSFER, LandRuleType.HOSTILE_MOB_SPAWN,
                LandRuleType.PASSIVE_MOB_SPAWN)) {
            assertEquals(PermissionState.ALLOW, defaults.get(vanilla),
                    vanilla + " must follow vanilla inside a land");
        }
        for (LandRuleType closed : List.of(LandRuleType.PVP, LandRuleType.EXPLOSION_TERRAIN,
                LandRuleType.EXPLOSION_ENTITY, LandRuleType.FIRE_SPREAD, LandRuleType.FIRE_BURN,
                LandRuleType.MOB_GRIEFING)) {
            assertEquals(PermissionState.DENY, defaults.get(closed),
                    closed + " must stay closed by default");
        }
    }

    @Test
    void builtInDefaultsCoverEveryRuleType() {
        Map<LandRuleType, PermissionState> defaults = LandRuleService.builtInDefaults();
        assertEquals(LandRuleType.values().length, defaults.size());
        for (LandRuleType rule : LandRuleType.values()) {
            assertNotEquals(PermissionState.INHERIT, defaults.get(rule),
                    "built-in default must be concrete for " + rule);
        }
    }
}

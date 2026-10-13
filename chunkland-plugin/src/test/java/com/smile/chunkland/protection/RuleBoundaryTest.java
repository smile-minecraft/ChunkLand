package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
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
 * Opening an in-land mechanic never opens the border: with
 * {@code PISTON}, {@code FLUID_FLOW} and {@code HOPPER_TRANSFER} all set
 * to {@code ALLOW}, the six directional cross-boundary actions still deny.
 * And a per-land {@code DENY} still binds the owner — the management
 * guarantee that lets the owner <em>change</em> a rule never exempts them
 * from the rule itself.
 */
class RuleBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final UUID worldId = UUID.randomUUID();
    private final LandId landId = new LandId(UUID.randomUUID());
    private final UUID owner = UUID.randomUUID();
    private final LandRegistry registry = LandRegistry.from(List.of(
            new LandSnapshot(landId, "Home", "home", OwnerRef.player(owner),
                    worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                    List.of(), 0, 0, NOW, NOW)));

    private static PermissionDefaultsCache.ConfigView configView() {
        PermissionDefaultsSnapshot snapshot = PermissionDefaultsSnapshot.empty();
        return new PermissionDefaultsCache.ConfigView(snapshot,
                LandRuleService.fromRuleSnapshot(snapshot), 0L, Map.of());
    }

    private ProtectionEngine engineWithRules(
            Map<LandRuleType, PermissionState> rules) {
        EnumMap<LandRuleType, PermissionState> states = new EnumMap<>(LandRuleType.class);
        states.putAll(rules);
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.empty()
                .withDirect(Map.of(), Map.of(), Map.of(),
                        Map.of(landId, Map.copyOf(states)),
                        Map.of(landId, 0L));
        LandRegistryStore store = new LandRegistryStore();
        store.publish(registry);
        return new ProtectionEngine(store::snapshot,
                SnapshotPermissionContextProvider.atomic(() -> configView(), () -> auth));
    }

    @ParameterizedTest
    @EnumSource(value = ProtectionActionType.class, names = {
            "BLOCK_MOVE_IN", "BLOCK_MOVE_OUT",
            "FLUID_ENTER", "FLUID_EXIT",
            "ITEM_TRANSFER_IN", "ITEM_TRANSFER_OUT"})
    void openMechanicRulesNeverOpenTheBorder(ProtectionActionType boundary) {
        ProtectionEngine engine = engineWithRules(Map.of(
                LandRuleType.PISTON, PermissionState.ALLOW,
                LandRuleType.FLUID_FLOW, PermissionState.ALLOW,
                LandRuleType.HOPPER_TRANSFER, PermissionState.ALLOW));
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, boundary).outcome(),
                boundary + " must stay denied even with every in-land mechanic allowed");
    }

    @Test
    void allowingMechanicsKeepsInsideBehavioursAllowed() {
        ProtectionEngine engine = engineWithRules(Map.of(
                LandRuleType.PISTON, PermissionState.ALLOW,
                LandRuleType.FLUID_FLOW, PermissionState.ALLOW,
                LandRuleType.HOPPER_TRANSFER, PermissionState.ALLOW));
        assertEquals(PermissionState.ALLOW,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.PISTON_MOVE)
                        .outcome());
        assertEquals(PermissionState.ALLOW,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.FLUID_FLOW)
                        .outcome());
        assertEquals(PermissionState.ALLOW,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.HOPPER_TRANSFER)
                        .outcome());
    }

    @Test
    void landDenyBindsTheOwnerOnTheSameRule() {
        ProtectionEngine engine = engineWithRules(
                Map.of(LandRuleType.PVP, PermissionState.DENY));
        assertEquals(PermissionState.DENY,
                engine.decide(owner, landId, ProtectionActionType.PLAYER_DAMAGE_PLAYER)
                        .outcome(),
                "the owner may change the rule but stays bound by its DENY");
    }
}

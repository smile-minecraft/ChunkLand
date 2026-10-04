package com.smile.chunkland.runtime.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConservativeConfigFactory;
import com.smile.chunkland.protection.PermissionDefaultsSnapshot;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The built-in {@code ALLOW} rule ({@code PASSIVE_MOB_SPAWN}) must not survive
 * conservative config mode: the conservative snapshot pins every rule default
 * to {@code DENY}, and the rule service resolves through that layer before
 * the built-in default.
 */
class ConservativeRuleDefaultsTest {

    @Test
    void builtInAllowRuleResolvesDenyUnderConservativeDefaults() {
        UUID worldId = UUID.randomUUID();
        ChunkLandConfig conservative =
                ConservativeConfigFactory.forWorlds(List.of("world"));
        PermissionDefaultsSnapshot snapshot = PermissionDefaultsSnapshot.resolve(
                conservative, name -> Optional.ofNullable("world".equals(name) ? worldId : null),
                warning -> {
                });
        Map<LandRuleType, PermissionState> global = new EnumMap<>(LandRuleType.class);
        for (LandRuleType rule : LandRuleType.values()) {
            global.put(rule, snapshot.ruleGlobalDefault(rule));
        }
        LandRuleService service =
                new LandRuleService(Map.of(), Map.of(), global);

        LandId landId = new LandId(UUID.randomUUID());
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        LandSnapshot land = new LandSnapshot(landId, "Home", LandName.normalize("Home"),
                OwnerRef.player(UUID.randomUUID()), worldId,
                Set.of(), List.of(), 0, 0, now, now);
        LandRegistry registry = LandRegistry.from(List.of(land));

        assertEquals(PermissionState.DENY,
                service.getRule(landId, LandRuleType.PASSIVE_MOB_SPAWN, registry).orElseThrow());
        assertEquals(PermissionState.DENY,
                service.getRule(landId, LandRuleType.PVP, registry).orElseThrow());
    }
}

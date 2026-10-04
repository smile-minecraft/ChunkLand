package com.smile.chunkland.api.rule;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smile.chunkland.api.permission.PermissionState;
import org.junit.jupiter.api.Test;

class RuleContractTest {

    @Test
    void landRuleTypeCoversEveryEnvironmentRule() {
        for (var t : new LandRuleType[] {
                LandRuleType.PVP, LandRuleType.EXPLOSION_TERRAIN, LandRuleType.EXPLOSION_ENTITY,
                LandRuleType.FIRE_SPREAD, LandRuleType.FIRE_BURN, LandRuleType.MOB_GRIEFING,
                LandRuleType.FLUID_FLOW, LandRuleType.PISTON, LandRuleType.HOPPER_TRANSFER,
                LandRuleType.HOSTILE_MOB_SPAWN, LandRuleType.PASSIVE_MOB_SPAWN}) {
            assertNotNull(t);
        }
    }

    @Test
    void landRuleRejectsNull() {
        assertThrows(NullPointerException.class, () -> new LandRule(null, PermissionState.ALLOW));
        assertThrows(NullPointerException.class, () -> new LandRule(LandRuleType.PVP, null));
    }
}

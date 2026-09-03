package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtectionActionRegistryTest {

    @Test
    void defaultsCoverEveryActionWithNonNullSource() {
        Map<ProtectionActionType, DecisionSource> routes =
                ProtectionActionRegistry.validated(ProtectionActionRegistry.defaults());
        for (ProtectionActionType action : ProtectionActionType.values()) {
            assertNotNull(routes.get(action), "every action must declare a route: " + action);
        }
    }

    @Test
    void missingActionFailsStartup() {
        Map<ProtectionActionType, DecisionSource> routes =
                new EnumMap<>(ProtectionActionRegistry.defaults());
        routes.remove(ProtectionActionType.BLOCK_BREAK);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ProtectionActionRegistry.validated(routes));
        assertTrue(ex.getMessage().contains("BLOCK_BREAK"),
                "startup failure must name the missing action, got: " + ex.getMessage());
    }

    @Test
    void nullSourceFailsStartup() {
        Map<ProtectionActionType, DecisionSource> routes =
                new EnumMap<>(ProtectionActionRegistry.defaults());
        routes.put(ProtectionActionType.PISTON_MOVE, null);
        assertThrows(IllegalStateException.class,
                () -> ProtectionActionRegistry.validated(routes),
                "a null decision source must refuse startup");
    }

    @Test
    void nullTableFailsStartup() {
        assertThrows(Exception.class,
                () -> ProtectionActionRegistry.validated(null),
                "a null routing table must refuse startup");
    }

    @Test
    void pvpUsesLandRuleAndEntityDamageUsesSubjectPermission() {
        Map<ProtectionActionType, DecisionSource> routes =
                ProtectionActionRegistry.validated(ProtectionActionRegistry.defaults());
        assertEquals(DecisionSource.LAND_RULE,
                routes.get(ProtectionActionType.PLAYER_DAMAGE_PLAYER),
                "PVP must resolve from LAND_RULE");
        assertEquals(DecisionSource.SUBJECT_PERMISSION,
                routes.get(ProtectionActionType.ENTITY_DAMAGE),
                "ENTITY_DAMAGE must resolve from SUBJECT_PERMISSION");
        assertEquals(DecisionSource.LAND_RULE,
                ProtectionActionType.PLAYER_DAMAGE_PLAYER.decisionSource());
        assertEquals(DecisionSource.SUBJECT_PERMISSION,
                ProtectionActionType.ENTITY_DAMAGE.decisionSource());
    }
}

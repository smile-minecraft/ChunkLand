package com.smile.chunkland.api.permission;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class PermissionContractTest {

    @Test
    void permissionStateHasThreeValues() {
        assertArrayEquals(
                new PermissionState[] {PermissionState.ALLOW, PermissionState.DENY, PermissionState.INHERIT},
                PermissionState.values());
    }

    @Test
    void decisionSourceHasThreeValues() {
        assertArrayEquals(
                new DecisionSource[] {DecisionSource.SUBJECT_PERMISSION, DecisionSource.LAND_RULE, DecisionSource.COMBINED},
                DecisionSource.values());
    }

    @Test
    void everyProtectionActionDeclaresDecisionSource() {
        for (var a : ProtectionActionType.values()) {
            assertNotNull(a.decisionSource(), a + " must declare a DecisionSource (spec §51-1)");
        }
        assertEquals(DecisionSource.SUBJECT_PERMISSION, ProtectionActionType.BLOCK_BREAK.decisionSource());
        assertEquals(DecisionSource.LAND_RULE, ProtectionActionType.PLAYER_DAMAGE_PLAYER.decisionSource());
    }

    @Test
    void permissionRejectsNull() {
        assertThrows(NullPointerException.class, () -> new Permission(null, PermissionState.ALLOW));
        assertThrows(NullPointerException.class, () -> new Permission(ProtectionActionType.BLOCK_BREAK, null));
    }

    @Test
    void permissionValueEquality() {
        var p = new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.DENY);
        assertEquals(p, new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.DENY));
        assertNotEquals(p, new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW));
    }
}

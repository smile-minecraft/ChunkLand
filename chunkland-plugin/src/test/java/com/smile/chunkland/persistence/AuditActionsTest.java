package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditActionsTest {

    private static final Set<String> SPEC_ACTIONS = Set.of(
            "LAND_CREATE",
            "LAND_DELETE",
            "LAND_RENAME",
            "CHUNK_ADD",
            "CHUNK_REMOVE",
            "DEPTH_EXTEND",
            "SUBLAND_CREATE",
            "SUBLAND_UPDATE",
            "SUBLAND_DELETE",
            "DIRECT_BINDING_CHANGE",
            "GROUP_CREATE",
            "GROUP_DELETE",
            "GROUP_MEMBER_CHANGE",
            "PROFILE_CREATE",
            "PROFILE_UPDATE",
            "PROFILE_DELETE",
            "BINDING_CREATE",
            "BINDING_UPDATE",
            "BINDING_DELETE",
            "DEFAULT_CHANGE",
            "RULE_CHANGE",
            "ENTRY_BAN",
            "ENTRY_UNBAN",
            "ADMIN_BYPASS_TOGGLE",
            "ECONOMY_CHARGE",
            "ECONOMY_REFUND",
            "ECONOMY_COMPENSATION",
            "LEDGER_RESOLVE");

    @Test
    void registryMatchesSpecListExactly() {
        assertEquals(SPEC_ACTIONS, AuditActions.actions());
    }

    @Test
    void registryHasNoOwnerTransfer() {
        assertFalse(AuditActions.actions().contains("OWNER_TRANSFER"));
        assertThrows(IllegalArgumentException.class, () -> AuditActions.writerTask("OWNER_TRANSFER"));
    }

    @Test
    void everyActionHasNonBlankWriterTask() {
        for (String action : AuditActions.actions()) {
            String writer = AuditActions.writerTask(action);
            assertTrue(writer != null && !writer.isBlank(), "writer task missing for " + action);
        }
    }

    @Test
    void allWriterMappingsMatchExactly() {
        assertEquals(EXPECTED_WRITERS.keySet(), AuditActions.actions());
        for (Map.Entry<String, String> entry : EXPECTED_WRITERS.entrySet()) {
            assertEquals(entry.getValue(), AuditActions.writerTask(entry.getKey()),
                    "writer mismatch for " + entry.getKey());
        }
    }

    // 完整對照表: every registry writer is pinned so a future
    // mis-mapping fails this test. Includes the six required hard mappings.
    private static final Map<String, String> EXPECTED_WRITERS = Map.ofEntries(
            Map.entry("LAND_CREATE", "CL-M2-09"),
            Map.entry("LAND_DELETE", "CL-M3-14"),
            Map.entry("LAND_RENAME", "CL-M2-22"),
            Map.entry("CHUNK_ADD", "CL-M2-21"),
            Map.entry("CHUNK_REMOVE", "CL-M3-13"),
            Map.entry("DEPTH_EXTEND", "CL-M3-02"),
            Map.entry("SUBLAND_CREATE", "CL-M3-03"),
            Map.entry("SUBLAND_UPDATE", "CL-M3-03"),
            Map.entry("SUBLAND_DELETE", "CL-M3-03"),
            Map.entry("DIRECT_BINDING_CHANGE", "CL-M2-17"),
            Map.entry("GROUP_CREATE", "CL-M4-01"),
            Map.entry("GROUP_DELETE", "CL-M4-01"),
            Map.entry("GROUP_MEMBER_CHANGE", "CL-M4-01"),
            Map.entry("PROFILE_CREATE", "CL-M4-02"),
            Map.entry("PROFILE_UPDATE", "CL-M4-02"),
            Map.entry("PROFILE_DELETE", "CL-M4-02"),
            Map.entry("BINDING_CREATE", "CL-M4-03"),
            Map.entry("BINDING_UPDATE", "CL-M4-03"),
            Map.entry("BINDING_DELETE", "CL-M4-03"),
            Map.entry("DEFAULT_CHANGE", "CL-M2-17"),
            Map.entry("RULE_CHANGE", "CL-M3-07"),
            Map.entry("ENTRY_BAN", "CL-M3-06"),
            Map.entry("ENTRY_UNBAN", "CL-M3-06"),
            Map.entry("ADMIN_BYPASS_TOGGLE", "CL-M5-07"),
            Map.entry("ECONOMY_CHARGE", "CL-M2-09"),
            Map.entry("ECONOMY_REFUND", "CL-M2-20"),
            Map.entry("ECONOMY_COMPENSATION", "CL-M1-15"),
            Map.entry("LEDGER_RESOLVE", "CL-M5-05"));

    @Test
    void unknownActionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AuditActions.writerTask("NOT_AN_ACTION"));
        assertThrows(IllegalArgumentException.class, () -> AuditActions.writerTask(""));
        assertThrows(IllegalArgumentException.class, () -> AuditActions.writerTask(null));
    }
}

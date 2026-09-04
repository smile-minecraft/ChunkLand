package com.smile.chunkland.persistence;

import java.util.Map;
import java.util.Set;

/**
 * Single registry of the audit actions from the spec's Audit Log section.
 *
 * <p>Each action maps to the writer task that owns its insert path. Task ids
 * are plain strings so the persistence module does not depend on task
 * registry code. Ownership transfer is not part of V1, so no
 * {@code OWNER_TRANSFER} entry exists here.
 */
public final class AuditActions {

    private static final Map<String, String> WRITERS = Map.ofEntries(
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

    private AuditActions() {
    }

    /** All registered audit actions. The returned set is unmodifiable. */
    public static Set<String> actions() {
        return WRITERS.keySet();
    }

    /**
     * Returns the writer task id for the given action.
     *
     * @throws IllegalArgumentException when the action is null, blank, or unregistered
     */
    public static String writerTask(String action) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("audit action must not be null or blank");
        }
        String writer = WRITERS.get(action);
        if (writer == null) {
            throw new IllegalArgumentException("unknown audit action: " + action);
        }
        return writer;
    }
}

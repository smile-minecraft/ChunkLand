package com.smile.chunkland.trust;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.persistence.AuditEntry;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Audit entry factory for direct-trust and land-default mutations.
 *
 * <p>Trust and untrust share the {@code DIRECT_BINDING_CHANGE} action;
 * the before/after payloads name the affected player and the binding
 * presence on each side, so a reviewer can tell a grant from a removal.
 * The before payload must be the durable binding presence read inside the
 * same mutation transaction, never a volatile-cache guess, so consecutive
 * mutations record true transitions. Land defaults use {@code
 * DEFAULT_CHANGE} with the action and the state on each side ({@code
 * INHERIT} marks a deleted row). Payloads are plain strings — no chunk rows
 * are attached because these mutations never move geometry.
 */
public final class LandAuthorisationAudit {

    /** Metadata schema version for every entry built here. */
    public static final int METADATA_VERSION = 1;

    private LandAuthorisationAudit() {
    }

    /** Trust audit: the durable binding presence before, the trusted player after. */
    public static AuditEntry trustEntry(UUID actor, LandId landId, UUID target,
            boolean boundBefore, Instant now) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(now, "now");
        String before = boundBefore ? "{\"target\":\"" + target + "\",\"bound\":true}" : null;
        return new AuditEntry(0L, now, actor, "DIRECT_BINDING_CHANGE", landId, null, null,
                METADATA_VERSION, before, "{\"target\":\"" + target + "\",\"bound\":true}",
                "{\"kind\":\"trust\"}", List.of());
    }

    /** Untrust audit: the durable binding presence before, no binding after. */
    public static AuditEntry untrustEntry(UUID actor, LandId landId, UUID target,
            boolean boundBefore, Instant now) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(now, "now");
        String before = boundBefore ? "{\"target\":\"" + target + "\",\"bound\":true}" : null;
        return new AuditEntry(0L, now, actor, "DIRECT_BINDING_CHANGE", landId, null, null,
                METADATA_VERSION, before, null,
                "{\"kind\":\"untrust\"}", List.of());
    }

    /** Land default audit: the previous state before, the new state after. */
    public static AuditEntry defaultEntry(UUID actor, LandId landId,
            ProtectionActionType action, PermissionState before, PermissionState state, Instant now) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        return new AuditEntry(0L, now, actor, "DEFAULT_CHANGE", landId, null, null,
                METADATA_VERSION,
                "{\"action\":\"" + action.name() + "\",\"state\":\"" + before.name() + "\"}",
                "{\"action\":\"" + action.name() + "\",\"state\":\"" + state.name() + "\"}",
                "{\"kind\":\"land-default\"}", List.of());
    }
}

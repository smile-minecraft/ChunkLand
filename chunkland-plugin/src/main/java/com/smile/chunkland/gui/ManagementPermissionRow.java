package com.smile.chunkland.gui;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.PermissionExplainLayer;
import java.util.Objects;

/**
 * One read-only permission row for the second management layer.
 *
 * <p>The row carries the final effect plus the structural layer that
 * produced it. It carries no player identifiers and no binding contents —
 * only the action, the outcome, the layer and whether the deciding layer
 * held both {@code ALLOW} and {@code DENY} (a conflict whose final effect
 * is always {@code DENY} under flat DENY-first precedence).
 */
public record ManagementPermissionRow(
        ProtectionActionType action,
        PermissionState outcome,
        PermissionExplainLayer layer,
        boolean conflict) {
    public ManagementPermissionRow {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(layer, "layer");
        if (outcome == PermissionState.INHERIT) {
            throw new IllegalArgumentException("outcome must be ALLOW or DENY, not INHERIT");
        }
    }
}

package com.smile.chunkland.protection;

import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.Objects;

/**
 * Structured, read-only answer to "why is this action allowed or denied
 * here".
 *
 * <p>The record carries the final outcome and source straight from the
 * resolver decision plus the structural layer that produced it. It carries
 * no player identifiers, no profile or member lists — only the action, the
 * outcome, the source, the layer, a short reason, the covering subland id
 * (or {@code null} outside every subland) and the owner/bypass/steward
 * flags that already describe the caller.
 */
public record PermissionExplain(
        ProtectionActionType action,
        PermissionState outcome,
        DecisionSource source,
        PermissionExplainLayer layer,
        String reason,
        String coveringSubLandId,
        boolean isOwner,
        boolean adminBypass,
        boolean steward) {
    public PermissionExplain {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (outcome == PermissionState.INHERIT) {
            throw new IllegalArgumentException("outcome must be ALLOW or DENY, not INHERIT");
        }
    }
}

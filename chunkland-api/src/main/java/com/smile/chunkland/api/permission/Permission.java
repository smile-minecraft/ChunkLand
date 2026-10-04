package com.smile.chunkland.api.permission;

import java.util.Objects;

/**
 * A single subject-permission setting: an action together with its tri-state
 * value. Represents one entry in an ACL binding or default.
 *
 * <p>Thread-safe immutable value object.
 */
public record Permission(ProtectionActionType action, PermissionState state) {
    public Permission {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(state, "state");
    }
}

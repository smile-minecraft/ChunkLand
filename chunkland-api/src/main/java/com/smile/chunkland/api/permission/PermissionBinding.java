package com.smile.chunkland.api.permission;

import java.util.Objects;

/**
 * One ACL binding row: a {@link PermissionSubject} together with the
 * {@link Permission} it grants/denies for a single action.
 *
 * <p>Thread-safe immutable value object.
 */
public record PermissionBinding(PermissionSubject subject, Permission permission) {
    public PermissionBinding {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(permission, "permission");
    }
}

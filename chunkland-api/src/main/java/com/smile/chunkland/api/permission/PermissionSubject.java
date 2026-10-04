package com.smile.chunkland.api.permission;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies the subject of a permission binding.
 *
 * <p>Only {@link Kind#PLAYER} and {@link Kind#GROUP} exist. There is deliberately
 * <em>no</em> {@code EVERYONE} / wildcard kind: in the DENY-first model a wildcard
 * subject at the same aggregation layer would silently swallow every exception.
 * "Settings for everyone" are expressed through the default layers instead, never
 * through a binding.
 *
 * <p>Thread-safe immutable value object.
 */
public final class PermissionSubject {

    /** The kind of subject a binding applies to. {@code EVERYONE} is intentionally absent. */
    public enum Kind {
        PLAYER,
        GROUP
    }

    private final Kind kind;
    private final String id;

    private PermissionSubject(Kind kind, String id) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.id = Objects.requireNonNull(id, "id");
    }

    /** A binding for one specific player, keyed by their Minecraft UUID. */
    public static PermissionSubject player(UUID uuid) {
        return new PermissionSubject(Kind.PLAYER, Objects.requireNonNull(uuid, "uuid").toString());
    }

    /** A binding for a group, keyed by the group's stable name. */
    public static PermissionSubject group(String name) {
        Objects.requireNonNull(name, "name");
        var stripped = name.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("group name must not be blank");
        }
        if (isReservedWildcard(stripped)) {
            throw new IllegalArgumentException("wildcard group name is not allowed: " + stripped);
        }
        return new PermissionSubject(Kind.GROUP, stripped);
    }

    /**
     * Reserved names that must never become a binding subject:
     * {@code EVERYONE} (case-insensitive) and the {@code *} glob. "Settings for
     * everyone" are expressed through the default layers, never through a binding.
     */
    private static boolean isReservedWildcard(String name) {
        return name.equalsIgnoreCase("EVERYONE") || name.equals("*");
    }

    public Kind kind() {
        return kind;
    }

    /** Stable id: the player UUID string, or the group name. Never {@code null}. */
    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PermissionSubject s && kind == s.kind && id.equals(s.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, id);
    }

    @Override
    public String toString() {
        return kind + ":" + id;
    }
}

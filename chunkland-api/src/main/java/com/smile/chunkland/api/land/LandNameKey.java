package com.smile.chunkland.api.land;

import java.util.Objects;

/**
 * Immutable owner-scoped land name key.
 *
 * <p>Uniqueness of a land name is scoped to its {@link OwnerRef}, not global:
 * two different owners may each own a land whose {@code nameKey} is
 * {@code "home"}. This value object pairs the owner reference with the
 * normalized key so it can be used directly as a map/set key for
 * owner-scoped uniqueness checks.
 *
 * <p>Thread-safe: a pure value object with no mutable state.
 */
public record LandNameKey(OwnerRef ownerRef, String nameKey) {

    public LandNameKey {
        Objects.requireNonNull(ownerRef, "ownerRef");
        Objects.requireNonNull(nameKey, "nameKey");
        String canonical = LandName.normalize(nameKey);
        if (!nameKey.equals(canonical)) {
            throw new IllegalArgumentException(
                    "nameKey must be canonical (LandName.normalize): got '" + nameKey + "' expected '" + canonical + "'");
        }
    }

    /** Build a key from an owner and a {@link LandName}. */
    public static LandNameKey of(OwnerRef ownerRef, LandName name) {
        return new LandNameKey(ownerRef, name.nameKey());
    }
}

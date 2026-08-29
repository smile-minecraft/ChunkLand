package com.smile.chunkland.api.land;

import java.util.Collection;
import java.util.Objects;

/**
 * Pure domain API for owner-scoped land name uniqueness (spec §10).
 *
 * <p>This is a stateless helper, not a persistence layer: callers pass the set
 * of already-used {@link LandNameKey}s (e.g. loaded from a store) and the
 * candidate owner/key, and the helper decides availability or throws on a
 * duplicate. It deliberately does not manage transactions, cross-thread
 * locking, or writes — the downstream store remains responsible for enforcing
 * the same owner scope under concurrency (e.g. a unique constraint on
 * {@code (owner_key, name_key)}).
 */
public final class LandNameUniqueness {

    private LandNameUniqueness() {
    }

    /** True when {@code (owner, nameKey)} is not already taken. */
    public static boolean isAvailable(OwnerRef owner, String nameKey, Collection<? extends LandNameKey> existing) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(nameKey, "nameKey");
        Objects.requireNonNull(existing, "existing");
        String canonical = LandName.normalize(nameKey);
        if (!nameKey.equals(canonical)) {
            throw new IllegalArgumentException(
                    "nameKey must be canonical (LandName.normalize): got '" + nameKey + "' expected '" + canonical + "'");
        }
        return existing.stream().noneMatch(k -> k.ownerRef().equals(owner) && k.nameKey().equals(nameKey));
    }

    /**
     * Throw {@link IllegalArgumentException} if {@code (owner, nameKey)} is
     * already present in {@code existing}; otherwise return normally.
     */
    public static void requireUnique(OwnerRef owner, String nameKey, Collection<? extends LandNameKey> existing) {
        if (!isAvailable(owner, nameKey, existing)) {
            throw new IllegalArgumentException(
                    "duplicate land name within owner " + owner.key() + ": '" + nameKey + "'");
        }
    }
}

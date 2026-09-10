package com.smile.chunkland.persistence;

import java.util.List;
import java.util.Objects;

/**
 * Refusal for a normal Permission Profile delete that is still referenced by
 * land or subland bindings.
 *
 * <p>Carries the blocking references so the caller can name them in the
 * reply. The delete leaves every row untouched; only an explicit force delete
 * removes the references, and it does so in the same transaction.
 */
public final class ProfileDeleteRestrictedException extends ProfileRejectedException {

    private final List<PermissionProfileRepository.AffectedBinding> affected;

    public ProfileDeleteRestrictedException(
            List<PermissionProfileRepository.AffectedBinding> affected) {
        super("profile.restricted");
        this.affected = List.copyOf(Objects.requireNonNull(affected, "affected"));
    }

    /** Bindings that block the normal delete. Never {@code null}, never mutable. */
    public List<PermissionProfileRepository.AffectedBinding> affected() {
        return affected;
    }
}

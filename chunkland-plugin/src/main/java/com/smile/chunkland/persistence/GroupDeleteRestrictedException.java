package com.smile.chunkland.persistence;

import java.util.List;
import java.util.Objects;

/**
 * Refusal for a normal Group delete that is still referenced by land or
 * subland bindings.
 *
 * <p>Carries the blocking references so the caller can name them in the
 * reply. The delete leaves every row untouched; only an explicit force delete
 * removes the references, and it does so in the same transaction.
 */
public final class GroupDeleteRestrictedException extends GroupRejectedException {

    private final List<SubjectGroupRepository.AffectedBinding> affected;

    public GroupDeleteRestrictedException(
            List<SubjectGroupRepository.AffectedBinding> affected) {
        super("group.restricted");
        this.affected = List.copyOf(Objects.requireNonNull(affected, "affected"));
    }

    /** Bindings that block the normal delete. Never {@code null}, never mutable. */
    public List<SubjectGroupRepository.AffectedBinding> affected() {
        return affected;
    }
}

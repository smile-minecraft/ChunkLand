package com.smile.chunkland.runtime.storage;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable rejection for a mutation targeting an isolated world (or global lockdown).
 */
public record MutationRejection(
        UUID worldId,
        MutationKind kind,
        WorldStorageStatus status,
        String reason,
        GlobalStorageState globalState) {

    public MutationRejection {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(globalState, "globalState");
        if (reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
    }
}

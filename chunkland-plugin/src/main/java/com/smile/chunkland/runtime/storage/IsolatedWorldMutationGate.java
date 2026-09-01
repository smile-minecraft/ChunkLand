package com.smile.chunkland.runtime.storage;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Gate that rejects any mutation targeting a world whose storage status is
 * {@code ISOLATED}, or any world when the global state is not READY.
 *
 * <p>Healthy worlds remain unaffected. Orphan rows are never cleared; rejection
 * preserves them.
 */
public final class IsolatedWorldMutationGate {

    private IsolatedWorldMutationGate() {}

    /**
     * Attempt a mutation against the observed storage outcome.
     *
     * @return empty when allowed; a rejection when the world is isolated or the server is not READY
     */
    public static Optional<MutationRejection> tryMutate(
            StorageLoadOutcome outcome,
            UUID worldId,
            MutationKind kind) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(kind, "kind");

        // Global fail-closed paths reject everything, even healthy worlds.
        if (outcome.globalState() != GlobalStorageState.READY) {
            String reason = switch (outcome.globalState()) {
                case LOCKDOWN -> "storage is in LOCKDOWN: mutations are rejected until storage recovers";
                case STOPPED -> "storage is STOPPED: server is not ready and mutations are rejected";
                case READY -> throw new AssertionError("unreachable");
            };
            WorldStorageStatus status = outcome.worldStatuses().getOrDefault(worldId, WorldStorageStatus.HEALTHY);
            // For global failure, treat status as the world status (still observable)
            return Optional.of(new MutationRejection(worldId, kind, status, reason, outcome.globalState()));
        }

        WorldStorageStatus status = outcome.worldStatuses().get(worldId);
        if (status == WorldStorageStatus.ISOLATED) {
            String reason = "world " + worldId + " is ISOLATED: land data retained but not published and mutations are rejected";
            return Optional.of(new MutationRejection(worldId, kind, status, reason, outcome.globalState()));
        }
        // Unknown world (no data) with READY global state is allowed to create.
        // The gate only blocks known ISOLATED worlds; absence means HEALTHY/unknown.
        return Optional.empty();
    }
}

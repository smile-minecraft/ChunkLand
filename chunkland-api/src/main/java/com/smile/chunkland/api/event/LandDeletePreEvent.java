package com.smile.chunkland.api.event;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.UUID;

/**
 * Cancellable Pre event for a whole-land delete.
 *
 * <p>Fired synchronously on the mutation caller's thread after validation
 * but before logical reservations, the ledger row, the domain commit, and any
 * Economy refund. Cancelling (or a throwing listener, which fails closed to
 * cancelled) aborts the delete with zero side effects: no reservation, no
 * ledger row, no domain write, no refund, no audit.
 *
 * <p>Thread contract: same-thread, synchronous dispatch. Listeners must not
 * block, must not perform I/O, must not wait, and must not touch Bukkit/Paper
 * world state unless already on the matching Folia thread.
 */
public final class LandDeletePreEvent implements ChunkLandCancellable {

    private final UUID actorUuid;
    private final UUID worldId;
    private final LandId targetLandId;
    private volatile boolean cancelled;

    /**
     * @param actorUuid deleting player (or console actor)
     * @param worldId world the land lives in
     * @param targetLandId land that would be removed
     */
    public LandDeletePreEvent(UUID actorUuid, UUID worldId, LandId targetLandId) {
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.targetLandId = Objects.requireNonNull(targetLandId, "targetLandId");
    }

    /** Deleting player (or console actor). */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** World the land lives in. */
    public UUID worldId() {
        return worldId;
    }

    /** Land that would be removed. */
    public LandId targetLandId() {
        return targetLandId;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}

package com.smile.chunkland.api.event;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.UUID;

/**
 * Post event for a committed whole-land delete.
 *
 * <p>Fired exactly once, after the durable domain commit plus the runtime
 * snapshot publish both succeeded, and before the Economy refund is
 * attempted — the land is already gone when this fires, even if the later
 * refund parks for recovery. Fires on the async continuation thread, never
 * on a Folia region thread: listeners must not block, must not perform I/O,
 * must not wait, and must hop through the player scheduler before touching
 * any Bukkit/Paper player or world state. A throwing listener is caught,
 * logged and isolated — the committed delete still stands and is never
 * rolled back.
 */
public final class LandDeletePostEvent {

    private final LandId landId;
    private final UUID actorUuid;
    private final long refundMinorUnits;

    /**
     * @param landId removed land
     * @param actorUuid deleting player (or console actor)
     * @param refundMinorUnits refund derived from the durable cost basis, never negative
     */
    public LandDeletePostEvent(LandId landId, UUID actorUuid, long refundMinorUnits) {
        this.landId = Objects.requireNonNull(landId, "landId");
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        if (refundMinorUnits < 0) {
            throw new IllegalArgumentException("refundMinorUnits must not be negative");
        }
        this.refundMinorUnits = refundMinorUnits;
    }

    /** Removed land. */
    public LandId landId() {
        return landId;
    }

    /** Deleting player (or console actor). */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** Refund derived from the durable cost basis. */
    public long refundMinorUnits() {
        return refundMinorUnits;
    }
}

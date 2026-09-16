package com.smile.chunkland.api.event;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.UUID;

/**
 * Post event for a committed land claim.
 *
 * <p>Fired exactly once, after the durable domain commit plus the runtime
 * snapshot publish both succeeded. Fires on the async continuation thread,
 * never on a Folia region thread: listeners must not block, must not perform
 * I/O, must not wait, and must hop through the player scheduler before
 * touching any Bukkit/Paper player or world state. A throwing listener is
 * caught, logged and isolated — the committed claim still stands and is
 * never rolled back.
 */
public final class LandCreatePostEvent {

    private final LandId landId;
    private final UUID actorUuid;
    private final UUID worldId;
    private final int chunkCount;
    private final long priceMinorUnits;

    /**
     * @param landId committed land
     * @param actorUuid claiming player (or console actor)
     * @param worldId world the land lives in
     * @param chunkCount committed chunk count, never negative
     * @param priceMinorUnits charged price, never negative
     */
    public LandCreatePostEvent(LandId landId, UUID actorUuid, UUID worldId,
            int chunkCount, long priceMinorUnits) {
        this.landId = Objects.requireNonNull(landId, "landId");
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount must not be negative");
        }
        if (priceMinorUnits < 0) {
            throw new IllegalArgumentException("priceMinorUnits must not be negative");
        }
        this.chunkCount = chunkCount;
        this.priceMinorUnits = priceMinorUnits;
    }

    /** Committed land. */
    public LandId landId() {
        return landId;
    }

    /** Claiming player (or console actor). */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** World the land lives in. */
    public UUID worldId() {
        return worldId;
    }

    /** Committed chunk count. */
    public int chunkCount() {
        return chunkCount;
    }

    /** Charged price in minor units. */
    public long priceMinorUnits() {
        return priceMinorUnits;
    }
}

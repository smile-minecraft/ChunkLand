package com.smile.chunkland.event.bukkit;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Bukkit view of {@code LandDeletePostEvent}: the delete committed and the
 * runtime published. Fired async; listeners must not block, must not perform
 * I/O, and must hop to the matching Folia thread before touching world
 * state. Throwing listeners are isolated and never roll back the delete.
 */
public final class LandDeletePostBukkitEvent extends ChunkLandBukkitEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LandId landId;
    private final UUID actorUuid;
    private final long refundMinorUnits;

    /** @param async mirrors the dispatch thread (see {@link ChunkLandBukkitEvent}) */
    public LandDeletePostBukkitEvent(LandId landId, UUID actorUuid, long refundMinorUnits,
            boolean async) {
        super(async);
        this.landId = Objects.requireNonNull(landId, "landId");
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
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

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** Bukkit handler list for this event type. */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

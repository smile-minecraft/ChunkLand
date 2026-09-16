package com.smile.chunkland.event.bukkit;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Bukkit view of {@code LandCreatePostEvent}: the claim committed and the
 * runtime published. Fired async; listeners must not block, must not perform
 * I/O, and must hop to the matching Folia thread before touching world
 * state. Throwing listeners are isolated and never roll back the claim.
 */
public final class LandCreatePostBukkitEvent extends ChunkLandBukkitEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LandId landId;
    private final UUID actorUuid;
    private final UUID worldId;
    private final int chunkCount;
    private final long priceMinorUnits;

    /** @param async mirrors the dispatch thread (see {@link ChunkLandBukkitEvent}) */
    public LandCreatePostBukkitEvent(LandId landId, UUID actorUuid, UUID worldId,
            int chunkCount, long priceMinorUnits, boolean async) {
        super(async);
        this.landId = Objects.requireNonNull(landId, "landId");
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
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

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** Bukkit handler list for this event type. */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

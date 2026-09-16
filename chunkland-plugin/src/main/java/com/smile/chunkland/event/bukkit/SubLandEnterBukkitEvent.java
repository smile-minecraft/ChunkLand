package com.smile.chunkland.event.bukkit;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Bukkit view of {@code SubLandEnterEvent}: fired only on an actual boundary
 * transition, synchronously on the movement thread without I/O. Listeners
 * must not block, must not wait, and must hop to the matching Folia thread
 * before touching world state. Throwing listeners are isolated.
 */
public final class SubLandEnterBukkitEvent extends ChunkLandBukkitEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final LandId landId;
    private final SubLandId subLandId;

    /** @param async mirrors the dispatch thread (see {@link ChunkLandBukkitEvent}) */
    public SubLandEnterBukkitEvent(UUID playerId, LandId landId, SubLandId subLandId, boolean async) {
        super(async);
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.landId = Objects.requireNonNull(landId, "landId");
        this.subLandId = Objects.requireNonNull(subLandId, "subLandId");
    }

    /** Moving player. */
    public UUID playerId() {
        return playerId;
    }

    /** Parent land entered. */
    public LandId landId() {
        return landId;
    }

    /** SubLand entered. */
    public SubLandId subLandId() {
        return subLandId;
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

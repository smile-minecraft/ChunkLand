package com.smile.chunkland.event.bukkit;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Bukkit view of {@code LandEnterEvent}: fired only on an actual boundary
 * transition, synchronously on the movement thread without I/O. Listeners
 * must not block, must not wait, and must hop to the matching Folia thread
 * before touching world state. Throwing listeners are isolated.
 */
public final class LandEnterBukkitEvent extends ChunkLandBukkitEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final LandId landId;
    private final UUID worldId;

    /** @param async mirrors the dispatch thread (see {@link ChunkLandBukkitEvent}) */
    public LandEnterBukkitEvent(UUID playerId, LandId landId, UUID worldId, boolean async) {
        super(async);
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.landId = Objects.requireNonNull(landId, "landId");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
    }

    /** Moving player. */
    public UUID playerId() {
        return playerId;
    }

    /** Land entered. */
    public LandId landId() {
        return landId;
    }

    /** World the crossing happened in. */
    public UUID worldId() {
        return worldId;
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

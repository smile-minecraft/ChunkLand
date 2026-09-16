package com.smile.chunkland.api.event;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.UUID;

/**
 * A player crossed into a land (without entering a SubLand).
 *
 * <p>Fired only on an actual boundary transition observed by the enter/leave
 * tracker — same-boundary movement and the silent first-observation baseline
 * never fire. Fires synchronously on the movement event thread, never
 * performing I/O: listeners must not block, must not wait, and must hop
 * through the player scheduler before touching any Bukkit/Paper player or
 * world state. A throwing listener is caught, logged and isolated.
 */
public final class LandEnterEvent {

    private final UUID playerId;
    private final LandId landId;
    private final UUID worldId;

    /**
     * @param playerId moving player
     * @param landId land entered
     * @param worldId world the crossing happened in
     */
    public LandEnterEvent(UUID playerId, LandId landId, UUID worldId) {
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
}

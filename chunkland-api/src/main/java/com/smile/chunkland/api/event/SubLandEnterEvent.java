package com.smile.chunkland.api.event;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import java.util.Objects;
import java.util.UUID;

/**
 * A player crossed into a SubLand.
 *
 * <p>Fired only on an actual boundary transition observed by the enter/leave
 * tracker — same-boundary movement and the silent first-observation baseline
 * never fire. Fires synchronously on the movement event thread, never
 * performing I/O: listeners must not block, must not wait, and must hop
 * through the player scheduler before touching any Bukkit/Paper player or
 * world state. A throwing listener is caught, logged and isolated.
 */
public final class SubLandEnterEvent {

    private final UUID playerId;
    private final LandId landId;
    private final SubLandId subLandId;

    /**
     * @param playerId moving player
     * @param landId parent land entered
     * @param subLandId SubLand entered
     */
    public SubLandEnterEvent(UUID playerId, LandId landId, SubLandId subLandId) {
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
}

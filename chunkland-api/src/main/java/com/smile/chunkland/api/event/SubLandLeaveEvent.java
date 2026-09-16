package com.smile.chunkland.api.event;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import java.util.Objects;
import java.util.UUID;

/**
 * A player crossed out of a SubLand.
 *
 * <p>Fired only on an actual boundary transition observed by the enter/leave
 * tracker — same-boundary movement and the silent first-observation baseline
 * never fire. The ids are the SubLand (and parent) being left. Fires
 * synchronously on the movement event thread, never performing I/O:
 * listeners must not block, must not wait, and must hop through the player
 * scheduler before touching any Bukkit/Paper player or world state. A
 * throwing listener is caught, logged and isolated.
 */
public final class SubLandLeaveEvent {

    private final UUID playerId;
    private final LandId landId;
    private final SubLandId subLandId;

    /**
     * @param playerId moving player
     * @param landId parent land left
     * @param subLandId SubLand left
     */
    public SubLandLeaveEvent(UUID playerId, LandId landId, SubLandId subLandId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.landId = Objects.requireNonNull(landId, "landId");
        this.subLandId = Objects.requireNonNull(subLandId, "subLandId");
    }

    /** Moving player. */
    public UUID playerId() {
        return playerId;
    }

    /** Parent land left. */
    public LandId landId() {
        return landId;
    }

    /** SubLand left. */
    public SubLandId subLandId() {
        return subLandId;
    }
}

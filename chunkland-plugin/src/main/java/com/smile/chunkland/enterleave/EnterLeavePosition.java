package com.smile.chunkland.enterleave;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import java.util.Objects;
import java.util.UUID;

/**
 * Where a player currently stands, resolved from one immutable registry
 * snapshot. A {@code null} land id means Wilderness; a {@code null} sub-land
 * id means the player is on the parent land but inside no SubLand.
 *
 * <p>Records are immutable and safe to share across the event thread and the
 * player scheduler hop. Names are display-only; boundary identity is the
 * {@code (landId, subLandId)} pair alone, so a rename never emits a prompt.
 */
public record EnterLeavePosition(UUID worldId, LandId landId, SubLandId subLandId,
        String landName, String subName) {

    public EnterLeavePosition {
        Objects.requireNonNull(worldId, "worldId");
    }

    /** Wilderness position in the given world. */
    public static EnterLeavePosition wilderness(UUID worldId) {
        return new EnterLeavePosition(Objects.requireNonNull(worldId, "worldId"),
                null, null, null, null);
    }

    /** Whether both positions sit on the same prompt boundary. */
    public boolean sameBoundary(EnterLeavePosition other) {
        Objects.requireNonNull(other, "other");
        return Objects.equals(landId, other.landId)
                && Objects.equals(subLandId, other.subLandId);
    }

    /** Whether this position is Wilderness. */
    public boolean isWilderness() {
        return landId == null;
    }
}

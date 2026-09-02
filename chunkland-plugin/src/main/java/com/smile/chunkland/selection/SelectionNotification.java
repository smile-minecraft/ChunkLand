package com.smile.chunkland.selection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Pure notification payload; presentation is deliberately outside the manager. */
public record SelectionNotification(
        UUID playerId,
        SelectionEndReason reason,
        Optional<LandId> targetLandId,
        Optional<SubLandId> targetSubLandId,
        boolean requiresReload) {

    public SelectionNotification {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reason, "reason");
        targetLandId = Objects.requireNonNull(targetLandId, "targetLandId");
        targetSubLandId = Objects.requireNonNull(targetSubLandId, "targetSubLandId");
    }
}

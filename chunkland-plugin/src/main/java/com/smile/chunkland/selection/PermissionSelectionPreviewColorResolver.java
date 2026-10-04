package com.smile.chunkland.selection;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.SubjectPermissionTier;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.ProtectionEngine;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.Objects;
import java.util.UUID;

/**
 * Samples the effective protection decisions for one preview target.
 *
 * <p>Every action is resolved through the existing engine against one
 * published registry snapshot. A missing or inconsistent snapshot, a resolver
 * failure, or a denied entry remains blocked; it can never become accessible by
 * an optimistic default.
 */
public final class PermissionSelectionPreviewColorResolver implements SelectionPreviewColorResolver {
    private final ProtectionEngine engine;

    public PermissionSelectionPreviewColorResolver(ProtectionEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public SelectionPreviewColor resolve(UUID actor, SelectionLandContext land, SelectionPoint target) {
        if (actor == null || land == null || target == null) {
            return SelectionPreviewColor.BLOCKED;
        }
        if (land.ownedBy(actor)) {
            return SelectionPreviewColor.OWN;
        }

        final LandRegistry snapshot;
        try {
            snapshot = engine.snapshot();
        } catch (RuntimeException failure) {
            return SelectionPreviewColor.BLOCKED;
        }
        if (snapshot == null || snapshot.land(land.landId()) == null) {
            return SelectionPreviewColor.BLOCKED;
        }
        if (!land.landId().equals(snapshot.findLandId(
                target.worldId(), target.blockX() >> 4, target.blockZ() >> 4))) {
            return SelectionPreviewColor.BLOCKED;
        }

        if (!allowedAt(actor, target, ProtectionActionType.ENTRY, snapshot)) {
            return SelectionPreviewColor.BLOCKED;
        }
        for (SubjectPermissionTier tier : new SubjectPermissionTier[]{
                SubjectPermissionTier.THIRD, SubjectPermissionTier.FOURTH}) {
            for (ProtectionActionType action : tier.actions()) {
                if (allowedAt(actor, target, action, snapshot)) {
                    return SelectionPreviewColor.ACCESSIBLE;
                }
            }
        }
        return SelectionPreviewColor.BLOCKED;
    }

    private boolean allowedAt(UUID actor, SelectionPoint target,
                              ProtectionActionType action, LandRegistry snapshot) {
        try {
            return engine.decideAtBlockOnSnapshot(
                    actor, target.worldId(), target.blockX(), target.blockY(), target.blockZ(),
                    action, snapshot).outcome() == PermissionState.ALLOW;
        } catch (RuntimeException failure) {
            return false;
        }
    }
}

package com.smile.chunkland.api.permission;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Display-only grouping for the subject permission actions.
 *
 * <p>The grouping does not participate in authorization or management. It
 * gives the occupied-land preview a stable, finite set of actions to sample
 * after it has established entry access.
 */
public enum SubjectPermissionTier {
    FIRST(1, List.of(ProtectionActionType.ENTRY)),
    SECOND(2, List.of(
            ProtectionActionType.CONTAINER_OPEN,
            ProtectionActionType.WORKSTATION_USE,
            ProtectionActionType.DOOR_USE,
            ProtectionActionType.BUTTON_USE,
            ProtectionActionType.LEVER_USE,
            ProtectionActionType.REDSTONE_USE,
            ProtectionActionType.VEHICLE_USE,
            ProtectionActionType.ENTITY_INTERACT)),
    THIRD(3, List.of(
            ProtectionActionType.BLOCK_BREAK,
            ProtectionActionType.BLOCK_PLACE,
            ProtectionActionType.ENTITY_DAMAGE,
            ProtectionActionType.BUCKET_USE,
            ProtectionActionType.ITEM_FRAME,
            ProtectionActionType.ARMOR_STAND,
            ProtectionActionType.HANGING_ENTITY,
            ProtectionActionType.FARMLAND_TRAMPLE)),
    FOURTH(4, List.of(
            ProtectionActionType.MANAGE_MEMBER,
            ProtectionActionType.MANAGE_PERMISSION,
            ProtectionActionType.MANAGE_SUBLAND,
            ProtectionActionType.EXPAND_LAND,
            ProtectionActionType.DELETE_LAND));

    private final int level;
    private final List<ProtectionActionType> actions;

    SubjectPermissionTier(int level, List<ProtectionActionType> actions) {
        this.level = level;
        this.actions = List.copyOf(actions);
    }

    public int level() {
        return level;
    }

    public List<ProtectionActionType> actions() {
        return actions;
    }

    /** Return the display tier, or empty for a non-subject action. */
    public static Optional<SubjectPermissionTier> of(ProtectionActionType action) {
        Objects.requireNonNull(action, "action");
        for (SubjectPermissionTier tier : values()) {
            if (tier.actions.contains(action)) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }
}

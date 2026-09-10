package com.smile.chunkland.protection;

import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Explicit subject subset a direct-trust profile may carry.
 *
 * <p>Trust grants everyday member actions only. Management actions and every
 * land rule stay outside the set, so a trusted player can never administer
 * the land or escape environment rules through a binding. The same set
 * bounds the land-default command, keeping both writers in one namespace.
 */
public final class DirectTrustWhitelist {

    /** Everyday member actions a direct profile may grant. Fixed contract. */
    public static final Set<ProtectionActionType> ALLOWED = Set.of(
            ProtectionActionType.BLOCK_BREAK,
            ProtectionActionType.BLOCK_PLACE,
            ProtectionActionType.CONTAINER_OPEN,
            ProtectionActionType.WORKSTATION_USE,
            ProtectionActionType.DOOR_USE,
            ProtectionActionType.BUTTON_USE,
            ProtectionActionType.LEVER_USE,
            ProtectionActionType.REDSTONE_USE,
            ProtectionActionType.BUCKET_USE,
            ProtectionActionType.ENTRY,
            ProtectionActionType.VEHICLE_USE,
            ProtectionActionType.ENTITY_INTERACT,
            ProtectionActionType.ENTITY_DAMAGE,
            ProtectionActionType.ITEM_FRAME,
            ProtectionActionType.ARMOR_STAND,
            ProtectionActionType.HANGING_ENTITY,
            ProtectionActionType.FARMLAND_TRAMPLE);

    private DirectTrustWhitelist() {
    }

    /** Whether the action may appear in a direct profile or land default. */
    public static boolean isAllowed(ProtectionActionType action) {
        return action != null && ALLOWED.contains(action);
    }

    /**
     * Returns the action or throws when it is outside the whitelist.
     *
     * @throws IllegalArgumentException when the action is null or not whitelisted
     */
    public static ProtectionActionType requireAllowed(ProtectionActionType action) {
        if (!isAllowed(action)) {
            throw new IllegalArgumentException(
                    "action is not in the direct-trust whitelist: " + action);
        }
        return action;
    }

    /**
     * Parses a case-insensitive action name, accepting only whitelisted
     * actions. Unknown, blank, management, rule and wildcard names yield
     * empty so callers fail closed.
     */
    public static Optional<ProtectionActionType> parseAllowed(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalized = name.strip().toUpperCase(Locale.ROOT);
        try {
            ProtectionActionType action = ProtectionActionType.valueOf(normalized);
            return isAllowed(action) ? Optional.of(action) : Optional.empty();
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}

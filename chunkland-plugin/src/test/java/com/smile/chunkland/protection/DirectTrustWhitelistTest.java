package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the explicit direct-trust whitelist: the exact subject subset a
 * {@code /land trust} profile may carry, with management actions and every
 * land rule excluded so trust can never escalate into administration or
 * environment rules.
 */
class DirectTrustWhitelistTest {

    @Test
    void allowedSetIsExactlyTheSeventeenSubjectActions() {
        assertEquals(17, DirectTrustWhitelist.ALLOWED.size());
        for (ProtectionActionType action : new ProtectionActionType[] {
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
                ProtectionActionType.FARMLAND_TRAMPLE}) {
            assertTrue(DirectTrustWhitelist.isAllowed(action), action + " must be whitelisted");
            assertEquals(DecisionSource.SUBJECT_PERMISSION, action.decisionSource(),
                    action + " must resolve from the subject chain");
        }
    }

    @Test
    void managementActionsAreExcluded() {
        for (ProtectionActionType action : new ProtectionActionType[] {
                ProtectionActionType.MANAGE_MEMBER,
                ProtectionActionType.MANAGE_PERMISSION,
                ProtectionActionType.MANAGE_SUBLAND,
                ProtectionActionType.EXPAND_LAND,
                ProtectionActionType.DELETE_LAND}) {
            assertFalse(DirectTrustWhitelist.isAllowed(action), action + " must never be trustable");
            assertTrue(DirectTrustWhitelist.parseAllowed(action.name()).isEmpty());
            assertThrows(IllegalArgumentException.class,
                    () -> DirectTrustWhitelist.requireAllowed(action));
        }
    }

    @Test
    void everyLandRuleIsExcluded() {
        for (ProtectionActionType action : ProtectionActionType.values()) {
            if (action.decisionSource() == DecisionSource.LAND_RULE) {
                assertFalse(DirectTrustWhitelist.isAllowed(action),
                        action + " must never be trustable");
                assertTrue(DirectTrustWhitelist.parseAllowed(action.name()).isEmpty());
            }
        }
    }

    @Test
    void parseIsCaseInsensitiveAndRejectsUnknown() {
        assertEquals(Optional.of(ProtectionActionType.BLOCK_BREAK),
                DirectTrustWhitelist.parseAllowed("block_break"));
        assertEquals(Optional.of(ProtectionActionType.ENTRY),
                DirectTrustWhitelist.parseAllowed("ENTRY"));
        assertTrue(DirectTrustWhitelist.parseAllowed("BOGUS_ACTION").isEmpty());
        assertTrue(DirectTrustWhitelist.parseAllowed(null).isEmpty());
        assertTrue(DirectTrustWhitelist.parseAllowed("   ").isEmpty());
        assertTrue(DirectTrustWhitelist.parseAllowed("EVERYONE").isEmpty());
        assertFalse(DirectTrustWhitelist.isAllowed(null));
    }
}

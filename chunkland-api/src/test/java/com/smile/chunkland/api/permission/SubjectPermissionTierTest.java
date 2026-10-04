package com.smile.chunkland.api.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SubjectPermissionTierTest {

    @Test
    void everySubjectPermissionActionHasExactlyOneDisplayTier() {
        Map<ProtectionActionType, SubjectPermissionTier> tiers = new EnumMap<>(ProtectionActionType.class);
        for (ProtectionActionType action : ProtectionActionType.values()) {
            if (action.decisionSource() == DecisionSource.SUBJECT_PERMISSION) {
                SubjectPermissionTier tier = SubjectPermissionTier.of(action).orElseThrow();
                assertEquals(null, tiers.put(action, tier), action + " must have one tier");
            }
        }
        assertEquals(22, tiers.size());
        assertEquals(Set.of(ProtectionActionType.ENTRY), actionsIn(tiers, SubjectPermissionTier.FIRST));
        assertEquals(Set.of(
                        ProtectionActionType.CONTAINER_OPEN,
                        ProtectionActionType.WORKSTATION_USE,
                        ProtectionActionType.DOOR_USE,
                        ProtectionActionType.BUTTON_USE,
                        ProtectionActionType.LEVER_USE,
                        ProtectionActionType.REDSTONE_USE,
                        ProtectionActionType.VEHICLE_USE,
                        ProtectionActionType.ENTITY_INTERACT),
                actionsIn(tiers, SubjectPermissionTier.SECOND));
        assertEquals(Set.of(
                        ProtectionActionType.BLOCK_BREAK,
                        ProtectionActionType.BLOCK_PLACE,
                        ProtectionActionType.ENTITY_DAMAGE,
                        ProtectionActionType.BUCKET_USE,
                        ProtectionActionType.ITEM_FRAME,
                        ProtectionActionType.ARMOR_STAND,
                        ProtectionActionType.HANGING_ENTITY,
                        ProtectionActionType.FARMLAND_TRAMPLE),
                actionsIn(tiers, SubjectPermissionTier.THIRD));
        assertEquals(Set.of(
                        ProtectionActionType.MANAGE_MEMBER,
                        ProtectionActionType.MANAGE_PERMISSION,
                        ProtectionActionType.MANAGE_SUBLAND,
                        ProtectionActionType.EXPAND_LAND,
                        ProtectionActionType.DELETE_LAND),
                actionsIn(tiers, SubjectPermissionTier.FOURTH));
    }

    private static Set<ProtectionActionType> actionsIn(
            Map<ProtectionActionType, SubjectPermissionTier> tiers, SubjectPermissionTier expected) {
        return tiers.entrySet().stream()
                .filter(entry -> entry.getValue() == expected)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void landRuleActionsHaveNoDisplayTier() {
        for (ProtectionActionType action : ProtectionActionType.values()) {
            if (action.decisionSource() == DecisionSource.LAND_RULE) {
                assertTrue(SubjectPermissionTier.of(action).isEmpty(), action + " must not be classified");
            }
        }
    }
}

package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.LandPermissions;
import com.smile.chunkland.protection.ManagementPermissionGate;
import org.junit.jupiter.api.Test;

/**
 * Red contract for {@code /land shrink} and {@code /land unclaim}
 * share one handler behind the structure gate.
 */
class ShrinkRedContractTest {

    @Test
    void shrinkAndUnclaimAreRegisteredSubcommands() {
        assertTrue(LandCommand.SUBCOMMANDS.contains("shrink"), "SUBCOMMANDS must contain shrink");
        assertTrue(LandCommand.SUBCOMMANDS.contains("unclaim"), "SUBCOMMANDS must contain unclaim");
    }

    @Test
    void shrinkAndUnclaimSharePermissionNode() {
        String shrink = LandPermissions.forSubcommand("shrink");
        String unclaim = LandPermissions.forSubcommand("unclaim");
        assertTrue(shrink != null && !shrink.isBlank(), "shrink must have a permission node");
        assertTrue(shrink.equals(unclaim), "shrink and unclaim must share one permission node");
    }

    @Test
    void shrinkAndUnclaimMapToExpandGate() {
        assertTrue(ManagementPermissionGate.actionForSubcommand("shrink")
                .map(action -> action == ProtectionActionType.EXPAND_LAND).orElse(false),
                "shrink must map to EXPAND_LAND");
        assertTrue(ManagementPermissionGate.actionForSubcommand("unclaim")
                .map(action -> action == ProtectionActionType.EXPAND_LAND).orElse(false),
                "unclaim must map to EXPAND_LAND");
    }
}

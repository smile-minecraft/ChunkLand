package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.protection.ManagementPermissionGate;
import org.junit.jupiter.api.Test;

/**
 * Red pins for Land/SubLand generic bindings: persistence epoch table,
 * command routing, and the management gate mapping do not exist yet.
 */
class LandBindingRedTest {

    @Test
    void schemaHasOwnerAclEpochTable() {
        assertEquals(5, SchemaMigrator.LATEST_VERSION);
    }

    @Test
    void bindingSubcommandIsRegistered() {
        assertTrue(LandCommand.SUBCOMMANDS.contains("binding"));
    }

    @Test
    void bindingSubcommandMapsToManagePermission() {
        assertTrue(ManagementPermissionGate.actionForSubcommand("binding").isPresent());
    }
}

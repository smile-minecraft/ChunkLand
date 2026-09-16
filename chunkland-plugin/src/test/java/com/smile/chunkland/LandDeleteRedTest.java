package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.LandPermissions;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.protection.ManagementPermissionGate;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * Red contract for Land Delete: {@code /land delete} must be backed by a
 * real handler once wired, not the production not-yet stub.
 */
class LandDeleteRedTest {

    @Test
    void deleteIsRegisteredSubcommand() {
        assertTrue(LandCommand.SUBCOMMANDS.contains("delete"), "SUBCOMMANDS must contain delete");
    }

    @Test
    void deleteHasPermissionNode() {
        assertEquals("chunkland.command.land.delete", LandPermissions.forSubcommand("delete"));
    }

    @Test
    void deleteMapsToDeleteLandGate() {
        assertTrue(ManagementPermissionGate.actionForSubcommand("delete")
                .map(action -> action == ProtectionActionType.DELETE_LAND).orElse(false),
                "delete must map to DELETE_LAND");
    }

    @Test
    void baseHandlersDeleteIsNotStub() {
        LandCommand.Handler handler = ChunkLandPlugin.buildLandHandlers().get("delete");
        assertTrue(handler != null, "base handlers must contain delete");
        List<String> keys = new ArrayList<>();
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return true;
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
        ReplySink sink = new ReplySink() {
            @Override
            public void reply(String messageKey, Map<String, Object> vars) {
                keys.add(messageKey);
            }

            @Override
            public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
                keys.add(messageKey);
            }
        };
        handler.handle(sender, new String[] {"delete"}, sink);
        assertEquals(1, keys.size(), "delete handler must reply exactly once");
        assertNotEquals("command.land.not_yet", keys.get(0),
                "delete must be implemented, not a stub");
    }
}

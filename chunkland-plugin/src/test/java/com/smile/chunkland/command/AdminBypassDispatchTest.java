package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Registration contract for the per-enable player-scoped admin bypass.
 *
 * <p>Holding the bypass node only allows <em>attempting</em> the toggle; it
 * never authorises a management mutation on its own. Only an explicit
 * audited {@code /land bypass on} flips the in-memory state the shared
 * domain gate reads.
 */
class AdminBypassDispatchTest {

    static final String BYPASS_NODE = "chunkland.admin.bypass";

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    private static CommandSender senderWithPerms(Map<String, Boolean> perms, List<String> keys) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class, Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("hasPermission")) {
                        Object node = args == null || args.length == 0 ? null : args[0];
                        return node instanceof String key && perms.getOrDefault(key, false);
                    }
                    if (name.equals("getUniqueId")) {
                        return UUID.randomUUID();
                    }
                    if (name.equals("getName")) {
                        return "Bypass-probe";
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "Bypass-probe-proxy";
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    if (rt == double.class) {
                        return 0d;
                    }
                    if (rt == float.class) {
                        return 0f;
                    }
                    return null;
                });
    }

    private static LandSnapshot playerLand(LandId id, UUID owner) {
        LandName name = LandName.of("Home");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(owner), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void bypassSubcommandIsRegisteredWithItsOwnPermissionNode() {
        assertTrue(LandCommand.SUBCOMMANDS.contains("bypass"),
                "SUBCOMMANDS must route /land bypass");
        assertEquals(BYPASS_NODE, LandPermissions.forSubcommand("bypass"));
        assertEquals(BYPASS_NODE, LandPermissions.forSubcommand("BYPASS"));
        assertTrue(LandPermissions.ALL_ORDERED.contains(BYPASS_NODE));
        assertNotEquals("chunkland.admin.serverland", LandPermissions.forSubcommand("bypass"));
        assertNotEquals("chunkland.admin.ledger", LandPermissions.forSubcommand("bypass"));
        assertNotEquals("chunkland.admin.orphan", LandPermissions.forSubcommand("bypass"));
    }

    @Test
    void bypassSubcommandCarriesNoManagementAction() {
        assertTrue(LandCommand.managementActionFor("bypass").isEmpty(),
                "bypass is a toggle, never a management mutation");
        assertTrue(ManagementPermissionGate.actionForSubcommand("bypass").isEmpty(),
                "bypass must not map to a domain gate action");
    }

    @Test
    void bypassNodeAloneNeverAuthorisesAManagementMutation() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        var provider = new SnapshotPermissionContextProvider(null, null);
        UUID stranger = UUID.randomUUID();
        // Holding the node is only toggle authority: without an explicit
        // audited toggle the gate input stays false, so a stranger is denied.
        assertEquals(PermissionState.DENY, ManagementPermissionGate.check(
                stranger, id, ProtectionActionType.DELETE_LAND, snapshot, false, false, provider)
                .outcome());
    }

    @Test
    void dispatchWithoutNodeIsDeniedAndNeverReachesTheHandler() {
        List<String> keys = new ArrayList<>();
        boolean[] called = {false};
        LandCommand cmd = new LandCommand(
                Map.of("bypass", (sender, args, sink) -> called[0] = true),
                (sender, pipeline) -> new ReplySink() {
                    public void reply(String key, Map<String, Object> vars) {
                        keys.add(key);
                    }

                    public void reply(String key, Map<String, Object> vars, java.util.Locale locale) {
                        keys.add(key);
                    }
                });
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CommandSender sender = senderWithPerms(Map.of(BYPASS_NODE, false), out);
        cmd.dispatch(sender, new String[] {"bypass", "on"}, null);
        assertTrue(keys.contains("command.land.denied"),
                "missing bypass node must stay denied");
        assertTrue(!called[0], "denied toggle must never reach the handler");
    }
}

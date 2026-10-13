package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * The {@code rule} subcommand lives under the shared management gate: it
 * maps to {@code MANAGE_PERMISSION} like the other permission writers, it
 * carries its own command node defaulting to op, a stranger is refused
 * without touching the handler, and the owner passes the gate. Tab
 * completion offers the eleven rules plus the three states.
 */
class LandRuleGateTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    @Test
    void ruleIsARegisteredSubcommandWithItsOwnNode() {
        assertTrue(LandCommand.SUBCOMMANDS.contains("rule"),
                "rule must be a registered /land subcommand");
        assertEquals("chunkland.command.land.rule", LandPermissions.forSubcommand("rule"));
        assertEquals(LandPermissions.RULE, LandPermissions.forSubcommand("rule"));
        assertTrue(LandPermissions.ALL_ORDERED.contains(LandPermissions.RULE));
    }

    @Test
    void ruleMapsToManagePermission() {
        assertEquals(Optional.of(ProtectionActionType.MANAGE_PERMISSION),
                ManagementPermissionGate.actionForSubcommand("rule"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_PERMISSION),
                ManagementPermissionGate.actionForSubcommand("RULE"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_PERMISSION),
                LandCommand.managementActionFor("rule"));
    }

    @Test
    void tabCompletionOffersRulesThenStates() {
        CommandSender sender = senderWithAllNodes();
        List<String> rules = LandCommand.tabComplete(sender, new String[] {"rule", ""});
        for (LandRuleType rule : LandRuleType.values()) {
            assertTrue(rules.contains(rule.name()), "completion must list " + rule);
        }
        assertEquals(List.of("PVP"),
                LandCommand.tabComplete(sender, new String[] {"rule", "pv"}));
        assertEquals(List.of("ALLOW", "DENY", "INHERIT"),
                LandCommand.tabComplete(sender, new String[] {"rule", "PVP", ""}));
        assertEquals(List.of("DENY"),
                LandCommand.tabComplete(sender, new String[] {"rule", "PVP", "d"}));
    }

    @Test
    void strangerIsDeniedWithoutReachingTheHandler() {
        AtomicBoolean called = new AtomicBoolean(false);
        List<String> keys = new ArrayList<>();
        LandId land = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(land, OWNER)));
        ManagementGateResolver.Request request = new ManagementGateResolver.Request(
                UUID.randomUUID(), land, snapshot, false, false, emptyProvider());
        LandCommand command = new LandCommand(
                Map.of("rule", (sender, args, sink) -> called.set(true)),
                (sender, pipeline) -> new ReplySink() {
                    public void reply(String key, Map<String, Object> vars) {
                        keys.add(key);
                    }

                    public void reply(String key, Map<String, Object> vars,
                            java.util.Locale locale) {
                        keys.add(key);
                    }
                },
                (sender, action, args) -> Optional.of(request));
        assertTrue(command.dispatch(senderWithAllNodes(), new String[] {"rule"}, null));
        assertFalse(called.get(), "a stranger must never reach the rule handler");
        assertTrue(keys.contains("command.land.denied"));
    }

    @Test
    void ownerPassesTheGateToTheHandler() {
        AtomicBoolean called = new AtomicBoolean(false);
        List<String> keys = new ArrayList<>();
        LandId land = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(land, OWNER)));
        ManagementGateResolver.Request request = new ManagementGateResolver.Request(
                OWNER, land, snapshot, false, false, emptyProvider());
        assertEquals(PermissionState.ALLOW, ManagementPermissionGate.check(
                OWNER, land, ProtectionActionType.MANAGE_PERMISSION, snapshot,
                false, false, emptyProvider()).outcome(),
                "the owner passes MANAGE_PERMISSION through the owner guarantee");
        LandCommand command = new LandCommand(
                Map.of("rule", (sender, args, sink) -> called.set(true)),
                (sender, pipeline) -> new ReplySink() {
                    public void reply(String key, Map<String, Object> vars) {
                        keys.add(key);
                    }

                    public void reply(String key, Map<String, Object> vars,
                            java.util.Locale locale) {
                        keys.add(key);
                    }
                },
                (sender, action, args) -> Optional.of(request));
        assertTrue(command.dispatch(senderWithAllNodes(), new String[] {"rule"}, null));
        assertTrue(called.get(), "the owner must reach the rule handler");
        assertTrue(keys.isEmpty());
    }

    private static LandSnapshot playerLand(LandId id, UUID owner) {
        LandName name = LandName.of("Home");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(owner), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static PermissionContextProvider emptyProvider() {
        return new SnapshotPermissionContextProvider(null, null);
    }

    private static CommandSender senderWithAllNodes() {
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class[] {CommandSender.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("sendMessage")) {
                        return null;
                    }
                    if (name.equals("hasPermission") || name.equals("isPermissionSet")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "Sender";
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "Sender-proxy";
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    if (type == long.class) {
                        return 0L;
                    }
                    if (type == double.class) {
                        return 0d;
                    }
                    if (type == float.class) {
                        return 0f;
                    }
                    return null;
                });
    }
}

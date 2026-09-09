package com.smile.chunkland.subland;

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
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.LandPermissions;
import com.smile.chunkland.command.ManagementGateResolver;
import com.smile.chunkland.command.ReplySink;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * Gate tests for the SubLand management entry point: the single
 * {@code MANAGE_SUBLAND} domain decision for owner / steward / stranger /
 * bypass-off, the subcommand mapping, and dispatcher deny-before-handler
 * behaviour.
 */
class SubLandGateTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STEWARD = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    private static LandSnapshot playerLand(LandId id, UUID owner) {
        LandName name = LandName.of("Home");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(owner), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static LandSnapshot serverLand(LandId id) {
        LandName name = LandName.of("Spawn");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.server(), WORLD, Set.of(new ChunkKey(WORLD, 8, 8)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static PermissionContextProvider emptyProvider() {
        return new SnapshotPermissionContextProvider(null, null);
    }

    @Test
    void sublandMapsToManageSublandEverywhere() {
        assertEquals(Optional.of(ProtectionActionType.MANAGE_SUBLAND),
                LandCommand.managementActionFor("subland"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_SUBLAND),
                LandCommand.managementActionFor("SUBLAND"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_SUBLAND),
                ManagementPermissionGate.actionForSubcommand("subland"));
        assertEquals("chunkland.command.land.subland", LandPermissions.forSubcommand("subland"));
        assertTrue(LandCommand.SUBCOMMANDS.contains("subland"));
        assertTrue(LandPermissions.ALL_ORDERED.contains(LandPermissions.SUBLAND));
        assertTrue(ManagementPermissionGate.isManagementAction(ProtectionActionType.MANAGE_SUBLAND));
    }

    @Test
    void ownerPassesWithBypassOff() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        assertEquals(PermissionState.ALLOW, ManagementPermissionGate.check(
                OWNER, id, ProtectionActionType.MANAGE_SUBLAND,
                snapshot, false, false, emptyProvider()).outcome());
    }

    @Test
    void strangerDeniedWithBypassOff() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        assertEquals(PermissionState.DENY, ManagementPermissionGate.check(
                STRANGER, id, ProtectionActionType.MANAGE_SUBLAND,
                snapshot, false, false, emptyProvider()).outcome());
    }

    @Test
    void stewardPassesOnServerLandWithoutBypass() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(serverLand(id)));
        assertEquals(PermissionState.ALLOW, ManagementPermissionGate.check(
                STEWARD, id, ProtectionActionType.MANAGE_SUBLAND,
                snapshot, false, true, emptyProvider()).outcome());
        // The steward flag alone never authorises player-owned land.
        LandRegistry player = LandRegistry.from(List.of(playerLand(id, OWNER)));
        assertEquals(PermissionState.DENY, ManagementPermissionGate.check(
                STEWARD, id, ProtectionActionType.MANAGE_SUBLAND,
                player, false, true, emptyProvider()).outcome());
    }

    @Test
    void adminBypassPasses() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        assertEquals(PermissionState.ALLOW, ManagementPermissionGate.check(
                STRANGER, id, ProtectionActionType.MANAGE_SUBLAND,
                snapshot, true, false, emptyProvider()).outcome());
    }

    @Test
    void dispatcherDenyNeverReachesSublandHandler() {
        AtomicBoolean called = new AtomicBoolean(false);
        Map<String, LandCommand.Handler> handlers =
                Map.of("subland", (s, a, sink) -> called.set(true));
        List<String> keys = new ArrayList<>();
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        ManagementGateResolver.Request stranger = new ManagementGateResolver.Request(
                STRANGER, id, snapshot, false, false, emptyProvider());
        LandCommand cmd = new LandCommand(handlers, (s, p) -> new ReplySink() {
            public void reply(String k, Map<String, Object> v) {
                keys.add(k);
            }

            public void reply(String k, Map<String, Object> v, java.util.Locale l) {
                keys.add(k);
            }
        }, (sender, action, args) -> Optional.of(stranger));
        assertTrue(cmd.dispatch(senderWithAllNodes(), new String[]{"subland", "create"}, null));
        assertFalse(called.get(), "domain deny must not invoke the subland handler");
        assertTrue(keys.contains("command.land.denied"));
    }

    @Test
    void dispatcherAllowReachesSublandHandler() {
        AtomicBoolean called = new AtomicBoolean(false);
        Map<String, LandCommand.Handler> handlers =
                Map.of("subland", (s, a, sink) -> called.set(true));
        List<String> keys = new ArrayList<>();
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        ManagementGateResolver.Request owner = new ManagementGateResolver.Request(
                OWNER, id, snapshot, false, false, emptyProvider());
        LandCommand cmd = new LandCommand(handlers, (s, p) -> new ReplySink() {
            public void reply(String k, Map<String, Object> v) {
                keys.add(k);
            }

            public void reply(String k, Map<String, Object> v, java.util.Locale l) {
                keys.add(k);
            }
        }, (sender, action, args) -> Optional.of(owner));
        assertTrue(cmd.dispatch(senderWithAllNodes(), new String[]{"subland", "delete"}, null));
        assertTrue(called.get(), "domain allow must invoke the subland handler");
        assertTrue(keys.isEmpty());
    }

    private static CommandSender senderWithAllNodes() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class[]{CommandSender.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("hasPermission")) {
                        return true;
                    }
                    if (n.equals("getName")) {
                        return "Sender";
                    }
                    if (n.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (n.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (n.equals("toString")) {
                        return "Sender-proxy";
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
                    return null;
                });
    }
}

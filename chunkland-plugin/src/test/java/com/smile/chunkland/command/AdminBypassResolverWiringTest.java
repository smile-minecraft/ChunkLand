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
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
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
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Shared-supplier contract: the command resolver, the GUI handler and the
 * Bedrock branch all read the same per-enable bypass memory, and the bypass
 * node never grants anything on its own.
 */
class AdminBypassResolverWiringTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    private static Player playerAt(UUID uuid, boolean bypassNode, boolean stewardNode) {
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) {
                        return WORLD;
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("toString")) {
                        return "World-proxy";
                    }
                    return defaultValue(method.getReturnType());
                });
        Location location = new Location(world, 5.0, 64.0, 5.0);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return uuid;
                    }
                    if (name.equals("getLocation")) {
                        return location;
                    }
                    if (name.equals("hasPermission")) {
                        Object node = args == null || args.length == 0 ? null : args[0];
                        if (PluginManagementGateResolver.SERVER_LAND_STEWARD_NODE.equals(node)) {
                            return stewardNode;
                        }
                        if ("chunkland.admin.bypass".equals(node)) {
                            return bypassNode;
                        }
                        return false;
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
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
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
    }

    private static LandSnapshot playerLand(LandId id, UUID owner) {
        LandName name = LandName.of("Home");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(owner), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void resolverReadsTheSharedBypassMemory() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        AdminBypassState states = new AdminBypassState();
        UUID stranger = UUID.randomUUID();
        PluginManagementGateResolver resolver = new PluginManagementGateResolver(
                () -> snapshot,
                () -> new SnapshotPermissionContextProvider(null, null),
                PluginManagementGateResolver.TargetLandResolver.currentLocation(),
                states::isOn);

        Optional<ManagementGateResolver.Request> before = resolver.resolve(
                playerAt(stranger, true, false), ProtectionActionType.DELETE_LAND,
                new String[] {"delete"});
        assertTrue(before.isPresent());
        assertFalse(before.get().adminBypass(),
                "holding the bypass node without a toggle must resolve to false");

        states.setEnabled(stranger, true);
        Optional<ManagementGateResolver.Request> after = resolver.resolve(
                playerAt(stranger, true, false), ProtectionActionType.DELETE_LAND,
                new String[] {"delete"});
        assertTrue(after.isPresent());
        assertTrue(after.get().adminBypass(), "an explicit toggle must reach the gate inputs");
        assertEquals(PermissionState.ALLOW, ManagementPermissionGate.check(
                after.get().actor(), after.get().landId(), ProtectionActionType.DELETE_LAND,
                after.get().snapshot(), after.get().adminBypass(),
                after.get().serverLandSteward(), after.get().provider()).outcome());
    }

    @Test
    void legacyResolverConstructorStaysFailClosed() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        UUID stranger = UUID.randomUUID();
        PluginManagementGateResolver resolver = new PluginManagementGateResolver(
                () -> snapshot,
                () -> new SnapshotPermissionContextProvider(null, null),
                PluginManagementGateResolver.TargetLandResolver.currentLocation());

        Optional<ManagementGateResolver.Request> resolved = resolver.resolve(
                playerAt(stranger, true, false), ProtectionActionType.DELETE_LAND,
                new String[] {"delete"});
        assertTrue(resolved.isPresent());
        assertFalse(resolved.get().adminBypass(),
                "legacy wiring without a bypass supplier must stay fail-closed");
    }

    @Test
    void throwingBypassSupplierFailsTheResolutionClosed() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        PluginManagementGateResolver resolver = new PluginManagementGateResolver(
                () -> snapshot,
                () -> new SnapshotPermissionContextProvider(null, null),
                PluginManagementGateResolver.TargetLandResolver.currentLocation(),
                uuid -> {
                    throw new IllegalStateException("bypass store down");
                });

        assertTrue(resolver.resolve(
                playerAt(UUID.randomUUID(), true, false), ProtectionActionType.DELETE_LAND,
                new String[] {"delete"}).isEmpty(),
                "a failing bypass source must fail the whole resolution closed");
    }

    @Test
    void guiHandlerSeesTheSameSupplier() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        AdminBypassState states = new AdminBypassState();
        UUID stranger = UUID.randomUUID();
        ManagementGateResolver resolver = new PluginManagementGateResolver(
                () -> snapshot,
                () -> new SnapshotPermissionContextProvider(null, null),
                (sender, action, args, seen) -> Optional.of(id),
                states::isOn);
        AtomicBoolean opened = new AtomicBoolean(false);
        List<String> keys = new ArrayList<>();
        ManageGuiCommandHandler gui = new ManageGuiCommandHandler(resolver,
                (player, landId) -> opened.set(true));
        ReplySink sink = new ReplySink() {
            public void reply(String key, Map<String, Object> vars) {
                keys.add(key);
            }

            public void reply(String key, Map<String, Object> vars, java.util.Locale locale) {
                keys.add(key);
            }
        };

        gui.handle(playerAt(stranger, true, false), new String[] {"manage"}, sink);
        assertFalse(opened.get(), "without a toggle the GUI must stay closed");
        assertTrue(keys.contains("command.land.manage.denied"));

        states.setEnabled(stranger, true);
        keys.clear();
        gui.handle(playerAt(stranger, true, false), new String[] {"manage"}, sink);
        assertTrue(opened.get(), "the same toggle must open the GUI for the same actor");

        states.setEnabled(stranger, false);
        opened.set(false);
        keys.clear();
        gui.handle(playerAt(stranger, true, false), new String[] {"manage"}, sink);
        assertFalse(opened.get(), "toggling off must close the bypass immediately");
    }

    @Test
    void serverlandNodeAloneNeverTogglesOrBypasses() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        AdminBypassState states = new AdminBypassState();
        UUID steward = UUID.randomUUID();
        PluginManagementGateResolver resolver = new PluginManagementGateResolver(
                () -> snapshot,
                () -> new SnapshotPermissionContextProvider(null, null),
                (sender, action, args, seen) -> Optional.of(id),
                states::isOn);

        Optional<ManagementGateResolver.Request> resolved = resolver.resolve(
                playerAt(steward, false, true), ProtectionActionType.DELETE_LAND,
                new String[] {"delete"});
        assertTrue(resolved.isPresent());
        assertFalse(resolved.get().adminBypass(),
                "the steward node must never flip the bypass flag");
        assertTrue(resolved.get().serverLandSteward());
        assertFalse(states.isOn(steward));
    }
}

package com.smile.chunkland;

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
import com.smile.chunkland.command.ManagementGateResolver;
import com.smile.chunkland.command.PluginManagementGateResolver;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Formal wiring resolves the management target from the player's current
 * location against the immutable registry snapshot: standing on a published
 * land names that land, wilderness or an unknown world refuses, and the
 * shared gate then separates owner, stranger and Server Land steward without
 * admin bypass. Consoles and unresolvable positions fail closed.
 */
class ManagementGateCurrentLocationWiringTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    private static World proxyWorld(UUID uid) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) {
                        return uid;
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

    private static Player playerAt(UUID uuid, boolean steward, UUID worldId, double x, double z) {
        Location location = new Location(proxyWorld(worldId), x, 64.0, z);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
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
                            return steward;
                        }
                        if (node instanceof String perm && perm.startsWith("chunkland.command.land.")) {
                            return true;
                        }
                        return false;
                    }
                    if (name.equals("isPermissionSet")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "Producer";
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "Producer-proxy";
                    }
                    if (name.equals("sendMessage")) {
                        return null;
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

    private static CommandSender consoleSender() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("hasPermission")) {
                        return true;
                    }
                    if (name.equals("isPermissionSet")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "Console";
                    }
                    if (name.equals("sendMessage")) {
                        return null;
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "Console-proxy";
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    private static LandSnapshot playerLand(LandId id, UUID owner, UUID world) {
        LandName name = LandName.of("Home");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(owner), world, Set.of(new ChunkKey(world, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static LandSnapshot serverLand(LandId id, UUID world) {
        LandName name = LandName.of("Spawn");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.server(), world, Set.of(new ChunkKey(world, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static LandCommand commandWithResolver(
            Map<String, LandCommand.Handler> handlers, List<String> keys, ManagementGateResolver resolver) {
        return new LandCommand(handlers, (s, p) -> new ReplySink() {
            public void reply(String k, Map<String, Object> v) {
                keys.add(k);
            }

            public void reply(String k, Map<String, Object> v, java.util.Locale l) {
                keys.add(k);
            }
        }, resolver);
    }

    @Test
    void formalWiringResolvesLandAtPlayerLocation() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(playerLand(id, OWNER, WORLD))));
        ManagementGateResolver resolver = ChunkLandPlugin.buildManagementGateResolver(store);
        Optional<ManagementGateResolver.Request> resolved = resolver.resolve(
                playerAt(OWNER, false, WORLD, 5.0, 5.0), ProtectionActionType.DELETE_LAND,
                new String[]{"delete"});
        assertTrue(resolved.isPresent(), "owner standing on a published land must resolve that land");
        assertEquals(id, resolved.get().landId());
    }

    @Test
    void wildernessAtPlayerLocationRefuses() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(playerLand(id, OWNER, WORLD))));
        ManagementGateResolver resolver = ChunkLandPlugin.buildManagementGateResolver(store);
        Optional<ManagementGateResolver.Request> resolved = resolver.resolve(
                playerAt(OWNER, false, WORLD, 100.0, 100.0), ProtectionActionType.DELETE_LAND,
                new String[]{"delete"});
        assertTrue(resolved.isEmpty(), "wilderness under the player must stay unresolved (fail-closed)");
    }

    @Test
    void crossWorldDoesNotMisresolve() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(playerLand(id, OWNER, WORLD))));
        ManagementGateResolver resolver = ChunkLandPlugin.buildManagementGateResolver(store);
        Optional<ManagementGateResolver.Request> resolved = resolver.resolve(
                playerAt(OWNER, false, UUID.randomUUID(), 5.0, 5.0), ProtectionActionType.DELETE_LAND,
                new String[]{"delete"});
        assertTrue(resolved.isEmpty(), "same chunk coords in another world must not resolve the land");
    }

    @Test
    void consoleSenderRefuses() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(playerLand(id, OWNER, WORLD))));
        ManagementGateResolver resolver = ChunkLandPlugin.buildManagementGateResolver(store);
        Optional<ManagementGateResolver.Request> resolved = resolver.resolve(
                consoleSender(), ProtectionActionType.DELETE_LAND, new String[]{"delete"});
        assertTrue(resolved.isEmpty(), "non-player sender has no location and must fail closed");
    }

    @Test
    void ownerGateAllowsWithoutBypassAndStrangerDenied() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(playerLand(id, OWNER, WORLD))));
        ManagementGateResolver resolver = ChunkLandPlugin.buildManagementGateResolver(store);
        ManagementGateResolver.Request owner = resolver.resolve(
                playerAt(OWNER, false, WORLD, 5.0, 5.0), ProtectionActionType.DELETE_LAND,
                new String[]{"delete"}).orElseThrow(() -> new AssertionError("owner must resolve"));
        assertFalse(owner.adminBypass(), "formal wiring carries no admin bypass in this milestone");
        assertEquals(PermissionState.ALLOW, ManagementPermissionGate.check(
                owner.actor(), owner.landId(), ProtectionActionType.DELETE_LAND,
                owner.snapshot(), owner.adminBypass(), owner.serverLandSteward(),
                owner.provider()).outcome(), "owner guarantee must allow without bypass");
        ManagementGateResolver.Request stranger = resolver.resolve(
                        playerAt(UUID.randomUUID(), false, WORLD, 5.0, 5.0),
                        ProtectionActionType.DELETE_LAND, new String[]{"delete"})
                .orElseThrow(() -> new AssertionError("stranger on the land still resolves the target"));
        assertEquals(PermissionState.DENY, ManagementPermissionGate.check(
                stranger.actor(), stranger.landId(), ProtectionActionType.DELETE_LAND,
                stranger.snapshot(), stranger.adminBypass(), stranger.serverLandSteward(),
                stranger.provider()).outcome(), "stranger must be denied on the same land");
    }

    @Test
    void stewardOnServerLandAllowedWithoutBypass() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(serverLand(id, WORLD))));
        ManagementGateResolver resolver = ChunkLandPlugin.buildManagementGateResolver(store);
        ManagementGateResolver.Request steward = resolver.resolve(
                playerAt(UUID.randomUUID(), true, WORLD, 5.0, 5.0), ProtectionActionType.DELETE_LAND,
                new String[]{"delete"}).orElseThrow(() -> new AssertionError("steward must resolve"));
        assertTrue(steward.serverLandSteward(), "steward node must be visible to the gate");
        assertEquals(PermissionState.ALLOW, ManagementPermissionGate.check(
                steward.actor(), steward.landId(), ProtectionActionType.DELETE_LAND,
                steward.snapshot(), steward.adminBypass(), steward.serverLandSteward(),
                steward.provider()).outcome(), "steward on Server Land must pass without bypass");
        ManagementGateResolver.Request plain = resolver.resolve(
                        playerAt(UUID.randomUUID(), false, WORLD, 5.0, 5.0),
                        ProtectionActionType.DELETE_LAND, new String[]{"delete"})
                .orElseThrow(() -> new AssertionError("plain player on Server Land still resolves"));
        assertEquals(PermissionState.DENY, ManagementPermissionGate.check(
                plain.actor(), plain.landId(), ProtectionActionType.DELETE_LAND,
                plain.snapshot(), plain.adminBypass(), plain.serverLandSteward(),
                plain.provider()).outcome(), "plain player on Server Land must be denied");
    }

    @Test
    void ownerDispatchReachesHandlerAndStrangerDeniedWithoutHandler() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(playerLand(id, OWNER, WORLD))));
        ManagementGateResolver resolver = ChunkLandPlugin.buildManagementGateResolver(store);
        AtomicBoolean ownerCalled = new AtomicBoolean(false);
        List<String> ownerKeys = new ArrayList<>();
        LandCommand ownerCmd = commandWithResolver(
                Map.of("delete", (s, a, sink) -> ownerCalled.set(true)), ownerKeys, resolver);
        assertTrue(ownerCmd.dispatch(playerAt(OWNER, false, WORLD, 5.0, 5.0),
                new String[]{"delete"}, null));
        assertTrue(ownerCalled.get(), "owner on the land must reach the handler");
        assertTrue(ownerKeys.isEmpty(), "allow must not reply denied");
        AtomicBoolean strangerCalled = new AtomicBoolean(false);
        List<String> strangerKeys = new ArrayList<>();
        LandCommand strangerCmd = commandWithResolver(
                Map.of("delete", (s, a, sink) -> strangerCalled.set(true)), strangerKeys, resolver);
        assertTrue(strangerCmd.dispatch(playerAt(UUID.randomUUID(), false, WORLD, 5.0, 5.0),
                new String[]{"delete"}, null));
        assertFalse(strangerCalled.get(), "stranger deny must not reach the handler");
        assertTrue(strangerKeys.contains("command.land.denied"), "stranger deny must reply denied");
    }
}

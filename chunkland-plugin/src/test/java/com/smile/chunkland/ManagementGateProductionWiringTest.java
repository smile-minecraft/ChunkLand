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
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Production wiring for the management gate: the formal {@code /land} entry
 * point carries a real Bukkit-dependent resolver into the shared Bukkit-free
 * gate, denials never reach the handler, and the same gate answers a
 * server-thread Form-style call without Bukkit.
 */
class ManagementGateProductionWiringTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    private static Player proxyPlayer(UUID uuid, boolean steward) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return uuid;
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

    private static LandSnapshot playerLand(LandId id, UUID owner) {
        LandName name = LandName.of("Home");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(owner), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
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
    void productionResolverIsCalledAndFailsClosedWhileTargetUnresolved() {
        AtomicInteger snapshots = new AtomicInteger();
        AtomicInteger targets = new AtomicInteger();
        LandRegistryStore store = new LandRegistryStore();
        ManagementGateResolver resolver = ChunkLandPlugin.buildManagementGateResolver(
                store,
                () -> new SnapshotPermissionContextProvider(null, null),
                (sender, action, args, snapshot) -> {
                    targets.incrementAndGet();
                    return Optional.empty();
                });
        ManagementGateResolver counting = new ManagementGateResolver() {
            @Override
            public Optional<Request> resolve(CommandSender sender, ProtectionActionType action, String[] args) {
                snapshots.incrementAndGet();
                return resolver.resolve(sender, action, args);
            }
        };
        AtomicBoolean called = new AtomicBoolean(false);
        List<String> keys = new ArrayList<>();
        LandCommand cmd = commandWithResolver(
                Map.of("delete", (s, a, sink) -> called.set(true)), keys, counting);
        Player player = proxyPlayer(OWNER, false);
        assertTrue(cmd.dispatch(player, new String[]{"delete"}, null));
        assertFalse(called.get(), "unresolved production target must not reach the handler");
        assertTrue(keys.contains("command.land.denied"), "unresolved production target must reply denied");
        assertTrue(snapshots.get() > 0, "production resolver must be consulted");
        assertTrue(targets.get() > 0, "target resolution must be attempted before denying");
    }

    @Test
    void productionResolverAllowReachesHandlerWhenDownstreamNamesTheLand() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        LandRegistryStore store = new LandRegistryStore();
        store.publish(snapshot);
        ManagementGateResolver resolver = ChunkLandPlugin.buildManagementGateResolver(
                store,
                () -> new SnapshotPermissionContextProvider(null, null),
                (sender, action, args, seen) -> Optional.of(id));
        AtomicBoolean called = new AtomicBoolean(false);
        List<String> keys = new ArrayList<>();
        LandCommand cmd = commandWithResolver(
                Map.of("delete", (s, a, sink) -> called.set(true)), keys, resolver);
        assertTrue(cmd.dispatch(proxyPlayer(OWNER, false), new String[]{"delete"}, null));
        assertTrue(called.get(), "owner with a resolved target must reach the handler");
        assertTrue(keys.isEmpty(), "allow must not reply denied");
    }

    @Test
    void sameBukkitFreeGateServesAFormStyleCall() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        var provider = new SnapshotPermissionContextProvider(null, null);
        // A server-thread Form reuses the identical Bukkit-free inputs the
        // command resolver would supply: no sender, no Bukkit types.
        assertEquals(PermissionState.ALLOW, ManagementPermissionGate.check(
                OWNER, id, ProtectionActionType.DELETE_LAND, snapshot, false, false, provider).outcome());
        assertEquals(PermissionState.DENY, ManagementPermissionGate.check(
                UUID.randomUUID(), id, ProtectionActionType.DELETE_LAND,
                snapshot, false, false, provider).outcome());
    }

    @Test
    void formalPluginWiringCarriesAProductionResolver() throws Exception {
        LandRegistryStore store = new LandRegistryStore();
        ManagementGateResolver resolver = ChunkLandPlugin.buildManagementGateResolver(store);
        assertTrue(resolver instanceof PluginManagementGateResolver,
                "formal wiring must use the production Bukkit adapter, got " + resolver.getClass());
        Method resolve = resolver.getClass().getMethod(
                "resolve", CommandSender.class, ProtectionActionType.class, String[].class);
        assertTrue(resolve.getReturnType().getName().contains("Optional"),
                "production resolver must supply gate inputs, never a bare allow verdict");
    }
}

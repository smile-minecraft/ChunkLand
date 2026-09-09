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
 * Verifies that management subcommands share the single domain enforcement
 * point: the dispatcher always runs {@link ManagementPermissionGate#check}
 * itself on resolver-supplied inputs, a domain denial refuses the operation
 * even when the Bukkit command node passes, and the handler is never invoked
 * on the deny path.
 */
class LandCommandManagementGateTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    private static CommandSender senderWithAllNodes(CopyOnWriteArrayList<String> out) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class[]{CommandSender.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("sendMessage")) {
                        return null;
                    }
                    if (n.equals("hasPermission")) {
                        return true;
                    }
                    if (n.equals("isPermissionSet")) {
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

    private static PermissionContextProvider emptyProvider() {
        return new SnapshotPermissionContextProvider(null, null);
    }

    private static ManagementGateResolver fixedResolver(ManagementGateResolver.Request request) {
        return (sender, action, args) -> Optional.of(request);
    }

    private static ManagementGateResolver.Request ownerRequest(ProtectionActionType action) {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        return new ManagementGateResolver.Request(OWNER, id, snapshot, false, false, emptyProvider());
    }

    private static ManagementGateResolver.Request strangerRequest(ProtectionActionType action) {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        return new ManagementGateResolver.Request(UUID.randomUUID(), id, snapshot, false, false, emptyProvider());
    }

    private static LandCommand commandWithResolver(
            Map<String, LandCommand.Handler> handlers,
            List<String> keys,
            ManagementGateResolver resolver) {
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
    void managementSubcommandsMapToDomainActions() {
        assertEquals(Optional.of(ProtectionActionType.MANAGE_MEMBER), LandCommand.managementActionFor("trust"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_MEMBER), LandCommand.managementActionFor("TRUST"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_MEMBER), LandCommand.managementActionFor("untrust"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_MEMBER), LandCommand.managementActionFor("ban"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_MEMBER), LandCommand.managementActionFor("unban"));
        assertEquals(Optional.of(ProtectionActionType.EXPAND_LAND), LandCommand.managementActionFor("expand"));
        assertEquals(Optional.of(ProtectionActionType.DELETE_LAND), LandCommand.managementActionFor("delete"));
        assertEquals(Optional.empty(), LandCommand.managementActionFor("wand"));
        assertEquals(Optional.empty(), LandCommand.managementActionFor("claim"));
        assertEquals(Optional.empty(), LandCommand.managementActionFor(null));
    }

    @Test
    void noArbitraryAllowPredicateSeam() {
        for (var ctor : LandCommand.class.getDeclaredConstructors()) {
            for (var param : ctor.getParameterTypes()) {
                assertFalse(
                        param.getName().contains("BiPredicate"),
                        "LandCommand must not accept an arbitrary allow predicate");
            }
        }
        for (var field : LandCommand.class.getDeclaredFields()) {
            assertFalse(
                    field.getType().getName().contains("BiPredicate"),
                    "LandCommand must not hold an arbitrary allow predicate");
        }
    }

    @Test
    void domainDenyRefusesEvenWhenBukkitNodePasses() {
        for (String sub : List.of("trust", "untrust", "ban", "unban", "expand", "delete")) {
            AtomicBoolean called = new AtomicBoolean(false);
            Map<String, LandCommand.Handler> handlers =
                    Map.of(sub, (s, a, sink) -> called.set(true));
            List<String> keys = new ArrayList<>();
            ProtectionActionType action = LandCommand.managementActionFor(sub).orElseThrow();
            // Stranger on someone else's land: the shared gate denies.
            LandCommand cmd = commandWithResolver(handlers, keys, fixedResolver(strangerRequest(action)));
            CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
            CommandSender sender = senderWithAllNodes(out);
            assertTrue(cmd.dispatch(sender, new String[]{sub}, null), sub);
            assertFalse(called.get(), sub + ": domain deny must not invoke handler");
            assertTrue(keys.contains("command.land.denied"), sub + ": domain deny must reply denied");
        }
    }

    @Test
    void domainAllowReachesHandler() {
        AtomicBoolean called = new AtomicBoolean(false);
        Map<String, LandCommand.Handler> handlers =
                Map.of("delete", (s, a, sink) -> called.set(true));
        List<String> keys = new ArrayList<>();
        ManagementGateResolver.Request request = ownerRequest(ProtectionActionType.DELETE_LAND);
        // Sanity: the shared gate really allows these inputs (owner, bypass off).
        assertEquals(PermissionState.ALLOW, ManagementPermissionGate.check(
                request.actor(), request.landId(), ProtectionActionType.DELETE_LAND,
                request.snapshot(), request.adminBypass(), request.serverLandSteward(),
                request.provider()).outcome());
        LandCommand cmd = commandWithResolver(handlers, keys, fixedResolver(request));
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        assertTrue(cmd.dispatch(senderWithAllNodes(out), new String[]{"delete"}, null));
        assertTrue(called.get(), "domain allow must invoke handler");
        assertTrue(keys.isEmpty(), "domain allow must not reply denied");
    }

    @Test
    void nonManagementSubcommandDoesNotConsultDomainGate() {
        AtomicBoolean called = new AtomicBoolean(false);
        Map<String, LandCommand.Handler> handlers =
                Map.of("wand", (s, a, sink) -> called.set(true));
        List<String> keys = new ArrayList<>();
        ManagementGateResolver exploding = (sender, action, args) -> {
            throw new AssertionError("domain gate must not be consulted for non-management subcommand");
        };
        LandCommand cmd = commandWithResolver(handlers, keys, exploding);
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        assertTrue(cmd.dispatch(senderWithAllNodes(out), new String[]{"wand"}, null));
        assertTrue(called.get(), "non-management subcommand must still reach its handler");
    }

    @Test
    void missingResolverFailsClosedWithoutGate() {
        AtomicBoolean called = new AtomicBoolean(false);
        Map<String, LandCommand.Handler> handlers =
                Map.of("delete", (s, a, sink) -> called.set(true));
        List<String> keys = new ArrayList<>();
        LandCommand cmd = new LandCommand(handlers, (s, p) -> new ReplySink() {
            public void reply(String k, Map<String, Object> v) {
                keys.add(k);
            }

            public void reply(String k, Map<String, Object> v, java.util.Locale l) {
                keys.add(k);
            }
        });
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        assertTrue(cmd.dispatch(senderWithAllNodes(out), new String[]{"delete"}, null));
        assertFalse(called.get(), "management subcommand without a resolver must fail closed");
        assertTrue(keys.contains("command.land.denied"), "fail-closed deny must reply denied");
    }

    @Test
    void unresolvedAndThrowingResolversFailClosed() {
        for (ManagementGateResolver resolver : List.of(
                (ManagementGateResolver) (sender, action, args) -> Optional.empty(),
                (ManagementGateResolver) (sender, action, args) -> null,
                (ManagementGateResolver) (sender, action, args) -> {
                    throw new IllegalStateException("boom");
                })) {
            AtomicBoolean called = new AtomicBoolean(false);
            Map<String, LandCommand.Handler> handlers =
                    Map.of("delete", (s, a, sink) -> called.set(true));
            List<String> keys = new ArrayList<>();
            LandCommand cmd = commandWithResolver(handlers, keys, resolver);
            CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
            assertTrue(cmd.dispatch(senderWithAllNodes(out), new String[]{"delete"}, null));
            assertFalse(called.get(), "unresolved resolver must fail closed");
            assertTrue(keys.contains("command.land.denied"), "unresolved resolver must reply denied");
        }
    }

    @Test
    void unknownLandInputFailsClosed() {
        AtomicBoolean called = new AtomicBoolean(false);
        Map<String, LandCommand.Handler> handlers =
                Map.of("delete", (s, a, sink) -> called.set(true));
        List<String> keys = new ArrayList<>();
        // Unknown land: the shared gate denies even for the would-be owner.
        LandId missing = new LandId(UUID.randomUUID());
        ManagementGateResolver.Request request = new ManagementGateResolver.Request(
                OWNER, missing, LandRegistry.empty(), false, false, emptyProvider());
        LandCommand cmd = commandWithResolver(handlers, keys, fixedResolver(request));
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        assertTrue(cmd.dispatch(senderWithAllNodes(out), new String[]{"delete"}, null));
        assertFalse(called.get(), "unknown land must fail closed");
        assertTrue(keys.contains("command.land.denied"), "unknown land must reply denied");
    }
}

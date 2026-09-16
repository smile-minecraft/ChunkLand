package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.ProtectionActionType;
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
 * Dispatcher-level Red contract for {@code /land inspect}: the subcommand
 * must be registered with its own permission node and its Bukkit-permission
 * and domain-gate failures must stay fail-closed on the generic
 * {@code command.land.inspect.denied} (empty vars, never the shared denied
 * key), mirroring the explain privacy posture.
 */
class LandCommandInspectDispatchTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    private record Captured(String key, Map<String, Object> vars) {
    }

    private static final class CaptureSink implements ReplySink {
        final List<Captured> replies = new ArrayList<>();

        @Override
        public void reply(String key, Map<String, Object> vars) {
            replies.add(new Captured(key, Map.copyOf(vars)));
        }

        @Override
        public void reply(String key, Map<String, Object> vars, java.util.Locale locale) {
            replies.add(new Captured(key, Map.copyOf(vars)));
        }
    }

    private static CommandSender senderWithPerms(Map<String, Boolean> perms) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("hasPermission")) {
                        Object node = args == null || args.length == 0 ? null : args[0];
                        if (node instanceof String perm) {
                            return perms.getOrDefault(perm, false);
                        }
                        return false;
                    }
                    if (name.equals("isPermissionSet")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "Sender";
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
                    return null;
                });
    }

    private static PermissionContextProvider emptyProvider() {
        return new SnapshotPermissionContextProvider(null, null);
    }

    private static LandSnapshot playerLand(LandId id, UUID owner) {
        LandName name = LandName.of("Home");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(owner), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static ManagementGateResolver.Request strangerRequest() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        return new ManagementGateResolver.Request(
                UUID.randomUUID(), id, snapshot, false, false, emptyProvider());
    }

    private static ManagementGateResolver.Request ownerRequest() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        return new ManagementGateResolver.Request(
                OWNER, id, snapshot, false, false, emptyProvider());
    }

    private static LandCommand commandWith(
            Map<String, LandCommand.Handler> handlers,
            CaptureSink sink,
            ManagementGateResolver resolver,
            AtomicBoolean called) {
        Map<String, LandCommand.Handler> wrapped = new java.util.HashMap<>();
        for (Map.Entry<String, LandCommand.Handler> e : handlers.entrySet()) {
            LandCommand.Handler inner = e.getValue();
            wrapped.put(e.getKey(), (s, a, reply) -> {
                called.set(true);
                inner.handle(s, a, reply);
            });
        }
        return new LandCommand(wrapped, (s, p) -> sink, resolver);
    }

    private static void assertGenericInspectDenied(CaptureSink sink) {
        assertEquals(1, sink.replies.size(), "inspect failure must reply exactly once");
        assertEquals("command.land.inspect.denied", sink.replies.get(0).key());
        assertTrue(sink.replies.get(0).vars().isEmpty(),
                "inspect denial must carry no vars so land existence is not probed");
        String joined = sink.replies.get(0).key() + sink.replies.get(0).vars().toString();
        assertFalse(joined.contains("unknown_land"));
        assertFalse(joined.contains("MANAGE_PERMISSION"));
        assertFalse(joined.contains("chunkland.command"));
    }

    @Test
    void inspectIsRegisteredWithPermissionAndGate() {
        assertTrue(LandCommand.SUBCOMMANDS.contains("inspect"),
                "inspect must be a registered /land subcommand");
        assertEquals("chunkland.command.land.inspect",
                LandPermissions.forSubcommand("inspect"));
        assertTrue(LandPermissions.ALL_ORDERED.contains(LandPermissions.INSPECT));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_PERMISSION),
                com.smile.chunkland.protection.ManagementPermissionGate
                        .actionForSubcommand("inspect"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_PERMISSION),
                LandCommand.managementActionFor("inspect"));
    }

    @Test
    void inspectWithoutCommandPermissionIsGenericDenied() {
        CaptureSink sink = new CaptureSink();
        AtomicBoolean called = new AtomicBoolean(false);
        LandCommand cmd = commandWith(
                Map.of("inspect", (s, a, reply) -> reply.reply("must.not.happen", Map.of())),
                sink,
                (sender, action, args) -> {
                    throw new AssertionError("gate must not run when the Bukkit node already denied");
                },
                called);
        CommandSender sender = senderWithPerms(Map.of());
        assertTrue(cmd.dispatch(sender, new String[] {"inspect"}, null));
        assertFalse(called.get(), "denied inspect must not invoke handler");
        assertGenericInspectDenied(sink);
    }

    @Test
    void inspectGateDenyIsGenericDenied() {
        CaptureSink sink = new CaptureSink();
        AtomicBoolean called = new AtomicBoolean(false);
        LandCommand cmd = commandWith(
                Map.of("inspect", (s, a, reply) -> reply.reply("must.not.happen", Map.of())),
                sink,
                (sender, action, args) -> Optional.of(strangerRequest()),
                called);
        CommandSender sender = senderWithPerms(
                Map.of(LandPermissions.forSubcommand("inspect"), true));
        assertTrue(cmd.dispatch(sender, new String[] {"inspect"}, null));
        assertFalse(called.get(), "gate deny must not invoke handler");
        assertGenericInspectDenied(sink);
    }

    @Test
    void inspectUnknownLandAndThrowingResolverAreGenericDenied() {
        LandId missing = new LandId(UUID.randomUUID());
        ManagementGateResolver.Request unknown = new ManagementGateResolver.Request(
                OWNER, missing, LandRegistry.empty(), false, false, emptyProvider());
        for (ManagementGateResolver resolver : List.of(
                (ManagementGateResolver) (sender, action, args) -> Optional.of(unknown),
                (ManagementGateResolver) (sender, action, args) -> Optional.empty(),
                (ManagementGateResolver) (sender, action, args) -> null,
                (ManagementGateResolver) (sender, action, args) -> {
                    throw new IllegalStateException("boom");
                })) {
            CaptureSink sink = new CaptureSink();
            AtomicBoolean called = new AtomicBoolean(false);
            LandCommand cmd = commandWith(
                    Map.of("inspect", (s, a, reply) -> reply.reply("must.not.happen", Map.of())),
                    sink, resolver, called);
            CommandSender sender = senderWithPerms(
                    Map.of(LandPermissions.forSubcommand("inspect"), true));
            assertTrue(cmd.dispatch(sender, new String[] {"inspect"}, null));
            assertFalse(called.get(), "unresolved inspect must fail closed");
            assertGenericInspectDenied(sink);
        }
    }

    @Test
    void authorizedInspectReachesHandler() {
        CaptureSink sink = new CaptureSink();
        AtomicBoolean called = new AtomicBoolean(false);
        LandCommand cmd = commandWith(
                Map.of("inspect",
                        (s, a, reply) -> reply.reply("command.land.inspect.result",
                                Map.of("landId", "x"))),
                sink,
                (sender, action, args) -> {
                    assertEquals(ProtectionActionType.MANAGE_PERMISSION, action);
                    return Optional.of(ownerRequest());
                },
                called);
        CommandSender sender = senderWithPerms(
                Map.of(LandPermissions.forSubcommand("inspect"), true));
        assertTrue(cmd.dispatch(sender, new String[] {"inspect"}, null));
        assertTrue(called.get(), "authorized inspect must reach handler exactly once");
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.inspect.result", sink.replies.get(0).key());
    }
}

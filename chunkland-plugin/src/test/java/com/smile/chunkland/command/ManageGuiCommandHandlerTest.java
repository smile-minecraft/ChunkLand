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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Red contract for the player-reachable management GUI entry:
 * {@code /land manage} routes through the Bukkit node, resolves the
 * current-location land, re-checks the shared domain gate for
 * {@code MANAGE_PERMISSION} inside the handler, and opens the GUI only on
 * ALLOW through the caller-owned opener seam. Consoles, unresolvable
 * targets, gate denials and opener failures all fail closed on generic
 * replies without invoking the opener.
 */
class ManageGuiCommandHandlerTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    private record Opened(Player player, LandId landId) {
    }

    private static Player player(UUID id, boolean node) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return id;
                    }
                    if (name.equals("hasPermission")) {
                        return node;
                    }
                    if (name.equals("isPermissionSet")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "GuiPlayer";
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
                        return "GuiPlayer-proxy";
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

    private static CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
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
                    return null;
                });
    }

    private static LandSnapshot playerLand(LandId id, UUID owner) {
        LandName name = LandName.of("Home");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(owner), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static ManagementGateResolver.Request ownerRequest() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        PermissionContextProvider provider = new SnapshotPermissionContextProvider(null, null);
        return new ManagementGateResolver.Request(OWNER, id, snapshot, false, false, provider);
    }

    private static ManagementGateResolver.Request strangerRequest() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        PermissionContextProvider provider = new SnapshotPermissionContextProvider(null, null);
        return new ManagementGateResolver.Request(
                UUID.randomUUID(), id, snapshot, false, false, provider);
    }

    private static List<String> run(ManagementGateResolver resolver,
            ManageGuiCommandHandler.GuiOpener opener,
            CommandSender sender, String[] args) {
        List<String> keys = new ArrayList<>();
        ReplySink sink = new ReplySink() {
            @Override
            public void reply(String key, Map<String, Object> vars) {
                keys.add(key);
            }

            @Override
            public void reply(String key, Map<String, Object> vars, java.util.Locale locale) {
                keys.add(key);
            }
        };
        new ManageGuiCommandHandler(resolver, opener).handle(sender, args, sink);
        return keys;
    }

    private static ManagementGateResolver fixed(ManagementGateResolver.Request request) {
        return (sender, action, args) -> Optional.ofNullable(request);
    }

    @Test
    void manageIsRegisteredWithItsOwnBukkitNode() {
        assertTrue(LandCommand.SUBCOMMANDS.contains("manage"),
                "SUBCOMMANDS must route /land manage");
        assertEquals(LandPermissions.MANAGE, LandPermissions.forSubcommand("manage"));
        assertTrue(LandPermissions.ALL_ORDERED.contains(LandPermissions.MANAGE));
    }

    @Test
    void ownerOpensGuiThroughSeam() {
        AtomicReference<Opened> opened = new AtomicReference<>();
        ManagementGateResolver.Request request = ownerRequest();
        Player actor = player(OWNER, true);
        List<String> keys = run(fixed(request),
                (player, landId) -> opened.set(new Opened(player, landId)),
                actor, new String[] {"manage"});

        assertTrue(opened.get() != null, "owner ALLOW must reach the opener seam");
        assertEquals(request.landId(), opened.get().landId());
        assertTrue(keys.isEmpty(), "successful open stays silent, got: " + keys);
    }

    @Test
    void consoleSenderFailsClosed() {
        AtomicReference<Opened> opened = new AtomicReference<>();
        List<String> keys = run(fixed(ownerRequest()),
                (player, landId) -> opened.set(new Opened(player, landId)),
                console(), new String[] {"manage"});

        assertTrue(opened.get() == null, "console must never reach the opener");
        assertTrue(keys.contains("command.land.manage.console"));
    }

    @Test
    void strangerGateDenyNeverOpens() {
        AtomicReference<Opened> opened = new AtomicReference<>();
        List<String> keys = run(fixed(strangerRequest()),
                (player, landId) -> opened.set(new Opened(player, landId)),
                player(UUID.randomUUID(), true), new String[] {"manage"});

        assertTrue(opened.get() == null, "gate DENY must not reach the opener");
        assertTrue(keys.contains("command.land.manage.denied"));
    }

    @Test
    void unresolvableTargetFailsClosed() {
        AtomicReference<Opened> opened = new AtomicReference<>();
        List<String> keys = run((sender, action, args) -> Optional.empty(),
                (player, landId) -> opened.set(new Opened(player, landId)),
                player(UUID.randomUUID(), true), new String[] {"manage"});

        assertTrue(opened.get() == null);
        assertTrue(keys.contains("command.land.manage.denied"));
    }

    @Test
    void missingSeamsAndOpenerFailureFailClosed() {
        List<String> keys = new ArrayList<>();
        ReplySink sink = new ReplySink() {
            @Override
            public void reply(String key, Map<String, Object> vars) {
                keys.add(key);
            }

            @Override
            public void reply(String key, Map<String, Object> vars, java.util.Locale locale) {
                keys.add(key);
            }
        };
        Player actor = player(OWNER, true);

        new ManageGuiCommandHandler(null, (player, landId) -> {
        }).handle(actor, new String[] {"manage"}, sink);
        new ManageGuiCommandHandler(
                (sender, action, args) -> Optional.of(ownerRequest()), null)
                .handle(actor, new String[] {"manage"}, sink);
        assertEquals(List.of("command.land.manage.denied", "command.land.manage.denied"), keys);

        AtomicReference<Opened> opened = new AtomicReference<>();
        List<String> boom = run(fixed(ownerRequest()),
                (player, landId) -> {
                    throw new RuntimeException("opener boom");
                },
                actor, new String[] {"manage"});
        assertTrue(opened.get() == null, "throwing opener must stay contained");
        assertTrue(boom.contains("command.land.manage.denied"));
    }

    @Test
    void dispatcherRoutesManageBehindItsBukkitNode() {
        AtomicReference<Opened> opened = new AtomicReference<>();
        ManagementGateResolver.Request request = ownerRequest();
        Map<String, LandCommand.Handler> handlers = new HashMap<>();
        handlers.put("manage", new ManageGuiCommandHandler(
                (sender, action, args) -> Optional.of(request),
                (player, landId) -> opened.set(new Opened(player, landId))));
        List<String> keys = new CopyOnWriteArrayList<>();
        LandCommand cmd = new LandCommand(handlers, (sender, pipeline) -> new ReplySink() {
            @Override
            public void reply(String key, Map<String, Object> vars) {
                keys.add(key);
            }

            @Override
            public void reply(String key, Map<String, Object> vars, java.util.Locale locale) {
                keys.add(key);
            }
        });

        assertTrue(cmd.dispatch(player(OWNER, true), new String[] {"manage"}, null));
        assertTrue(opened.get() != null, "dispatch must reach the manage handler on node ALLOW");

        opened.set(null);
        keys.clear();
        assertTrue(cmd.dispatch(player(OWNER, false), new String[] {"manage"}, null));
        assertTrue(opened.get() == null, "node deny must not reach the handler");
        assertTrue(keys.contains("command.land.denied"));
    }

    @Test
    void noEveryoneEntryInManageReplies() {
        assertFalse("command.land.manage.console".toUpperCase(java.util.Locale.ROOT)
                .contains("EVERYONE"));
        assertFalse("command.land.manage.denied".toUpperCase(java.util.Locale.ROOT)
                .contains("EVERYONE"));
    }
}

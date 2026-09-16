package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * Red contract for the {@code /land admin} branch routing.
 *
 * <p>{@code /land admin orphan ...} is gated by the independent
 * {@code chunkland.admin.orphan} node at the dispatcher, while every other
 * {@code /land admin ...} verb keeps the ledger node. The router then hands
 * each branch to its own handler; a missing branch fails closed without
 * touching the other one.
 */
class LandCommandOrphanBranchTest {

    private static CommandSender senderWithPerms(Map<String, Boolean> perms,
            CopyOnWriteArrayList<String> out) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        String node = args == null || args.length == 0 ? null : String.valueOf(args[0]);
                        return perms.getOrDefault(node, false);
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
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        return null;
    }

    private static ReplySink recordingSink(List<String> keys) {
        return new ReplySink() {
            @Override public void reply(String messageKey, Map<String, Object> vars) {
                keys.add(messageKey + "|" + vars.getOrDefault("permission", ""));
            }

            @Override public void reply(String messageKey, Map<String, Object> vars, Locale locale) {
                reply(messageKey, vars);
            }
        };
    }

    @Test
    void orphanBranchRequiresTheOrphanNodeRatherThanTheLedgerNode() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        LandCommand.Handler admin = (sender, args, sink) -> invoked.set(true);

        // Ledger-only sender: orphan branch stays denied, handler never runs.
        List<String> keys = new ArrayList<>();
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CommandSender ledgerOnly = senderWithPerms(
                Map.of(LandPermissions.ADMIN, true), out);
        LandCommand deny = new LandCommand(Map.of("admin", admin),
                (ignored, pipeline) -> recordingSink(keys));
        assertTrue(deny.dispatch(ledgerOnly,
                new String[] {"admin", "orphan", "list"}, null));
        assertTrue(!invoked.get());
        assertTrue(keys.contains("command.land.denied|" + LandPermissions.ORPHAN), keys.toString());

        // Orphan holder without the ledger node: the branch runs.
        invoked.set(false);
        keys.clear();
        CommandSender orphanOnly = senderWithPerms(
                Map.of(LandPermissions.ORPHAN, true), out);
        LandCommand allow = new LandCommand(Map.of("admin", admin),
                (ignored, pipeline) -> recordingSink(keys));
        assertTrue(allow.dispatch(orphanOnly,
                new String[] {"ADMIN", "ORPHAN", "list"}, null));
        assertTrue(invoked.get(), "case-insensitive orphan branch must reach the handler");
    }

    @Test
    void ledgerBranchKeepsTheLedgerNode() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        LandCommand.Handler admin = (sender, args, sink) -> invoked.set(true);
        List<String> keys = new ArrayList<>();
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();

        CommandSender orphanOnly = senderWithPerms(
                Map.of(LandPermissions.ORPHAN, true), out);
        LandCommand deny = new LandCommand(Map.of("admin", admin),
                (ignored, pipeline) -> recordingSink(keys));
        assertTrue(deny.dispatch(orphanOnly,
                new String[] {"admin", "ledger", "list"}, null));
        assertTrue(!invoked.get(), "orphan node must never grant the ledger branch");
        assertTrue(keys.contains("command.land.denied|" + LandPermissions.ADMIN), keys.toString());

        invoked.set(false);
        keys.clear();
        CommandSender ledgerOnly = senderWithPerms(
                Map.of(LandPermissions.ADMIN, true), out);
        LandCommand allow = new LandCommand(Map.of("admin", admin),
                (ignored, pipeline) -> recordingSink(keys));
        assertTrue(allow.dispatch(ledgerOnly,
                new String[] {"admin", "ledger", "list"}, null));
        assertTrue(invoked.get());
    }

    @Test
    void routerHandsEachBranchToItsOwnHandler() {
        AtomicBoolean ledgerHit = new AtomicBoolean(false);
        AtomicBoolean orphanHit = new AtomicBoolean(false);
        LandCommand.Handler ledger = (sender, args, sink) -> ledgerHit.set(true);
        LandCommand.Handler orphan = (sender, args, sink) -> orphanHit.set(true);
        AdminCommandRouter router = new AdminCommandRouter(ledger, orphan);
        ReplySink sink = recordingSink(new ArrayList<>());
        CommandSender sender = senderWithPerms(Map.of(), new CopyOnWriteArrayList<>());

        router.handle(sender, new String[] {"admin", "orphan", "list"}, sink);
        assertTrue(orphanHit.get());
        assertTrue(!ledgerHit.get());

        orphanHit.set(false);
        router.handle(sender, new String[] {"admin", "ledger", "list"}, sink);
        assertTrue(ledgerHit.get());
        assertTrue(!orphanHit.get());

        ledgerHit.set(false);
        router.handle(sender, new String[] {"admin", "LEDGER", "show", "x"}, sink);
        assertTrue(ledgerHit.get(), "non-orphan admin verbs stay on the ledger handler");
    }

    @Test
    void routerFailsClosedWhenABranchIsMissing() {
        List<String> keys = new ArrayList<>();
        ReplySink sink = recordingSink(keys);
        CommandSender sender = senderWithPerms(Map.of(), new CopyOnWriteArrayList<>());

        AtomicBoolean ledgerHit = new AtomicBoolean(false);
        new AdminCommandRouter(
                (s, a, k) -> ledgerHit.set(true), null)
                .handle(sender, new String[] {"admin", "orphan", "list"}, sink);
        assertTrue(!ledgerHit.get());
        assertTrue(keys.stream().anyMatch(k -> k.startsWith("command.land.admin.orphan.failed")),
                keys.toString());

        keys.clear();
        AtomicBoolean orphanHit = new AtomicBoolean(false);
        new AdminCommandRouter(null,
                (s, a, k) -> orphanHit.set(true))
                .handle(sender, new String[] {"admin", "ledger", "list"}, sink);
        assertTrue(!orphanHit.get());
        assertTrue(keys.stream().anyMatch(k -> k.startsWith("command.land.admin.ledger.failed")),
                keys.toString());
    }
}

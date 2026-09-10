package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * Rename dispatch contract: the Bukkit command node gates the attempt, rename
 * owns no management action (its owner/steward rule lives in the rename flow
 * itself), and the dispatcher never substitutes a not-yet stub once a real
 * handler is wired.
 */
class RenameCommandWiringTest {

    private static final class CapturingSink implements ReplySink {
        final List<String> keys = new ArrayList<>();
        final Map<String, Map<String, Object>> vars = new HashMap<>();

        @Override
        public void reply(String messageKey, Map<String, Object> vs) {
            keys.add(messageKey);
            vars.put(messageKey, Map.copyOf(vs));
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vs, Locale localeOverride) {
            reply(messageKey, vs);
        }
    }

    private static CommandSender senderWithoutRenameNode() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return !"chunkland.command.land.rename".equals(args[0]);
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }

    @Test
    void renameOwnsNoManagementAction() {
        assertTrue(LandCommand.managementActionFor("rename").isEmpty(),
                "rename authorisation lives in the rename flow, not the shared management gate");
        assertEquals("chunkland.command.land.rename", LandPermissions.forSubcommand("rename"));
    }

    @Test
    void dispatcherDeniesWithoutCommandNodeAndNeverInvokesHandler() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        Map<String, LandCommand.Handler> handlers = Map.of(
                "rename", (sender, args, sink) -> invoked.set(true));
        CapturingSink sink = new CapturingSink();
        LandCommand command = new LandCommand(handlers, (sender, pipeline) -> sink);

        boolean recognised = command.dispatch(
                senderWithoutRenameNode(), new String[] {"rename", "Garden"}, null);

        assertTrue(recognised);
        assertEquals(List.of("command.land.denied"), sink.keys);
        assertEquals("chunkland.command.land.rename",
                sink.vars.get("command.land.denied").get("permission"));
        assertTrue(!invoked.get(), "a denied sender must never reach the rename handler");
    }

    @Test
    void oldStubStillReportsNotYetUntilProductionWiringReplacesIt() {
        assertEquals("command.land.not_yet",
                stubReplyFor("rename"));
    }

    private static String stubReplyFor(String subcommand) {
        List<String> keys = new ArrayList<>();
        LandCommand.Handler stub = LandCommand.defaultStubHandlers().get(subcommand);
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
        stub.handle(sender, new String[] {subcommand}, new ReplySink() {
            @Override
            public void reply(String messageKey, Map<String, Object> vs) {
                keys.add(messageKey);
            }

            @Override
            public void reply(String messageKey, Map<String, Object> vs, Locale localeOverride) {
                keys.add(messageKey);
            }
        });
        assertEquals(1, keys.size());
        return keys.get(0);
    }
}

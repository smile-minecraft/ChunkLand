package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Land default handler parses {@code /land default <action> <state>},
 * persists ALLOW/DENY, deletes the row on INHERIT, and fails closed on every
 * unresolvable input with zero side effects.
 */
class LandDefaultCommandHandlerTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());

    private record Call(UUID actor, LandId land, ProtectionActionType action, PermissionState state) {
    }

    private static final class FakeMutation implements LandDefaultCommandHandler.DefaultMutation {
        final CopyOnWriteArrayList<Call> calls = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        @Override
        public CompletionStage<Void> apply(UUID actor, LandId landId,
                ProtectionActionType action, PermissionState state) {
            calls.add(new Call(actor, landId, action, state));
            if (fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("injected"));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

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

    private static Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "hasPermission" -> true;
                        case "getName" -> "Actor";
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "Player-proxy";
                        default -> method.getReturnType() == boolean.class ? false : null;
                    };
                });
    }

    private static CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return true;
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }

    private LandDefaultCommandHandler handler(FakeMutation mutation, boolean landKnown) {
        return new LandDefaultCommandHandler(
                sender -> landKnown ? Optional.of(LAND) : Optional.empty(),
                mutation);
    }

    @Test
    void allowDenyAndInheritPersistThroughOneMutationEach() {
        assertAllowDenyInherit(PermissionState.ALLOW, "ALLOW");
        assertAllowDenyInherit(PermissionState.DENY, "deny");
        assertAllowDenyInherit(PermissionState.INHERIT, "Inherit");
    }

    private void assertAllowDenyInherit(PermissionState state, String raw) {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        handler(mutation, true).handle(player(ACTOR),
                new String[] {"default", "block_break", raw}, sink);
        assertEquals(List.of("command.land.default.success"), sink.keys);
        assertEquals(1, mutation.calls.size());
        assertEquals(new Call(ACTOR, LAND, ProtectionActionType.BLOCK_BREAK, state),
                mutation.calls.get(0));
    }

    @Test
    void consoleUsageUnknownActionUnknownLandAndFailureFailClosed() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        handler(mutation, true).handle(console(),
                new String[] {"default", "BLOCK_BREAK", "ALLOW"}, sink);
        assertEquals(List.of("command.land.default.console"), sink.keys);
        assertTrue(mutation.calls.isEmpty());

        sink = new CapturingSink();
        handler(mutation, true).handle(player(ACTOR), new String[] {"default"}, sink);
        assertEquals(List.of("command.land.default.usage"), sink.keys);
        sink = new CapturingSink();
        handler(mutation, true).handle(player(ACTOR),
                new String[] {"default", "BLOCK_BREAK", "SOMETIMES"}, sink);
        assertEquals(List.of("command.land.default.usage"), sink.keys);
        assertTrue(mutation.calls.isEmpty());

        for (String bad : List.of("MANAGE_MEMBER", "MANAGE_PERMISSION", "FIRE_SPREAD",
                "PVP", "BOGUS", "EVERYONE", "*")) {
            sink = new CapturingSink();
            handler(mutation, true).handle(player(ACTOR),
                    new String[] {"default", bad, "ALLOW"}, sink);
            assertEquals(List.of("command.land.default.failed"), sink.keys, bad + " must fail closed");
            assertEquals("default.unknown_action",
                    sink.vars.get("command.land.default.failed").get("reason"));
            assertTrue(mutation.calls.isEmpty());
        }

        sink = new CapturingSink();
        handler(mutation, false).handle(player(ACTOR),
                new String[] {"default", "BLOCK_BREAK", "ALLOW"}, sink);
        assertEquals(List.of("command.land.default.failed"), sink.keys);
        assertEquals("default.unknown_land",
                sink.vars.get("command.land.default.failed").get("reason"));
        assertTrue(mutation.calls.isEmpty());

        FakeMutation failing = new FakeMutation();
        failing.fail = true;
        sink = new CapturingSink();
        handler(failing, true).handle(player(ACTOR),
                new String[] {"default", "ENTRY", "DENY"}, sink);
        assertEquals(List.of("command.land.default.failed"), sink.keys);
        assertEquals("default.failed", sink.vars.get("command.land.default.failed").get("reason"));
    }
}

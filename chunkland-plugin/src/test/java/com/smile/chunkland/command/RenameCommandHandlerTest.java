package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.rename.LandRenameResult;
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
 * Rename handler: console, missing/illegal names and wilderness fail closed
 * without touching the mutation; outcomes reply exactly once with the rename
 * keys, and the steward flag travels through to the mutation.
 */
class RenameCommandHandlerTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());

    private record Call(UUID actor, LandId land, String name, boolean steward) {
    }

    private static final class FakeMutation implements RenameCommandHandler.RenameMutation {
        final CopyOnWriteArrayList<Call> calls = new CopyOnWriteArrayList<>();
        volatile LandRenameResult result =
                LandRenameResult.success(LAND, "Home", "Garden");
        volatile boolean fail;
        volatile boolean throwSync;
        volatile boolean returnNull;

        @Override
        public CompletionStage<LandRenameResult> apply(
                UUID actor, LandId landId, String newName, boolean serverLandSteward) {
            calls.add(new Call(actor, landId, newName, serverLandSteward));
            if (throwSync) {
                throw new IllegalStateException("injected");
            }
            if (returnNull) {
                return null;
            }
            if (fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("injected"));
            }
            return CompletableFuture.completedFuture(result);
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

        String onlyKey() {
            assertEquals(1, keys.size(), "expected exactly one reply, got " + keys);
            return keys.get(0);
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

    private static RenameCommandHandler handler(RenameCommandHandler.LandResolver lands,
            FakeMutation mutation) {
        return new RenameCommandHandler(lands, mutation, sender -> false);
    }

    @Test
    void consoleRepliesConsoleWithoutSideEffect() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();

        handler(sender -> Optional.of(LAND), mutation)
                .handle(console(), new String[] {"rename", "Garden"}, sink);

        assertEquals("command.land.rename.console", sink.onlyKey());
        assertTrue(mutation.calls.isEmpty());
    }

    @Test
    void missingNameRepliesUsageWithoutSideEffect() {
        FakeMutation mutation = new FakeMutation();
        for (String[] args : new String[][] {
                {"rename"}, {"rename", "   "}, {"rename", ""}, null}) {
            CapturingSink sink = new CapturingSink();
            handler(sender -> Optional.of(LAND), mutation).handle(player(ACTOR), args, sink);
            assertEquals("command.land.rename.usage", sink.onlyKey(),
                    "args: " + java.util.Arrays.toString(args));
        }
        assertTrue(mutation.calls.isEmpty());
    }

    @Test
    void illegalNameRepliesUsageWithoutSideEffect() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();

        handler(sender -> Optional.of(LAND), mutation)
                .handle(player(ACTOR), new String[] {"rename", "a\nb"}, sink);

        assertEquals("command.land.rename.usage", sink.onlyKey());
        assertTrue(mutation.calls.isEmpty());
    }

    @Test
    void wildernessRepliesUnknownLandWithoutSideEffect() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();

        handler(sender -> Optional.empty(), mutation)
                .handle(player(ACTOR), new String[] {"rename", "Garden"}, sink);

        assertEquals("command.land.rename.failed", sink.onlyKey());
        assertEquals("rename.unknown_land", sink.vars.get("command.land.rename.failed").get("reason"));
        assertTrue(mutation.calls.isEmpty());
    }

    @Test
    void nullResolverRepliesUnknownLandWithoutSideEffect() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();

        new RenameCommandHandler(null, mutation, sender -> false)
                .handle(player(ACTOR), new String[] {"rename", "Garden"}, sink);

        assertEquals("command.land.rename.failed", sink.onlyKey());
        assertTrue(mutation.calls.isEmpty());
    }

    @Test
    void missingMutationRepliesUnavailable() {
        CapturingSink sink = new CapturingSink();

        handler(sender -> Optional.of(LAND), null)
                .handle(player(ACTOR), new String[] {"rename", "Garden"}, sink);

        assertEquals("command.land.rename.failed", sink.onlyKey());
        assertEquals("rename.unavailable",
                sink.vars.get("command.land.rename.failed").get("reason"));
    }

    @Test
    void successRepliesWithOldAndNewNames() {
        FakeMutation mutation = new FakeMutation();
        mutation.result = LandRenameResult.success(LAND, "Home", "Garden");
        CapturingSink sink = new CapturingSink();

        handler(sender -> Optional.of(LAND), mutation)
                .handle(player(ACTOR), new String[] {"rename", "Garden"}, sink);

        assertEquals("command.land.rename.success", sink.onlyKey());
        assertEquals(1, mutation.calls.size());
        Call call = mutation.calls.get(0);
        assertEquals(ACTOR, call.actor());
        assertEquals(LAND, call.land());
        assertEquals("Garden", call.name());
        assertEquals("Garden",
                sink.vars.get("command.land.rename.success").get("new_name"));
        assertEquals("Home",
                sink.vars.get("command.land.rename.success").get("old_name"));
    }

    @Test
    void multiWordNameJoinedWithSpaces() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();

        handler(sender -> Optional.of(LAND), mutation)
                .handle(player(ACTOR), new String[] {"rename", "My", "Home"}, sink);

        assertEquals(1, mutation.calls.size());
        assertEquals("My Home", mutation.calls.get(0).name());
    }

    @Test
    void stewardFlagTravelsToMutation() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        RenameCommandHandler stewardHandler =
                new RenameCommandHandler(sender -> Optional.of(LAND), mutation, sender -> true);

        stewardHandler.handle(player(ACTOR), new String[] {"rename", "Plaza"}, sink);

        assertEquals(1, mutation.calls.size());
        assertTrue(mutation.calls.get(0).steward());
    }

    @Test
    void rejectedRepliesRejectedWithReason() {
        FakeMutation mutation = new FakeMutation();
        mutation.result = LandRenameResult.rejected("rename.duplicate");
        CapturingSink sink = new CapturingSink();

        handler(sender -> Optional.of(LAND), mutation)
                .handle(player(ACTOR), new String[] {"rename", "Garden"}, sink);

        assertEquals("command.land.rename.rejected", sink.onlyKey());
        assertEquals("rename.duplicate",
                sink.vars.get("command.land.rename.rejected").get("reason"));
    }

    @Test
    void failedResultRepliesFailedWithReason() {
        FakeMutation mutation = new FakeMutation();
        mutation.result = LandRenameResult.failed("rename.failed");
        CapturingSink sink = new CapturingSink();

        handler(sender -> Optional.of(LAND), mutation)
                .handle(player(ACTOR), new String[] {"rename", "Garden"}, sink);

        assertEquals("command.land.rename.failed", sink.onlyKey());
        assertEquals("rename.failed",
                sink.vars.get("command.land.rename.failed").get("reason"));
    }

    @Test
    void degradedRepliesDegradedWithReason() {
        FakeMutation mutation = new FakeMutation();
        mutation.result = LandRenameResult.degraded(LAND, "Home", "Garden", "rename.publish_failed");
        CapturingSink sink = new CapturingSink();

        handler(sender -> Optional.of(LAND), mutation)
                .handle(player(ACTOR), new String[] {"rename", "Garden"}, sink);

        assertEquals("command.land.rename.degraded", sink.onlyKey());
        assertEquals("rename.publish_failed",
                sink.vars.get("command.land.rename.degraded").get("reason"));
        assertEquals("Garden",
                sink.vars.get("command.land.rename.degraded").get("new_name"));
        assertEquals("Home",
                sink.vars.get("command.land.rename.degraded").get("old_name"));
    }

    @Test
    void mutationFailuresReplyFailed() {
        for (String mode : new String[] {"async", "sync", "null"}) {
            FakeMutation mutation = new FakeMutation();
            switch (mode) {
                case "async" -> mutation.fail = true;
                case "sync" -> mutation.throwSync = true;
                default -> mutation.returnNull = true;
            }
            CapturingSink sink = new CapturingSink();
            handler(sender -> Optional.of(LAND), mutation)
                    .handle(player(ACTOR), new String[] {"rename", "Garden"}, sink);
            assertEquals("command.land.rename.failed", sink.onlyKey(), "mode: " + mode);
            assertEquals("rename.failed",
                    sink.vars.get("command.land.rename.failed").get("reason"), "mode: " + mode);
        }
    }
}

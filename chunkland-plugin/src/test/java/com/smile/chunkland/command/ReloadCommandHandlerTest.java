package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.config.ReloadDiff;
import com.smile.chunkland.config.ReloadOutcome;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * {@code /land reload} reply mapping: every distinguishable outcome gets
 * its own message key, failure is never reported as no-change, and the
 * console shares the slot (this handler never needs a player position).
 */
class ReloadCommandHandlerTest {

    private static final String BASE = """
            worlds:
              world:
                claim-enabled: true
                vertical-mode: PER_CHUNK_DEPTH
            """;

    private record Reply(String key, Map<String, Object> vars) {
    }

    private static final class RecordingSink implements ReplySink {
        final List<Reply> replies = new ArrayList<>();

        @Override
        public void reply(String messageKey, Map<String, Object> vars) {
            replies.add(new Reply(messageKey, Map.copyOf(vars)));
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
            reply(messageKey, vars);
        }
    }

    private static ChunkLandConfig snapshot() {
        return ConfigSchema.parseYamlText(BASE);
    }

    private static ReloadDiff emptyDiff() {
        return ReloadDiff.of(java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), 0L, 1L, false);
    }

    private static CommandSender sender(boolean player) {
        UUID id = UUID.randomUUID();
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("getUniqueId")) {
                return id;
            }
            if (name.equals("hasPermission")) {
                return true;
            }
            if (name.equals("getName")) {
                return player ? "tester" : "CONSOLE";
            }
            if (name.equals("toString")) {
                return "fake-sender";
            }
            if (name.equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals")) {
                return proxy == args[0];
            }
            Class<?> returns = method.getReturnType();
            if (returns == boolean.class) {
                return false;
            }
            if (returns == int.class || returns == long.class || returns == float.class
                    || returns == double.class || returns == short.class || returns == byte.class) {
                return 0;
            }
            return null;
        };
        if (player) {
            return (CommandSender) Proxy.newProxyInstance(ReloadCommandHandlerTest.class.getClassLoader(),
                    new Class<?>[]{Player.class}, handler);
        }
        return (CommandSender) Proxy.newProxyInstance(ReloadCommandHandlerTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, handler);
    }

    private static String onlyKey(RecordingSink sink) {
        assertEquals(1, sink.replies.size(), "the handler must reply exactly once");
        return sink.replies.get(0).key();
    }

    @Test
    void successRepliesSuccess() {
        RecordingSink sink = new RecordingSink();
        ReloadCommandHandler handler = new ReloadCommandHandler(
                () -> ReloadOutcome.success(snapshot(), emptyDiff(), null));

        handler.handle(sender(true), new String[]{"reload"}, sink);

        assertEquals("command.land.reload.success", onlyKey(sink));
    }

    @Test
    void noChangeRepliesNoChange() {
        RecordingSink sink = new RecordingSink();
        ReloadCommandHandler handler = new ReloadCommandHandler(
                () -> ReloadOutcome.noChange(snapshot()));

        handler.handle(sender(true), new String[]{"reload"}, sink);

        assertEquals("command.land.reload.no_change", onlyKey(sink));
    }

    @Test
    void failureRepliesFailedWithReasonAndNeverAsNoChange() {
        RecordingSink sink = new RecordingSink();
        RuntimeException cause = new RuntimeException("worlds.world.claim-enabled must be a boolean");
        ReloadCommandHandler handler = new ReloadCommandHandler(
                () -> ReloadOutcome.failed(snapshot(), cause));

        handler.handle(sender(true), new String[]{"reload"}, sink);

        String key = onlyKey(sink);
        assertEquals("command.land.reload.failed", key);
        assertNotEquals("command.land.reload.no_change", key,
                "a failure must never read as no-change");
        assertTrue(String.valueOf(sink.replies.get(0).vars().get("reason")).contains("claim-enabled"));
    }

    @Test
    void rejectionRepliesRestartRequiredWithReason() {
        RecordingSink sink = new RecordingSink();
        ReloadCommandHandler handler = new ReloadCommandHandler(
                () -> ReloadOutcome.rejected(snapshot(), "economy changed; restart to apply"));

        handler.handle(sender(true), new String[]{"reload"}, sink);

        assertEquals("command.land.reload.restart_required", onlyKey(sink));
        assertTrue(String.valueOf(sink.replies.get(0).vars().get("reason")).contains("economy"));
    }

    @Test
    void busyRepliesBusy() {
        RecordingSink sink = new RecordingSink();
        ReloadCommandHandler handler = new ReloadCommandHandler(
                () -> ReloadOutcome.busy(snapshot()));

        handler.handle(sender(true), new String[]{"reload"}, sink);

        assertEquals("command.land.reload.busy", onlyKey(sink));
    }

    @Test
    void committedCallbackFailureIsReportedAsCommitted() {
        RecordingSink sink = new RecordingSink();
        ReloadCommandHandler handler = new ReloadCommandHandler(() -> ReloadOutcome.success(
                snapshot(), emptyDiff(), new RuntimeException("budget sync failed")));

        handler.handle(sender(true), new String[]{"reload"}, sink);

        String key = onlyKey(sink);
        assertEquals("command.land.reload.committed_callback_failed", key);
        assertNotNull(sink.replies.get(0).vars().get("reason"));
    }

    @Test
    void consoleSharesTheSlot() {
        RecordingSink sink = new RecordingSink();
        ReloadCommandHandler handler = new ReloadCommandHandler(
                () -> ReloadOutcome.noChange(snapshot()));

        handler.handle(sender(false), new String[]{"reload"}, sink);

        assertEquals("command.land.reload.no_change", onlyKey(sink));
    }

    @Test
    void extraArgumentsAnswerWithUsage() {
        RecordingSink sink = new RecordingSink();
        ReloadCommandHandler handler = new ReloadCommandHandler(
                () -> ReloadOutcome.noChange(snapshot()));

        handler.handle(sender(true), new String[]{"reload", "now"}, sink);

        assertEquals("command.land.reload.usage", onlyKey(sink));
    }

    @Test
    void missingRunnerFailsClosed() {
        RecordingSink sink = new RecordingSink();
        ReloadCommandHandler handler = new ReloadCommandHandler(null);

        handler.handle(sender(true), new String[]{"reload"}, sink);

        assertEquals("command.land.reload.failed", onlyKey(sink));
        assertTrue(String.valueOf(sink.replies.get(0).vars().get("reason")).contains("unavailable"));
    }
}

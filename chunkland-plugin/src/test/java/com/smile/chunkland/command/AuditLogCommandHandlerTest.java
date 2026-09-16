package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.AuditRepository;
import com.smile.chunkland.persistence.AuditSearchQuery;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class AuditLogCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    private record Reply(String key, Map<String, Object> vars, Locale locale) {
    }

    private static final class CapturingSink implements ReplySink {
        final List<Reply> replies = new ArrayList<>();

        @Override public void reply(String messageKey, Map<String, Object> vars) {
            replies.add(new Reply(messageKey, vars, null));
        }

        @Override public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
            replies.add(new Reply(messageKey, vars, localeOverride));
        }
    }

    private static CommandSender consoleSender() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Player playerSender(Locale locale) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return true;
                    }
                    if (method.getName().equals("locale")) {
                        return locale;
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

    private static final class StubAudits implements AuditRepository {
        final AtomicReference<AuditSearchQuery> lastQuery = new AtomicReference<>();
        List<AuditEntry> rows = List.of();
        RuntimeException searchFailure;

        @Override public CompletionStage<Long> insert(AuditEntry entry) {
            return CompletableFuture.completedFuture(1L);
        }

        @Override public CompletionStage<Optional<AuditEntry>> findById(long id) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override public CompletionStage<List<AuditEntry>> findByLand(LandId landId, int limit, int offset) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override public CompletionStage<List<AuditEntry>> findByAction(String action, int limit) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override public CompletionStage<List<AuditEntry>> findAll(int limit, int offset) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override public CompletionStage<List<AuditEntry>> search(AuditSearchQuery query) {
            lastQuery.set(query);
            if (searchFailure != null) {
                CompletableFuture<List<AuditEntry>> failed = new CompletableFuture<>();
                failed.completeExceptionally(searchFailure);
                return failed;
            }
            return CompletableFuture.completedFuture(rows);
        }

        @Override public CompletionStage<List<String>> explainSearch(AuditSearchQuery query) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override public CompletionStage<Integer> purgeOlderThan(java.time.Instant cutoff) {
            return CompletableFuture.completedFuture(0);
        }
    }

    private AuditEntry row(String action) {
        return new AuditEntry(1L, NOW, UUID.randomUUID(), action, null, UUID.randomUUID(),
                null, 1, null, null, null, List.of());
    }

    @Test
    void logSubcommandIsRegisteredWithPermission() {
        assertTrue(LandCommand.SUBCOMMANDS.contains("log"));
        assertEquals("chunkland.command.land.log", LandPermissions.forSubcommand("log"));
        assertTrue(LandPermissions.ALL_ORDERED.contains("chunkland.command.land.log"));
        assertTrue(LandCommand.defaultStubHandlers().containsKey("log"));
    }

    @Test
    void blankTailRepliesUsage() {
        AuditLogCommandHandler handler = new AuditLogCommandHandler(null, () -> NOW);
        CapturingSink sink = new CapturingSink();
        handler.handle(consoleSender(), new String[] {"log"}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.log.usage", sink.replies.get(0).key());
        CapturingSink sink2 = new CapturingSink();
        handler.handle(consoleSender(), null, sink2);
        assertEquals("command.land.log.usage", sink2.replies.get(0).key());
    }

    @Test
    void malformedFilterRepliesUsage() {
        StubAudits audits = new StubAudits();
        AuditLogCommandHandler handler = new AuditLogCommandHandler(() -> audits, () -> NOW);
        CapturingSink sink = new CapturingSink();
        handler.handle(consoleSender(), new String[] {"log", "x:1"}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.log.usage", sink.replies.get(0).key());
        assertNull(audits.lastQuery.get());
    }

    @Test
    void missingRepoRepliesUnavailable() {
        AuditLogCommandHandler handler = new AuditLogCommandHandler(null, () -> NOW);
        CapturingSink sink = new CapturingSink();
        handler.handle(consoleSender(), new String[] {"log", "a:LAND_CREATE"}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.log.failed", sink.replies.get(0).key());
        assertEquals("log.unavailable", sink.replies.get(0).vars().get("reason"));
    }

    @Test
    void emptyResultRepliesEmpty() {
        StubAudits audits = new StubAudits();
        AuditLogCommandHandler handler = new AuditLogCommandHandler(() -> audits, () -> NOW);
        CapturingSink sink = new CapturingSink();
        handler.handle(consoleSender(), new String[] {"log", "a:LEDGER_RESOLVE"}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.log.empty", sink.replies.get(0).key());
        assertEquals("LEDGER_RESOLVE", audits.lastQuery.get().action());
    }

    @Test
    void rowsReplyOneLineEachWithRenderTimeLocale() {
        StubAudits audits = new StubAudits();
        AuditEntry first = row("LAND_CREATE");
        AuditEntry second = row("DEPTH_EXTEND");
        audits.rows = List.of(first, second);
        Locale playerLocale = Locale.forLanguageTag("zh-TW");
        AuditLogCommandHandler handler = new AuditLogCommandHandler(() -> audits, () -> NOW);
        CapturingSink sink = new CapturingSink();
        handler.handle(playerSender(playerLocale), new String[] {"log", "t:7d"}, sink);
        assertEquals(2, sink.replies.size());
        assertEquals("command.land.log.line", sink.replies.get(0).key());
        assertEquals("command.land.log.line", sink.replies.get(1).key());
        assertEquals(AuditLogFormatter.format(first, playerLocale),
                sink.replies.get(0).vars().get("value"));
        assertEquals(AuditLogFormatter.format(second, playerLocale),
                sink.replies.get(1).vars().get("value"));
        assertEquals(playerLocale, sink.replies.get(0).locale());
        assertEquals(NOW.minusSeconds(7L * 86400L), audits.lastQuery.get().since());
    }

    @Test
    void failingStageRepliesFailed() {
        StubAudits audits = new StubAudits();
        audits.searchFailure = new RuntimeException("db down");
        AuditLogCommandHandler handler = new AuditLogCommandHandler(() -> audits, () -> NOW);
        CapturingSink sink = new CapturingSink();
        handler.handle(consoleSender(), new String[] {"log", "a:LAND_CREATE"}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.log.failed", sink.replies.get(0).key());
    }

    @Test
    void allowDispatchReachesLogHandler() {
        StubAudits audits = new StubAudits();
        AuditLogCommandHandler handler = new AuditLogCommandHandler(() -> audits, () -> NOW);
        CapturingSink sink = new CapturingSink();
        LandCommand cmd = new LandCommand(Map.of("log", handler),
                (sender, pipeline) -> sink);
        Player player = playerSender(Locale.US);
        assertTrue(cmd.dispatch(player, new String[] {"log", "a:LAND_CREATE"}, null));
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.log.empty", sink.replies.get(0).key());
    }
}

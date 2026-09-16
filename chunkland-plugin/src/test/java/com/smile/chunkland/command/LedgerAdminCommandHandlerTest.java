package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.AuditRepository;
import com.smile.chunkland.persistence.CrashRecoveryScanner;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.LedgerState;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RecoveryHandlers;
import com.smile.chunkland.persistence.SqliteAuditRepository;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Red contract for {@code /land admin ledger list/show/resolve}.
 *
 * <p>Every resolve is an explicit operator decision guarded by a typed
 * compare-and-set out of {@code NEEDS_RECONCILIATION} plus one
 * {@code LEDGER_RESOLVE} audit row. Nothing here calls Economy, replays the
 * domain or heals rows automatically.
 */
class LedgerAdminCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    @TempDir Path temporaryDirectory;

    private record Reply(String key, Map<String, Object> vars, String threadName) {
    }

    private static final class CapturingSink implements ReplySink {
        final List<Reply> replies = new CopyOnWriteArrayList<>();

        @Override public void reply(String messageKey, Map<String, Object> vars) {
            replies.add(new Reply(messageKey, vars, Thread.currentThread().getName()));
        }

        @Override public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
            reply(messageKey, vars);
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
                    if (method.getName().equals("getName")) {
                        return "Console";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Player playerSender(UUID uuid) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) {
                        return uuid;
                    }
                    if (method.getName().equals("hasPermission")) {
                        return true;
                    }
                    if (method.getName().equals("getName")) {
                        return "Operator";
                    }
                    if (method.getName().equals("locale")) {
                        return Locale.US;
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("toString")) {
                        return "Player-proxy";
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

    /** Scheduler fake that records the hop instead of touching Bukkit. */
    private static final class RecordingScheduler implements PlayerScheduler {
        final AtomicInteger hops = new AtomicInteger();
        final AtomicReference<String> hopThread = new AtomicReference<>();

        @Override public void runForPlayer(Player player, Runnable task) {
            hops.incrementAndGet();
            hopThread.set(Thread.currentThread().getName());
            task.run();
        }
    }

    private Path database() {
        return temporaryDirectory.resolve(UUID.randomUUID() + ".db");
    }

    private OperationPayload payload(UUID operationId) {
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID world = UUID.fromString("00000000-0000-0000-0000-000000000012");
        return OperationPayload.claim(operationId, actor, world,
                new LandId(UUID.nameUUIDFromBytes((operationId + "-land").getBytes())),
                List.of(new OperationPayload.Chunk(new ChunkKey(world, 7, -3), 12,
                        UUID.nameUUIDFromBytes((operationId + "-lot").getBytes()), 417L)),
                417L, "test-economy", NOW, "Ledger land " + operationId.toString().substring(28));
    }

    private LedgerEntry seed(PersistenceStore store, LedgerState state) {
        OperationPayload payload = payload(UUID.randomUUID());
        OperationLedger ledger = new OperationLedger(store);
        ledger.insert(LedgerEntry.fromPayload(payload, LedgerState.CREATED)).toCompletableFuture().join();
        if (state != LedgerState.CREATED) {
            ledger.quarantine(payload.operationId(), LedgerState.CREATED, NOW)
                    .toCompletableFuture().join();
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(),
                    ledger.find(payload.operationId()).toCompletableFuture().join().state());
        }
        return ledger.find(payload.operationId()).toCompletableFuture().join();
    }

    private LedgerAdminCommandHandler handler(PersistenceStore store, RecordingScheduler scheduler) {
        OperationLedger ledger = new OperationLedger(store);
        AuditRepository audits = new SqliteAuditRepository(store);
        return new LedgerAdminCommandHandler(() -> ledger, () -> audits, () -> NOW, scheduler);
    }

    private static List<Reply> awaitReplies(CapturingSink sink, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (sink.replies.size() >= expected) {
                break;
            }
            Thread.sleep(25);
        }
        return List.copyOf(sink.replies);
    }

    @Test
    void adminSubcommandUsesAnIndependentLedgerPermission() {
        assertTrue(LandCommand.SUBCOMMANDS.contains("admin"));
        assertEquals("chunkland.admin.ledger", LandPermissions.forSubcommand("admin"));
        assertTrue(LandPermissions.ALL_ORDERED.contains("chunkland.admin.ledger"));
        assertTrue(LandCommand.defaultStubHandlers().containsKey("admin"));
        assertNotEquals("chunkland.admin.serverland", LandPermissions.forSubcommand("admin"));
        assertNotEquals("chunkland.admin.bypass", LandPermissions.forSubcommand("admin"));
    }

    @Test
    void listAllRepliesBoundedLinesWithoutPayloadLeak() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            RecordingScheduler scheduler = new RecordingScheduler();
            seed(store, LedgerState.CREATED);
            seed(store, LedgerState.NEEDS_RECONCILIATION);
            seed(store, LedgerState.NEEDS_RECONCILIATION);
            CapturingSink sink = new CapturingSink();
            handler(store, scheduler).handle(consoleSender(),
                    new String[] {"admin", "ledger", "list"}, sink);
            List<Reply> replies = awaitReplies(sink, 3, 5000);
            assertEquals(3, replies.size());
            for (Reply reply : replies) {
                assertEquals("command.land.admin.ledger.line", reply.key());
                String value = String.valueOf(reply.vars().get("value"));
                assertTrue(value.length() <= 220, "list line must stay bounded, got " + value.length());
                assertTrue(!value.contains("payloadJson") && !value.contains("chunk"),
                        "list line must not leak payload internals");
            }
        }
    }

    @Test
    void listWithStateFilterAcceptsKnownStatesAndRejectsUnknown() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            RecordingScheduler scheduler = new RecordingScheduler();
            seed(store, LedgerState.CREATED);
            LedgerEntry quarantined = seed(store, LedgerState.NEEDS_RECONCILIATION);
            CapturingSink filtered = new CapturingSink();
            handler(store, scheduler).handle(consoleSender(),
                    new String[] {"admin", "ledger", "list", "NEEDS_RECONCILIATION"}, filtered);
            List<Reply> replies = awaitReplies(filtered, 1, 5000);
            assertEquals(1, replies.size());
            assertTrue(String.valueOf(replies.get(0).vars().get("value")).contains("NEEDS_RECONCILIATION"));

            CapturingSink badFilter = new CapturingSink();
            handler(store, scheduler).handle(consoleSender(),
                    new String[] {"admin", "ledger", "list", "BOGUS"}, badFilter);
            List<Reply> bad = awaitReplies(badFilter, 1, 5000);
            assertEquals("command.land.admin.ledger.usage", bad.get(0).key());
            assertEquals(quarantined.operationId().toString().split("-")[0].length(), 8);
        }
    }

    @Test
    void showValidUnknownAndMalformed() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            RecordingScheduler scheduler = new RecordingScheduler();
            LedgerEntry entry = seed(store, LedgerState.NEEDS_RECONCILIATION);
            CapturingSink valid = new CapturingSink();
            handler(store, scheduler).handle(consoleSender(),
                    new String[] {"admin", "ledger", "show", entry.operationId().toString()}, valid);
            List<Reply> shown = awaitReplies(valid, 1, 5000);
            assertEquals("command.land.admin.ledger.line", shown.get(0).key());
            assertTrue(String.valueOf(shown.get(0).vars().get("value"))
                    .contains(entry.operationId().toString()));

            CapturingSink unknown = new CapturingSink();
            handler(store, scheduler).handle(consoleSender(),
                    new String[] {"admin", "ledger", "show", UUID.randomUUID().toString()}, unknown);
            List<Reply> missing = awaitReplies(unknown, 1, 5000);
            assertEquals("command.land.admin.ledger.failed", missing.get(0).key());

            CapturingSink malformed = new CapturingSink();
            handler(store, scheduler).handle(consoleSender(),
                    new String[] {"admin", "ledger", "show", "not-a-uuid"}, malformed);
            List<Reply> bad = awaitReplies(malformed, 1, 5000);
            assertEquals("command.land.admin.ledger.usage", bad.get(0).key());
        }
    }

    @Test
    void resolveAcceptsOnlyThreeDecisions() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            RecordingScheduler scheduler = new RecordingScheduler();
            LedgerEntry entry = seed(store, LedgerState.NEEDS_RECONCILIATION);
            for (String decision : new String[] {"MAYBE", "", "REFUND", "resolved-lower"}) {
                CapturingSink sink = new CapturingSink();
                handler(store, scheduler).handle(consoleSender(),
                        new String[] {"admin", "ledger", "resolve",
                                entry.operationId().toString(), decision},
                        sink);
                List<Reply> replies = awaitReplies(sink, 1, 5000);
                assertTrue(replies.get(0).key().equals("command.land.admin.ledger.usage")
                        || replies.get(0).key().equals("command.land.admin.ledger.failed"),
                        "decision " + decision + " must be rejected");
            }
            OperationLedger ledger = new OperationLedger(store);
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(),
                    ledger.find(entry.operationId()).toCompletableFuture().join().state());
        }
    }

    @Test
    void resolveWritesLedgerResolveAuditWithDecisionMetadata() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            RecordingScheduler scheduler = new RecordingScheduler();
            LedgerEntry entry = seed(store, LedgerState.NEEDS_RECONCILIATION);
            UUID operator = UUID.randomUUID();
            CapturingSink sink = new CapturingSink();
            handler(store, scheduler).handle(playerSender(operator),
                    new String[] {"admin", "ledger", "resolve",
                            entry.operationId().toString(), "RESOLVED"},
                    sink);
            List<Reply> replies = awaitReplies(sink, 1, 5000);
            assertEquals("command.land.admin.ledger.resolved", replies.get(0).key());

            OperationLedger ledger = new OperationLedger(store);
            assertEquals(LedgerState.RESOLVED.name(),
                    ledger.find(entry.operationId()).toCompletableFuture().join().state());

            AuditRepository audits = new SqliteAuditRepository(store);
            List<AuditEntry> rows = audits.findByAction("LEDGER_RESOLVE", 10)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(1, rows.size());
            assertEquals(operator, rows.get(0).actor());
            String metadata = rows.get(0).metadataJson();
            assertNotNull(metadata);
            assertTrue(metadata.contains(entry.operationId().toString()));
            assertTrue(metadata.contains("NEEDS_RECONCILIATION"));
            assertTrue(metadata.contains("RESOLVED"));
        }
    }

    @Test
    void resolveRejectsNonReconciliationAndTerminalStates() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            RecordingScheduler scheduler = new RecordingScheduler();
            OperationLedger ledger = new OperationLedger(store);
            OperationPayload created = payload(UUID.randomUUID());
            ledger.create(created).toCompletableFuture().join();
            CapturingSink createdSink = new CapturingSink();
            handler(store, scheduler).handle(consoleSender(),
                    new String[] {"admin", "ledger", "resolve",
                            created.operationId().toString(), "IGNORED"},
                    createdSink);
            List<Reply> createdReplies = awaitReplies(createdSink, 1, 5000);
            assertEquals("command.land.admin.ledger.failed", createdReplies.get(0).key());
            assertEquals(LedgerState.CREATED.name(),
                    ledger.find(created.operationId()).toCompletableFuture().join().state());

            LedgerEntry reconciled = seed(store, LedgerState.NEEDS_RECONCILIATION);
            CapturingSink first = new CapturingSink();
            handler(store, scheduler).handle(consoleSender(),
                    new String[] {"admin", "ledger", "resolve",
                            reconciled.operationId().toString(), "IGNORED"},
                    first);
            assertEquals("command.land.admin.ledger.resolved",
                    awaitReplies(first, 1, 5000).get(0).key());
            CapturingSink repeated = new CapturingSink();
            handler(store, scheduler).handle(consoleSender(),
                    new String[] {"admin", "ledger", "resolve",
                            reconciled.operationId().toString(), "RESOLVED"},
                    repeated);
            List<Reply> repeatedReplies = awaitReplies(repeated, 1, 5000);
            assertEquals("command.land.admin.ledger.failed", repeatedReplies.get(0).key());
            assertEquals(LedgerState.IGNORED.name(),
                    ledger.find(reconciled.operationId()).toCompletableFuture().join().state());
        }
    }

    @Test
    void resolveRaceLetsExactlyOneDecisionWin() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            RecordingScheduler scheduler = new RecordingScheduler();
            LedgerEntry entry = seed(store, LedgerState.NEEDS_RECONCILIATION);
            LedgerAdminCommandHandler first = handler(store, scheduler);
            LedgerAdminCommandHandler second = handler(store, scheduler);
            CapturingSink firstSink = new CapturingSink();
            CapturingSink secondSink = new CapturingSink();
            first.handle(consoleSender(), new String[] {"admin", "ledger", "resolve",
                    entry.operationId().toString(), "RESOLVED"}, firstSink);
            second.handle(consoleSender(), new String[] {"admin", "ledger", "resolve",
                    entry.operationId().toString(), "IGNORED"}, secondSink);
            List<Reply> firstReplies = awaitReplies(firstSink, 1, 5000);
            List<Reply> secondReplies = awaitReplies(secondSink, 1, 5000);
            int wins = 0;
            if (firstReplies.get(0).key().equals("command.land.admin.ledger.resolved")) {
                wins++;
            }
            if (secondReplies.get(0).key().equals("command.land.admin.ledger.resolved")) {
                wins++;
            }
            assertEquals(1, wins, "CAS race must leave exactly one winner");
            AuditRepository audits = new SqliteAuditRepository(store);
            List<AuditEntry> rows = audits.findByAction("LEDGER_RESOLVE", 10)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(1, rows.size());
        }
    }

    @Test
    void startupRecoveryNeverMovesNeedsReconciliation() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            LedgerEntry entry = seed(store, LedgerState.NEEDS_RECONCILIATION);
            OperationLedger ledger = new OperationLedger(store);
            AtomicBoolean economyTouched = new AtomicBoolean(false);
            RecoveryHandlers handlers = RecoveryHandlers.of(
                    ignored -> {
                        economyTouched.set(true);
                        return CompletableFuture.completedFuture(null);
                    },
                    ignored -> {
                        economyTouched.set(true);
                        return CompletableFuture.completedFuture(null);
                    },
                    (ignored, payload) -> CompletableFuture.completedFuture(null),
                    (ignored, payload) -> CompletableFuture.completedFuture(null));
            CrashRecoveryScanner scanner = new CrashRecoveryScanner(ledger, handlers,
                    Clock.fixed(NOW, ZoneOffset.UTC), 3);
            var first = scanner.scan().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var second = scanner.scan().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(),
                    ledger.find(entry.operationId()).toCompletableFuture().join().state());
            assertEquals(first.get(0).resultingState(), second.get(0).resultingState());
            assertTrue(!economyTouched.get(), "recovery must not touch Economy for operator rows");
        }
    }

    @Test
    void playerRepliesArriveThroughThePlayerScheduler() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            RecordingScheduler scheduler = new RecordingScheduler();
            LedgerEntry entry = seed(store, LedgerState.NEEDS_RECONCILIATION);
            UUID operator = UUID.randomUUID();
            Player player = playerSender(operator);
            CapturingSink sink = new CapturingSink();
            String callerThread = Thread.currentThread().getName();
            handler(store, scheduler).handle(player,
                    new String[] {"admin", "ledger", "show", entry.operationId().toString()}, sink);
            List<Reply> replies = awaitReplies(sink, 1, 5000);
            assertEquals(1, replies.size());
            assertTrue(scheduler.hops.get() >= 1, "player reply must hop through the player scheduler");
            assertNotEquals(callerThread + "-never", scheduler.hopThread.get());
            assertEquals(scheduler.hopThread.get(), replies.get(0).threadName(),
                    "sink must be touched on the scheduler hop, not the persistence thread");
        }
    }

    @Test
    void dispatchDeniesWithoutTheLedgerPermission() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        LandCommand.Handler guarded = (sender, args, sink) -> invoked.set(true);
        List<String> denied = new CopyOnWriteArrayList<>();
        ReplySink sink = new ReplySink() {
            @Override public void reply(String messageKey, Map<String, Object> vars) {
                denied.add(messageKey);
            }

            @Override public void reply(String messageKey, Map<String, Object> vars, Locale locale) {
                denied.add(messageKey);
            }
        };
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return false;
                    }
                    return defaultValue(method.getReturnType());
                });
        LandCommand command = new LandCommand(Map.of("admin", guarded), (ignored, pipeline) -> sink);
        assertTrue(command.dispatch(sender, new String[] {"admin", "ledger", "list"}, null));
        assertTrue(!invoked.get());
        assertEquals(List.of("command.land.denied"), new ArrayList<>(denied));
        assertTrue(!LandPermissions.forSubcommand("admin").equals("chunkland.admin.serverland"));
    }

    @Test
    void everyDecisionHasADistinctTerminalState() {
        assertTrue(LedgerState.RESOLVED.isTerminal());
        assertTrue(LedgerState.REFUNDED.isTerminal());
        assertTrue(LedgerState.IGNORED.isTerminal());
        assertTrue(LedgerState.NEEDS_RECONCILIATION.canTransitionTo(LedgerState.RESOLVED));
        assertTrue(LedgerState.NEEDS_RECONCILIATION.canTransitionTo(LedgerState.REFUNDED));
        assertTrue(LedgerState.NEEDS_RECONCILIATION.canTransitionTo(LedgerState.IGNORED));
        assertTrue(!LedgerState.RESOLVED.canTransitionTo(LedgerState.IGNORED));
        assertTrue(Collections.singleton("WAIT_FOR_OPERATOR")
                .contains(LedgerState.NEEDS_RECONCILIATION.recoveryClassification().name()));
    }
}

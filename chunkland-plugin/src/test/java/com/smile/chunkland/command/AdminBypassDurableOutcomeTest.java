package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Durable terminals and restart recovery for the bypass toggle.
 *
 * <p>Every {@code on} or {@code off} writes one attempt row before its flip
 * and one terminal afterwards: committed on success, aborted when the
 * commit cannot succeed. An attempt alone is never success. A crash
 * between the attempt and its terminal leaves an open attempt that the
 * next enable aborts; a terminal write failure fail-closes the state and
 * stays retryable.
 */
class AdminBypassDurableOutcomeTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final String BYPASS_NODE = "chunkland.admin.bypass";

    private record Reply(String key, Map<String, Object> vars) {
    }

    private static final class CapturingSink implements ReplySink {
        final List<Reply> replies = new CopyOnWriteArrayList<>();

        @Override public void reply(String messageKey, Map<String, Object> vars) {
            replies.add(new Reply(messageKey, vars));
        }

        @Override public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
            reply(messageKey, vars);
        }
    }

    private static final class DeferredAudits implements AuditRepository {
        final List<AuditEntry> inserted = new CopyOnWriteArrayList<>();
        final ConcurrentLinkedQueue<CompletableFuture<Long>> gates = new ConcurrentLinkedQueue<>();
        final AtomicInteger ids = new AtomicInteger();
        volatile boolean failCommitted = false;
        volatile boolean failAborts = false;

        @Override public CompletionStage<Long> insert(AuditEntry entry) {
            boolean committed = entry.metadataJson() != null
                    && entry.metadataJson().contains("\"outcome\":\"" + AdminBypassCommandHandler.OUTCOME_COMMITTED + "\"");
            boolean aborted = entry.metadataJson() != null
                    && entry.metadataJson().contains("\"outcome\":\"" + AdminBypassCommandHandler.OUTCOME_ABORTED + "\"");
            if (committed && failCommitted) {
                return CompletableFuture.failedFuture(new IllegalStateException("terminal store down"));
            }
            if (aborted && failAborts) {
                return CompletableFuture.failedFuture(new IllegalStateException("abort store down"));
            }
            inserted.add(entry);
            boolean attempt = entry.metadataJson() != null
                    && entry.metadataJson().contains("\"outcome\":\"" + AdminBypassCommandHandler.OUTCOME_ATTEMPT + "\"");
            if (!attempt) {
                return CompletableFuture.completedFuture((long) ids.incrementAndGet());
            }
            CompletableFuture<Long> gate = gates.poll();
            if (gate == null) {
                return CompletableFuture.completedFuture((long) ids.incrementAndGet());
            }
            return gate.thenApply(ignored -> (long) ids.incrementAndGet());
        }

        @Override public CompletionStage<Optional<AuditEntry>> findById(long id) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override public CompletionStage<List<AuditEntry>> findByLand(LandId landId, int limit, int offset) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override public CompletionStage<List<AuditEntry>> findByAction(String action, int limit) {
            return CompletableFuture.completedFuture(List.copyOf(inserted));
        }

        @Override public CompletionStage<List<AuditEntry>> findAll(int limit, int offset) {
            return CompletableFuture.completedFuture(List.copyOf(inserted));
        }

        @Override public CompletionStage<List<AuditEntry>> search(AuditSearchQuery query) {
            List<AuditEntry> matching = new ArrayList<>();
            for (AuditEntry e : inserted) {
                if (query.action() == null || query.action().equals(e.action())) {
                    matching.add(e);
                }
            }
            matching.sort((a, b) -> {
                int c = b.timestamp().compareTo(a.timestamp());
                if (c != 0) {
                    return c;
                }
                return Long.compare(b.id(), a.id());
            });
            int from = Math.min(query.offset(), matching.size());
            int to = Math.min(from + query.limit(), matching.size());
            return CompletableFuture.completedFuture(List.copyOf(matching.subList(from, to)));
        }

        @Override public CompletionStage<List<String>> explainSearch(AuditSearchQuery query) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override public CompletionStage<Integer> purgeOlderThan(Instant cutoff) {
            return CompletableFuture.completedFuture(0);
        }
    }

    private static Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return uuid;
                    }
                    if (name.equals("hasPermission")) {
                        return BYPASS_NODE.equals(args[0]);
                    }
                    if (name.equals("getName")) {
                        return "Bypass-admin";
                    }
                    if (name.equals("locale")) {
                        return Locale.US;
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "Bypass-admin-proxy";
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

    @Test
    void successfulOnHasCommittedTerminalAttemptAloneIsNeverSuccess() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);

        assertTrue(states.isOn(actor));
        assertEquals(2, audits.inserted.size());
        AuditEntry attempt = audits.inserted.get(0);
        AuditEntry terminal = audits.inserted.get(1);
        assertTrue(AdminBypassCommandHandler.isAttemptRow(attempt));
        assertTrue(AdminBypassCommandHandler.isCommittedRow(terminal));
        assertEquals(AdminBypassCommandHandler.attemptIdOf(attempt),
                AdminBypassCommandHandler.attemptIdOf(terminal));
        UUID attemptId = AdminBypassCommandHandler.attemptIdOf(attempt).orElseThrow();
        assertTrue(AdminBypassCommandHandler.hasCommittedFor(attemptId, audits.inserted));
        assertTrue(AdminBypassRecovery.isSuccessfulOn(attemptId, audits.inserted));
        assertEquals(0, AdminBypassCommandHandler.countUnmarkedSuccessRows(audits.inserted));

        List<AuditEntry> attemptOnly = List.of(attempt);
        assertFalse(AdminBypassRecovery.isSuccessfulOn(attemptId, attemptOnly),
                "an attempt without its committed terminal must never read as success");
    }

    @Test
    void successfulOnThenOffEachHaveDistinguishableTerminals() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());

        handler.handle(player(actor), new String[] {"bypass", "on"}, new CapturingSink());
        assertTrue(states.isOn(actor));
        handler.handle(player(actor), new String[] {"bypass", "off"}, new CapturingSink());
        assertFalse(states.isOn(actor));

        assertEquals(4, audits.inserted.size());
        AuditEntry onAttempt = audits.inserted.get(0);
        AuditEntry onTerminal = audits.inserted.get(1);
        AuditEntry offAttempt = audits.inserted.get(2);
        AuditEntry offTerminal = audits.inserted.get(3);
        assertTrue(AdminBypassCommandHandler.isAttemptRow(onAttempt));
        assertTrue(AdminBypassCommandHandler.isCommittedRow(onTerminal));
        assertEquals("true", onTerminal.afterJson());
        assertTrue(AdminBypassCommandHandler.isAttemptRow(offAttempt));
        assertTrue(AdminBypassCommandHandler.isCommittedRow(offTerminal));
        assertEquals("false", offTerminal.afterJson());
        assertFalse(AdminBypassCommandHandler.attemptIdOf(onAttempt)
                .equals(AdminBypassCommandHandler.attemptIdOf(offAttempt)));
        UUID onId = AdminBypassCommandHandler.attemptIdOf(onAttempt).orElseThrow();
        UUID offId = AdminBypassCommandHandler.attemptIdOf(offAttempt).orElseThrow();
        assertTrue(AdminBypassRecovery.isSuccessfulOn(onId, audits.inserted));
        assertFalse(AdminBypassRecovery.isSuccessfulOn(offId, audits.inserted),
                "an off committed terminal must not read as a successful on");
    }

    @Test
    void crashOpenAttemptIsAbortedByRecoveryAndFreshStateStaysOff() throws Exception {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        CompletableFuture<Long> attemptGate = new CompletableFuture<>();
        audits.gates.add(attemptGate);
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
        assertEquals(1, audits.inserted.size());
        AuditEntry openAttempt = audits.inserted.get(0);
        UUID openId = AdminBypassCommandHandler.attemptIdOf(openAttempt).orElseThrow();
        assertFalse(AdminBypassRecovery.isSuccessfulOn(openId, audits.inserted));

        // Simulate crash: attempt durable, commit and terminal never run.
        // New enable starts with a fresh off memory.
        AdminBypassState fresh = new AdminBypassState();
        assertFalse(fresh.isOn(actor));

        AdminBypassRecovery.RecoveryResult result = AdminBypassRecovery.recover(
                () -> audits, () -> NOW, 500).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(1, result.openFound());
        assertEquals(1, result.abortedWritten());
        assertEquals(List.of(openId), result.abortedAttemptIds());
        assertTrue(result.failedAttemptIds().isEmpty());
        assertEquals(2, audits.inserted.size());
        assertTrue(AdminBypassCommandHandler.hasAbortFor(openId, audits.inserted));
        assertFalse(AdminBypassRecovery.isSuccessfulOn(openId, audits.inserted));
        assertFalse(fresh.isOn(actor), "recovery never re-enables bypass");
        assertEquals(0, AdminBypassCommandHandler.countUnmarkedSuccessRows(audits.inserted));
    }

    @Test
    void committedTerminalFailureFailClosesAndStaysRetryable() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        audits.failCommitted = true;
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);

        assertFalse(states.isOn(actor), "a terminal failure must fail the state back closed");
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
        assertEquals("bypass.audit_failed", sink.replies.get(0).vars().get("reason"));
        assertEquals(2, audits.inserted.size(), "attempt plus abort, failed terminal leaves no row");
        AuditEntry attempt = audits.inserted.get(0);
        UUID attemptId = AdminBypassCommandHandler.attemptIdOf(attempt).orElseThrow();
        assertTrue(AdminBypassCommandHandler.hasAbortFor(attemptId, audits.inserted));
        assertFalse(AdminBypassRecovery.isSuccessfulOn(attemptId, audits.inserted));

        audits.failCommitted = false;
        CapturingSink retrySink = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, retrySink);
        assertEquals("command.land.bypass.on", retrySink.replies.get(0).key());
        assertTrue(states.isOn(actor));
        UUID retryId = AdminBypassCommandHandler.attemptIdOf(
                audits.inserted.get(audits.inserted.size() - 2)).orElseThrow();
        assertTrue(AdminBypassRecovery.isSuccessfulOn(retryId, audits.inserted));
    }

    @Test
    void clearBeforeCommitProducesAbortedTerminal() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        CompletableFuture<Long> attemptGate = new CompletableFuture<>();
        audits.gates.add(attemptGate);
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
        states.clear();
        attemptGate.complete(1L);

        assertFalse(states.isOn(actor));
        assertEquals(2, audits.inserted.size());
        AuditEntry attempt = audits.inserted.get(0);
        AuditEntry abort = audits.inserted.get(1);
        assertTrue(AdminBypassCommandHandler.isAttemptRow(attempt));
        assertTrue(AdminBypassCommandHandler.isAbortRow(abort));
        assertEquals(AdminBypassCommandHandler.attemptIdOf(attempt),
                AdminBypassCommandHandler.attemptIdOf(abort));
        UUID attemptId = AdminBypassCommandHandler.attemptIdOf(attempt).orElseThrow();
        assertFalse(AdminBypassRecovery.isSuccessfulOn(attemptId, audits.inserted));
    }

    @Test
    void recoverySkipsAttemptsThatAlreadyHaveTerminals() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID open = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        AuditEntry firstAttempt = AdminBypassCommandHandler.attemptAudit(actor, false, NOW, first, 0);
        AuditEntry firstTerminal = AdminBypassCommandHandler.committedAudit(
                actor, false, true, NOW, first, 0, true);
        AuditEntry secondAttempt = AdminBypassCommandHandler.attemptAudit(actor, false, NOW, second, 0);
        AuditEntry secondAbort = AdminBypassCommandHandler.abortAudit(actor, false, NOW, second, 0);
        AuditEntry openAttempt = AdminBypassCommandHandler.attemptAudit(actor, false, NOW, open, 0);
        List<AuditEntry> history = new CopyOnWriteArrayList<>(
                List.of(firstAttempt, firstTerminal, secondAttempt, secondAbort, openAttempt));
        AuditRepository repo = repoWithHistory(history);
        AdminBypassRecovery.RecoveryResult result = AdminBypassRecovery.recover(
                () -> repo, () -> NOW, 500).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(5, result.examined());
        assertEquals(1, result.openFound());
        assertEquals(1, result.abortedWritten());
        assertEquals(List.of(open), result.abortedAttemptIds());
        assertTrue(result.failedAttemptIds().isEmpty());
        assertEquals(6, history.size());
        assertTrue(AdminBypassCommandHandler.hasAbortFor(open, history));
        assertFalse(AdminBypassRecovery.isSuccessfulOn(open, history));
        assertTrue(AdminBypassRecovery.isSuccessfulOn(first, history));
    }

    @Test
    void recoveryAbortFailureStaysRetryable() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID open = UUID.randomUUID();
        AuditEntry openAttempt = AdminBypassCommandHandler.attemptAudit(actor, false, NOW, open, 0);
        List<AuditEntry> history = new CopyOnWriteArrayList<>(List.of(openAttempt));
        AtomicInteger calls = new AtomicInteger();
        AuditRepository flaky = new AuditRepository() {
            @Override public CompletionStage<Long> insert(AuditEntry entry) {
                if (calls.getAndIncrement() == 0) {
                    return CompletableFuture.failedFuture(new IllegalStateException("abort down"));
                }
                history.add(entry);
                return CompletableFuture.completedFuture((long) history.size());
            }

            @Override public CompletionStage<Optional<AuditEntry>> findById(long id) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override public CompletionStage<List<AuditEntry>> findByLand(LandId landId, int limit, int offset) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override public CompletionStage<List<AuditEntry>> findByAction(String action, int limit) {
                return CompletableFuture.completedFuture(List.copyOf(history));
            }

            @Override public CompletionStage<List<AuditEntry>> findAll(int limit, int offset) {
                return CompletableFuture.completedFuture(List.copyOf(history));
            }

            @Override public CompletionStage<List<AuditEntry>> search(AuditSearchQuery query) {
                List<AuditEntry> matching = new ArrayList<>();
                for (AuditEntry e : history) {
                    if (query.action() == null || query.action().equals(e.action())) {
                        matching.add(e);
                    }
                }
                matching.sort((a, b) -> {
                    int c = b.timestamp().compareTo(a.timestamp());
                    if (c != 0) {
                        return c;
                    }
                    return Long.compare(b.id(), a.id());
                });
                int from = Math.min(query.offset(), matching.size());
                int to = Math.min(from + query.limit(), matching.size());
                return CompletableFuture.completedFuture(List.copyOf(matching.subList(from, to)));
            }

            @Override public CompletionStage<List<String>> explainSearch(AuditSearchQuery query) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override public CompletionStage<Integer> purgeOlderThan(Instant cutoff) {
                return CompletableFuture.completedFuture(0);
            }
        };

        AdminBypassRecovery.RecoveryResult first = AdminBypassRecovery.recover(
                () -> flaky, () -> NOW, 500).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, first.openFound());
        assertEquals(0, first.abortedWritten());
        assertEquals(List.of(open), first.failedAttemptIds());
        assertFalse(AdminBypassRecovery.isSuccessfulOn(open, history));

        AdminBypassRecovery.RecoveryResult second = AdminBypassRecovery.recover(
                () -> flaky, () -> NOW, 500).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, second.abortedWritten());
        assertTrue(second.failedAttemptIds().isEmpty());
        assertTrue(AdminBypassCommandHandler.hasAbortFor(open, history));
    }

    private static AuditRepository repoWithHistory(List<AuditEntry> history) {
        return new AuditRepository() {
            @Override public CompletionStage<Long> insert(AuditEntry entry) {
                history.add(entry);
                return CompletableFuture.completedFuture((long) history.size());
            }

            @Override public CompletionStage<Optional<AuditEntry>> findById(long id) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override public CompletionStage<List<AuditEntry>> findByLand(LandId landId, int limit, int offset) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override public CompletionStage<List<AuditEntry>> findByAction(String action, int limit) {
                return CompletableFuture.completedFuture(List.copyOf(history));
            }

            @Override public CompletionStage<List<AuditEntry>> findAll(int limit, int offset) {
                return CompletableFuture.completedFuture(List.copyOf(history));
            }

            @Override public CompletionStage<List<AuditEntry>> search(AuditSearchQuery query) {
                List<AuditEntry> matching = new ArrayList<>();
                for (AuditEntry e : history) {
                    if (query.action() == null || query.action().equals(e.action())) {
                        matching.add(e);
                    }
                }
                matching.sort((a, b) -> {
                    int c = b.timestamp().compareTo(a.timestamp());
                    if (c != 0) {
                        return c;
                    }
                    return Long.compare(b.id(), a.id());
                });
                int from = Math.min(query.offset(), matching.size());
                int to = Math.min(from + query.limit(), matching.size());
                return CompletableFuture.completedFuture(List.copyOf(matching.subList(from, to)));
            }

            @Override public CompletionStage<List<String>> explainSearch(AuditSearchQuery query) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override public CompletionStage<Integer> purgeOlderThan(Instant cutoff) {
                return CompletableFuture.completedFuture(0);
            }
        };
    }
}

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
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Process-generation isolation and full paged recovery for the bypass toggle.
 *
 * <p>Each enable owns a unique process id carried by every new attempt and
 * terminal row. Recovery aborts only previous-generation opens, never the
 * recovering enable itself. Toggles wait for the recovery gate: while the
 * gate is pending or failed every toggle fail-closes without audit or
 * state change. Recovery scans the full history with stable timestamp and
 * id pages, collecting before compensating so new abort rows cannot shift
 * offsets.
 */
class AdminBypassRecoveryIsolationTest {

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

    /** Audit double with stable search paging and attempt-only gates. */
    private static final class DeferredAudits implements AuditRepository {
        final List<AuditEntry> inserted = new CopyOnWriteArrayList<>();
        final ConcurrentLinkedQueue<CompletableFuture<Long>> gates = new ConcurrentLinkedQueue<>();
        final AtomicInteger ids = new AtomicInteger();
        final AtomicLong rowIds = new AtomicLong();
        volatile CompletableFuture<List<AuditEntry>> searchGate;

        @Override public CompletionStage<Long> insert(AuditEntry entry) {
            AuditEntry stored = new AuditEntry(rowIds.incrementAndGet(), entry.timestamp(), entry.actor(),
                    entry.action(), entry.landId(), entry.worldId(), entry.singleChunkPacked(),
                    entry.metadataVersion(), entry.beforeJson(), entry.afterJson(),
                    entry.metadataJson(), entry.chunks());
            inserted.add(stored);
            boolean attempt = stored.metadataJson() != null
                    && stored.metadataJson().contains("\"outcome\":\"attempt\"");
            if (!attempt) {
                return CompletableFuture.completedFuture(stored.id());
            }
            CompletableFuture<Long> gate = gates.poll();
            if (gate == null) {
                return CompletableFuture.completedFuture(stored.id());
            }
            return gate.thenApply(ignored -> stored.id());
        }

        @Override public CompletionStage<Optional<AuditEntry>> findById(long id) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override public CompletionStage<List<AuditEntry>> findByLand(LandId landId, int limit, int offset) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override public CompletionStage<List<AuditEntry>> findByAction(String action, int limit) {
            List<AuditEntry> matching = new ArrayList<>();
            for (AuditEntry e : inserted) {
                if (action.equals(e.action())) {
                    matching.add(e);
                }
            }
            int from = Math.max(0, matching.size() - Math.max(0, limit));
            return CompletableFuture.completedFuture(List.copyOf(matching.subList(from, matching.size())));
        }

        @Override public CompletionStage<List<AuditEntry>> findAll(int limit, int offset) {
            return CompletableFuture.completedFuture(List.copyOf(inserted));
        }

        @Override public CompletionStage<List<AuditEntry>> search(AuditSearchQuery query) {
            CompletableFuture<List<AuditEntry>> gate = searchGate;
            if (gate != null) {
                return gate.thenApply(ignored -> searchPage(query));
            }
            return CompletableFuture.completedFuture(searchPage(query));
        }

        private List<AuditEntry> searchPage(AuditSearchQuery query) {
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
            return List.copyOf(matching.subList(from, to));
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
    void delayedRecoveryNeverAbortsCurrentAttemptAndOutcomeStaysUnique() throws Exception {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        UUID process = UUID.randomUUID();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct(), process, gate);
        CompletableFuture<Long> attemptGate = new CompletableFuture<>();
        audits.gates.add(attemptGate);
        CapturingSink sink = new CapturingSink();

        // Toggle starts while recovery is still pending: it must fail-closed
        // without writing, so the delayed scan can never see it.
        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
        assertTrue(audits.inserted.isEmpty(), "gated toggle must not write audit");
        assertFalse(states.isOn(actor));

        // Recovery over an empty history completes and opens the gate.
        var recovery = AdminBypassRecovery.recover(() -> audits, () -> NOW, 100, process);
        var result = recovery.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(0, result.openFound());
        gate.complete(null);

        // Now the same enable toggles normally with a unique outcome.
        CapturingSink retrySink = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, retrySink);
        attemptGate.complete(1L);
        assertTrue(states.isOn(actor));
        assertEquals("command.land.bypass.on", retrySink.replies.get(0).key());
        for (AuditEntry row : audits.inserted) {
            Optional<UUID> id = AdminBypassCommandHandler.attemptIdOf(row);
            if (id.isEmpty()) {
                continue;
            }
            assertFalse(AdminBypassCommandHandler.hasAbortFor(id.get(), audits.inserted)
                    && AdminBypassCommandHandler.hasCommittedFor(id.get(), audits.inserted),
                    "no attempt may carry both aborted and committed terminals");
        }
    }

    @Test
    void toggleDuringIncompleteRecoveryFailClosesAndSucceedsAfterGateOpens() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        UUID process = UUID.randomUUID();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct(), process, gate);

        CapturingSink blockedSink = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, blockedSink);
        assertEquals(1, blockedSink.replies.size());
        assertEquals("command.land.bypass.failed", blockedSink.replies.get(0).key());
        assertEquals("bypass.unavailable", blockedSink.replies.get(0).vars().get("reason"));
        assertTrue(audits.inserted.isEmpty());
        assertFalse(states.isOn(actor));

        gate.complete(null);
        CapturingSink retrySink = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, retrySink);
        assertEquals("command.land.bypass.on", retrySink.replies.get(0).key());
        assertTrue(states.isOn(actor));
        assertEquals(2, audits.inserted.size());
    }

    @Test
    void recoveryFailureKeepsGateClosedForRetry() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        UUID process = UUID.randomUUID();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct(), process, gate);

        gate.completeExceptionally(new IllegalStateException("recovery down"));
        CapturingSink sink = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
        assertTrue(audits.inserted.isEmpty(), "failed gate must not write audit");
        assertFalse(states.isOn(actor));
    }

    @Test
    void deepOpenBeyondOldPrefixAndTimestampTieIsFoundOnce() throws Exception {
        DeferredAudits audits = new DeferredAudits();
        UUID actor = UUID.randomUUID();
        // 300 committed pairs (600 rows) plus one deep open, all same timestamp
        // to tie the stable sort at page boundaries.
        for (int i = 0; i < 300; i++) {
            UUID id = UUID.randomUUID();
            audits.inserted.add(AdminBypassCommandHandler.attemptAudit(actor, false, NOW, id, 0));
            audits.inserted.add(AdminBypassCommandHandler.committedAudit(
                    actor, false, true, NOW, id, 0, true));
        }
        UUID deepOpen = UUID.randomUUID();
        AuditEntry openAttempt = AdminBypassCommandHandler.attemptAudit(actor, false, NOW, deepOpen, 0);
        audits.inserted.add(0, openAttempt);
        // Give every row a stable id for timestamp-tie ordering.
        List<AuditEntry> rekeyed = new ArrayList<>();
        long rowId = 1;
        for (AuditEntry e : audits.inserted) {
            rekeyed.add(new AuditEntry(rowId++, e.timestamp(), e.actor(), e.action(), e.landId(),
                    e.worldId(), e.singleChunkPacked(), e.metadataVersion(), e.beforeJson(),
                    e.afterJson(), e.metadataJson(), e.chunks()));
        }
        audits.inserted.clear();
        audits.inserted.addAll(rekeyed);

        var result = AdminBypassRecovery.recover(
                () -> audits, () -> NOW, 100, UUID.randomUUID())
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(1, result.openFound());
        assertEquals(1, result.abortedWritten());
        assertEquals(List.of(deepOpen), result.abortedAttemptIds());
        assertTrue(result.failedAttemptIds().isEmpty());
        assertTrue(AdminBypassCommandHandler.hasAbortFor(deepOpen, audits.inserted));
        assertFalse(AdminBypassRecovery.isSuccessfulOn(deepOpen, audits.inserted));
        long abortsForOpen = audits.inserted.stream()
                .filter(AdminBypassCommandHandler::isAbortRow)
                .filter(e -> AdminBypassCommandHandler.attemptIdOf(e)
                        .map(deepOpen::equals).orElse(false))
                .count();
        assertEquals(1, abortsForOpen, "deep open must get exactly one abort terminal");
    }

    @Test
    void recoverySkipsCurrentProcessWrites() throws Exception {
        DeferredAudits audits = new DeferredAudits();
        UUID actor = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        UUID currentOpen = UUID.randomUUID();
        UUID oldOpen = UUID.randomUUID();
        audits.inserted.add(AdminBypassCommandHandler.attemptAudit(
                actor, false, NOW, currentOpen, 0, current));
        audits.inserted.add(AdminBypassCommandHandler.attemptAudit(actor, false, NOW, oldOpen, 0));

        var result = AdminBypassRecovery.recover(
                () -> audits, () -> NOW, 100, current)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(1, result.openFound());
        assertEquals(List.of(oldOpen), result.abortedAttemptIds());
        assertFalse(AdminBypassCommandHandler.hasAbortFor(currentOpen, audits.inserted));
        assertTrue(AdminBypassCommandHandler.hasAbortFor(oldOpen, audits.inserted));
    }
}

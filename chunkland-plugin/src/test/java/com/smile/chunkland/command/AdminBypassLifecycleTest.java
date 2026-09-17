package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle quiesce for the bypass toggle.
 *
 * <p>Each enable owns one shared close token. Disable closes it before
 * clearing the state, so late async callbacks stop before any audit
 * insert, terminal write or state mutation. Old recoveries stop the same
 * way, and a null page fails the stage so the gate stays closed.
 */
class AdminBypassLifecycleTest {

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

        @Override public CompletionStage<Long> insert(AuditEntry entry) {
            AuditEntry stored = new AuditEntry(ids.incrementAndGet(), entry.timestamp(), entry.actor(),
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
    void lateOnCallbackAfterCloseWritesNothingAndChangesNoState() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        UUID process = UUID.randomUUID();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        gate.complete(null);
        AdminBypassLifecycle lifecycle = new AdminBypassLifecycle();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct(),
                process, gate, lifecycle);
        CompletableFuture<Long> attemptGate = new CompletableFuture<>();
        audits.gates.add(attemptGate);
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
        assertEquals(1, audits.inserted.size(), "the attempt row is durable before disable");
        // Disable: close the generation first, then clear the memory.
        lifecycle.close();
        states.clear();
        attemptGate.complete(1L);

        assertEquals(1, audits.inserted.size(),
                "the late callback must not append a terminal or abort row");
        assertFalse(states.isOn(actor));
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
        assertEquals(0, states.reservationCount(), "no pending slot may linger");
    }

    @Test
    void lateOffCallbackAfterCloseChangesNoState() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        states.setEnabled(actor, true);
        DeferredAudits audits = new DeferredAudits();
        UUID process = UUID.randomUUID();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        gate.complete(null);
        AdminBypassLifecycle lifecycle = new AdminBypassLifecycle();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct(),
                process, gate, lifecycle);
        CompletableFuture<Long> attemptGate = new CompletableFuture<>();
        audits.gates.add(attemptGate);
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "off"}, sink);
        assertEquals(1, audits.inserted.size());
        lifecycle.close();
        attemptGate.complete(1L);

        assertEquals(1, audits.inserted.size(), "the late off callback must not append a terminal");
        assertTrue(states.isOn(actor), "the late off callback must not flip cleared state");
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
    }

    @Test
    void queuedToggleStartingAfterCloseDoesNotAdmitOrAudit() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        UUID process = UUID.randomUUID();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        gate.complete(null);
        AdminBypassLifecycle lifecycle = new AdminBypassLifecycle();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct(),
                process, gate, lifecycle);
        CompletableFuture<Long> firstGate = new CompletableFuture<>();
        audits.gates.add(firstGate);
        CapturingSink firstSink = new CapturingSink();
        CapturingSink secondSink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, firstSink);
        handler.handle(player(actor), new String[] {"bypass", "on"}, secondSink);
        assertEquals(1, audits.inserted.size(), "the second toggle must stay queued");
        // Disable while the first attempt is still deferred.
        lifecycle.close();
        states.clear();
        firstGate.complete(1L);

        assertEquals(1, audits.inserted.size(),
                "the queued toggle must not admit or write audit after close");
        assertEquals(0, states.reservationCount(), "no pending slot may be admitted after close");
        assertFalse(states.isOn(actor));
        assertEquals(1, firstSink.replies.size());
        assertEquals("command.land.bypass.failed", firstSink.replies.get(0).key());
        assertEquals(1, secondSink.replies.size(), "the queue must settle, not stall");
        assertEquals("command.land.bypass.failed", secondSink.replies.get(0).key());
        assertEquals("bypass.unavailable", secondSink.replies.get(0).vars().get("reason"));
    }

    @Test
    void closeDuringReadsSkipsInsertInitiation() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        AdminBypassLifecycle lifecycle = new AdminBypassLifecycle();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        gate.complete(null);
        // The audits supplier closes the generation mid-initiation, between
        // the admission check and the audit insert call.
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> {
                    lifecycle.close();
                    return audits;
                },
                () -> NOW, states, PlayerScheduler.direct(),
                UUID.randomUUID(), gate, lifecycle);
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
        // Mimic disable clearing the memory after the close.
        states.clear();

        assertTrue(audits.inserted.isEmpty(),
                "an insert initiation racing close must not write audit rows");
        assertFalse(states.isOn(actor));
        assertEquals(0, states.reservationCount());
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
        assertEquals("bypass.unavailable", sink.replies.get(0).vars().get("reason"));
    }

    @Test
    void recoveryAbortCloseRaceStopsWithoutCountingSuccess() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<AuditEntry> history = new CopyOnWriteArrayList<>(List.of(
                AdminBypassCommandHandler.attemptAudit(actor, false, NOW, first, 0),
                AdminBypassCommandHandler.attemptAudit(actor, false, NOW, second, 0)));
        AdminBypassLifecycle lifecycle = new AdminBypassLifecycle();
        CompletableFuture<List<AuditEntry>> searchGate = new CompletableFuture<>();
        searchGate.complete(List.of());
        AuditRepository repo = searchingRepo(history, searchGate);
        AtomicInteger inserts = new AtomicInteger();
        AuditRepository closingRepo = new AuditRepository() {
            @Override public CompletionStage<Long> insert(AuditEntry entry) {
                // Close lands inside the second abort initiation: the entry
                // check already passed, so only an atomic boundary can stop it.
                if (inserts.incrementAndGet() == 2) {
                    lifecycle.close();
                }
                history.add(entry);
                return CompletableFuture.completedFuture((long) history.size());
            }

            @Override public CompletionStage<Optional<AuditEntry>> findById(long id) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override public CompletionStage<List<AuditEntry>> findByLand(LandId landId, int limit, int offset) {
                return repo.findByLand(landId, limit, offset);
            }

            @Override public CompletionStage<List<AuditEntry>> findByAction(String action, int limit) {
                return repo.findByAction(action, limit);
            }

            @Override public CompletionStage<List<AuditEntry>> findAll(int limit, int offset) {
                return repo.findAll(limit, offset);
            }

            @Override public CompletionStage<List<AuditEntry>> search(AuditSearchQuery query) {
                return repo.search(query);
            }

            @Override public CompletionStage<List<String>> explainSearch(AuditSearchQuery query) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override public CompletionStage<Integer> purgeOlderThan(Instant cutoff) {
                return CompletableFuture.completedFuture(0);
            }
        };

        var stage = AdminBypassRecovery.recover(
                () -> closingRepo, () -> NOW, 100, null, lifecycle);
        try {
            var result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            fail("an abort initiation racing close must stop the recovery, got " + result);
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        assertEquals(2, inserts.get(), "both initiations ran; only the outcome differs");
    }

    @Test
    void initiateBoundaryContract() {
        AdminBypassLifecycle lifecycle = new AdminBypassLifecycle();
        AtomicBoolean ran = new AtomicBoolean(false);
        assertEquals("ok", lifecycle.initiate(() -> {
            ran.set(true);
            return "ok";
        }));
        assertTrue(ran.get(), "open initiation must run");

        lifecycle.close();
        assertTrue(lifecycle.isClosed());
        try {
            lifecycle.initiate(() -> "never");
            fail("closed initiation must throw Closed");
        } catch (AdminBypassLifecycle.Closed expected) {
        }
        lifecycle.close();
        assertTrue(lifecycle.isClosed(), "close stays idempotent");

        AdminBypassLifecycle racing = new AdminBypassLifecycle();
        try {
            racing.initiate(() -> {
                racing.close();
                return "discarded";
            });
            fail("a close landing inside the initiation must discard the result");
        } catch (AdminBypassLifecycle.Closed expected) {
        }
        assertTrue(racing.isClosed());
    }

    @Test
    void toggleAfterCloseFailClosesWithoutAudit() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        AdminBypassLifecycle lifecycle = new AdminBypassLifecycle();
        lifecycle.close();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct(),
                UUID.randomUUID(), completedGate(), lifecycle);
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);

        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
        assertTrue(audits.inserted.isEmpty());
        assertFalse(states.isOn(actor));
    }

    @Test
    void oldRecoveryStopsAfterCloseWithoutWriting() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID open = UUID.randomUUID();
        List<AuditEntry> history = new CopyOnWriteArrayList<>(List.of(
                AdminBypassCommandHandler.attemptAudit(actor, false, NOW, open, 0)));
        CompletableFuture<List<AuditEntry>> pageGate = new CompletableFuture<>();
        AuditRepository repo = searchingRepo(history, pageGate);
        AdminBypassLifecycle lifecycle = new AdminBypassLifecycle();

        var stage = AdminBypassRecovery.recover(() -> repo, () -> NOW, 100, null, lifecycle);
        lifecycle.close();
        pageGate.complete(List.of());

        try {
            stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            fail("a closed recovery must fail so its gate never opens");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        assertEquals(1, history.size(), "the stopped recovery must not append compensation rows");
    }

    @Test
    void nullPageFailsRecoverySoGateStaysClosed() {
        AuditRepository repo = new AuditRepository() {
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
                return CompletableFuture.completedFuture(List.copyOf(List.of()));
            }

            @Override public CompletionStage<List<AuditEntry>> search(AuditSearchQuery query) {
                return CompletableFuture.completedFuture(null);
            }

            @Override public CompletionStage<List<String>> explainSearch(AuditSearchQuery query) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override public CompletionStage<Integer> purgeOlderThan(Instant cutoff) {
                return CompletableFuture.completedFuture(0);
            }
        };
        var stage = AdminBypassRecovery.recover(
                () -> repo, () -> NOW, 100, UUID.randomUUID(), new AdminBypassLifecycle());
        try {
            stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            fail("a null page must fail the recovery stage so the gate stays closed");
        } catch (Exception expected) {
            assertTrue(expected instanceof java.util.concurrent.ExecutionException);
        }
    }

    @Test
    void newRecoveryGateOpensOnlyAfterNewScanCompletes() throws Exception {
        DeferredAudits audits = new DeferredAudits();
        UUID actor = UUID.randomUUID();
        UUID oldOpen = UUID.randomUUID();
        audits.inserted.add(AdminBypassCommandHandler.attemptAudit(actor, false, NOW, oldOpen, 0));
        UUID process = UUID.randomUUID();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AdminBypassLifecycle lifecycle = new AdminBypassLifecycle();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, new AdminBypassState(), PlayerScheduler.direct(),
                process, gate, lifecycle);

        // Gate pending: new toggles fail-closed without audit rows.
        CapturingSink blocked = new CapturingSink();
        handler.handle(player(UUID.randomUUID()), new String[] {"bypass", "on"}, blocked);
        assertEquals("command.land.bypass.failed", blocked.replies.get(0).key());
        assertEquals(1, audits.inserted.size(), "gated toggles must not write");

        var recovery = AdminBypassRecovery.recover(
                () -> audits, () -> NOW, 100, process, lifecycle);
        var result = recovery.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, result.openFound());
        gate.complete(null);

        CapturingSink retry = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, retry);
        assertEquals("command.land.bypass.on", retry.replies.get(0).key());
    }

    private static CompletableFuture<Void> completedGate() {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        gate.complete(null);
        return gate;
    }

    private static AuditRepository searchingRepo(
            List<AuditEntry> history, CompletableFuture<List<AuditEntry>> pageGate) {
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
                return pageGate.thenApply(ignored -> {
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
                    return List.copyOf(matching.subList(from, to));
                });
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

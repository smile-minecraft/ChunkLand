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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Race regression for the bypass toggle path.
 *
 * <p>The capacity bound must hold across distinct actors racing at the cap,
 * and one actor's toggles must complete audit-then-state in enqueue order:
 * the second audit may not start before the first completes, and its
 * {@code before} edge must reflect the first toggle's outcome. A failed
 * audit changes no state and never stalls the same actor's queue, while
 * different actors stay independent.
 */
class AdminBypassConcurrencyTest {

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

    /** Audit double with caller-controlled completion per insert, in order. */
    private static final class DeferredAudits implements AuditRepository {
        final List<AuditEntry> inserted = new CopyOnWriteArrayList<>();
        final List<Boolean> stateAtInsert = new CopyOnWriteArrayList<>();
        final ConcurrentLinkedQueue<CompletableFuture<Long>> gates = new ConcurrentLinkedQueue<>();
        final AdminBypassState states;
        final AtomicInteger ids = new AtomicInteger();
        volatile boolean returnNull = false;
        volatile boolean throwInline = false;

        DeferredAudits(AdminBypassState states) {
            this.states = states;
        }

        @Override public CompletionStage<Long> insert(AuditEntry entry) {
            if (throwInline) {
                throw new IllegalStateException("audit store down");
            }
            if (returnNull) {
                inserted.add(entry);
                return null;
            }
            inserted.add(entry);
            stateAtInsert.add(states.isOn(entry.actor()));
            boolean attempt = entry.metadataJson() != null
                    && entry.metadataJson().contains("\"outcome\":\"attempt\"");
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
            return CompletableFuture.completedFuture(List.of());
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
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        return null;
    }

    @Test
    void capacityRaceAcrossDistinctActorsStaysBounded() throws Exception {
        AdminBypassState states = new AdminBypassState();
        for (int i = 0; i < AdminBypassState.MAX_ACTORS - 1; i++) {
            assertTrue(states.setEnabled(UUID.randomUUID(), true), "prefill slot " + i);
        }
        assertEquals(AdminBypassState.MAX_ACTORS - 1, states.size());
        int racers = 64;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(racers);
        AtomicInteger winners = new AtomicInteger();
        try {
            for (int i = 0; i < racers; i++) {
                UUID contender = UUID.randomUUID();
                pool.submit(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        if (states.setEnabled(contender, true)) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "all racers must finish");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, winners.get(), "exactly one racer may take the last slot");
        assertEquals(AdminBypassState.MAX_ACTORS, states.size(),
                "distinct actors racing at the cap must never exceed the bound");
    }

    @Test
    void sameActorSecondAuditWaitsForFirstCompletionAndSeesItsOutcome() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits(states);
        CompletableFuture<Long> first = new CompletableFuture<>();
        CompletableFuture<Long> second = new CompletableFuture<>();
        audits.gates.add(first);
        audits.gates.add(second);
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
        handler.handle(player(actor), new String[] {"bypass", "off"}, sink);

        assertEquals(1, audits.inserted.size(),
                "the second audit must not start before the first completes");
        assertEquals("true", audits.inserted.get(0).afterJson());

        first.complete(1L);
        assertEquals(3, audits.inserted.size());
        assertTrue(AdminBypassCommandHandler.isAttemptRow(audits.inserted.get(0)));
        assertTrue(AdminBypassCommandHandler.isCommittedRow(audits.inserted.get(1)));
        assertEquals("true", audits.inserted.get(2).beforeJson(),
                "the second before edge must reflect the first toggle's success");
        assertEquals("false", audits.inserted.get(2).afterJson());
        assertTrue(states.isOn(actor), "the first toggle lands before the second runs");

        second.complete(2L);
        assertEquals(4, audits.inserted.size());
        assertTrue(AdminBypassCommandHandler.isCommittedRow(audits.inserted.get(3)));
        assertFalse(states.isOn(actor));
        assertEquals(2, sink.replies.size());
        assertEquals("command.land.bypass.on", sink.replies.get(0).key());
        assertEquals("command.land.bypass.off", sink.replies.get(1).key());
    }

    @Test
    void auditFailureChangesNoStateAndDoesNotStallTheSameActorQueue() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits(states);
        CompletableFuture<Long> first = new CompletableFuture<>();
        CompletableFuture<Long> second = new CompletableFuture<>();
        audits.gates.add(first);
        audits.gates.add(second);
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
        handler.handle(player(actor), new String[] {"bypass", "off"}, sink);
        assertEquals(1, audits.inserted.size());

        first.completeExceptionally(new IllegalStateException("audit write failed"));
        assertEquals(2, audits.inserted.size(),
                "the queue must continue after a failed audit");
        assertEquals("false", audits.inserted.get(1).beforeJson(),
                "the failed toggle must leave the state untouched");
        assertFalse(states.isOn(actor));

        second.complete(2L);
        assertFalse(states.isOn(actor), "off after a failed on stays off");
        assertEquals(2, sink.replies.size());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
        assertEquals("command.land.bypass.off", sink.replies.get(1).key());
    }

    @Test
    void differentActorsAreNotSerializedBehindEachOther() {
        AdminBypassState states = new AdminBypassState();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits(states);
        CompletableFuture<Long> gateA = new CompletableFuture<>();
        CompletableFuture<Long> gateB = new CompletableFuture<>();
        audits.gates.add(gateA);
        audits.gates.add(gateB);
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());

        handler.handle(player(first), new String[] {"bypass", "on"}, new CapturingSink());
        handler.handle(player(second), new String[] {"bypass", "on"}, new CapturingSink());

        assertEquals(2, audits.inserted.size(),
                "a pending audit for one actor must not block another actor's toggle");

        gateA.complete(1L);
        gateB.complete(2L);
        assertTrue(states.isOn(first));
        assertTrue(states.isOn(second));
    }

    @Test
    void clearAfterRacesRestoresDefaultOff() throws Exception {
        AdminBypassState states = new AdminBypassState();
        int writers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        try {
            for (int i = 0; i < writers; i++) {
                UUID actor = UUID.randomUUID();
                pool.submit(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        for (int j = 0; j < 25; j++) {
                            states.setEnabled(actor, true);
                            states.setEnabled(actor, false);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "all writers must finish");
        } finally {
            pool.shutdownNow();
        }
        states.clear();
        assertEquals(0, states.size());
        assertFalse(states.isOn(UUID.randomUUID()));
    }

    @Test
    void secondActorWritesNoPhantomAuditWhenOneSlotRemains() {
        AdminBypassState states = new AdminBypassState();
        for (int i = 0; i < AdminBypassState.MAX_ACTORS - 1; i++) {
            assertTrue(states.setEnabled(UUID.randomUUID(), true), "prefill slot " + i);
        }
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits(states);
        CompletableFuture<Long> gateA = new CompletableFuture<>();
        audits.gates.add(gateA);
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink firstSink = new CapturingSink();
        CapturingSink secondSink = new CapturingSink();

        handler.handle(player(first), new String[] {"bypass", "on"}, firstSink);
        handler.handle(player(second), new String[] {"bypass", "on"}, secondSink);

        assertEquals(1, audits.inserted.size(),
                "the refused toggle must not write a phantom audit");
        assertEquals(1, secondSink.replies.size());
        assertEquals("command.land.bypass.failed", secondSink.replies.get(0).key());
        assertEquals("bypass.unavailable", secondSink.replies.get(0).vars().get("reason"));
        assertFalse(states.isOn(second));

        gateA.complete(1L);
        assertTrue(states.isOn(first));
        assertEquals(1, firstSink.replies.size());
        assertEquals("command.land.bypass.on", firstSink.replies.get(0).key());
        assertEquals(AdminBypassState.MAX_ACTORS, states.size());
    }

    @Test
    void onWhileFullWritesNoAudit() {
        AdminBypassState states = new AdminBypassState();
        for (int i = 0; i < AdminBypassState.MAX_ACTORS; i++) {
            assertTrue(states.setEnabled(UUID.randomUUID(), true), "prefill slot " + i);
        }
        DeferredAudits audits = new DeferredAudits(states);
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink sink = new CapturingSink();

        handler.handle(player(UUID.randomUUID()), new String[] {"bypass", "on"}, sink);

        assertTrue(audits.inserted.isEmpty(), "a full memory must refuse before any audit");
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
        assertEquals(AdminBypassState.MAX_ACTORS, states.size());
    }

    @Test
    void failedReservationReleasesCapacityForLaterToggles() {
        AdminBypassState states = new AdminBypassState();
        for (int i = 0; i < AdminBypassState.MAX_ACTORS - 1; i++) {
            assertTrue(states.setEnabled(UUID.randomUUID(), true), "prefill slot " + i);
        }
        UUID first = UUID.randomUUID();
        UUID latecomer = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits(states);
        CompletableFuture<Long> gateA = new CompletableFuture<>();
        audits.gates.add(gateA);
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink firstSink = new CapturingSink();

        handler.handle(player(first), new String[] {"bypass", "on"}, firstSink);
        assertEquals(1, audits.inserted.size());

        gateA.completeExceptionally(new IllegalStateException("audit write failed"));
        assertEquals(1, firstSink.replies.size());
        assertEquals("command.land.bypass.failed", firstSink.replies.get(0).key());
        assertFalse(states.isOn(first));

        CapturingSink lateSink = new CapturingSink();
        handler.handle(player(latecomer), new String[] {"bypass", "on"}, lateSink);
        assertEquals(3, audits.inserted.size(),
                "the released reservation must admit a later toggle");
        assertTrue(AdminBypassCommandHandler.isCommittedRow(audits.inserted.get(2)));
        assertTrue(states.isOn(latecomer));
        assertEquals("command.land.bypass.on", lateSink.replies.get(0).key());
    }

    @Test
    void nullAuditStageReleasesReservationAndFailsClosed() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits(states);
        audits.returnNull = true;
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);

        assertEquals(1, audits.inserted.size());
        assertFalse(states.isOn(actor));
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());

        audits.returnNull = false;
        CapturingSink retrySink = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, retrySink);
        assertEquals("command.land.bypass.on", retrySink.replies.get(0).key());
        assertTrue(states.isOn(actor));
    }

    @Test
    void throwingAuditInsertReleasesReservationAndFailsClosed() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits(states);
        audits.throwInline = true;
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);

        assertTrue(audits.inserted.isEmpty());
        assertFalse(states.isOn(actor));
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());

        audits.throwInline = false;
        CapturingSink retrySink = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, retrySink);
        assertEquals("command.land.bypass.on", retrySink.replies.get(0).key());
        assertTrue(states.isOn(actor));
    }

    @Test
    void offCompletesWhileFullAndReservationsPending() {
        AdminBypassState states = new AdminBypassState();
        UUID leaver = UUID.randomUUID();
        assertTrue(states.setEnabled(leaver, true));
        for (int i = 0; i < AdminBypassState.MAX_ACTORS - 2; i++) {
            assertTrue(states.setEnabled(UUID.randomUUID(), true), "prefill slot " + i);
        }
        UUID waiter = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits(states);
        CompletableFuture<Long> gate = new CompletableFuture<>();
        audits.gates.add(gate);
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());

        handler.handle(player(waiter), new String[] {"bypass", "on"}, new CapturingSink());
        assertEquals(1, audits.inserted.size(), "the waiter holds the last reservation");

        CapturingSink offSink = new CapturingSink();
        handler.handle(player(leaver), new String[] {"bypass", "off"}, offSink);
        assertEquals("command.land.bypass.off", offSink.replies.get(0).key(),
                "off must never be blocked by enable capacity");
        assertFalse(states.isOn(leaver));

        gate.complete(1L);
        assertTrue(states.isOn(waiter), "the freed slot commits to the waiter");
    }

    @Test
    void clearDuringPendingAuditNeverReEnables() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits(states);
        CompletableFuture<Long> gate = new CompletableFuture<>();
        audits.gates.add(gate);
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
        assertEquals(1, audits.inserted.size());

        states.clear();

        gate.complete(1L);
        assertFalse(states.isOn(actor),
                "a pending audit settling after disable must not re-enable bypass");
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());

        CompletableFuture<Long> fresh = new CompletableFuture<>();
        audits.gates.add(fresh);
        CapturingSink freshSink = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, freshSink);
        fresh.complete(2L);
        assertTrue(states.isOn(actor), "the new generation toggles normally");
        assertEquals("command.land.bypass.on", freshSink.replies.get(0).key());
    }
}

package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Token-bound reservation regressions for the enable-generation race.
 *
 * <p>Admission binds one actor to the actual generation atomically; commit
 * and release only accept a token that still matches the pending map for
 * the current generation. A disable racing the in-flight audit, or a
 * legacy cancel racing the commit, therefore fails closed without leaving
 * a new-generation orphan or re-enabling bypass.
 */
class AdminBypassTokenRaceTest {

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
        final java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<Long>> gates =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.concurrent.atomic.AtomicInteger ids = new java.util.concurrent.atomic.AtomicInteger();

        @Override public CompletionStage<Long> insert(AuditEntry entry) {
            inserted.add(entry);
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
    void admissionBindsActualEpochAcrossBarrierForcedClear() throws Exception {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        CyclicBarrier admitted = new CyclicBarrier(2);
        CountDownLatch clearDone = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AdminBypassState.Admission> held = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread toggler = new Thread(() -> {
            try {
                AdminBypassState.Admission admission = states.admit(actor);
                held.set(admission);
                admitted.await(5, TimeUnit.SECONDS);
                clearDone.await(5, TimeUnit.SECONDS);
                done.countDown();
            } catch (Throwable t) {
                failure.set(t);
                done.countDown();
            }
        });
        toggler.start();
        admitted.await(5, TimeUnit.SECONDS);
        long epochAtAdmit = held.get().epoch();
        states.clear();
        clearDone.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "toggler must finish");
        toggler.join(5000);
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(epochAtAdmit, held.get().reservation().epoch(),
                "the token must carry the actual generation observed at admission");
        assertEquals(0, states.reservationCount(),
                "a clear after admission must wipe the pending slot, leaving no orphan");
        assertFalse(states.isOn(actor));

        AdminBypassState.Admission fresh = states.admit(actor);
        assertNotEquals(epochAtAdmit, fresh.epoch(),
                "the next generation must advance the epoch");
        assertEquals(1, states.reservationCount());
        states.release(held.get().reservation());
        assertEquals(1, states.reservationCount(),
                "releasing the stale token must preserve the new generation slot");
        assertTrue(states.commit(fresh.reservation()));
        assertTrue(states.isOn(actor));
    }

    @Test
    void staleTokenCommitAfterLegacyCancelMustStayOff() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        AdminBypassState.Admission admission = states.admit(actor);
        assertEquals(AdminBypassState.ReserveOutcome.RESERVED, admission.outcome());

        assertTrue(states.setEnabled(actor, false), "legacy cancel must succeed");
        assertEquals(0, states.reservationCount(), "legacy cancel drops the pending slot");

        assertFalse(states.commit(admission.reservation()),
                "a commit for a cancelled token must fail");
        assertFalse(states.commitReserved(actor, admission.epoch()),
                "the legacy commit path must also fail after the cancel");
        assertFalse(states.isOn(actor), "the stale commit must not re-enable bypass");
        assertEquals(0, states.size());
    }

    @Test
    void staleReleasePreservesNewGenerationSlot() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        AdminBypassState.Admission stale = states.admit(actor);
        states.clear();
        AdminBypassState.Admission fresh = states.admit(actor);

        assertEquals(1, states.reservationCount());
        states.release(stale.reservation());
        assertEquals(1, states.reservationCount(),
                "a stale release must not delete the new generation slot");
        assertFalse(states.commit(stale.reservation()),
                "a stale commit must fail without touching the new slot");
        assertTrue(states.commit(fresh.reservation()));
        assertTrue(states.isOn(actor));
    }

    @Test
    void handlerStaleCommitAfterLegacyCancelMustNotReEnable() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        CompletableFuture<Long> gate = new CompletableFuture<>();
        audits.gates.add(gate);
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
        assertEquals(1, audits.inserted.size());

        assertTrue(states.setEnabled(actor, false), "legacy cancel races the pending audit");
        gate.complete(1L);

        assertFalse(states.isOn(actor), "the stale commit must not re-enable bypass");
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
        assertEquals(0, states.reservationCount(), "the cancelled slot must not linger");

        CapturingSink retrySink = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, retrySink);
        assertEquals("command.land.bypass.on", retrySink.replies.get(0).key());
        assertTrue(states.isOn(actor), "the next generation toggles normally");
    }

    @Test
    void handlerClearDuringPendingLeavesNoOrphanAndAdmitsFreshGeneration() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        CompletableFuture<Long> gate = new CompletableFuture<>();
        audits.gates.add(gate);
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
        assertEquals(1, audits.inserted.size());
        assertEquals(1, states.reservationCount(), "the pending audit holds one slot");

        states.clear();

        gate.complete(1L);
        assertFalse(states.isOn(actor),
                "a pending audit settling after disable must not re-enable bypass");
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
        assertEquals(0, states.reservationCount(),
                "settling after clear must not leave a new-generation orphan");

        CompletableFuture<Long> fresh = new CompletableFuture<>();
        audits.gates.add(fresh);
        CapturingSink freshSink = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, freshSink);
        fresh.complete(2L);
        assertTrue(states.isOn(actor), "the new generation toggles normally");
        assertEquals("command.land.bypass.on", freshSink.replies.get(0).key());
    }
}

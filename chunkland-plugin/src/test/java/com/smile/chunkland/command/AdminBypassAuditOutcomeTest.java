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
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Audit outcome and strict token regressions for the bypass toggle.
 *
 * <p>Every {@code on} attempt writes one attempt row whose {@code after}
 * edge is the requested state; actual success also flips the state and
 * leaves no abort row for the same attempt id. When the commit fails after
 * a successful attempt write, one abort row with the same attempt id and
 * the actual {@code false} edge is appended, so no unmarked
 * {@code after=true} row can be read as success. A failed compensation
 * stays fail-closed and traceable via its attempt id, and the actor's
 * queue still advances.
 */
class AdminBypassAuditOutcomeTest {

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

    /** Audit double with per-insert gates and optional abort failure. */
    private static final class DeferredAudits implements AuditRepository {
        final List<AuditEntry> inserted = new CopyOnWriteArrayList<>();
        final ConcurrentLinkedQueue<CompletableFuture<Long>> gates = new ConcurrentLinkedQueue<>();
        final AtomicInteger ids = new AtomicInteger();
        volatile boolean failAborts = false;
        volatile boolean nullAborts = false;

        @Override public CompletionStage<Long> insert(AuditEntry entry) {
            boolean abort = entry.metadataJson() != null
                    && entry.metadataJson().contains("\"outcome\":\"" + AdminBypassCommandHandler.OUTCOME_ABORTED + "\"");
            if (abort && failAborts) {
                return CompletableFuture.failedFuture(new IllegalStateException("abort store down"));
            }
            if (abort && nullAborts) {
                inserted.add(entry);
                return null;
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
    void successfulOnLeavesAttemptPlusCommittedTerminal() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);

        assertEquals(2, audits.inserted.size(), "success writes attempt plus committed terminal");
        AuditEntry attempt = audits.inserted.get(0);
        assertEquals("ADMIN_BYPASS_TOGGLE", attempt.action());
        assertEquals("false", attempt.beforeJson());
        assertEquals("true", attempt.afterJson());
        assertTrue(attempt.metadataJson().contains("\"outcome\":\"attempt\""),
                "the attempt row must carry its outcome marker");
        assertTrue(AdminBypassCommandHandler.isAttemptRow(attempt));
        assertFalse(AdminBypassCommandHandler.isAbortRow(attempt));
        assertTrue(AdminBypassCommandHandler.attemptIdOf(attempt).isPresent());
        AuditEntry terminal = audits.inserted.get(1);
        assertTrue(AdminBypassCommandHandler.isCommittedRow(terminal));
        assertEquals(AdminBypassCommandHandler.attemptIdOf(attempt),
                AdminBypassCommandHandler.attemptIdOf(terminal));
        assertTrue(AdminBypassCommandHandler.hasCommittedFor(
                AdminBypassCommandHandler.attemptIdOf(attempt).orElseThrow(), audits.inserted));
        assertEquals(0, AdminBypassCommandHandler.countUnmarkedSuccessRows(audits.inserted));
        assertTrue(states.isOn(actor));
        assertEquals("command.land.bypass.on", sink.replies.get(0).key());
    }

    @Test
    void clearAfterAuditLeavesAttemptPlusAbortAndNoUnmarkedSuccess() {
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
        states.clear();
        attemptGate.complete(1L);

        assertFalse(states.isOn(actor), "the aborted commit must not re-enable bypass");
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
        assertEquals("bypass.unavailable", sink.replies.get(0).vars().get("reason"));
        assertEquals(0, states.reservationCount());
        assertEquals(2, audits.inserted.size(), "the abort compensation must be appended");

        AuditEntry attempt = audits.inserted.get(0);
        AuditEntry abort = audits.inserted.get(1);
        assertEquals("true", attempt.afterJson());
        assertTrue(AdminBypassCommandHandler.isAttemptRow(attempt));
        assertTrue(AdminBypassCommandHandler.isAbortRow(abort));
        assertEquals("false", abort.afterJson(), "the abort row carries the actual edge");
        assertEquals(AdminBypassCommandHandler.attemptIdOf(attempt),
                AdminBypassCommandHandler.attemptIdOf(abort),
                "the abort must reference its attempt");
        assertTrue(AdminBypassCommandHandler.hasAbortFor(
                AdminBypassCommandHandler.attemptIdOf(attempt).orElseThrow(), audits.inserted));
        assertEquals(0, AdminBypassCommandHandler.countUnmarkedSuccessRows(audits.inserted),
                "no unmarked after=true row may remain");

        CompletableFuture<Long> freshAttempt = new CompletableFuture<>();
        audits.gates.add(freshAttempt);
        CapturingSink freshSink = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, freshSink);
        freshAttempt.complete(2L);
        assertTrue(states.isOn(actor), "the next generation toggles normally");
        assertEquals("command.land.bypass.on", freshSink.replies.get(0).key());
    }

    @Test
    void abortCompensationFailureStaysFailClosedAndQueueAdvances() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        DeferredAudits audits = new DeferredAudits();
        audits.failAborts = true;
        CompletableFuture<Long> attemptGate = new CompletableFuture<>();
        audits.gates.add(attemptGate);
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct());
        CapturingSink sink = new CapturingSink();

        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
        states.clear();
        attemptGate.complete(1L);

        assertFalse(states.isOn(actor));
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
        assertEquals("bypass.audit_failed", sink.replies.get(0).vars().get("reason"),
                "a failed compensation must stay traceable as an audit failure");
        assertEquals(1, audits.inserted.size(), "a failed abort leaves no row, attempt stays open");
        assertTrue(AdminBypassCommandHandler.attemptIdOf(audits.inserted.get(0)).isPresent(),
                "the pending attempt stays traceable via its attempt id");

        audits.failAborts = false;
        CapturingSink retrySink = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, retrySink);
        assertEquals("command.land.bypass.on", retrySink.replies.get(0).key(),
                "the queue must advance so a retry can succeed");
        assertTrue(states.isOn(actor));
    }

    @Test
    void forgedTokenForCommittedActorMustFailWithoutStateChange() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        AdminBypassState.Admission admission = states.admit(actor);
        assertTrue(states.commit(admission.reservation()));
        assertTrue(states.isOn(actor));
        long epoch = states.epoch();

        AdminBypassState.Reservation forged = new AdminBypassState.Reservation(actor, epoch);
        assertFalse(states.commit(forged),
                "a token that was never admitted must fail even when the actor is on");
        assertFalse(states.commitReserved(actor, epoch),
                "the legacy commit without a pending reservation must fail once strict");
        assertFalse(states.commit(new AdminBypassState.Reservation(UUID.randomUUID(), epoch)),
                "a wrong-actor token must fail");
        assertFalse(states.commit(new AdminBypassState.Reservation(actor, epoch + 1)),
                "a wrong-epoch token must fail");
        assertFalse(states.commit(admission.reservation()),
                "replaying the consumed token must fail once its pending slot is gone");
        assertTrue(states.isOn(actor), "forged commits must not disturb the state");
        assertEquals(1, states.size());
        assertEquals(0, states.reservationCount());
    }

    @Test
    void missingPendingAndLegacyCancelStaleTokenMustFail() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();

        assertFalse(states.commit(new AdminBypassState.Reservation(actor, states.epoch())),
                "a commit with no pending reservation must fail");
        assertFalse(states.commitReserved(actor, states.epoch()),
                "the legacy commit with no pending reservation must fail");

        AdminBypassState.Admission admission = states.admit(actor);
        assertTrue(states.setEnabled(actor, false), "legacy cancel drops the pending slot");
        assertFalse(states.commit(admission.reservation()),
                "a stale token after legacy cancel must fail");
        assertFalse(states.commitReserved(actor, admission.epoch()),
                "the legacy stale commit must fail");
        assertFalse(states.isOn(actor));
    }
}

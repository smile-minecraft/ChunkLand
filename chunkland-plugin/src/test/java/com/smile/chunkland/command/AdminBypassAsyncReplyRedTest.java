package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Production-like regression for the bypass async reply: async audit plus
 * deferred or throwing scheduler, covering on, off, audit failure and the
 * recovery gate with exactly-once replies and unchanged audit/state
 * invariants. Every audited terminal must reply once even when the
 * player-thread hop cannot be scheduled.
 */
class AdminBypassAsyncReplyRedTest {

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

    private static final class AsyncAudits implements AuditRepository {
        final List<AuditEntry> inserted = new CopyOnWriteArrayList<>();
        final AtomicInteger ids = new AtomicInteger();
        final ExecutorService persistence = Executors.newSingleThreadExecutor();

        @Override public CompletionStage<Long> insert(AuditEntry entry) {
            CompletableFuture<Long> result = new CompletableFuture<>();
            persistence.execute(() -> {
                try {
                    AuditEntry stored = new AuditEntry(ids.incrementAndGet(), entry.timestamp(),
                            entry.actor(), entry.action(), entry.landId(), entry.worldId(),
                            entry.singleChunkPacked(), entry.metadataVersion(), entry.beforeJson(),
                            entry.afterJson(), entry.metadataJson(), entry.chunks());
                    inserted.add(stored);
                    result.complete(stored.id());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
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

        void shutdown() {
            persistence.shutdownNow();
        }
    }

    private static final class DeferredScheduler implements PlayerScheduler {
        final List<Runnable> pending = new CopyOnWriteArrayList<>();

        @Override public void runForPlayer(Player player, Runnable task) {
            pending.add(task);
        }

        void drain() {
            List<Runnable> due = List.copyOf(pending);
            pending.clear();
            for (Runnable task : due) {
                task.run();
            }
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

    private static void awaitReplies(CapturingSink sink, int expected, DeferredScheduler scheduler,
            AsyncAudits audits) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            scheduler.drain();
            if (sink.replies.size() >= expected) {
                return;
            }
            Thread.sleep(10);
            scheduler.drain();
        }
    }

    @Test
    void asyncOnWithDeferredSchedulerRepliesOnce() throws Exception {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        AsyncAudits audits = new AsyncAudits();
        DeferredScheduler scheduler = new DeferredScheduler();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, scheduler);
        CapturingSink sink = new CapturingSink();
        try {
            handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
            awaitReplies(sink, 1, scheduler, audits);
            assertEquals(2, audits.inserted.size(), "async on must write attempt plus committed");
            assertTrue(states.isOn(actor));
            assertEquals(1, sink.replies.size(), "async on must reply exactly once");
            assertEquals("command.land.bypass.on", sink.replies.get(0).key());
        } finally {
            audits.shutdown();
        }
    }

    @Test
    void asyncOffWithDeferredSchedulerRepliesOnce() throws Exception {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        states.setEnabled(actor, true);
        AsyncAudits audits = new AsyncAudits();
        DeferredScheduler scheduler = new DeferredScheduler();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, scheduler);
        CapturingSink sink = new CapturingSink();
        try {
            handler.handle(player(actor), new String[] {"bypass", "off"}, sink);
            awaitReplies(sink, 1, scheduler, audits);
            assertEquals(2, audits.inserted.size());
            assertEquals(1, sink.replies.size());
            assertEquals("command.land.bypass.off", sink.replies.get(0).key());
        } finally {
            audits.shutdown();
        }
    }

    @Test
    void throwingSchedulerStillRepliesOnce() throws Exception {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        AsyncAudits audits = new AsyncAudits();
        PlayerScheduler throwing = (p, task) -> {
            throw new IllegalStateException("scheduler retired");
        };
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, throwing);
        CapturingSink sink = new CapturingSink();
        try {
            handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && sink.replies.isEmpty()) {
                Thread.sleep(10);
            }
            assertEquals(2, audits.inserted.size(), "audit must stay paired even when scheduler throws");
            assertTrue(states.isOn(actor), "state must stay committed even when scheduler throws");
            assertEquals(1, sink.replies.size(),
                    "Red: audit committed but reply dropped when scheduler throws");
            assertEquals("command.land.bypass.on", sink.replies.get(0).key());
        } finally {
            audits.shutdown();
        }
    }

    @Test
    void throwingSchedulerOffStillRepliesOnce() throws Exception {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        states.setEnabled(actor, true);
        AsyncAudits audits = new AsyncAudits();
        PlayerScheduler throwing = (p, task) -> {
            throw new IllegalStateException("scheduler retired");
        };
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, throwing);
        CapturingSink sink = new CapturingSink();
        try {
            handler.handle(player(actor), new String[] {"bypass", "off"}, sink);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && sink.replies.isEmpty()) {
                Thread.sleep(10);
            }
            assertEquals(2, audits.inserted.size());
            assertEquals(1, sink.replies.size());
            assertEquals("command.land.bypass.off", sink.replies.get(0).key());
        } finally {
            audits.shutdown();
        }
    }

    @Test
    void asyncAuditFailureRepliesOnceWithDeferredScheduler() throws Exception {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        AuditRepository failing = new AuditRepository() {
            @Override public CompletionStage<Long> insert(AuditEntry entry) {
                return CompletableFuture.failedFuture(new IllegalStateException("audit down"));
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
                return CompletableFuture.completedFuture(List.of());
            }

            @Override public CompletionStage<List<String>> explainSearch(AuditSearchQuery query) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override public CompletionStage<Integer> purgeOlderThan(Instant cutoff) {
                return CompletableFuture.completedFuture(0);
            }
        };
        DeferredScheduler scheduler = new DeferredScheduler();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> failing, () -> NOW, states, scheduler);
        CapturingSink sink = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && sink.replies.isEmpty()) {
            scheduler.drain();
            Thread.sleep(10);
        }
        scheduler.drain();
        assertEquals(1, sink.replies.size(), "audit failure must reply exactly once");
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
        assertTrue(!states.isOn(actor), "audit failure must not flip state");
    }

    @Test
    void throwingSchedulerAuditFailureStillRepliesOnce() throws Exception {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        AuditRepository failing = new AuditRepository() {
            @Override public CompletionStage<Long> insert(AuditEntry entry) {
                return CompletableFuture.failedFuture(new IllegalStateException("audit down"));
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
                return CompletableFuture.completedFuture(List.of());
            }

            @Override public CompletionStage<List<String>> explainSearch(AuditSearchQuery query) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override public CompletionStage<Integer> purgeOlderThan(Instant cutoff) {
                return CompletableFuture.completedFuture(0);
            }
        };
        PlayerScheduler throwing = (p, task) -> {
            throw new IllegalStateException("scheduler retired");
        };
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> failing, () -> NOW, states, throwing);
        CapturingSink sink = new CapturingSink();
        handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end && sink.replies.isEmpty()) {
            Thread.sleep(10);
        }
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
    }

    @Test
    void recoveryGateClosedRepliesOnceWithoutAudit() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        AsyncAudits audits = new AsyncAudits();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        gate.completeExceptionally(new IllegalStateException("recovery failed"));
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct(),
                UUID.randomUUID(), gate);
        CapturingSink sink = new CapturingSink();
        try {
            handler.handle(player(actor), new String[] {"bypass", "on"}, sink);
            assertEquals(1, sink.replies.size());
            assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
            assertTrue(audits.inserted.isEmpty(), "gated toggle must not audit");
            assertTrue(!states.isOn(actor));
        } finally {
            audits.shutdown();
        }
    }
}

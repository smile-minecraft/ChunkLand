package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.chunkland.api.history.HistoryEntry;
import com.smile.chunkland.api.history.HistoryResult;
import com.smile.chunkland.api.history.WorldHistoryProvider;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.command.AdminBypassCommandHandler;
import com.smile.chunkland.command.AdminBypassState;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.history.HistoryCommandHandler;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.AuditRepository;
import com.smile.chunkland.persistence.AuditSearchQuery;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Failure propagation for the production player-thread hop.
 *
 * <p>The production seam only bridges through the Folia entity scheduler.
 * When the hop itself cannot be scheduled it must report the failure to the
 * caller: the audited bypass toggle falls back to its single inline reply,
 * while read-only paths keep dropping the reply fail-closed. A null
 * scheduler handle counts as a rejected hop, never as success. The success
 * path must still defer to the player thread, never run inline.
 */
class ProductionPlayerSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final String BYPASS_NODE = "chunkland.admin.bypass";
    private static final UUID WORLD_ID = UUID.randomUUID();

    private record Reply(String key, Map<String, Object> vars) {
    }

    private static final class RecordingSink implements ReplySink {
        final List<Reply> replies = new CopyOnWriteArrayList<>();

        @Override
        public void reply(String messageKey, Map<String, Object> vars) {
            replies.add(new Reply(messageKey, vars));
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
            reply(messageKey, vars);
        }
    }

    private static ChunkLandPlugin allocatePlugin() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        return (ChunkLandPlugin) unsafe.allocateInstance(ChunkLandPlugin.class);
    }

    private static EntityScheduler throwingEntityScheduler() {
        return (EntityScheduler) Proxy.newProxyInstance(
                EntityScheduler.class.getClassLoader(),
                new Class<?>[] {EntityScheduler.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("run")) {
                        throw new IllegalStateException("scheduler retired");
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduledTaskStub() {
        return (io.papermc.paper.threadedregions.scheduler.ScheduledTask) Proxy.newProxyInstance(
                io.papermc.paper.threadedregions.scheduler.ScheduledTask.class.getClassLoader(),
                new Class<?>[] {io.papermc.paper.threadedregions.scheduler.ScheduledTask.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static EntityScheduler nullEntityScheduler() {
        return (EntityScheduler) Proxy.newProxyInstance(
                EntityScheduler.class.getClassLoader(),
                new Class<?>[] {EntityScheduler.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("run")) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static final class CapturingEntityScheduler {
        final List<Object> pending = new CopyOnWriteArrayList<>();
        final EntityScheduler mock = (EntityScheduler) Proxy.newProxyInstance(
                EntityScheduler.class.getClassLoader(),
                new Class<?>[] {EntityScheduler.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("run")) {
                        pending.add(args[1]);
                        return scheduledTaskStub();
                    }
                    return defaultValue(method.getReturnType());
                });

        @SuppressWarnings("unchecked")
        void drain() {
            List<Object> due = List.copyOf(pending);
            pending.clear();
            for (Object consumer : due) {
                ((Consumer<Object>) consumer).accept(null);
            }
        }
    }

    private static Player playerWithScheduler(UUID uuid, EntityScheduler scheduler,
            boolean bypassNode, Location location) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getScheduler":
                            return scheduler;
                        case "getUniqueId":
                            return uuid;
                        case "hasPermission":
                            return args != null && args.length > 0
                                    && BYPASS_NODE.equals(args[0]) && bypassNode;
                        case "locale":
                            return Locale.US;
                        case "getLocation":
                            return location;
                        case "getName":
                            return "Scheduler-probe";
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "Scheduler-probe-proxy";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
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
        if (type == void.class) {
            return null;
        }
        return null;
    }

    private static World worldProxy() {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUID":
                            return WORLD_ID;
                        case "getName":
                            return "world";
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "World-proxy";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static final class AsyncAudits implements AuditRepository {
        final List<AuditEntry> inserted = new CopyOnWriteArrayList<>();
        final AtomicInteger ids = new AtomicInteger();
        final ExecutorService persistence = Executors.newSingleThreadExecutor();

        @Override
        public CompletionStage<Long> insert(AuditEntry entry) {
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

        @Override
        public CompletionStage<Optional<AuditEntry>> findById(long id) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletionStage<List<AuditEntry>> findByLand(LandId landId, int limit, int offset) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<List<AuditEntry>> findByAction(String action, int limit) {
            return CompletableFuture.completedFuture(List.copyOf(inserted));
        }

        @Override
        public CompletionStage<List<AuditEntry>> findAll(int limit, int offset) {
            return CompletableFuture.completedFuture(List.copyOf(inserted));
        }

        @Override
        public CompletionStage<List<AuditEntry>> search(AuditSearchQuery query) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<List<String>> explainSearch(AuditSearchQuery query) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<Integer> purgeOlderThan(Instant cutoff) {
            return CompletableFuture.completedFuture(0);
        }

        void shutdown() {
            persistence.shutdownNow();
        }
    }

    @Test
    void schedulingFailureReachesTheCaller() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        PlayerScheduler seam = ChunkLandPlugin.buildPlayerScheduler(plugin);
        Player player = playerWithScheduler(UUID.randomUUID(), throwingEntityScheduler(), true, null);
        AtomicBoolean ran = new AtomicBoolean(false);

        try {
            seam.runForPlayer(player, () -> ran.set(true));
            fail("a hop that cannot be scheduled must report the failure to the caller");
        } catch (RuntimeException expected) {
            // The caller decides fail-closed handling; the seam must not swallow.
        }
        assertTrue(!ran.get(), "a rejected hop must not run the continuation");
    }

    @Test
    void successfulScheduleStillDefersToThePlayerThread() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        CapturingEntityScheduler capturing = new CapturingEntityScheduler();
        PlayerScheduler seam = ChunkLandPlugin.buildPlayerScheduler(plugin);
        Player player = playerWithScheduler(UUID.randomUUID(), capturing.mock, true, null);
        AtomicBoolean ran = new AtomicBoolean(false);

        seam.runForPlayer(player, () -> ran.set(true));

        assertTrue(!ran.get(), "the success path must defer, never run inline");
        assertEquals(1, capturing.pending.size(), "one continuation must be scheduled");
        capturing.drain();
        assertTrue(ran.get(), "draining the scheduled continuation must run it");
    }

    @Test
    void bypassFallbackFiresThroughTheProductionSeam() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        PlayerScheduler seam = ChunkLandPlugin.buildPlayerScheduler(plugin);
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        AsyncAudits audits = new AsyncAudits();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, seam);
        Player player = playerWithScheduler(actor, throwingEntityScheduler(), true, null);
        RecordingSink sink = new RecordingSink();
        try {
            handler.handle(player, new String[] {"bypass", "on"}, sink);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && sink.replies.isEmpty()) {
                Thread.sleep(10);
            }
            assertEquals(2, audits.inserted.size(),
                    "the audit must stay paired even when the hop is rejected");
            assertTrue(states.isOn(actor), "the state must stay committed");
            assertEquals(1, sink.replies.size(),
                    "a rejected hop must still leave exactly one fallback reply");
            assertEquals("command.land.bypass.on", sink.replies.get(0).key());
        } finally {
            audits.shutdown();
        }
    }

    @Test
    void bypassSuccessStillHopsThroughTheProductionSeam() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        CapturingEntityScheduler capturing = new CapturingEntityScheduler();
        PlayerScheduler seam = ChunkLandPlugin.buildPlayerScheduler(plugin);
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        AsyncAudits audits = new AsyncAudits();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, seam);
        Player player = playerWithScheduler(actor, capturing.mock, true, null);
        RecordingSink sink = new RecordingSink();
        try {
            handler.handle(player, new String[] {"bypass", "on"}, sink);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && capturing.pending.isEmpty()) {
                Thread.sleep(10);
            }
            assertEquals(1, capturing.pending.size(), "the success path must schedule one hop");
            assertTrue(sink.replies.isEmpty(), "nothing may reply before the hop runs");
            capturing.drain();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && sink.replies.isEmpty()) {
                capturing.drain();
                Thread.sleep(10);
            }
            assertEquals(1, sink.replies.size());
            assertEquals("command.land.bypass.on", sink.replies.get(0).key());
            assertEquals(2, audits.inserted.size());
            assertTrue(states.isOn(actor));
        } finally {
            audits.shutdown();
        }
    }

    @Test
    void nullHandleReachesTheCallerAsFailure() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        PlayerScheduler seam = ChunkLandPlugin.buildPlayerScheduler(plugin);
        Player player = playerWithScheduler(UUID.randomUUID(), nullEntityScheduler(), true, null);
        AtomicBoolean ran = new AtomicBoolean(false);

        try {
            seam.runForPlayer(player, () -> ran.set(true));
            fail("a null scheduler handle must report the failure to the caller");
        } catch (RuntimeException expected) {
            // The caller decides fail-closed handling; the seam must not swallow.
        }
        assertTrue(!ran.get(), "a null handle must not run the continuation");
    }

    @Test
    void bypassFallbackFiresOnNullHandle() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        PlayerScheduler seam = ChunkLandPlugin.buildPlayerScheduler(plugin);
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        AsyncAudits audits = new AsyncAudits();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, seam);
        Player player = playerWithScheduler(actor, nullEntityScheduler(), true, null);
        RecordingSink sink = new RecordingSink();
        try {
            handler.handle(player, new String[] {"bypass", "on"}, sink);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && sink.replies.isEmpty()) {
                Thread.sleep(10);
            }
            assertEquals(2, audits.inserted.size(),
                    "the audit must stay paired even when the handle is null");
            assertTrue(states.isOn(actor), "the state must stay committed");
            assertEquals(1, sink.replies.size(),
                    "a null handle must still leave exactly one fallback reply");
            assertEquals("command.land.bypass.on", sink.replies.get(0).key());
        } finally {
            audits.shutdown();
        }
    }

    @Test
    void bypassOffFallbackFiresOnNullHandle() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        PlayerScheduler seam = ChunkLandPlugin.buildPlayerScheduler(plugin);
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        states.setEnabled(actor, true);
        AsyncAudits audits = new AsyncAudits();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, seam);
        Player player = playerWithScheduler(actor, nullEntityScheduler(), true, null);
        RecordingSink sink = new RecordingSink();
        try {
            handler.handle(player, new String[] {"bypass", "off"}, sink);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && sink.replies.isEmpty()) {
                Thread.sleep(10);
            }
            assertEquals(2, audits.inserted.size());
            assertTrue(!states.isOn(actor), "the state must stay off");
            assertEquals(1, sink.replies.size(),
                    "a null handle must still leave exactly one fallback reply");
            assertEquals("command.land.bypass.off", sink.replies.get(0).key());
        } finally {
            audits.shutdown();
        }
    }

    @Test
    void historyWithAFailingProductionSeamDropsFailClosed() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        PlayerScheduler seam = ChunkLandPlugin.buildPlayerScheduler(plugin);
        CompletableFuture<HistoryResult> future = new CompletableFuture<>();
        WorldHistoryProvider provider = query -> future;
        Location location = new Location(worldProxy(), 0.5, 64, 0.5);
        Player player = playerWithScheduler(UUID.randomUUID(), throwingEntityScheduler(), true, location);
        RecordingSink sink = new RecordingSink();

        new HistoryCommandHandler(() -> provider, seam)
                .handle(player, new String[] {"history"}, sink);

        List<HistoryEntry> entries = new ArrayList<>();
        entries.add(new HistoryEntry(1, 64, 0, "placed", "STONE", 1_700_000_000L));
        ExecutorService background = Executors.newSingleThreadExecutor();
        try {
            background.submit(() -> future.complete(HistoryResult.of(entries, true))).get(10, TimeUnit.SECONDS);
            Thread.sleep(300);
        } finally {
            background.shutdownNow();
        }

        assertTrue(sink.replies.isEmpty(),
                "a rejected hop on a read path must drop the reply fail-closed");
    }
}

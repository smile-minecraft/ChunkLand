package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.command.AdminBypassCommandHandler;
import com.smile.chunkland.command.AdminBypassLifecycle;
import com.smile.chunkland.command.AdminBypassState;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.LandPermissions;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.command.ReplySink;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.AuditRepository;
import com.smile.chunkland.persistence.AuditSearchQuery;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Fallback wiring for the bypass toggle.
 *
 * <p>When the cached command is gone, the fallback slot must reuse the
 * current enable's gated handler — never an independent legacy handler —
 * so a pending or failed recovery gate still fail-closes. Without a stored
 * handler the slot only replies unavailable.
 */
class ChunkLandPluginBypassFallbackTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final String BYPASS_NODE = "chunkland.admin.bypass";

    private static ChunkLandPlugin allocatePlugin() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        ChunkLandPlugin plugin =
                (ChunkLandPlugin) unsafe.allocateInstance(ChunkLandPlugin.class);
        for (var field : ChunkLandPlugin.class.getDeclaredFields()) {
            field.setAccessible(true);
            if (field.getType() == java.util.Optional.class && field.get(plugin) == null) {
                field.set(plugin, java.util.Optional.empty());
            }
        }
        return plugin;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class CapturingSink implements ReplySink {
        final List<String> keys = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> vars = new CopyOnWriteArrayList<>();

        @Override public void reply(String messageKey, Map<String, Object> variables) {
            keys.add(messageKey);
            vars.add(variables);
        }

        @Override public void reply(String messageKey, Map<String, Object> variables, Locale locale) {
            reply(messageKey, variables);
        }
    }

    private static final class DeferredAudits implements AuditRepository {
        final List<AuditEntry> inserted = new CopyOnWriteArrayList<>();
        final AtomicInteger ids = new AtomicInteger();

        @Override public CompletionStage<Long> insert(AuditEntry entry) {
            inserted.add(entry);
            return CompletableFuture.completedFuture((long) ids.incrementAndGet());
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
            return CompletableFuture.completedFuture(List.copyOf(inserted));
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
                        return BYPASS_NODE.equals(args[0])
                                || LandPermissions.HELP.equals(args[0]);
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
    void fallbackReusesStoredGatedHandler() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        AdminBypassState states = new AdminBypassState();
        DeferredAudits audits = new DeferredAudits();
        UUID process = UUID.randomUUID();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AdminBypassLifecycle lifecycle = new AdminBypassLifecycle();
        AdminBypassCommandHandler stored = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct(),
                process, gate, lifecycle);
        setField(plugin, "bypassHandler", stored);
        setField(plugin, "adminBypassStates", states);

        LandCommand.Handler fallback = plugin.fallbackBypassHandler();
        assertSame(stored, fallback, "fallback must reuse the enable's gated handler");

        UUID actor = UUID.randomUUID();
        CapturingSink blocked = new CapturingSink();
        fallback.handle(player(actor), new String[] {"bypass", "on"}, blocked);
        assertEquals(1, blocked.keys.size());
        assertEquals("command.land.bypass.failed", blocked.keys.get(0));
        assertTrue(audits.inserted.isEmpty(), "pending gate must not write audit");
        assertFalse(states.isOn(actor));

        gate.complete(null);
        CapturingSink retry = new CapturingSink();
        fallback.handle(player(actor), new String[] {"bypass", "on"}, retry);
        assertEquals("command.land.bypass.on", retry.keys.get(0));
        assertTrue(states.isOn(actor));
    }

    @Test
    void fallbackWithoutStoredHandlerStaysUnavailable() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        setField(plugin, "bypassHandler", null);

        LandCommand.Handler fallback = plugin.fallbackBypassHandler();
        CapturingSink sink = new CapturingSink();
        fallback.handle(player(UUID.randomUUID()), new String[] {"bypass", "on"}, sink);

        assertEquals(1, sink.keys.size());
        assertEquals("command.land.bypass.failed", sink.keys.get(0));
    }

    @Test
    void fallbackFailedGateStaysFailClosed() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        AdminBypassState states = new AdminBypassState();
        DeferredAudits audits = new DeferredAudits();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        gate.completeExceptionally(new IllegalStateException("recovery down"));
        AdminBypassCommandHandler stored = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, PlayerScheduler.direct(),
                UUID.randomUUID(), gate, new AdminBypassLifecycle());
        setField(plugin, "bypassHandler", stored);

        LandCommand.Handler fallback = plugin.fallbackBypassHandler();
        CapturingSink sink = new CapturingSink();
        UUID actor = UUID.randomUUID();
        fallback.handle(player(actor), new String[] {"bypass", "on"}, sink);

        assertEquals("command.land.bypass.failed", sink.keys.get(0));
        assertTrue(audits.inserted.isEmpty());
        assertFalse(states.isOn(actor));
    }
}

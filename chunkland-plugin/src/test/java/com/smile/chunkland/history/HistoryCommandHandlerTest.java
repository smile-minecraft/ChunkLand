package com.smile.chunkland.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.history.HistoryEntry;
import com.smile.chunkland.api.history.HistoryResult;
import com.smile.chunkland.api.history.WorldHistoryProvider;
import com.smile.chunkland.command.ReplySink;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Read-only history handler contract: console, missing backends and every
 * failure stay fail-closed on one generic unavailable key; valid answers
 * render bounded line replies with coordinates, action and material only.
 */
class HistoryCommandHandlerTest {

    private static final UUID WORLD_ID = UUID.randomUUID();

    private record Reply(String key, Map<String, Object> vars) {
    }

    private static final class RecordingSink implements ReplySink {
        final List<Reply> replies = new CopyOnWriteArrayList<>();

        @Override
        public void reply(String messageKey, Map<String, Object> vars) {
            replies.add(new Reply(messageKey, Map.copyOf(vars)));
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vars, Locale locale) {
            replies.add(new Reply(messageKey, Map.copyOf(vars)));
        }
    }

    private static World worldProxy() {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getUID" -> WORLD_ID;
                        case "getName" -> "world";
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "World-proxy";
                        default -> {
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                yield false;
                            }
                            if (rt == int.class) {
                                yield 0;
                            }
                            if (rt == long.class) {
                                yield 0L;
                            }
                            if (rt == double.class) {
                                yield 0d;
                            }
                            yield null;
                        }
                    };
                });
    }

    private static Player playerAt(double x, double y, double z) {
        Location location = new Location(worldProxy(), x, y, z);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getUniqueId" -> UUID.randomUUID();
                        case "getLocation" -> location;
                        case "getName" -> "Producer";
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "Producer-proxy";
                        default -> {
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                yield false;
                            }
                            if (rt == int.class) {
                                yield 0;
                            }
                            if (rt == long.class) {
                                yield 0L;
                            }
                            if (rt == double.class) {
                                yield 0d;
                            }
                            yield null;
                        }
                    };
                });
    }

    private static CommandSender consoleSender() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getName" -> "Console";
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "Console-proxy";
                        default -> {
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                yield false;
                            }
                            if (rt == int.class) {
                                yield 0;
                            }
                            if (rt == long.class) {
                                yield 0L;
                            }
                            if (rt == double.class) {
                                yield 0d;
                            }
                            yield null;
                        }
                    };
                });
    }

    @Test
    void consoleStaysFailClosedWithoutQuerying() {
        AtomicBoolean queried = new AtomicBoolean(false);
        WorldHistoryProvider provider = new WorldHistoryProvider() {
            @Override
            public CompletableFuture<com.smile.chunkland.api.history.HistoryResult> query(
                    com.smile.chunkland.api.history.HistoryQuery query) {
                queried.set(true);
                return CompletableFuture.completedFuture(HistoryResult.unavailable());
            }
        };
        RecordingSink sink = new RecordingSink();
        new HistoryCommandHandler(() -> provider).handle(consoleSender(),
                new String[] {"history"}, sink);
        assertEquals(List.of("command.land.history.unavailable"),
                sink.replies.stream().map(Reply::key).toList());
        assertTrue(!queried.get());
    }

    @Test
    void missingProviderStaysFailClosed() {
        RecordingSink sink = new RecordingSink();
        new HistoryCommandHandler(null).handle(playerAt(0.5, 64, 0.5),
                new String[] {"history"}, sink);
        assertEquals(List.of("command.land.history.unavailable"),
                sink.replies.stream().map(Reply::key).toList());
    }

    @Test
    void absentBackendStaysFailClosedWithoutQuerying() {
        AtomicBoolean queried = new AtomicBoolean(false);
        WorldHistoryProvider provider = new WorldHistoryProvider() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public CompletableFuture<HistoryResult> query(
                    com.smile.chunkland.api.history.HistoryQuery query) {
                queried.set(true);
                return CompletableFuture.completedFuture(HistoryResult.unavailable());
            }
        };
        RecordingSink sink = new RecordingSink();
        new HistoryCommandHandler(() -> provider).handle(playerAt(0.5, 64, 0.5),
                new String[] {"history"}, sink);
        assertEquals(List.of("command.land.history.unavailable"),
                sink.replies.stream().map(Reply::key).toList());
        assertTrue(!queried.get());
    }

    @Test
    void failedQueryRepliesGenericUnavailable() {
        WorldHistoryProvider provider =
                query -> CompletableFuture.failedFuture(new IllegalStateException("db leaked?"));
        RecordingSink sink = new RecordingSink();
        new HistoryCommandHandler(() -> provider).handle(playerAt(0.5, 64, 0.5),
                new String[] {"history"}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.history.unavailable", sink.replies.get(0).key());
        assertTrue(sink.replies.get(0).vars().isEmpty());
    }

    @Test
    void emptyAnswerRepliesEmpty() {
        WorldHistoryProvider provider =
                query -> CompletableFuture.completedFuture(HistoryResult.of(List.of(), false));
        RecordingSink sink = new RecordingSink();
        new HistoryCommandHandler(() -> provider).handle(playerAt(0.5, 64, 0.5),
                new String[] {"history"}, sink);
        assertEquals(List.of("command.land.history.empty"),
                sink.replies.stream().map(Reply::key).toList());
    }

    @Test
    void validAnswerRendersBoundedLines() {
        List<HistoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            entries.add(new HistoryEntry(i, 64, 0, "placed", "STONE", 1_700_000_000L));
        }
        WorldHistoryProvider provider =
                query -> CompletableFuture.completedFuture(HistoryResult.of(entries, true));
        RecordingSink sink = new RecordingSink();
        new HistoryCommandHandler(() -> provider).handle(playerAt(0.5, 64, 0.5),
                new String[] {"history"}, sink);
        assertEquals(10, sink.replies.size());
        assertTrue(sink.replies.stream().allMatch(r -> r.key().equals("command.land.history.line")));
        assertEquals("0,64,0 placed STONE",
                String.valueOf(sink.replies.get(0).vars().get("value")));
    }

    @Test
    void extraArgsReplyUsage() {
        AtomicBoolean queried = new AtomicBoolean(false);
        WorldHistoryProvider provider = new WorldHistoryProvider() {
            @Override
            public CompletableFuture<HistoryResult> query(
                    com.smile.chunkland.api.history.HistoryQuery query) {
                queried.set(true);
                return CompletableFuture.completedFuture(HistoryResult.unavailable());
            }
        };
        RecordingSink sink = new RecordingSink();
        new HistoryCommandHandler(() -> provider).handle(playerAt(0.5, 64, 0.5),
                new String[] {"history", "extra"}, sink);
        assertEquals(List.of("command.land.history.usage"),
                sink.replies.stream().map(Reply::key).toList());
        assertTrue(!queried.get());
    }

    @Test
    void locationFailureStaysFailClosed() {
        Player broken = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getLocation")) {
                        throw new IllegalStateException("gone");
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
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
                    return null;
                });
        RecordingSink sink = new RecordingSink();
        new HistoryCommandHandler(WorldHistoryProvider::empty).handle(broken,
                new String[] {"history"}, sink);
        assertEquals(List.of("command.land.history.unavailable"),
                sink.replies.stream().map(Reply::key).toList());
    }
}

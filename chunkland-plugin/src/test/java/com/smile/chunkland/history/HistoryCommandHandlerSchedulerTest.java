package com.smile.chunkland.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.history.HistoryEntry;
import com.smile.chunkland.api.history.HistoryResult;
import com.smile.chunkland.api.history.WorldHistoryProvider;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.command.ReplySink;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Folia thread-safety contract for {@code /land history}: completions that
 * arrive on the provider executor must never touch the {@link ReplySink}
 * directly for players — every terminal reply hops through the injected
 * {@link PlayerScheduler} first. Console completions stay inline.
 */
class HistoryCommandHandlerSchedulerTest {

    private static final UUID WORLD_ID = UUID.randomUUID();

    private record Reply(String key, Map<String, Object> vars, String threadName) {
    }

    private static final class RecordingSink implements ReplySink {
        final List<Reply> replies = new CopyOnWriteArrayList<>();

        @Override
        public void reply(String messageKey, Map<String, Object> vars) {
            replies.add(new Reply(messageKey, Map.copyOf(vars),
                    Thread.currentThread().getName()));
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vars, Locale locale) {
            replies.add(new Reply(messageKey, Map.copyOf(vars),
                    Thread.currentThread().getName()));
        }
    }

    private static final class RecordingScheduler implements PlayerScheduler {
        final List<String> hopThreads = new CopyOnWriteArrayList<>();
        final List<Runnable> pending = new CopyOnWriteArrayList<>();
        volatile Player seenPlayer;

        @Override
        public void runForPlayer(Player player, Runnable task) {
            seenPlayer = player;
            hopThreads.add(Thread.currentThread().getName());
            pending.add(task);
        }

        void drain() {
            for (Runnable task : pending) {
                task.run();
            }
            pending.clear();
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

    private static List<HistoryEntry> twoEntries() {
        List<HistoryEntry> entries = new ArrayList<>();
        entries.add(new HistoryEntry(1, 64, 0, "placed", "STONE", 1_700_000_000L));
        entries.add(new HistoryEntry(2, 64, 0, "broke", "DIRT", 1_700_000_001L));
        return entries;
    }

    /**
     * Completes {@code future} on a background executor thread and waits for
     * that thread to finish, so the provider-style completion (and therefore
     * the handler's terminal callback) provably runs off the calling thread
     * without any sleep or join on handler internals.
     */
    private static String completeFromBackground(CompletableFuture<HistoryResult> future,
            HistoryResult result, Throwable failure) throws Exception {
        ExecutorService background = Executors.newSingleThreadExecutor();
        try {
            Future<String> done = background.submit(() -> {
                if (failure != null) {
                    future.completeExceptionally(failure);
                } else {
                    future.complete(result);
                }
                return Thread.currentThread().getName();
            });
            return done.get(10, TimeUnit.SECONDS);
        } finally {
            background.shutdownNow();
        }
    }

    @Test
    void playerSuccessRepliesOnlyInsideSchedulerHop() throws Exception {
        CompletableFuture<HistoryResult> future = new CompletableFuture<>();
        WorldHistoryProvider provider = query -> future;
        RecordingSink sink = new RecordingSink();
        RecordingScheduler scheduler = new RecordingScheduler();
        Player player = playerAt(0.5, 64, 0.5);

        new HistoryCommandHandler(() -> provider, scheduler)
                .handle(player, new String[] {"history"}, sink);

        String backgroundThread = completeFromBackground(future,
                HistoryResult.of(twoEntries(), true), null);

        // The provider executor must not touch the sink directly: nothing
        // replies before the scheduler hop runs.
        assertTrue(sink.replies.isEmpty());
        assertEquals(1, scheduler.pending.size());
        assertEquals(1, scheduler.hopThreads.size());
        assertEquals(backgroundThread, scheduler.hopThreads.get(0));

        scheduler.drain();

        assertEquals(2, sink.replies.size());
        assertTrue(sink.replies.stream()
                .allMatch(r -> r.key().equals("command.land.history.line")));
        // Every reply ran on the hop-drain thread, never on the provider
        // executor thread.
        assertTrue(sink.replies.stream()
                .noneMatch(r -> r.threadName().equals(backgroundThread)));
    }

    @Test
    void playerEmptyAnswerHopsBeforeReply() throws Exception {
        CompletableFuture<HistoryResult> future = new CompletableFuture<>();
        WorldHistoryProvider provider = query -> future;
        RecordingSink sink = new RecordingSink();
        RecordingScheduler scheduler = new RecordingScheduler();

        new HistoryCommandHandler(() -> provider, scheduler)
                .handle(playerAt(0.5, 64, 0.5), new String[] {"history"}, sink);

        completeFromBackground(future, HistoryResult.of(List.of(), true), null);

        assertTrue(sink.replies.isEmpty());
        assertEquals(1, scheduler.pending.size());

        scheduler.drain();

        assertEquals(List.of("command.land.history.empty"),
                sink.replies.stream().map(Reply::key).toList());
    }

    @Test
    void playerUnavailableAnswerHopsBeforeReply() throws Exception {
        CompletableFuture<HistoryResult> future = new CompletableFuture<>();
        WorldHistoryProvider provider = query -> future;
        RecordingSink sink = new RecordingSink();
        RecordingScheduler scheduler = new RecordingScheduler();

        new HistoryCommandHandler(() -> provider, scheduler)
                .handle(playerAt(0.5, 64, 0.5), new String[] {"history"}, sink);

        completeFromBackground(future, HistoryResult.unavailable(), null);

        assertTrue(sink.replies.isEmpty());
        assertEquals(1, scheduler.pending.size());

        scheduler.drain();

        assertEquals(List.of("command.land.history.unavailable"),
                sink.replies.stream().map(Reply::key).toList());
    }

    @Test
    void playerFailedQueryHopsBeforeReply() throws Exception {
        CompletableFuture<HistoryResult> future = new CompletableFuture<>();
        WorldHistoryProvider provider = query -> future;
        RecordingSink sink = new RecordingSink();
        RecordingScheduler scheduler = new RecordingScheduler();

        new HistoryCommandHandler(() -> provider, scheduler)
                .handle(playerAt(0.5, 64, 0.5), new String[] {"history"}, sink);

        completeFromBackground(future, null, new IllegalStateException("db leaked?"));

        assertTrue(sink.replies.isEmpty());
        assertEquals(1, scheduler.pending.size());

        scheduler.drain();

        assertEquals(1, sink.replies.size());
        assertEquals("command.land.history.unavailable", sink.replies.get(0).key());
        assertTrue(sink.replies.get(0).vars().isEmpty());
    }

    @Test
    void consoleCompletionStaysInlineWithoutScheduler() {
        WorldHistoryProvider provider =
                query -> CompletableFuture.completedFuture(HistoryResult.unavailable());
        RecordingSink sink = new RecordingSink();
        PlayerScheduler neverHop = (player, task) -> {
            throw new AssertionError("console must not hop");
        };

        new HistoryCommandHandler(() -> provider, neverHop)
                .handle(consoleSender(), new String[] {"history"}, sink);

        assertEquals(List.of("command.land.history.unavailable"),
                sink.replies.stream().map(Reply::key).toList());
    }

    @Test
    void retiredSchedulerDropsPlayerReplyFailClosed() throws Exception {
        CompletableFuture<HistoryResult> future = new CompletableFuture<>();
        WorldHistoryProvider provider = query -> future;
        RecordingSink sink = new RecordingSink();
        PlayerScheduler retired = (player, task) -> {
            throw new IllegalStateException("retired");
        };

        new HistoryCommandHandler(() -> provider, retired)
                .handle(playerAt(0.5, 64, 0.5), new String[] {"history"}, sink);

        completeFromBackground(future, HistoryResult.of(twoEntries(), true), null);

        assertTrue(sink.replies.isEmpty());
    }
}

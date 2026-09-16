package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.persistence.LandBindingRepository;
import com.smile.chunkland.persistence.PermissionProfileRepository;
import com.smile.chunkland.persistence.SubjectGroupRepository;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Red contract for async offline-player resolution inside
 * {@link BindingCommandHandler}: an offline name must be resolved on the
 * explicit async executor without blocking the region-facing call, and the
 * follow-up mutation must still go through the existing async
 * {@link BindingCommandHandler.Bindings} seam — never a direct repository.
 */
class BindingCommandHandlerAsyncResolutionTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());
    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID OFFLINE_TARGET = UUID.randomUUID();

    private static final class CapturingSink implements ReplySink {
        final List<String> keys = new ArrayList<>();
        final List<Map<String, Object>> vars = new ArrayList<>();

        @Override
        public void reply(String messageKey, Map<String, Object> vs) {
            keys.add(messageKey);
            vars.add(Map.copyOf(vs));
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vs, Locale localeOverride) {
            reply(messageKey, vs);
        }
    }

    /** Executor that captures tasks so tests drive async completion by hand. */
    private static final class RecordingExecutor implements Executor {
        final List<Runnable> pending = new CopyOnWriteArrayList<>();

        @Override
        public void execute(Runnable task) {
            pending.add(task);
        }

        void runAll() {
            List<Runnable> due = new ArrayList<>(pending);
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
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "hasPermission" -> true;
                    case "getName" -> "Actor";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Player-proxy";
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
    }

    private record Mutation(AtomicBoolean called,
            AtomicReference<LandBindingRepository.Subject> subject) {
    }

    private static BindingCommandHandler.Bindings bindings(Mutation mutation) {
        return new BindingCommandHandler.Bindings() {
            @Override
            public CompletionStage<LandBindingRepository.BindOutcome> bindLand(
                    UUID owner, LandId landId, LandBindingRepository.Subject subject,
                    UUID profile) {
                mutation.called().set(true);
                mutation.subject().set(subject);
                return CompletableFuture.completedFuture(new LandBindingRepository.BindOutcome(
                        landId.value(), null, subject, profile, false));
            }

            @Override
            public CompletionStage<LandBindingRepository.UnbindOutcome> unbindLand(
                    UUID owner, LandId landId, LandBindingRepository.Subject subject) {
                mutation.called().set(true);
                mutation.subject().set(subject);
                return CompletableFuture.completedFuture(new LandBindingRepository.UnbindOutcome(
                        landId.value(), null, subject, PROFILE));
            }

            @Override
            public CompletionStage<LandBindingRepository.BindOutcome> bindSubland(
                    UUID owner, SubLandId sublandId, LandBindingRepository.Subject subject,
                    UUID profile) {
                mutation.called().set(true);
                mutation.subject().set(subject);
                return CompletableFuture.completedFuture(new LandBindingRepository.BindOutcome(
                        LAND.value(), sublandId.value(), subject, profile, false));
            }

            @Override
            public CompletionStage<LandBindingRepository.UnbindOutcome> unbindSubland(
                    UUID owner, SubLandId sublandId, LandBindingRepository.Subject subject) {
                mutation.called().set(true);
                mutation.subject().set(subject);
                return CompletableFuture.completedFuture(new LandBindingRepository.UnbindOutcome(
                        LAND.value(), sublandId.value(), subject, PROFILE));
            }
        };
    }

    private static BindingCommandHandler.Groups groups() {
        return (owner, ref) -> CompletableFuture.failedFuture(
                new com.smile.chunkland.persistence.GroupRejectedException("binding.invalid"));
    }

    private static BindingCommandHandler.Profiles profiles() {
        return (owner, ref) -> CompletableFuture.completedFuture(
                new PermissionProfileRepository.ProfileView(PROFILE, ACTOR, "Default",
                        "default", 0L, Map.of()));
    }

    private static BindingCommandHandler handlerWith(
            Mutation mutation, OfflinePlayerResolver players) {
        return new BindingCommandHandler(
                bindings(mutation),
                groups(),
                profiles(),
                sender -> Optional.of(LAND),
                (sender, landId) -> Optional.empty(),
                name -> Optional.empty(),
                players);
    }

    @Test
    void offlineNameBindDoesNotBlockAndMutatesViaSeamOnly() {
        RecordingExecutor executor = new RecordingExecutor();
        AtomicInteger offlineCalls = new AtomicInteger(0);
        OfflinePlayerResolver players = new OfflinePlayerResolver(
                name -> Optional.empty(),
                name -> {
                    offlineCalls.incrementAndGet();
                    return Optional.of(OFFLINE_TARGET);
                },
                executor);
        Mutation mutation = new Mutation(new AtomicBoolean(false), new AtomicReference<>());
        BindingCommandHandler handler = handlerWith(mutation, players);
        CapturingSink sink = new CapturingSink();
        handler.handle(player(ACTOR),
                new String[] {"binding", "bind", "player", "OfflineSteve", PROFILE.toString()},
                sink);
        assertTrue(sink.keys.isEmpty(),
                "region-facing bind must return before async resolution completes");
        assertFalse(mutation.called().get(), "no mutation before the offline lookup runs");
        assertEquals(0, offlineCalls.get(), "blocking lookup must not run on the caller");
        assertEquals(1, executor.pending.size(), "exactly one async lookup must be queued");

        executor.runAll();

        assertTrue(mutation.called().get(), "resolved bind must reach the Bindings seam");
        assertEquals(LandBindingRepository.SubjectKind.PLAYER,
                mutation.subject().get().kind());
        assertEquals(OFFLINE_TARGET, mutation.subject().get().id());
        assertEquals(List.of("command.land.binding.created"), sink.keys,
                "resolved bind must reply created through the seam outcome");
    }

    @Test
    void unknownOfflineNameFailsClosedWithoutMutation() {
        RecordingExecutor executor = new RecordingExecutor();
        OfflinePlayerResolver players = new OfflinePlayerResolver(
                name -> Optional.empty(),
                name -> Optional.empty(),
                executor);
        Mutation mutation = new Mutation(new AtomicBoolean(false), new AtomicReference<>());
        BindingCommandHandler handler = handlerWith(mutation, players);
        CapturingSink sink = new CapturingSink();
        handler.handle(player(ACTOR),
                new String[] {"binding", "bind", "player", "NobodyEverPlayed",
                        PROFILE.toString()},
                sink);
        assertTrue(sink.keys.isEmpty());
        executor.runAll();
        assertFalse(mutation.called().get(), "unknown subject must never mutate");
        assertEquals(1, sink.keys.size());
        assertEquals("command.land.binding.failed", sink.keys.get(0));
        assertEquals("binding.invalid", sink.vars.get(0).get("reason"));
    }

    @Test
    void offlineLookupFailureFailsClosedWithoutMutation() {
        RecordingExecutor executor = new RecordingExecutor();
        OfflinePlayerResolver players = new OfflinePlayerResolver(
                name -> Optional.empty(),
                name -> {
                    throw new IllegalStateException("network down");
                },
                executor);
        Mutation mutation = new Mutation(new AtomicBoolean(false), new AtomicReference<>());
        BindingCommandHandler handler = handlerWith(mutation, players);
        CapturingSink sink = new CapturingSink();
        handler.handle(player(ACTOR),
                new String[] {"binding", "unbind", "player", "OfflineSteve"},
                sink);
        assertTrue(sink.keys.isEmpty());
        executor.runAll();
        assertFalse(mutation.called().get(), "failed resolution must never mutate");
        assertEquals(1, sink.keys.size());
        assertEquals("command.land.binding.failed", sink.keys.get(0));
    }

    @Test
    void syncOnlineNameStillResolvesWithoutExecutor() {
        AtomicInteger executorTasks = new AtomicInteger(0);
        OfflinePlayerResolver players = new OfflinePlayerResolver(
                name -> Optional.empty(),
                name -> Optional.of(OFFLINE_TARGET),
                task -> executorTasks.incrementAndGet());
        Mutation mutation = new Mutation(new AtomicBoolean(false), new AtomicReference<>());
        BindingCommandHandler handler = new BindingCommandHandler(
                bindings(mutation),
                groups(),
                profiles(),
                sender -> Optional.of(LAND),
                (sender, landId) -> Optional.empty(),
                name -> Optional.of(OFFLINE_TARGET),
                players);
        CapturingSink sink = new CapturingSink();
        handler.handle(player(ACTOR),
                new String[] {"binding", "unbind", "player", "OnlineSteve"},
                sink);
        assertTrue(mutation.called().get(), "online name must resolve synchronously");
        assertEquals(OFFLINE_TARGET, mutation.subject().get().id());
        assertEquals(0, executorTasks.get(), "online hit must not touch the async executor");
        assertEquals(List.of("command.land.binding.deleted"), sink.keys);
    }

    @Test
    void legacyConstructorKeepsOnlineOnlyBehaviour() {
        Mutation mutation = new Mutation(new AtomicBoolean(false), new AtomicReference<>());
        BindingCommandHandler handler = new BindingCommandHandler(
                bindings(mutation),
                groups(),
                profiles(),
                sender -> Optional.of(LAND),
                (sender, landId) -> Optional.empty(),
                name -> Optional.empty());
        CapturingSink sink = new CapturingSink();
        handler.handle(player(ACTOR),
                new String[] {"binding", "bind", "player", "OfflineSteve", PROFILE.toString()},
                sink);
        assertFalse(mutation.called().get(), "offline name must fail closed without a resolver");
        assertEquals(List.of("command.land.binding.failed"), sink.keys);
        assertEquals("binding.invalid", sink.vars.get(0).get("reason"));
    }

    /** Scheduler seam that captures player-thread tasks so tests drive them by hand. */
    private static final class RecordingScheduler implements PlayerScheduler {
        final List<Runnable> pending = new CopyOnWriteArrayList<>();

        @Override
        public void runForPlayer(Player player, Runnable task) {
            pending.add(task);
        }

        void runAll() {
            List<Runnable> due = new ArrayList<>(pending);
            pending.clear();
            for (Runnable task : due) {
                task.run();
            }
        }
    }

    /** Player proxy that counts every Bukkit call, so tests prove the executor thread touches none. */
    private static Player countingPlayer(UUID uuid, AtomicInteger calls) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "Player-proxy";
                    }
                    calls.incrementAndGet();
                    return switch (name) {
                        case "getUniqueId" -> uuid;
                        case "hasPermission" -> true;
                        case "getName" -> "Actor";
                        default -> method.getReturnType() == boolean.class ? false : null;
                    };
                });
    }

    private static BindingCommandHandler scheduledHandler(
            Mutation mutation, OfflinePlayerResolver players, PlayerScheduler scheduler) {
        return new BindingCommandHandler(
                bindings(mutation),
                groups(),
                profiles(),
                sender -> Optional.of(LAND),
                (sender, landId) -> Optional.empty(),
                name -> Optional.empty(),
                players,
                scheduler);
    }

    @Test
    void offlineBindMutatesAndRepliesOnlyOnPlayerScheduler() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingScheduler scheduler = new RecordingScheduler();
        OfflinePlayerResolver players = new OfflinePlayerResolver(
                name -> Optional.empty(),
                name -> Optional.of(OFFLINE_TARGET),
                executor);
        Mutation mutation = new Mutation(new AtomicBoolean(false), new AtomicReference<>());
        BindingCommandHandler handler = scheduledHandler(mutation, players, scheduler);
        AtomicInteger playerCalls = new AtomicInteger(0);
        CapturingSink sink = new CapturingSink();
        handler.handle(countingPlayer(ACTOR, playerCalls),
                new String[] {"binding", "bind", "player", "OfflineSteve", PROFILE.toString()},
                sink);
        int afterHandle = playerCalls.get();
        assertTrue(afterHandle > 0, "region-facing handle must read the actor");
        assertTrue(sink.keys.isEmpty(), "handle must return before async resolution completes");
        assertFalse(mutation.called().get());

        executor.runAll();
        assertEquals(afterHandle, playerCalls.get(),
                "resolver-executor completion must not touch the Player");
        assertTrue(sink.keys.isEmpty(),
                "executor-thread completion must not reply directly");
        assertFalse(mutation.called().get(),
                "mutation must wait for the player-thread hop");
        assertEquals(1, scheduler.pending.size(), "exactly one player-thread task must be queued");

        scheduler.runAll();
        assertEquals(afterHandle, playerCalls.get(),
                "player-thread continuation must not need further Player reads");
        assertTrue(mutation.called().get(), "resolved bind must reach the Bindings seam");
        assertEquals(OFFLINE_TARGET, mutation.subject().get().id());
        assertEquals(List.of("command.land.binding.created"), sink.keys);
    }

    @Test
    void retiredSchedulerDropsMutationAndReplyFailClosed() {
        RecordingExecutor executor = new RecordingExecutor();
        PlayerScheduler retired = (player, task) -> {
            throw new IllegalStateException("scheduler retired");
        };
        OfflinePlayerResolver players = new OfflinePlayerResolver(
                name -> Optional.empty(),
                name -> Optional.of(OFFLINE_TARGET),
                executor);
        Mutation mutation = new Mutation(new AtomicBoolean(false), new AtomicReference<>());
        BindingCommandHandler handler = scheduledHandler(mutation, players, retired);
        CapturingSink sink = new CapturingSink();
        handler.handle(player(ACTOR),
                new String[] {"binding", "bind", "player", "OfflineSteve", PROFILE.toString()},
                sink);
        executor.runAll();
        assertFalse(mutation.called().get(), "retired scheduler must drop the mutation");
        assertTrue(sink.keys.isEmpty(), "retired scheduler must drop the reply without leaking");
    }

    @Test
    void schedulerSeamFailureOnSyncPathDropsReplyFailClosed() {
        PlayerScheduler failing = (player, task) -> {
            throw new IllegalStateException("scheduler retired");
        };
        Mutation mutation = new Mutation(new AtomicBoolean(false), new AtomicReference<>());
        BindingCommandHandler handler = new BindingCommandHandler(
                bindings(mutation),
                groups(),
                profiles(),
                sender -> Optional.of(LAND),
                (sender, landId) -> Optional.empty(),
                name -> Optional.of(OFFLINE_TARGET),
                new OfflinePlayerResolver(name -> Optional.empty(), name -> Optional.empty(),
                        Runnable::run),
                failing);
        CapturingSink sink = new CapturingSink();
        handler.handle(player(ACTOR),
                new String[] {"binding", "unbind", "player", "OnlineSteve"},
                sink);
        assertFalse(mutation.called().get(), "failed hop must not mutate");
        assertTrue(sink.keys.isEmpty(), "failed hop must not reply");
    }
}

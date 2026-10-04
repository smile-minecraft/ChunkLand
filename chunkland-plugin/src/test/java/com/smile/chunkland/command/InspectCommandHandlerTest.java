package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.protection.ConfigSubjectPermissionLookup;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import com.smile.chunkland.protection.PermissionDefaultsSnapshot;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Red contract for {@link InspectCommandHandler}: read-only
 * {@code /land inspect} over the immutable snapshot (land/permission/limit
 * observables, no Bukkit world/chunk/SQL/network on the region-facing path),
 * generic fail-closed denials for console/unknown/not-ready, and async
 * offline-player resolution whose reply never blocks the caller.
 */
class InspectCommandHandlerTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    /** Strong references for proxy worlds held weakly by Paper Location. */
    private static final List<World> PINNED_WORLDS = new CopyOnWriteArrayList<>();

    private static World proxyWorld(UUID uid) {
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) {
                        return uid;
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("toString")) {
                        return "World-proxy";
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
        PINNED_WORLDS.add(world);
        return world;
    }

    private static Player playerAt(UUID uuid, UUID worldId, double x, double y, double z) {
        Location location = new Location(proxyWorld(worldId), x, y, z);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return uuid;
                    }
                    if (name.equals("getLocation")) {
                        return location;
                    }
                    if (name.equals("hasPermission")) {
                        return false;
                    }
                    if (name.equals("isPermissionSet")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "Producer";
                    }
                    if (name.equals("sendMessage")) {
                        return null;
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "Producer-proxy";
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
    }

    private static CommandSender consoleSender() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("hasPermission")) {
                        return true;
                    }
                    if (name.equals("isPermissionSet")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "Console";
                    }
                    if (name.equals("sendMessage")) {
                        return null;
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "Console-proxy";
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
                    return null;
                });
    }

    private record Captured(String key, Map<String, Object> vars) {
    }

    private static final class CaptureSink implements ReplySink {
        final List<Captured> replies = new ArrayList<>();

        @Override
        public void reply(String key, Map<String, Object> vars) {
            replies.add(new Captured(key, Map.copyOf(vars)));
        }

        @Override
        public void reply(String key, Map<String, Object> vars, java.util.Locale locale) {
            replies.add(new Captured(key, Map.copyOf(vars)));
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

    private static LandSnapshot homeLand(LandId id, UUID owner, UUID world) {
        LandName name = LandName.of("Home");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(owner), world, Set.of(new ChunkKey(world, 0, 0)),
                List.of(), 3L, 1L, Instant.EPOCH, Instant.EPOCH);
    }

    private record Env(LandRegistryStore store, SnapshotPermissionContextProvider provider,
            LandId landId) {
    }

    private static Env ownerEnv() {
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(homeLand(landId, OWNER, WORLD))));
        SnapshotPermissionContextProvider provider = new SnapshotPermissionContextProvider(null,
                new ConfigSubjectPermissionLookup(
                        PermissionDefaultsSnapshot::empty,
                        LandAuthorisationSnapshot::empty));
        return new Env(store, provider, landId);
    }

    private static InspectCommandHandler handlerOf(Env env,
            InspectCommandHandler.Limits limits, OfflinePlayerResolver players) {
        return new InspectCommandHandler(env.store()::snapshot, () -> env.provider(),
                limits, players);
    }

    private static InspectCommandHandler handlerOf(Env env) {
        return handlerOf(env, ignored -> Map.of(), null);
    }

    private static void assertGenericDenied(CaptureSink sink) {
        assertEquals(1, sink.replies.size(), "inspect failure must reply exactly once");
        assertEquals("command.land.inspect.denied", sink.replies.get(0).key());
        assertTrue(sink.replies.get(0).vars().isEmpty(),
                "inspect denial must carry no vars so land existence is not probed");
    }

    @Test
    void consoleSenderFailsClosed() {
        Env env = ownerEnv();
        CaptureSink sink = new CaptureSink();
        handlerOf(env).handle(consoleSender(), new String[] {"inspect"}, sink);
        assertGenericDenied(sink);
    }

    @Test
    void wildernessFailsClosed() {
        Env env = ownerEnv();
        Player owner = playerAt(OWNER, WORLD, 500.0, 64.0, 500.0);
        CaptureSink sink = new CaptureSink();
        handlerOf(env).handle(owner, new String[] {"inspect"}, sink);
        assertGenericDenied(sink);
    }

    @Test
    void storeNotReadyFailsClosed() {
        Env env = ownerEnv();
        Player owner = playerAt(OWNER, WORLD, 5.0, 64.0, 5.0);
        InspectCommandHandler handler = new InspectCommandHandler(
                () -> {
                    throw new IllegalStateException("not ready");
                },
                () -> env.provider(), o -> Map.of(), null);
        CaptureSink sink = new CaptureSink();
        handler.handle(owner, new String[] {"inspect"}, sink);
        assertGenericDenied(sink);

        InspectCommandHandler nullSnapshot = new InspectCommandHandler(
                () -> null, () -> env.provider(), o -> Map.of(), null);
        CaptureSink second = new CaptureSink();
        nullSnapshot.handle(owner, new String[] {"inspect"}, second);
        assertGenericDenied(second);
    }

    @Test
    void strangerWithoutManagePermissionIsDenied() {
        Env env = ownerEnv();
        Player stranger = playerAt(UUID.randomUUID(), WORLD, 5.0, 64.0, 5.0);
        CaptureSink sink = new CaptureSink();
        handlerOf(env).handle(stranger, new String[] {"inspect"}, sink);
        assertGenericDenied(sink);
    }

    @Test
    void ownerSeesSnapshotOnlyResultWithLandPermissionAndLimit() {
        Env env = ownerEnv();
        AtomicInteger snapshots = new AtomicInteger(0);
        LandRegistryStore counting = new LandRegistryStore();
        counting.publish(env.store().snapshot());
        InspectCommandHandler handler = new InspectCommandHandler(
                () -> {
                    snapshots.incrementAndGet();
                    return counting.snapshot();
                },
                () -> env.provider(),
                owner -> Map.of("limitMaxChunksPerLand", 128L,
                        "limitMaxSublandsPerLand", 16L),
                null);
        Player owner = playerAt(OWNER, WORLD, 5.0, 64.0, 5.0);
        CaptureSink sink = new CaptureSink();
        handler.handle(owner, new String[] {"inspect"}, sink);
        assertEquals(1, sink.replies.size());
        Captured got = sink.replies.get(0);
        assertEquals("command.land.inspect.result", got.key());
        assertEquals(env.landId().value().toString(), got.vars().get("landId"));
        assertEquals("Home", got.vars().get("landName"));
        assertEquals(OWNER.toString(), got.vars().get("owner"));
        assertEquals(WORLD.toString(), got.vars().get("worldId"));
        assertEquals(1, got.vars().get("chunks"));
        assertEquals(0, got.vars().get("sublands"));
        assertEquals(3L, got.vars().get("structureRevision"));
        assertEquals(true, got.vars().get("isOwner"));
        assertEquals(false, got.vars().get("serverLand"));
        assertEquals(128L, got.vars().get("limitMaxChunksPerLand"));
        assertEquals(16L, got.vars().get("limitMaxSublandsPerLand"));
        assertEquals(1, snapshots.get(),
                "inspect must read the immutable snapshot exactly once per call");
    }

    @Test
    void ownerLineShowsThePlayerNameWhenItIsKnownWithoutBlocking() {
        Env env = ownerEnv();
        Player owner = playerAt(OWNER, WORLD, 5.0, 64.0, 5.0);

        InspectCommandHandler named = new InspectCommandHandler(env.store()::snapshot,
                () -> env.provider(), o -> Map.of(), null, null, null,
                id -> OWNER.equals(id) ? "Steve" : null);
        CaptureSink first = new CaptureSink();
        named.handle(owner, new String[] {"inspect"}, first);
        assertEquals("Steve", first.replies.get(0).vars().get("owner"));

        InspectCommandHandler unknown = new InspectCommandHandler(env.store()::snapshot,
                () -> env.provider(), o -> Map.of(), null, null, null, id -> null);
        CaptureSink second = new CaptureSink();
        unknown.handle(owner, new String[] {"inspect"}, second);
        assertEquals(OWNER.toString(), second.replies.get(0).vars().get("owner"),
                "a name the cache does not know falls back to the UUID");

        InspectCommandHandler failing = new InspectCommandHandler(env.store()::snapshot,
                () -> env.provider(), o -> Map.of(), null, null, null, id -> {
                    throw new IllegalStateException("profile cache unavailable");
                });
        CaptureSink third = new CaptureSink();
        failing.handle(owner, new String[] {"inspect"}, third);
        assertEquals("command.land.inspect.result", third.replies.get(0).key(),
                "a failing name lookup must not cost the player the answer");
        assertEquals(OWNER.toString(), third.replies.get(0).vars().get("owner"));
    }

    @Test
    void tooManyArgsRepliesUsage() {
        Env env = ownerEnv();
        Player owner = playerAt(OWNER, WORLD, 5.0, 64.0, 5.0);
        CaptureSink sink = new CaptureSink();
        handlerOf(env).handle(owner, new String[] {"inspect", "a", "b"}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.inspect.usage", sink.replies.get(0).key());
    }

    @Test
    void playerArgResolvesAsyncWithoutBlockingCaller() {
        Env env = ownerEnv();
        RecordingExecutor executor = new RecordingExecutor();
        AtomicInteger offlineCalls = new AtomicInteger(0);
        UUID target = UUID.randomUUID();
        OfflinePlayerResolver players = new OfflinePlayerResolver(
                name -> Optional.empty(),
                name -> {
                    offlineCalls.incrementAndGet();
                    return Optional.of(target);
                },
                executor);
        InspectCommandHandler handler = handlerOf(env, ignored -> Map.of(), players);
        Player owner = playerAt(OWNER, WORLD, 5.0, 64.0, 5.0);
        CaptureSink sink = new CaptureSink();
        handler.handle(owner, new String[] {"inspect", "OfflineSteve"}, sink);
        assertTrue(sink.replies.isEmpty(),
                "region-facing inspect must return before async resolution completes");
        assertEquals(0, offlineCalls.get(), "blocking lookup must not run on the caller");
        assertEquals(1, executor.pending.size());
        executor.runAll();
        assertEquals(1, sink.replies.size());
        Captured got = sink.replies.get(0);
        assertEquals("command.land.inspect.result", got.key());
        assertEquals(target.toString(), got.vars().get("playerUuid"));
        assertEquals(env.landId().value().toString(), got.vars().get("landId"));
    }

    @Test
    void unknownPlayerArgFailsClosedWithoutLeak() {
        Env env = ownerEnv();
        RecordingExecutor executor = new RecordingExecutor();
        OfflinePlayerResolver players = new OfflinePlayerResolver(
                name -> Optional.empty(),
                name -> Optional.empty(),
                executor);
        InspectCommandHandler handler = handlerOf(env, ignored -> Map.of(), players);
        Player owner = playerAt(OWNER, WORLD, 5.0, 64.0, 5.0);
        CaptureSink sink = new CaptureSink();
        handler.handle(owner, new String[] {"inspect", "NobodyEverPlayed"}, sink);
        assertTrue(sink.replies.isEmpty());
        executor.runAll();
        assertGenericDenied(sink);
    }

    @Test
    void playerArgWithoutResolverFailsClosed() {
        Env env = ownerEnv();
        Player owner = playerAt(OWNER, WORLD, 5.0, 64.0, 5.0);
        CaptureSink sink = new CaptureSink();
        handlerOf(env).handle(owner, new String[] {"inspect", "Someone"}, sink);
        assertGenericDenied(sink);
    }

    @Test
    void uuidPlayerArgResolvesWithoutExecutor() {
        Env env = ownerEnv();
        AtomicInteger executorTasks = new AtomicInteger(0);
        UUID target = UUID.randomUUID();
        OfflinePlayerResolver players = new OfflinePlayerResolver(
                name -> Optional.empty(),
                name -> Optional.of(target),
                task -> executorTasks.incrementAndGet());
        InspectCommandHandler handler = handlerOf(env, ignored -> Map.of(), players);
        Player owner = playerAt(OWNER, WORLD, 5.0, 64.0, 5.0);
        CaptureSink sink = new CaptureSink();
        handler.handle(owner, new String[] {"inspect", target.toString()}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.inspect.result", sink.replies.get(0).key());
        assertEquals(target.toString(), sink.replies.get(0).vars().get("playerUuid"));
        assertEquals(0, executorTasks.get(), "UUID text must not touch the executor");
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

    /** Player proxy at a position that counts every Bukkit call. */
    private static Player countingPlayerAt(UUID uuid, UUID worldId, double x, double y, double z,
            AtomicInteger calls) {
        Location location = new Location(proxyWorld(worldId), x, y, z);
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
                        return "Producer-proxy";
                    }
                    calls.incrementAndGet();
                    return switch (name) {
                        case "getUniqueId" -> uuid;
                        case "getLocation" -> location;
                        case "hasPermission" -> false;
                        case "isPermissionSet" -> true;
                        case "getName" -> "Producer";
                        case "sendMessage" -> null;
                        default -> method.getReturnType() == boolean.class ? false : null;
                    };
                });
    }

    private static InspectCommandHandler scheduledHandlerOf(Env env,
            InspectCommandHandler.Limits limits, OfflinePlayerResolver players,
            PlayerScheduler scheduler) {
        return new InspectCommandHandler(env.store()::snapshot, () -> env.provider(),
                limits, players, scheduler);
    }

    @Test
    void playerArgCompletionRepliesOnlyOnPlayerScheduler() {
        Env env = ownerEnv();
        RecordingExecutor executor = new RecordingExecutor();
        RecordingScheduler scheduler = new RecordingScheduler();
        UUID target = UUID.randomUUID();
        OfflinePlayerResolver players = new OfflinePlayerResolver(
                name -> Optional.empty(),
                name -> Optional.of(target),
                executor);
        InspectCommandHandler handler =
                scheduledHandlerOf(env, ignored -> Map.of(), players, scheduler);
        AtomicInteger playerCalls = new AtomicInteger(0);
        Player owner = countingPlayerAt(OWNER, WORLD, 5.0, 64.0, 5.0, playerCalls);
        CaptureSink sink = new CaptureSink();
        handler.handle(owner, new String[] {"inspect", "OfflineSteve"}, sink);
        int afterHandle = playerCalls.get();
        assertTrue(afterHandle > 0, "region-facing handle must read actor and position");
        assertTrue(sink.replies.isEmpty(),
                "region-facing inspect must return before async resolution completes");

        executor.runAll();
        assertEquals(afterHandle, playerCalls.get(),
                "resolver-executor completion must not touch the Player");
        assertTrue(sink.replies.isEmpty(),
                "executor-thread completion must not reply directly");
        assertEquals(1, scheduler.pending.size(),
                "exactly one player-thread task must be queued");

        scheduler.runAll();
        assertEquals(afterHandle, playerCalls.get(),
                "player-thread reply must use captured values, not fresh Player reads");
        assertEquals(1, sink.replies.size());
        Captured got = sink.replies.get(0);
        assertEquals("command.land.inspect.result", got.key());
        assertEquals(target.toString(), got.vars().get("playerUuid"));
        assertEquals("OfflineSteve", got.vars().get("playerRef"));
        assertEquals(env.landId().value().toString(), got.vars().get("landId"));
    }

    @Test
    void retiredSchedulerDropsPlayerArgReplyFailClosed() {
        Env env = ownerEnv();
        RecordingExecutor executor = new RecordingExecutor();
        PlayerScheduler retired = (player, task) -> {
            throw new IllegalStateException("scheduler retired");
        };
        UUID target = UUID.randomUUID();
        OfflinePlayerResolver players = new OfflinePlayerResolver(
                name -> Optional.empty(),
                name -> Optional.of(target),
                executor);
        InspectCommandHandler handler =
                scheduledHandlerOf(env, ignored -> Map.of(), players, retired);
        Player owner = playerAt(OWNER, WORLD, 5.0, 64.0, 5.0);
        CaptureSink sink = new CaptureSink();
        handler.handle(owner, new String[] {"inspect", "OfflineSteve"}, sink);
        executor.runAll();
        assertTrue(sink.replies.isEmpty(),
                "retired scheduler must drop the reply without leaking");
    }
}

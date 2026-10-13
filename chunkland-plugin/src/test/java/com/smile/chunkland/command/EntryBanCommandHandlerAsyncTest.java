package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Ban/unban handlers accept known offline names through the shared async
 * operation, keep refusing the land owner and Server Land after resolution,
 * and re-validate the fixed land plus the viewer before writing.
 */
class EntryBanCommandHandlerAsyncTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());
    private static final LandId SERVER_LAND = new LandId(UUID.randomUUID());

    private static final class FakeMutation implements EntryBanCommandHandler.BanMutation {
        final CopyOnWriteArrayList<UUID> targets = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<Void> apply(UUID actor, LandId landId, UUID target) {
            targets.add(target);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class CapturingSink implements ReplySink {
        final List<String> keys = new ArrayList<>();
        final Map<String, Map<String, Object>> vars = new HashMap<>();

        @Override
        public void reply(String messageKey, Map<String, Object> vs) {
            keys.add(messageKey);
            vars.put(messageKey, Map.copyOf(vs));
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vs, Locale localeOverride) {
            reply(messageKey, vs);
        }
    }

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

    private static LandSnapshot playerLand() {
        UUID world = UUID.randomUUID();
        return new LandSnapshot(LAND, "Home", "home", OwnerRef.player(OWNER), world,
                Set.of(new ChunkKey(world, 0, 0)), List.of(),
                0, 0, Instant.EPOCH, Instant.EPOCH);
    }

    private static LandSnapshot serverLand() {
        UUID world = UUID.randomUUID();
        return new LandSnapshot(SERVER_LAND, "Spawn", "spawn", OwnerRef.server(), world,
                Set.of(new ChunkKey(world, 0, 0)), List.of(),
                0, 0, Instant.EPOCH, Instant.EPOCH);
    }

    private static final class Wiring {
        final FakeMutation mutation = new FakeMutation();
        final RecordingExecutor executor = new RecordingExecutor();
        final AtomicBoolean gateOpen = new AtomicBoolean(true);
        final AtomicBoolean online = new AtomicBoolean(true);
        final AtomicBoolean alive = new AtomicBoolean(true);
        final EntryBanCommandHandler handler;

        Wiring(EntryBanCommandHandler.Mode mode, LandSnapshot view, String offlineName) {
            OfflinePlayerResolver resolver = new OfflinePlayerResolver(
                    name -> "OnlineExact".equals(name) ? Optional.of(TARGET) : Optional.empty(),
                    name -> offlineName.equals(name) ? Optional.of(TARGET) : Optional.empty(),
                    executor);
            handler = new EntryBanCommandHandler(mode,
                    sender -> Optional.of(view.id()),
                    name -> "OnlineExact".equals(name) ? Optional.of(TARGET) : Optional.empty(),
                    resolver, PlayerScheduler.direct(),
                    (viewer, land, slot) -> gateOpen.get(),
                    viewer -> online.get(),
                    land -> true,
                    alive::get,
                    landId -> Optional.of(view),
                    mutation);
        }
    }

    @Test
    void offlineNameBanDoesNotBlockAndWritesAfterResolution() {
        Wiring wiring = new Wiring(EntryBanCommandHandler.Mode.BAN, playerLand(), "OfflineSteve");
        CapturingSink sink = new CapturingSink();
        wiring.handler.handle(player(ACTOR), new String[] {"ban", "OfflineSteve"}, sink);
        assertTrue(sink.keys.isEmpty(), "handle must return before async resolution completes");
        assertTrue(wiring.mutation.targets.isEmpty(), "no write before the offline lookup runs");
        assertEquals(1, wiring.executor.pending.size(), "exactly one async lookup must be queued");

        wiring.executor.runAll();

        assertEquals(List.of(TARGET), List.copyOf(wiring.mutation.targets));
        assertEquals(List.of("command.land.ban.success"), sink.keys);
    }

    @Test
    void unbanOfflineNameUsesUnbanKeys() {
        Wiring wiring = new Wiring(EntryBanCommandHandler.Mode.UNBAN, playerLand(), "OfflineSteve");
        CapturingSink sink = new CapturingSink();
        wiring.handler.handle(player(ACTOR), new String[] {"unban", "OfflineSteve"}, sink);
        wiring.executor.runAll();
        assertEquals(List.of("command.land.unban.success"), sink.keys);
        assertEquals(List.of(TARGET), List.copyOf(wiring.mutation.targets));
    }

    @Test
    void unknownOfflineNameIsDeniedWithoutWriting() {
        Wiring wiring = new Wiring(EntryBanCommandHandler.Mode.BAN, playerLand(), "SomeoneElse");
        CapturingSink sink = new CapturingSink();
        wiring.handler.handle(player(ACTOR), new String[] {"ban", "NobodyEverPlayed"}, sink);
        assertTrue(sink.keys.isEmpty());
        wiring.executor.runAll();
        assertTrue(wiring.mutation.targets.isEmpty(), "unknown names must never write");
        assertEquals(List.of("command.land.ban.failed"), sink.keys);
        assertEquals("ban.unknown_target",
                sink.vars.get("command.land.ban.failed").get("reason"));
    }

    @Test
    void ownerBanIsDeniedWithoutWriting() {
        Wiring wiring = new Wiring(EntryBanCommandHandler.Mode.BAN, playerLand(), "OfflineSteve");
        CapturingSink sink = new CapturingSink();
        wiring.handler.handle(player(ACTOR), new String[] {"ban", OWNER.toString()}, sink);
        assertTrue(wiring.mutation.targets.isEmpty(), "banning the owner must never write");
        assertEquals(List.of("command.land.ban.failed"), sink.keys);
        assertEquals("ban.not_allowed", sink.vars.get("command.land.ban.failed").get("reason"));
    }

    @Test
    void serverLandBanIsDeniedWithoutWriting() {
        Wiring wiring = new Wiring(EntryBanCommandHandler.Mode.BAN, serverLand(), "OfflineSteve");
        CapturingSink sink = new CapturingSink();
        wiring.handler.handle(player(ACTOR), new String[] {"ban", "OfflineSteve"}, sink);
        wiring.executor.runAll();
        assertTrue(wiring.mutation.targets.isEmpty(), "banning on Server Land must never write");
        assertEquals(List.of("command.land.ban.failed"), sink.keys);
    }

    @Test
    void revokedGateDeniesWithoutWriting() {
        Wiring wiring = new Wiring(EntryBanCommandHandler.Mode.BAN, playerLand(), "OfflineSteve");
        CapturingSink sink = new CapturingSink();
        wiring.handler.handle(player(ACTOR), new String[] {"ban", "OfflineSteve"}, sink);
        wiring.gateOpen.set(false);
        wiring.executor.runAll();
        assertTrue(wiring.mutation.targets.isEmpty(), "a revoked viewer must not write");
        assertEquals(List.of("command.land.ban.failed"), sink.keys);
    }

    @Test
    void uuidTextStillResolvesSynchronously() {
        Wiring wiring = new Wiring(EntryBanCommandHandler.Mode.BAN, playerLand(), "OfflineSteve");
        CapturingSink sink = new CapturingSink();
        wiring.handler.handle(player(ACTOR), new String[] {"ban", TARGET.toString()}, sink);
        assertEquals(List.of("command.land.ban.success"), sink.keys);
        assertEquals(List.of(TARGET), List.copyOf(wiring.mutation.targets));
        assertTrue(wiring.executor.pending.isEmpty(), "UUID text must not touch the async executor");
    }
}

package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.LandId;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Trust/untrust handlers accept known offline names through the shared
 * async operation: the region-facing call returns before resolution
 * completes, and the fixed land plus the viewer are re-validated on the
 * viewer thread before the single allowed write.
 */
class DirectTrustCommandHandlerAsyncTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());

    private static final class FakeMutation implements DirectTrustCommandHandler.TrustMutation {
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

    private static final class Wiring {
        final FakeMutation mutation = new FakeMutation();
        final RecordingExecutor executor = new RecordingExecutor();
        final AtomicBoolean gateOpen = new AtomicBoolean(true);
        final AtomicBoolean online = new AtomicBoolean(true);
        final AtomicBoolean landKnown = new AtomicBoolean(true);
        final AtomicBoolean alive = new AtomicBoolean(true);
        final DirectTrustCommandHandler handler;

        Wiring(DirectTrustCommandHandler.Mode mode, String offlineName) {
            OfflinePlayerResolver resolver = new OfflinePlayerResolver(
                    name -> "OnlineExact".equals(name) ? Optional.of(TARGET) : Optional.empty(),
                    name -> offlineName.equals(name) ? Optional.of(TARGET) : Optional.empty(),
                    executor);
            handler = new DirectTrustCommandHandler(mode,
                    sender -> Optional.of(LAND),
                    name -> "OnlineExact".equals(name) ? Optional.of(TARGET) : Optional.empty(),
                    resolver, PlayerScheduler.direct(),
                    (viewer, land, slot) -> gateOpen.get(),
                    viewer -> online.get(),
                    land -> landKnown.get(),
                    alive::get,
                    mutation);
        }
    }

    @Test
    void offlineNameTrustDoesNotBlockAndWritesAfterResolution() {
        Wiring wiring = new Wiring(DirectTrustCommandHandler.Mode.TRUST, "OfflineSteve");
        CapturingSink sink = new CapturingSink();
        wiring.handler.handle(player(ACTOR), new String[] {"trust", "OfflineSteve"}, sink);
        assertTrue(sink.keys.isEmpty(), "handle must return before async resolution completes");
        assertTrue(wiring.mutation.targets.isEmpty(), "no write before the offline lookup runs");
        assertEquals(1, wiring.executor.pending.size(), "exactly one async lookup must be queued");

        wiring.executor.runAll();

        assertEquals(List.of(TARGET), List.copyOf(wiring.mutation.targets));
        assertEquals(List.of("command.land.trust.success"), sink.keys);
    }

    @Test
    void untrustOfflineNameUsesUntrustKeys() {
        Wiring wiring = new Wiring(DirectTrustCommandHandler.Mode.UNTRUST, "OfflineSteve");
        CapturingSink sink = new CapturingSink();
        wiring.handler.handle(player(ACTOR), new String[] {"untrust", "OfflineSteve"}, sink);
        wiring.executor.runAll();
        assertEquals(List.of("command.land.untrust.success"), sink.keys);
        assertEquals(List.of(TARGET), List.copyOf(wiring.mutation.targets));
    }

    @Test
    void unknownOfflineNameIsDeniedWithoutWriting() {
        Wiring wiring = new Wiring(DirectTrustCommandHandler.Mode.TRUST, "SomeoneElse");
        CapturingSink sink = new CapturingSink();
        wiring.handler.handle(player(ACTOR), new String[] {"trust", "NobodyEverPlayed"}, sink);
        assertTrue(sink.keys.isEmpty());
        wiring.executor.runAll();
        assertTrue(wiring.mutation.targets.isEmpty(), "unknown names must never write");
        assertEquals(List.of("command.land.trust.failed"), sink.keys);
        assertEquals("trust.unknown_target",
                sink.vars.get("command.land.trust.failed").get("reason"));
    }

    @Test
    void reservedNamesNeverReachTheAsyncLookup() {
        Wiring wiring = new Wiring(DirectTrustCommandHandler.Mode.TRUST, "OfflineSteve");
        for (String reserved : List.of("EVERYONE", "*", "group(Friends)")) {
            CapturingSink sink = new CapturingSink();
            wiring.handler.handle(player(ACTOR), new String[] {"trust", reserved}, sink);
            assertEquals(List.of("command.land.trust.failed"), sink.keys,
                    reserved + " must fail closed");
            assertTrue(wiring.mutation.targets.isEmpty());
        }
        assertTrue(wiring.executor.pending.isEmpty(), "reserved names must never queue a lookup");
    }

    @Test
    void revokedGateDeniesWithoutWriting() {
        Wiring wiring = new Wiring(DirectTrustCommandHandler.Mode.TRUST, "OfflineSteve");
        CapturingSink sink = new CapturingSink();
        wiring.handler.handle(player(ACTOR), new String[] {"trust", "OfflineSteve"}, sink);
        wiring.gateOpen.set(false);
        wiring.executor.runAll();
        assertTrue(wiring.mutation.targets.isEmpty(), "a revoked viewer must not write");
        assertEquals(List.of("command.land.trust.failed"), sink.keys);
    }

    @Test
    void deletedLandDeniesWithoutWriting() {
        Wiring wiring = new Wiring(DirectTrustCommandHandler.Mode.TRUST, "OfflineSteve");
        CapturingSink sink = new CapturingSink();
        wiring.handler.handle(player(ACTOR), new String[] {"trust", "OfflineSteve"}, sink);
        wiring.landKnown.set(false);
        wiring.executor.runAll();
        assertTrue(wiring.mutation.targets.isEmpty(), "a deleted land must not be written to");
        assertEquals(List.of("command.land.trust.failed"), sink.keys);
    }

    @Test
    void uuidTextStillResolvesSynchronously() {
        Wiring wiring = new Wiring(DirectTrustCommandHandler.Mode.TRUST, "OfflineSteve");
        CapturingSink sink = new CapturingSink();
        wiring.handler.handle(player(ACTOR), new String[] {"trust", TARGET.toString()}, sink);
        assertEquals(List.of("command.land.trust.success"), sink.keys);
        assertEquals(List.of(TARGET), List.copyOf(wiring.mutation.targets));
        assertTrue(wiring.executor.pending.isEmpty(), "UUID text must not touch the async executor");
    }
}

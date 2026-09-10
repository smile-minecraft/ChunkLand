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
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Ban/unban handlers resolve the current-location land and an online player
 * target, refuse the land owner and Server Land without side effects, then
 * run the durable mutation exactly once; every other unresolvable input
 * fails closed and never answers {@code not_yet}.
 */
class EntryBanCommandHandlerTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());
    private static final LandId SERVER_LAND = new LandId(UUID.randomUUID());

    private record Call(UUID actor, LandId land, UUID target) {
    }

    private static final class FakeMutation implements EntryBanCommandHandler.BanMutation {
        final CopyOnWriteArrayList<Call> calls = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        @Override
        public CompletionStage<Void> apply(UUID actor, LandId landId, UUID target) {
            calls.add(new Call(actor, landId, target));
            if (fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("injected"));
            }
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

    private static Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "hasPermission" -> true;
                        case "getName" -> "Actor";
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "Player-proxy";
                        default -> method.getReturnType() == boolean.class ? false : null;
                    };
                });
    }

    private static CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return true;
                    }
                    return method.getReturnType() == boolean.class ? false : null;
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

    private EntryBanCommandHandler handler(EntryBanCommandHandler.Mode mode,
            FakeMutation mutation, AtomicInteger landCalls, LandSnapshot view) {
        return new EntryBanCommandHandler(mode,
                sender -> {
                    landCalls.incrementAndGet();
                    return Optional.of(view.id());
                },
                name -> TARGET.toString().equals(name) || "Target".equals(name)
                        ? Optional.of(TARGET) : Optional.empty(),
                landId -> Optional.of(view),
                mutation);
    }

    @Test
    void banSuccessResolvesLandAndTargetAndMutatesOnce() {
        FakeMutation mutation = new FakeMutation();
        AtomicInteger landCalls = new AtomicInteger();
        CapturingSink sink = new CapturingSink();
        handler(EntryBanCommandHandler.Mode.BAN, mutation, landCalls, playerLand())
                .handle(player(ACTOR), new String[] {"ban", TARGET.toString()}, sink);
        assertEquals(List.of("command.land.ban.success"), sink.keys);
        assertEquals(1, mutation.calls.size());
        assertEquals(new Call(ACTOR, LAND, TARGET), mutation.calls.get(0));
        assertEquals(1, landCalls.get());
    }

    @Test
    void unbanSuccessUsesUnbanKeys() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        handler(EntryBanCommandHandler.Mode.UNBAN, mutation, new AtomicInteger(), playerLand())
                .handle(player(ACTOR), new String[] {"unban", "Target"}, sink);
        assertEquals(List.of("command.land.unban.success"), sink.keys);
        assertEquals(1, mutation.calls.size());
    }

    @Test
    void banLandOwnerFailsClosedWithZeroSideEffects() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        handler(EntryBanCommandHandler.Mode.BAN, mutation, new AtomicInteger(), playerLand())
                .handle(player(ACTOR), new String[] {"ban", OWNER.toString()}, sink);
        assertEquals(List.of("command.land.ban.failed"), sink.keys);
        assertTrue(mutation.calls.isEmpty(), "banning the owner must never reach the mutation");
    }

    @Test
    void banOnServerLandFailsClosedWithZeroSideEffects() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        handler(EntryBanCommandHandler.Mode.BAN, mutation, new AtomicInteger(), serverLand())
                .handle(player(ACTOR), new String[] {"ban", TARGET.toString()}, sink);
        assertEquals(List.of("command.land.ban.failed"), sink.keys);
        assertTrue(mutation.calls.isEmpty(), "banning on Server Land must never reach the mutation");
    }

    @Test
    void reservedTargetsNeverResolve() {
        for (String reserved : List.of("EVERYONE", "*", "group(friends)")) {
            FakeMutation mutation = new FakeMutation();
            CapturingSink sink = new CapturingSink();
            handler(EntryBanCommandHandler.Mode.BAN, mutation, new AtomicInteger(), playerLand())
                    .handle(player(ACTOR), new String[] {"ban", reserved}, sink);
            assertEquals(List.of("command.land.ban.failed"), sink.keys,
                    reserved + " must never resolve as a ban target");
            assertTrue(mutation.calls.isEmpty());
        }
    }

    @Test
    void unknownTargetUnknownLandAndConsoleFailClosed() {
        FakeMutation offline = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        handler(EntryBanCommandHandler.Mode.BAN, offline, new AtomicInteger(), playerLand())
                .handle(player(ACTOR), new String[] {"ban", "NobodyOnline"}, sink);
        assertEquals(List.of("command.land.ban.failed"), sink.keys);
        assertTrue(offline.calls.isEmpty());

        FakeMutation noLand = new FakeMutation();
        CapturingSink noLandSink = new CapturingSink();
        new EntryBanCommandHandler(EntryBanCommandHandler.Mode.BAN,
                sender -> Optional.empty(),
                name -> Optional.of(TARGET),
                landId -> Optional.of(playerLand()),
                noLand).handle(player(ACTOR), new String[] {"ban", TARGET.toString()}, noLandSink);
        assertEquals(List.of("command.land.ban.failed"), noLandSink.keys);
        assertTrue(noLand.calls.isEmpty());

        FakeMutation consoleMutation = new FakeMutation();
        CapturingSink consoleSink = new CapturingSink();
        handler(EntryBanCommandHandler.Mode.BAN, consoleMutation, new AtomicInteger(), playerLand())
                .handle(console(), new String[] {"ban", TARGET.toString()}, consoleSink);
        assertEquals(List.of("command.land.ban.console"), consoleSink.keys);
        assertTrue(consoleMutation.calls.isEmpty());
    }

    @Test
    void missingMutationRepliesUnavailableWithoutSideEffects() {
        CapturingSink sink = new CapturingSink();
        new EntryBanCommandHandler(EntryBanCommandHandler.Mode.BAN,
                sender -> Optional.of(LAND),
                name -> Optional.of(TARGET),
                landId -> Optional.of(playerLand()),
                null).handle(player(ACTOR), new String[] {"ban", TARGET.toString()}, sink);
        assertEquals(List.of("command.land.ban.failed"), sink.keys);
    }

    @Test
    void failedMutationRepliesFailed() {
        FakeMutation mutation = new FakeMutation();
        mutation.fail = true;
        CapturingSink sink = new CapturingSink();
        handler(EntryBanCommandHandler.Mode.BAN, mutation, new AtomicInteger(), playerLand())
                .handle(player(ACTOR), new String[] {"ban", TARGET.toString()}, sink);
        assertEquals(List.of("command.land.ban.failed"), sink.keys);
    }
}

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
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Trust/untrust handlers resolve the current-location land and an online
 * player target, then run the durable mutation exactly once; every
 * unresolvable input fails closed with zero side effects and never answers
 * {@code not_yet}.
 */
class DirectTrustCommandHandlerTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());

    private record Call(UUID actor, LandId land, UUID target) {
    }

    private static final class FakeMutation implements DirectTrustCommandHandler.TrustMutation {
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

    private DirectTrustCommandHandler handler(DirectTrustCommandHandler.Mode mode,
            FakeMutation mutation, AtomicInteger landCalls) {
        return new DirectTrustCommandHandler(mode,
                sender -> {
                    landCalls.incrementAndGet();
                    return Optional.of(LAND);
                },
                name -> TARGET.toString().equals(name) || "Target".equals(name)
                        ? Optional.of(TARGET) : Optional.empty(),
                mutation);
    }

    @Test
    void trustSuccessResolvesLandAndTargetAndMutatesOnce() {
        FakeMutation mutation = new FakeMutation();
        AtomicInteger landCalls = new AtomicInteger();
        CapturingSink sink = new CapturingSink();
        handler(DirectTrustCommandHandler.Mode.TRUST, mutation, landCalls)
                .handle(player(ACTOR), new String[] {"trust", TARGET.toString()}, sink);
        assertEquals(List.of("command.land.trust.success"), sink.keys);
        assertEquals(1, mutation.calls.size());
        assertEquals(new Call(ACTOR, LAND, TARGET), mutation.calls.get(0));
        assertEquals(1, landCalls.get());
    }

    @Test
    void untrustSuccessUsesUntrustKeys() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        handler(DirectTrustCommandHandler.Mode.UNTRUST, mutation, new AtomicInteger())
                .handle(player(ACTOR), new String[] {"untrust", "Target"}, sink);
        assertEquals(List.of("command.land.untrust.success"), sink.keys);
        assertEquals(1, mutation.calls.size());
    }

    @Test
    void consoleMissingArgWildcardUnknownTargetAndUnknownLandFailClosed() {
        for (DirectTrustCommandHandler.Mode mode : DirectTrustCommandHandler.Mode.values()) {
            String base = mode == DirectTrustCommandHandler.Mode.TRUST ? "trust" : "untrust";
            // Console sender.
            FakeMutation mutation = new FakeMutation();
            CapturingSink sink = new CapturingSink();
            handler(mode, mutation, new AtomicInteger())
                    .handle(console(), new String[] {base, TARGET.toString()}, sink);
            assertEquals(List.of("command.land." + base + ".console"), sink.keys);
            assertTrue(mutation.calls.isEmpty());

            // Missing target arg.
            sink = new CapturingSink();
            handler(mode, mutation, new AtomicInteger())
                    .handle(player(ACTOR), new String[] {base}, sink);
            assertEquals(List.of("command.land." + base + ".usage"), sink.keys);
            assertTrue(mutation.calls.isEmpty());

            // Wildcard and reserved names never resolve.
            for (String wildcard : List.of("EVERYONE", "everyone", "*", "group(Friends)")) {
                sink = new CapturingSink();
                handler(mode, mutation, new AtomicInteger())
                        .handle(player(ACTOR), new String[] {base, wildcard}, sink);
                assertEquals(List.of("command.land." + base + ".failed"), sink.keys,
                        wildcard + " must fail closed");
                assertEquals(base + ".unknown_target", sink.vars.get(
                        "command.land." + base + ".failed").get("reason"));
                assertTrue(mutation.calls.isEmpty());
            }

            // Offline/unknown target.
            sink = new CapturingSink();
            handler(mode, mutation, new AtomicInteger())
                    .handle(player(ACTOR), new String[] {base, "NobodyOnline"}, sink);
            assertEquals(List.of("command.land." + base + ".failed"), sink.keys);
            assertTrue(mutation.calls.isEmpty());

            // Wilderness / unknown land.
            FakeMutation landMutation = new FakeMutation();
            CapturingSink landSink = new CapturingSink();
            new DirectTrustCommandHandler(mode, sender -> Optional.empty(),
                    name -> Optional.of(TARGET), landMutation)
                    .handle(player(ACTOR), new String[] {base, TARGET.toString()}, landSink);
            assertEquals(List.of("command.land." + base + ".failed"), landSink.keys);
            assertEquals(base + ".unknown_land", landSink.vars.get(
                    "command.land." + base + ".failed").get("reason"));
            assertTrue(landMutation.calls.isEmpty());
        }
    }

    @Test
    void mutationFailureRepliesFailedWithoutThrowing() {
        FakeMutation mutation = new FakeMutation();
        mutation.fail = true;
        CapturingSink sink = new CapturingSink();
        handler(DirectTrustCommandHandler.Mode.TRUST, mutation, new AtomicInteger())
                .handle(player(ACTOR), new String[] {"trust", TARGET.toString()}, sink);
        assertEquals(List.of("command.land.trust.failed"), sink.keys);
        assertEquals("trust.failed", sink.vars.get("command.land.trust.failed").get("reason"));
        assertEquals(1, mutation.calls.size(), "the attempt happened, the reply carries the failure");
    }

    @Test
    void neverAnswersNotYet() {
        FakeMutation mutation = new FakeMutation();
        for (DirectTrustCommandHandler.Mode mode : DirectTrustCommandHandler.Mode.values()) {
            CapturingSink sink = new CapturingSink();
            handler(mode, mutation, new AtomicInteger())
                    .handle(player(ACTOR), new String[] {"trust", TARGET.toString()}, sink);
            assertTrue(sink.keys.stream().noneMatch("command.land.not_yet"::equals));
        }
    }
}

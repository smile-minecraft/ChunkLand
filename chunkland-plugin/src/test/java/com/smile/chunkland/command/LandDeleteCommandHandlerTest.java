package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.claim.DeleteOutcome;
import com.smile.chunkland.claim.DeleteRequest;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Two-step confirmation contract for {@code /land delete}: a bare call
 * prompts with the live structure revision, and only {@code confirm
 * <revision>} reaches the saga with the token the actor reviewed.
 */
class LandDeleteCommandHandlerTest {

    static final class Reply {
        final String key;
        final Map<String, Object> vars;

        Reply(String key, Map<String, Object> vars) {
            this.key = key;
            this.vars = Map.copyOf(vars);
        }
    }

    static final class LatchSink implements ReplySink {
        final List<Reply> replies = new CopyOnWriteArrayList<>();
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);

        @Override
        public void reply(String messageKey, Map<String, Object> vars) {
            replies.add(new Reply(messageKey, vars));
            latch.countDown();
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
            reply(messageKey, vars);
        }

        void awaitReply() throws Exception {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "handler must reply exactly once");
        }
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[] {Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return id;
                    }
                    if (name.equals("hasPermission")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "TestPlayer";
                    }
                    if (name.equals("equals") || name.equals("hashCode")
                            || name.equals("toString")) {
                        return switch (name) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "Player-proxy:" + id;
                        };
                    }
                    Class<?> result = method.getReturnType();
                    if (result == boolean.class) {
                        return false;
                    }
                    if (result == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    private static CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? true : null);
    }

    private final UUID actor = UUID.randomUUID();
    private final UUID world = UUID.randomUUID();
    private final LandId land = new LandId(UUID.randomUUID());

    private Function<CommandSender, Optional<LandId>> standingOnLand() {
        return sender -> Optional.of(land);
    }

    private Function<LandId, Optional<OwnerRef>> playerOwned() {
        return target -> Optional.of(OwnerRef.player(actor));
    }

    private Function<LandId, Optional<OwnerRef>> serverOwned() {
        return target -> Optional.of(OwnerRef.server());
    }

    private Function<LandId, Optional<LandDeleteCommandHandler.TargetView>> view() {
        return target -> Optional.of(
                new LandDeleteCommandHandler.TargetView("Home", world, 2));
    }

    private com.smile.chunkland.selection.SelectionStructureRevisionLookup structures(long revision) {
        return target -> OptionalLong.of(revision);
    }

    @Test
    void consoleSenderIsRefusedWithoutTouchingSaga() {
        AtomicReference<DeleteRequest> seen = new AtomicReference<>();
        LandDeleteCommandHandler handler = new LandDeleteCommandHandler(
                request -> {
                    seen.set(request);
                    return CompletableFuture.completedFuture(DeleteOutcome.success(land, 200L));
                },
                null, standingOnLand(), playerOwned(), view(), structures(7L), null);
        LatchSink sink = new LatchSink();
        handler.handle(console(), new String[] {"delete"}, sink);

        assertEquals("command.land.delete.console", sink.replies.get(0).key);
        assertTrue(seen.get() == null, "console must never reach the saga");
    }

    @Test
    void wildernessRepliesNoTarget() {
        LandDeleteCommandHandler handler = new LandDeleteCommandHandler(
                request -> CompletableFuture.completedFuture(DeleteOutcome.success(land, 0L)),
                null, sender -> Optional.empty(), playerOwned(), view(), structures(7L), null);
        LatchSink sink = new LatchSink();
        handler.handle(player(actor), new String[] {"delete"}, sink);

        assertEquals("command.land.delete.no_target", sink.replies.get(0).key);
    }

    @Test
    void bareDeletePromptsWithLiveRevision() {
        AtomicReference<DeleteRequest> seen = new AtomicReference<>();
        LandDeleteCommandHandler handler = new LandDeleteCommandHandler(
                request -> {
                    seen.set(request);
                    return CompletableFuture.completedFuture(DeleteOutcome.success(land, 200L));
                },
                null, standingOnLand(), playerOwned(), view(), structures(7L), null);
        LatchSink sink = new LatchSink();
        handler.handle(player(actor), new String[] {"delete"}, sink);

        assertEquals("command.land.delete.confirm", sink.replies.get(0).key);
        assertEquals(7L, sink.replies.get(0).vars.get("revision"));
        assertTrue(seen.get() == null, "the prompt must not touch the saga");
    }

    @Test
    void confirmWithoutRevisionRepliesUsage() {
        LandDeleteCommandHandler handler = new LandDeleteCommandHandler(
                request -> CompletableFuture.completedFuture(DeleteOutcome.success(land, 0L)),
                null, standingOnLand(), playerOwned(), view(), structures(7L), null);
        LatchSink sink = new LatchSink();
        handler.handle(player(actor), new String[] {"delete", "confirm"}, sink);

        assertEquals("command.land.delete.usage", sink.replies.get(0).key);
    }

    @Test
    void confirmExecutesWithReviewedToken() throws Exception {
        AtomicReference<DeleteRequest> seen = new AtomicReference<>();
        LandDeleteCommandHandler handler = new LandDeleteCommandHandler(
                request -> {
                    seen.set(request);
                    return CompletableFuture.completedFuture(DeleteOutcome.success(land, 200L));
                },
                null, standingOnLand(), playerOwned(), view(), structures(7L), null);
        LatchSink sink = new LatchSink();
        handler.handle(player(actor), new String[] {"delete", "confirm", "7"}, sink);
        sink.awaitReply();

        assertEquals(1, sink.replies.size());
        assertEquals("command.land.delete.success", sink.replies.get(0).key);
        assertEquals("Home", sink.replies.get(0).vars.get("land_name"));
        assertEquals(2, sink.replies.get(0).vars.get("chunk_count"));
        assertEquals(200L, sink.replies.get(0).vars.get("refund"));
        assertTrue(seen.get() != null, "confirm must reach the saga");
        assertEquals(7L, seen.get().structureRevision());
        assertEquals(land, seen.get().targetLandId());
        assertEquals(world, seen.get().worldId());
        assertEquals(actor, seen.get().actorUuid());
    }

    @Test
    void serverStewardRequestCarriesServerOwner() throws Exception {
        AtomicReference<DeleteRequest> seen = new AtomicReference<>();
        LandDeleteCommandHandler handler = new LandDeleteCommandHandler(
                request -> {
                    seen.set(request);
                    return CompletableFuture.completedFuture(DeleteOutcome.success(land, 0L));
                },
                null, standingOnLand(), serverOwned(), view(), structures(3L), null);
        LatchSink sink = new LatchSink();
        handler.handle(player(actor), new String[] {"delete", "confirm", "3"}, sink);
        sink.awaitReply();

        assertEquals("command.land.delete.success", sink.replies.get(0).key);
        assertTrue(seen.get().owner() instanceof OwnerRef.ServerOwnerRef,
                "a steward on Server Land must carry the Server owner");
    }

    @Test
    void staleRejectionRepliesStale() throws Exception {
        LandDeleteCommandHandler handler = new LandDeleteCommandHandler(
                request -> CompletableFuture.completedFuture(DeleteOutcome.rejected("structure.stale")),
                null, standingOnLand(), playerOwned(), view(), structures(7L), null);
        LatchSink sink = new LatchSink();
        handler.handle(player(actor), new String[] {"delete", "confirm", "6"}, sink);
        sink.awaitReply();

        assertEquals("command.land.delete.stale", sink.replies.get(0).key);
    }

    @Test
    void degradedAndCompensationRepliesMap() throws Exception {
        LandDeleteCommandHandler degraded = new LandDeleteCommandHandler(
                request -> CompletableFuture.completedFuture(
                        DeleteOutcome.degraded(land, 200L, "delete.publish_failed")),
                null, standingOnLand(), playerOwned(), view(), structures(7L), null);
        LatchSink first = new LatchSink();
        degraded.handle(player(actor), new String[] {"delete", "confirm", "7"}, first);
        first.awaitReply();
        assertEquals("command.land.delete.degraded", first.replies.get(0).key);

        LandDeleteCommandHandler pending = new LandDeleteCommandHandler(
                request -> CompletableFuture.completedFuture(
                        DeleteOutcome.compensationPending(land, 200L)),
                null, standingOnLand(), playerOwned(), view(), structures(7L), null);
        LatchSink second = new LatchSink();
        pending.handle(player(actor), new String[] {"delete", "confirm", "7"}, second);
        second.awaitReply();
        assertEquals("command.land.delete.compensation_pending", second.replies.get(0).key);
    }

    @Test
    void missingRunnerIsFailClosed() {
        LandDeleteCommandHandler handler = new LandDeleteCommandHandler(
                null, null, standingOnLand(), playerOwned(), view(), structures(7L), null);
        LatchSink sink = new LatchSink();
        handler.handle(player(actor), new String[] {"delete", "confirm", "7"}, sink);

        assertEquals("command.land.delete.failed", sink.replies.get(0).key);
        assertEquals("delete.unavailable", sink.replies.get(0).vars.get("reason"));
    }

    @Test
    void recoveryGatesMapToDeleteReasons() {
        CompletionStage<?> pending = new CompletableFuture<>();
        LandDeleteCommandHandler gated = new LandDeleteCommandHandler(
                request -> CompletableFuture.completedFuture(DeleteOutcome.success(land, 0L)),
                () -> pending, standingOnLand(), playerOwned(), view(), structures(7L), null);
        LatchSink sink = new LatchSink();
        gated.handle(player(actor), new String[] {"delete", "confirm", "7"}, sink);

        assertEquals("command.land.delete.failed", sink.replies.get(0).key);
        assertEquals("delete.recovery_pending", sink.replies.get(0).vars.get("reason"));
    }
}

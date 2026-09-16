package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.claim.ShrinkOutcome;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.command.ShrinkCommandHandler;
import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionUpdate;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * The shrink success and compensation-pending replies must render the refund
 * as human-readable major units with the currency code, never the raw
 * minor-unit long. The legacy constructor (no currency) keeps the historical
 * raw fallback so existing wiring and tests stay source- and behaviour-
 * compatible.
 */
class ShrinkRefundDisplayTest {

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final UUID WORLD = UUID.randomUUID();

    @Test
    void successRendersFormattedRefundWithConfiguredCurrency() {
        Reply reply = run(EMC, CompletableFuture.completedFuture(
                ShrinkOutcome.success(new LandId(UUID.randomUUID()), 50L)));

        assertEquals("command.land.shrink.success", reply.key);
        assertEquals("0.50 EMC", reply.vars.get("refund"));
    }

    @Test
    void compensationPendingRendersFormattedRefundWithConfiguredCurrency() {
        Reply reply = run(EMC, CompletableFuture.completedFuture(
                ShrinkOutcome.compensationPending(new LandId(UUID.randomUUID()), 50L)));

        assertEquals("command.land.shrink.compensation_pending", reply.key);
        assertEquals("0.50 EMC", reply.vars.get("refund"));
    }

    @Test
    void zeroRefundRendersFormattedZero() {
        Reply reply = run(EMC, CompletableFuture.completedFuture(
                ShrinkOutcome.success(new LandId(UUID.randomUUID()), 0L)));

        assertEquals("0.00 EMC", reply.vars.get("refund"));
    }

    @Test
    void nullRefundMinorUnitsRendersFormattedZero() {
        Reply reply = run(EMC, CompletableFuture.completedFuture(
                new ShrinkOutcome(ShrinkOutcome.Status.SUCCESS, new LandId(UUID.randomUUID()),
                        null, null)));

        assertEquals("0.00 EMC", reply.vars.get("refund"));
    }

    @Test
    void negativeRefundOnSuccessFailsClosed() {
        Reply reply = run(EMC, CompletableFuture.completedFuture(
                ShrinkOutcome.success(new LandId(UUID.randomUUID()), -50L)));

        assertEquals("command.land.shrink.failed", reply.key);
        assertEquals("shrink.failed", reply.vars.get("reason"));
    }

    @Test
    void negativeRefundOnCompensationPendingFailsClosed() {
        Reply reply = run(EMC, CompletableFuture.completedFuture(
                ShrinkOutcome.compensationPending(new LandId(UUID.randomUUID()), -50L)));

        assertEquals("command.land.shrink.failed", reply.key);
        assertEquals("shrink.failed", reply.vars.get("reason"));
    }

    @Test
    void maxRefundRendersWithoutOverflow() {
        Reply reply = run(EMC, CompletableFuture.completedFuture(
                ShrinkOutcome.success(new LandId(UUID.randomUUID()), Long.MAX_VALUE)));

        assertEquals("command.land.shrink.success", reply.key);
        assertEquals("92233720368547758.07 EMC", reply.vars.get("refund"));
    }

    @Test
    void legacyConstructorWithoutCurrencyKeepsRawFallback() {
        Reply reply = run(null, CompletableFuture.completedFuture(
                ShrinkOutcome.success(new LandId(UUID.randomUUID()), 50L)));

        assertInstanceOf(Long.class, reply.vars.get("refund"),
                "unwired handler must keep the raw minor-unit fallback");
        assertEquals(50L, reply.vars.get("refund"));
    }

    @Test
    void productionOverloadWiresConfiguredCurrencyIntoShrinkReply() {
        SelectionSessionManager selections = manager();
        UUID actor = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());
        selectDelta(selections, actor, target);

        ShrinkCommandHandler.ShrinkRunner runner = request -> CompletableFuture.completedFuture(
                ShrinkOutcome.success(target, 50L));
        Map<String, LandCommand.Handler> handlers = ChunkLandPlugin.buildLandHandlers(
                selections, null, null,
                SelectionStructureRevisionLookup.unavailable(), null, null,
                null, null, null, null, null, null, null, runner, null, null,
                null, null, null, null, null, null, EMC);

        LatchSink sink = new LatchSink();
        handlers.get("shrink").handle(player(actor), new String[] {"shrink"}, sink);
        await(sink);

        assertEquals("command.land.shrink.success", sink.replies.get(0).key);
        assertEquals("0.50 EMC", sink.replies.get(0).vars.get("refund"),
                "production wiring must pass the configured currency to the shrink handler");
    }

    // --- helpers ---------------------------------------------------------

    private static Reply run(Currency currency, CompletionStage<ShrinkOutcome> outcome) {
        SelectionSessionManager selections = manager();
        UUID actor = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());
        selectDelta(selections, actor, target);

        ShrinkCommandHandler.ShrinkRunner runner = request -> outcome;
        Function<CommandSender, Optional<LandId>> currentLand = sender -> Optional.of(target);
        Function<LandId, Optional<OwnerRef>> owner = ignored -> Optional.of(OwnerRef.player(actor));
        ShrinkCommandHandler handler = currency == null
                ? new ShrinkCommandHandler(selections, runner, null, currentLand, owner)
                : new ShrinkCommandHandler(selections, runner, null, currentLand, owner,
                        "shrink", currency);

        LatchSink sink = new LatchSink();
        handler.handle(player(actor), new String[] {"shrink"}, sink);
        await(sink);
        return sink.replies.get(0);
    }

    private static void await(LatchSink sink) {
        try {
            assertTrue(sink.latch.await(10, TimeUnit.SECONDS), "handler must reply");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static SelectionSessionManager manager() {
        // A resolvable structure revision lets updateSelection keep the session;
        // the handler only consumes the selection tokens, not the revision.
        SelectionStructureRevisionLookup structures = landId -> java.util.OptionalLong.of(0);
        return new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                (SelectionClock) () -> NOW,
                Duration.ofMinutes(10),
                ignored -> Optional.of("world"),
                structures);
    }

    private static void selectDelta(SelectionSessionManager selections, UUID actor, LandId target) {
        SelectionSession initial = SelectionSession.initial(
                actor, WORLD, SelectionMode.CREATE_LAND,
                Optional.of(target), Optional.empty(),
                Optional.of(new SelectionPoint(WORLD, 0, 64, 0)),
                Optional.of(new SelectionPoint(WORLD, 16, 64, 16)),
                0, NOW);
        SelectionSession stamped = selections.start(initial);
        selections.updateSelection(actor, stamped, new SelectionUpdate(
                        initial.pointA(), initial.pointB(),
                        Set.of(new ChunkKey(WORLD, 1, 0)), Map.of()))
                .orElseThrow(() -> new IllegalStateException("selection update must succeed"));
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

    private record Reply(String key, Map<String, Object> vars) {}

    private static final class LatchSink implements ReplySink {
        final List<Reply> replies = new CopyOnWriteArrayList<>();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        @Override
        public void reply(String messageKey, Map<String, Object> vars) {
            replies.add(new Reply(messageKey, vars));
            latch.countDown();
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
            reply(messageKey, vars);
        }
    }
}

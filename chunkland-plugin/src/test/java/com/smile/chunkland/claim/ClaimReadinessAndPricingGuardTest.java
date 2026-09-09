package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.command.ClaimCommandHandler;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
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
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery-readiness and production economy/pricing fail-closed guards.
 *
 * <p>Covers the two production blockers: a claim must not enter the saga
 * while the startup recovery scan is still in flight (or has failed), and a
 * player claim must not slip through the zero-price shortcut when the
 * production pricing table is still a placeholder or no Vault provider is
 * present. The saga seam still accepts an injected available economy with
 * non-zero pricing for the established success paths.
 */
class ClaimReadinessAndPricingGuardTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    static final class FakeEconomy implements ClaimEconomy {
        record ChargeCall(UUID operationId, ClaimRequest request, Money price) {
        }

        final List<ChargeCall> charges = Collections.synchronizedList(new ArrayList<>());
        volatile boolean available = true;

        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
            charges.add(new ChargeCall(operationId, request, price));
            return CompletableFuture.completedFuture(ChargeResult.ok());
        }

        @Override
        public CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
            return CompletableFuture.completedFuture(RefundOutcome.REFUNDED);
        }

        @Override
        public String providerId() {
            return "test-economy";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }
    }

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
        final CountDownLatch latch = new CountDownLatch(1);

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

    final class Harness implements AutoCloseable {
        final PersistenceStore store;
        final OperationLedger ledger;
        final OwnerQuotaService quota;
        final LogicalReservationRegistry reservations = new LogicalReservationRegistry();
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        final RuntimeRegistryRebuilder rebuilder;
        final SelectionSessionManager selections;
        final ClaimSaga saga;
        final ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "readiness-guard-test");
            t.setDaemon(true);
            return t;
        });
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        final PricingTable pricing;
        final AtomicInteger sagaCalls = new AtomicInteger();

        Harness(PricingTable pricing) {
            this.pricing = pricing;
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = new OperationLedger(store);
            quota = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), new LimitSettings(100, 100, 128, 16), 0L, Map.of())));
            rebuilder = new RuntimeRegistryRebuilder(new SqliteLandRepository(store), registryStore);
            selections = new SelectionSessionManager(
                    (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                    SelectionVisualizationTaskController.noop(),
                    SelectionNotifier.noop(),
                    (SelectionClock) () -> NOW,
                    Duration.ofMinutes(10),
                    ignored -> Optional.of("world"),
                    SelectionStructureRevisionLookup.unavailable());
            ClaimValidator validator = request -> new SnapshotClaimValidator(registryStore,
                    actorUuid -> selections.sessionFor(actorUuid)
                            .map(session -> OptionalLongOf(session.selectionRevision()))
                            .orElseGet(java.util.OptionalLong::empty),
                    chunk -> 64, owner -> quota.chunkCommitted(owner)).validate(request);
            ClaimEconomy counting = new ClaimEconomy() {
                @Override
                public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
                    return economy.charge(operationId, request, price);
                }

                @Override
                public CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
                    return economy.refund(entry);
                }

                @Override
                public String providerId() {
                    return economy.providerId();
                }

                @Override
                public boolean isAvailable() {
                    return economy.isAvailable();
                }
            };
            saga = new ClaimSaga(validator, quota, pricing, reservations, ledger,
                    counting, rebuilder, clock, async, 3);
        }

        private static java.util.OptionalLong OptionalLongOf(long value) {
            return java.util.OptionalLong.of(value);
        }

        ClaimCommandHandler.ClaimRunner countingRunner() {
            return request -> {
                sagaCalls.incrementAndGet();
                return saga.claim(request);
            };
        }

        SelectionSession selectSingleChunk(UUID actor, UUID world, int x, int z) {
            SelectionSession initial = SelectionSession.initial(
                    actor, world, SelectionMode.CREATE_LAND,
                    Optional.empty(), Optional.empty(),
                    Optional.of(new SelectionPoint(world, 0, 64, 0)),
                    Optional.of(new SelectionPoint(world, 16, 64, 16)),
                    0, NOW);
            selections.start(initial);
            return selections.updateSelection(actor, initial, new SelectionUpdate(
                            initial.pointA(), initial.pointB(),
                            Set.of(new ChunkKey(world, x, z)), Map.of()))
                    .orElseThrow(() -> new IllegalStateException("selection update must succeed"));
        }

        LandCommand command(ClaimCommandHandler handler, LatchSink sink) {
            Map<String, LandCommand.Handler> handlers = new HashMap<>(LandCommand.defaultStubHandlers());
            handlers.put("claim", handler);
            return new LandCommand(handlers, (sender, pipeline) -> sink);
        }

        int ledgerCount() {
            return ledger.findAll().toCompletableFuture().join().size();
        }

        @Override
        public void close() {
            async.shutdownNow();
            store.close();
        }
    }

    private static PricingTable tiered() {
        return PricingTable.of(List.of(
                PricingTier.of(5, new Money(100, EMC)),
                PricingTier.of(PricingTier.UNBOUNDED, new Money(200, EMC))));
    }

    private static PricingTable freeTable() {
        return PricingTable.of(List.of(
                PricingTier.of(PricingTier.UNBOUNDED, Money.zero(EMC))));
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[]{Player.class},
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
                    if (name.equals("equals") || name.equals("hashCode") || name.equals("toString")) {
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

    private static void publishExisting(Harness h, UUID world, int x, int z) {
        LandSnapshot existing = new LandSnapshot(
                new com.smile.chunkland.api.land.LandId(UUID.randomUUID()),
                "Taken", "taken", OwnerRef.player(UUID.randomUUID()),
                world, Set.of(new ChunkKey(world, x, z)), List.of(), 0, 0, NOW, NOW);
        h.registryStore.publish(LandRegistry.from(List.of(existing)));
    }

    // ------------------------------------------------------------------
    // Recovery readiness: pending scan blocks on two different snapshots
    // ------------------------------------------------------------------

    @Test
    void pendingScanRejectsOnEmptySnapshotWithoutSagaCall() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (Harness h = new Harness(tiered())) {
            h.selectSingleChunk(actor, world, 3, 4);
            CompletableFuture<List<com.smile.chunkland.persistence.RecoveryResult>> pending =
                    new CompletableFuture<>();
            Supplier<CompletionStage<?>> scan = () -> pending;
            ClaimCommandHandler handler = new ClaimCommandHandler(h.selections, h.countingRunner(), scan);
            LatchSink sink = new LatchSink();

            assertTrue(h.command(handler, sink).dispatch(player(actor), new String[]{"claim", "Home"}, null));
            sink.awaitReply();

            assertEquals(0, h.sagaCalls.get(), "pending scan must not enter the saga");
            assertEquals(0, h.ledgerCount(), "pending scan must not create a ledger row");
            assertTrue(h.economy.charges.isEmpty(), "pending scan must not charge");
            assertEquals(1, sink.replies.size());
            assertEquals("command.land.claim.failed", sink.replies.get(0).key);
            assertEquals("claim.recovery_pending", sink.replies.get(0).vars.get("reason"));
        }
    }

    @Test
    void pendingScanRejectsOnPopulatedSnapshotWithoutSagaCall() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (Harness h = new Harness(tiered())) {
            // Second snapshot shape: an unrelated land is already visible, the
            // new claim does not collide — readiness alone must still block it.
            publishExisting(h, world, 90, 90);
            h.selectSingleChunk(actor, world, 3, 4);
            CompletableFuture<List<com.smile.chunkland.persistence.RecoveryResult>> pending =
                    new CompletableFuture<>();
            Supplier<CompletionStage<?>> scan = () -> pending;
            ClaimCommandHandler handler = new ClaimCommandHandler(h.selections, h.countingRunner(), scan);
            LatchSink sink = new LatchSink();

            assertTrue(h.command(handler, sink).dispatch(player(actor), new String[]{"claim", "Home"}, null));
            sink.awaitReply();

            assertEquals(0, h.sagaCalls.get(), "pending scan must not enter the saga on any snapshot");
            assertEquals(0, h.ledgerCount());
            assertTrue(h.economy.charges.isEmpty());
            assertEquals("command.land.claim.failed", sink.replies.get(0).key);
            assertEquals("claim.recovery_pending", sink.replies.get(0).vars.get("reason"));
        }
    }

    @Test
    void failedScanStaysFailClosedWithoutSagaCall() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (Harness h = new Harness(tiered())) {
            h.selectSingleChunk(actor, world, 3, 4);
            CompletableFuture<List<com.smile.chunkland.persistence.RecoveryResult>> failed =
                    new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("scan down"));
            Supplier<CompletionStage<?>> scan = () -> failed;
            ClaimCommandHandler handler = new ClaimCommandHandler(h.selections, h.countingRunner(), scan);
            LatchSink sink = new LatchSink();

            assertTrue(h.command(handler, sink).dispatch(player(actor), new String[]{"claim", "Home"}, null));
            sink.awaitReply();

            assertEquals(0, h.sagaCalls.get(), "failed scan must stay fail-closed");
            assertEquals(0, h.ledgerCount());
            assertTrue(h.economy.charges.isEmpty());
            assertEquals("command.land.claim.failed", sink.replies.get(0).key);
            assertEquals("claim.recovery_failed", sink.replies.get(0).vars.get("reason"));

            // A second attempt stays blocked: the failure is permanent until restart.
            LatchSink second = new LatchSink();
            assertTrue(h.command(handler, second).dispatch(player(actor), new String[]{"claim", "Home"}, null));
            second.awaitReply();
            assertEquals(0, h.sagaCalls.get());
            assertEquals("claim.recovery_failed", second.replies.get(0).vars.get("reason"));
        }
    }

    @Test
    void readyScanProceedsToPricedSagaSuccess() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (Harness h = new Harness(tiered())) {
            h.selectSingleChunk(actor, world, 3, 4);
            Supplier<CompletionStage<?>> scan =
                    () -> CompletableFuture.completedFuture(List.of());
            ClaimCommandHandler handler = new ClaimCommandHandler(h.selections, h.countingRunner(), scan);
            LatchSink sink = new LatchSink();

            assertTrue(h.command(handler, sink).dispatch(player(actor), new String[]{"claim", "Home"}, null));
            sink.awaitReply();

            assertEquals(1, h.sagaCalls.get(), "ready scan must enter the saga");
            assertEquals("command.land.claim.success", sink.replies.get(0).key);
            assertEquals(1, h.ledgerCount());
            assertEquals(1, h.economy.charges.size());
            assertNotNull(h.registryStore.snapshot().findLand(world, 3, 4));
        }
    }

    // ------------------------------------------------------------------
    // Production pricing/economy fail-closed (no silent free land)
    // ------------------------------------------------------------------

    @Test
    void zeroPricingRejectsPlayerClaimWithoutSideEffects() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (Harness h = new Harness(freeTable())) {
            h.selectSingleChunk(actor, world, 1, 2);
            Supplier<CompletionStage<?>> scan =
                    () -> CompletableFuture.completedFuture(List.of());
            ClaimCommandHandler handler = new ClaimCommandHandler(h.selections, h.countingRunner(), scan);
            LatchSink sink = new LatchSink();

            assertTrue(h.command(handler, sink).dispatch(player(actor), new String[]{"claim", "Free"}, null));
            sink.awaitReply();

            // The placeholder zero table must not create a free land in production.
            assertEquals("command.land.claim.rejected", sink.replies.get(0).key);
            assertEquals("pricing.unavailable", sink.replies.get(0).vars.get("reason"));
            assertEquals(0, h.ledgerCount(), "zero-price reject must not create a ledger row");
            assertTrue(h.economy.charges.isEmpty(), "zero-price reject must not charge");
            assertEquals(0, h.reservations.size());
            assertTrue(h.registryStore.snapshot().isEmpty());
        }
    }

    @Test
    void unavailableEconomyRejectsPricedClaimWithoutSideEffects() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (Harness h = new Harness(tiered())) {
            h.economy.available = false;
            h.selectSingleChunk(actor, world, 5, 5);
            Supplier<CompletionStage<?>> scan =
                    () -> CompletableFuture.completedFuture(List.of());
            ClaimCommandHandler handler = new ClaimCommandHandler(h.selections, h.countingRunner(), scan);
            LatchSink sink = new LatchSink();

            assertTrue(h.command(handler, sink).dispatch(player(actor), new String[]{"claim", "Home"}, null));
            sink.awaitReply();

            assertEquals("command.land.claim.rejected", sink.replies.get(0).key);
            assertEquals("economy.unavailable", sink.replies.get(0).vars.get("reason"));
            assertEquals(0, h.ledgerCount(), "unavailable economy must not create a ledger row");
            assertTrue(h.economy.charges.isEmpty(), "unavailable economy must not charge");
            assertEquals(0, h.reservations.size());
            assertTrue(h.registryStore.snapshot().isEmpty());
        }
    }

    @Test
    void serverLandStaysFreeWhenEconomyUnavailable() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (Harness h = new Harness(tiered())) {
            h.economy.available = false;
            OwnerRef server = OwnerRef.server();
            ClaimRequest request = new ClaimRequest(server, actor, world,
                    Set.of(new ChunkKey(world, 4, 4)), "Spawn");
            ClaimOutcome outcome = h.saga.claim(request).toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.SUCCESS, outcome.status());
            assertTrue(h.economy.charges.isEmpty(), "server land must never charge");
            assertEquals(1, h.ledgerCount());
        }
    }
}

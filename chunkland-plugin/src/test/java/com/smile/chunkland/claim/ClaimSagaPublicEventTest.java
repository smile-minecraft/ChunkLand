package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.event.LandCreatePostEvent;
import com.smile.chunkland.api.event.LandCreatePreEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.event.PublicEventBus;
import com.smile.chunkland.event.PublicEventCancelledException;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.AtomicCommitStep;
import com.smile.chunkland.persistence.LandRepository;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Public Pre/Post for claims: veto and listener failure before any
 * Economy/ledger/quota side effect; Post exactly once after commit plus
 * publish; Post failure isolated; compensation never replays Post.
 */
class ClaimSagaPublicEventTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    static final class FakeEconomy implements ClaimEconomy {
        final List<UUID> charges = Collections.synchronizedList(new ArrayList<>());
        final List<LedgerEntry> refunds = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
            charges.add(operationId);
            return CompletableFuture.completedFuture(ChargeResult.ok());
        }

        @Override
        public CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
            refunds.add(entry);
            return CompletableFuture.completedFuture(RefundOutcome.REFUNDED);
        }

        @Override
        public String providerId() {
            return "test-economy";
        }
    }

    static final class EmptyLands implements LandRepository {
        @Override
        public CompletionStage<Void> save(LandSnapshot land) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Optional<LandSnapshot>> findById(LandId id) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletionStage<List<LandSnapshot>> findByOwner(OwnerRef owner) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<List<LandSnapshot>> findAll() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<Void> delete(LandId id) {
            return CompletableFuture.completedFuture(null);
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
        final ClaimSaga saga;
        final PublicEventBus bus = new PublicEventBus();
        final PublicEvents events;
        final AtomicInteger preCalls = new AtomicInteger();
        final AtomicInteger postCalls = new AtomicInteger();
        final List<LandCreatePostEvent> posts = Collections.synchronizedList(new ArrayList<>());
        final ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "claim-event-test");
            t.setDaemon(true);
            return t;
        });
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        final OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        final UUID actor = UUID.randomUUID();
        final UUID world = UUID.randomUUID();
        final LandId landId = new LandId(UUID.randomUUID());

        Harness() {
            this(null);
        }

        Harness(java.util.function.Consumer<AtomicCommitStep> injector) {
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = injector == null ? new OperationLedger(store) : new OperationLedger(store, injector);
            quota = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), new LimitSettings(10, 100, 128, 16), 0L, Map.of())));
            rebuilder = new RuntimeRegistryRebuilder(new EmptyLands(), registryStore);
            PricingTable pricing = PricingTable.of(List.of(
                    PricingTier.of(PricingTier.UNBOUNDED, new Money(100, EMC))));
            ClaimValidator validator = request -> new ValidatedClaim(landId, request.owner(),
                    request.actorUuid(), request.worldId(), request.displayName(),
                    List.of(new ValidatedClaim.ChunkDetail(
                            request.chunks().iterator().next(), 64)),
                    0L);
            bus.register(LandCreatePreEvent.class, event -> preCalls.incrementAndGet());
            bus.register(LandCreatePostEvent.class, event -> {
                postCalls.incrementAndGet();
                posts.add(event);
            });
            events = PublicEvents.create(bus, null);
            saga = new ClaimSaga(validator, quota, pricing, reservations, ledger,
                    economy, rebuilder, clock, async, 3, events);
        }

        ClaimRequest request() {
            return new ClaimRequest(owner, actor, world, Set.of(new ChunkKey(world, 7, 9)), "Home");
        }

        ClaimOutcome run() throws Exception {
            return saga.claim(request()).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        int ledgerRows() {
            return ledger.findAll().toCompletableFuture().join().size();
        }

        @Override
        public void close() {
            async.shutdownNow();
            store.close();
        }
    }

    @Test
    void preVetoProducesZeroSideEffects() throws Exception {
        try (Harness harness = new Harness()) {
            harness.bus.register(LandCreatePreEvent.class, event -> event.setCancelled(true));
            ClaimOutcome outcome = harness.run();
            assertEquals(ClaimOutcome.Status.REJECTED, outcome.status());
            assertEquals(PublicEventCancelledException.DIAGNOSTIC_KEY, outcome.diagnosticKey());
            assertEquals(1, harness.preCalls.get());
            assertEquals(0, harness.postCalls.get());
            assertTrue(harness.economy.charges.isEmpty());
            assertTrue(harness.economy.refunds.isEmpty());
            assertEquals(0, harness.ledgerRows());
            assertEquals(0, harness.reservations.size());
            assertEquals(0, harness.quota.reserved(harness.owner));
            assertEquals(0, harness.quota.chunkCommitted(harness.owner));
        }
    }

    @Test
    void throwingPreListenerFailsClosedWithZeroSideEffects() throws Exception {
        try (Harness harness = new Harness()) {
            harness.bus.register(LandCreatePreEvent.class, event -> {
                throw new IllegalStateException("broken listener");
            });
            ClaimOutcome outcome = harness.run();
            assertEquals(ClaimOutcome.Status.REJECTED, outcome.status());
            assertEquals(PublicEventCancelledException.DIAGNOSTIC_KEY, outcome.diagnosticKey());
            assertTrue(harness.economy.charges.isEmpty());
            assertEquals(0, harness.ledgerRows());
            assertEquals(0, harness.reservations.size());
            assertEquals(0, harness.postCalls.get());
        }
    }

    @Test
    void postFiresExactlyOnceAfterCommitAndPublish() throws Exception {
        try (Harness harness = new Harness()) {
            ClaimOutcome outcome = harness.run();
            assertEquals(ClaimOutcome.Status.SUCCESS, outcome.status());
            assertEquals(harness.landId, outcome.landId());
            assertEquals(1, harness.preCalls.get());
            assertEquals(1, harness.postCalls.get());
            assertEquals(1, harness.economy.charges.size());
            LandCreatePostEvent post = harness.posts.get(0);
            assertEquals(harness.landId, post.landId());
            assertEquals(harness.actor, post.actorUuid());
            assertEquals(1, post.chunkCount());
        }
    }

    @Test
    void throwingPostListenerDoesNotChangeOutcomeOrRefund() throws Exception {
        try (Harness harness = new Harness()) {
            harness.bus.register(LandCreatePostEvent.class, event -> {
                throw new IllegalStateException("broken post listener");
            });
            ClaimOutcome outcome = harness.run();
            assertEquals(ClaimOutcome.Status.SUCCESS, outcome.status());
            assertEquals(1, harness.postCalls.get());
            assertTrue(harness.economy.refunds.isEmpty());
        }
    }

    @Test
    void failedCommitCompensatesWithoutReplayingPost() throws Exception {
        try (Harness harness = new Harness(step -> {
            if (step == AtomicCommitStep.AFTER_LAND) {
                throw new IllegalStateException("commit down");
            }
        })) {
            ClaimOutcome outcome = harness.run();
            assertEquals(ClaimOutcome.Status.FAILED, outcome.status());
            assertEquals(1, harness.preCalls.get());
            assertEquals(0, harness.postCalls.get());
            assertEquals(1, harness.economy.charges.size());
            assertEquals(1, harness.economy.refunds.size());
        }
    }
}

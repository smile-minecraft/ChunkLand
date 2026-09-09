package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.ChunkCoordinate;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.economy.VaultBridge;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.AtomicCommitStep;
import com.smile.chunkland.persistence.CrashRecoveryScanner;
import com.smile.chunkland.persistence.LandRepository;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RecoveryHandlers;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteAuditRepository;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Saga coverage: strict six-step order with a single ledger row, every failure
 * path, idempotency key carriage, owner-total pricing with exact cost basis,
 * deterministic quota barriers under parallel load, and recovery handoff.
 */
class ClaimSagaTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    static final class FakeEconomy implements ClaimEconomy {
        record ChargeCall(UUID operationId, ClaimRequest request, Money price) {
        }

        final List<ChargeCall> charges = Collections.synchronizedList(new ArrayList<>());
        final List<LedgerEntry> refundCalls = Collections.synchronizedList(new ArrayList<>());
        final List<String> chargeObservedLedgerStates = Collections.synchronizedList(new ArrayList<>());
        volatile ClaimEconomy.ChargeResult nextCharge = ClaimEconomy.ChargeResult.ok();
        volatile RuntimeException chargeThrow;
        volatile RefundOutcome refundResult = RefundOutcome.REFUNDED;
        volatile OperationLedger ledgerProbe;

        @Override
        public CompletionStage<ClaimEconomy.ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
            if (chargeThrow != null) {
                throw chargeThrow;
            }
            OperationLedger probe = ledgerProbe;
            if (probe != null) {
                try {
                    chargeObservedLedgerStates.add(
                            probe.find(operationId).toCompletableFuture().get(5, TimeUnit.SECONDS).state());
                } catch (Exception failure) {
                    chargeObservedLedgerStates.add("lookup-failed");
                }
            }
            charges.add(new ChargeCall(operationId, request, price));
            return CompletableFuture.completedFuture(nextCharge);
        }

        @Override
        public CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
            refundCalls.add(entry);
            return CompletableFuture.completedFuture(refundResult);
        }

        @Override
        public String providerId() {
            return "test-economy";
        }
    }

    static final class RecordingLands implements LandRepository {
        final LandRepository delegate;
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<String> sharedOrder;

        RecordingLands(LandRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletionStage<Void> save(LandSnapshot land) {
            return delegate.save(land);
        }

        @Override
        public CompletionStage<Optional<LandSnapshot>> findById(LandId id) {
            return delegate.findById(id);
        }

        @Override
        public CompletionStage<List<LandSnapshot>> findByOwner(OwnerRef owner) {
            return delegate.findByOwner(owner);
        }

        @Override
        public CompletionStage<List<LandSnapshot>> findAll() {
            events.add("publish-read");
            if (sharedOrder != null) {
                sharedOrder.add("publish-read");
            }
            return delegate.findAll();
        }

        @Override
        public CompletionStage<Void> delete(LandId id) {
            return delegate.delete(id);
        }
    }

    static final class FailingLands implements LandRepository {
        @Override
        public CompletionStage<Void> save(LandSnapshot land) {
            return CompletableFuture.failedFuture(new IllegalStateException("land store down"));
        }

        @Override
        public CompletionStage<Optional<LandSnapshot>> findById(LandId id) {
            return CompletableFuture.failedFuture(new IllegalStateException("land store down"));
        }

        @Override
        public CompletionStage<List<LandSnapshot>> findByOwner(OwnerRef owner) {
            return CompletableFuture.failedFuture(new IllegalStateException("land store down"));
        }

        @Override
        public CompletionStage<List<LandSnapshot>> findAll() {
            return CompletableFuture.failedFuture(new IllegalStateException("land store down"));
        }

        @Override
        public CompletionStage<Void> delete(LandId id) {
            return CompletableFuture.failedFuture(new IllegalStateException("land store down"));
        }
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    final class Harness implements AutoCloseable {
        final PersistenceStore store;
        final OperationLedger ledger;
        final OwnerQuotaService quota;
        final LogicalReservationRegistry reservations = new LogicalReservationRegistry();
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        final RecordingLands lands;
        final RuntimeRegistryRebuilder rebuilder;
        final ClaimSaga saga;
        final ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "claim-saga-test");
            t.setDaemon(true);
            return t;
        });
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        Harness(PricingTable pricing, int maxLands, int maxChunks) {
            this(pricing, maxLands, maxChunks, null, 3);
        }

        Harness(PricingTable pricing, int maxLands, int maxChunks,
                java.util.function.Consumer<AtomicCommitStep> injector, int retryLimit) {
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = injector == null ? new OperationLedger(store) : new OperationLedger(store, injector);
            LimitSettings limits = new LimitSettings(maxLands, maxChunks, 128, 16);
            quota = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), limits, 0L, Map.of())));
            lands = new RecordingLands(new SqliteLandRepository(store));
            lands.sharedOrder = order;
            rebuilder = new RuntimeRegistryRebuilder(lands, registryStore);
            ClaimValidator recording = request -> {
                order.add("validate");
                return new SnapshotClaimValidator(registryStore,
                        ClaimValidator.RevisionSource.none(), chunk -> 64,
                        owner -> quota.chunkCommitted(owner)).validate(request);
            };
            ClaimEconomy recordingEconomy = new ClaimEconomy() {
                @Override
                public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
                    order.add("charge");
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
            };
            economy.ledgerProbe = ledger;
            saga = new ClaimSaga(recording, quota, pricing, reservations, ledger,
                    recordingEconomy, rebuilder, clock, async, retryLimit);
        }

        ClaimOutcome run(ClaimRequest request) throws Exception {
            return saga.claim(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            async.shutdownNow();
            try {
                async.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
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

    private static ClaimRequest claim(OwnerRef owner, UUID actor, UUID world, int x, int z, String name) {
        return new ClaimRequest(owner, actor, world, Set.of(new ChunkKey(world, x, z)), name);
    }

    private static int ledgerCount(OperationLedger ledger) {
        return ledger.findAll().toCompletableFuture().join().size();
    }

    private static int landCount(PersistenceStore store) {
        return new SqliteLandRepository(store).findAll().toCompletableFuture().join().size();
    }

    private static int chunkCount(PersistenceStore store, LandId landId) {
        return new SqliteChunkRepository(store).listByLand(landId)
                .toCompletableFuture().join().size();
    }

    private static int auditCount(PersistenceStore store, LandId landId) {
        return new SqliteAuditRepository(store).findByLand(landId, 1000, 0)
                .toCompletableFuture().join().size();
    }

    private static long payloadCostBasisSum(LedgerEntry row) {
        return OperationPayload.fromJson(row.payloadJson()).chunkSet().stream()
                .mapToLong(OperationPayload.Chunk::costBasisMinorUnits).sum();
    }

    private static int totalChunks(PersistenceStore store) {
        List<LandSnapshot> lands = new SqliteLandRepository(store).findAll()
                .toCompletableFuture().join();
        SqliteChunkRepository chunks = new SqliteChunkRepository(store);
        int total = 0;
        for (LandSnapshot land : lands) {
            total += chunks.listByLand(land.id()).toCompletableFuture().join().size();
        }
        return total;
    }

    private static List<LedgerEntry> allLedgers(OperationLedger ledger) {
        return ledger.findAll().toCompletableFuture().join();
    }

    // ------------------------------------------------------------------
    // Success path
    // ------------------------------------------------------------------

    @Test
    void successFollowsSixStepsWithSingleLedgerRow() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        try (Harness h = new Harness(tiered(), 100, 100)) {
            ClaimOutcome outcome = h.run(claim(owner, actor, world, 3, 4, "Home"));

            assertEquals(ClaimOutcome.Status.SUCCESS, outcome.status());
            assertNotNull(outcome.landId());
            // Strict order through the seams the test can observe.
            assertEquals(List.of("validate", "charge", "publish-read"), h.order);
            // The durable boundary precedes Economy: the charge ran while the row was PAYMENT_PENDING.
            assertEquals(List.of("PAYMENT_PENDING"), h.economy.chargeObservedLedgerStates);
            // Single ledger row through the whole saga.
            List<LedgerEntry> rows = allLedgers(h.ledger);
            assertEquals(1, rows.size());
            LedgerEntry row = rows.get(0);
            assertEquals("ACTIVE", row.state());
            assertEquals("ACTIVE", h.ledger.find(row.operationId()).toCompletableFuture().join().state());
            assertEquals(100L, row.priceMinorUnits());
            assertEquals("charge:" + row.operationId(), row.economyTransactionRef());
            assertEquals("test-economy", row.economyProviderId());
            // Operation id reached Economy exactly once.
            assertEquals(1, h.economy.charges.size());
            assertEquals(row.operationId(), h.economy.charges.get(0).operationId());
            assertEquals(new Money(100, EMC), h.economy.charges.get(0).price());
            // Atomic domain write: land, chunk with exact cost basis, audit trail.
            assertEquals(1, landCount(h.store));
            assertEquals(1, chunkCount(h.store, outcome.landId()));
            assertEquals(1, auditCount(h.store, outcome.landId()));
            assertEquals(1, new SqliteAuditRepository(h.store).findByAction("LAND_CREATE", 100)
                    .toCompletableFuture().join().size());
            assertEquals(100L, payloadCostBasisSum(row));
            // Published from the authoritative database.
            assertNotNull(h.registryStore.snapshot().findLand(world, 3, 4));
            assertEquals(outcome.landId(), h.registryStore.snapshot().findLandId(world, 3, 4));
            // No reservation or quota side effects remain.
            assertEquals(0, h.reservations.size());
            assertEquals(0, h.quota.landReserved(owner));
            assertEquals(0, h.quota.chunkReserved(owner));
            assertEquals(1, h.quota.landCommitted(owner));
            assertEquals(1, h.quota.chunkCommitted(owner));
            assertTrue(h.economy.refundCalls.isEmpty());
        }
    }

    @Test
    void pricingBasisIsOwnerTotalWithExactCostBasisSum() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        try (Harness h = new Harness(tiered(), 100, 100)) {
            h.quota.setChunkCommitted(owner, 5);
            Set<ChunkKey> chunks = Set.of(
                    new ChunkKey(world, 0, 0), new ChunkKey(world, 1, 0), new ChunkKey(world, 2, 0));
            ClaimOutcome outcome = h.run(new ClaimRequest(owner, actor, world, chunks, "Estate"));

            assertEquals(ClaimOutcome.Status.SUCCESS, outcome.status());
            // Basis 5 + 3 new chunks at the 200 tier = 600.
            assertEquals(new Money(600, EMC), h.economy.charges.get(0).price());
            assertEquals(600L, allLedgers(h.ledger).get(0).priceMinorUnits());
            assertEquals(3, chunkCount(h.store, outcome.landId()));
            assertEquals(600L, payloadCostBasisSum(allLedgers(h.ledger).get(0)));
            assertEquals(1, ledgerCount(h.ledger));
        }
    }

    // ------------------------------------------------------------------
    // Failure paths
    // ------------------------------------------------------------------

    @Test
    void economyFailureMarksFailedWithNoRefundOrSideEffects() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        try (Harness h = new Harness(tiered(), 100, 100)) {
            h.economy.nextCharge = ClaimEconomy.ChargeResult.failed("economy.insufficient");

            ClaimOutcome outcome = h.run(claim(owner, actor, world, 1, 1, "Home"));

            assertEquals(ClaimOutcome.Status.FAILED, outcome.status());
            assertEquals("economy.insufficient", outcome.diagnosticKey());
            List<LedgerEntry> rows = allLedgers(h.ledger);
            assertEquals(1, rows.size());
            assertEquals("FAILED", rows.get(0).state());
            assertTrue(h.economy.refundCalls.isEmpty(), "economy failure must never refund");
            assertEquals(0, landCount(h.store));
            assertEquals(0, totalChunks(h.store));
            assertEquals(0, h.reservations.size());
            assertEquals(0, h.quota.landReserved(owner));
            assertEquals(0, h.quota.chunkReserved(owner));
            assertEquals(0, h.quota.landCommitted(owner));
            assertEquals(0, h.quota.chunkCommitted(owner));
            assertEquals(List.of("PAYMENT_PENDING"), h.economy.chargeObservedLedgerStates);
        }
    }

    @Test
    void domainFailureCompensatesToCompensated() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        try (Harness h = new Harness(tiered(), 100, 100,
                step -> {
                    if (step == AtomicCommitStep.AFTER_LAND) {
                        throw new IllegalStateException("injected domain failure");
                    }
                }, 3)) {
            ClaimOutcome outcome = h.run(claim(owner, actor, world, 2, 2, "Home"));

            assertEquals(ClaimOutcome.Status.FAILED, outcome.status());
            assertEquals("claim.compensated", outcome.diagnosticKey());
            List<LedgerEntry> rows = allLedgers(h.ledger);
            assertEquals(1, rows.size());
            assertEquals("COMPENSATED", rows.get(0).state());
            // Atomic rollback: no partial domain rows survive.
            assertEquals(0, landCount(h.store));
            assertEquals(0, totalChunks(h.store));
            // Exactly one charge, exactly one refund against the compensation row.
            assertEquals(1, h.economy.charges.size());
            assertEquals(1, h.economy.refundCalls.size());
            assertEquals(rows.get(0).operationId(), h.economy.refundCalls.get(0).operationId());
            assertEquals("COMPENSATION_PENDING", h.economy.refundCalls.get(0).state());
            assertEquals(0, h.reservations.size());
            assertEquals(0, h.quota.chunkReserved(owner));
            assertEquals(0, h.quota.chunkCommitted(owner));
        }
    }

    @Test
    void refundFailureAtRetryLimitReachesReconciliation() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        try (Harness h = new Harness(tiered(), 100, 100,
                step -> {
                    throw new IllegalStateException("injected domain failure");
                }, 1)) {
            h.economy.refundResult = RefundOutcome.FAILED;

            ClaimOutcome outcome = h.run(claim(owner, actor, world, 2, 2, "Home"));

            assertEquals(ClaimOutcome.Status.FAILED, outcome.status());
            assertEquals("claim.reconciliation", outcome.diagnosticKey());
            List<LedgerEntry> rows = allLedgers(h.ledger);
            assertEquals(1, rows.size());
            assertEquals("NEEDS_RECONCILIATION", rows.get(0).state());
            assertEquals(1, rows.get(0).compensationAttempts());
            assertEquals(0, landCount(h.store));
            assertEquals(0, h.reservations.size());
        }
    }

    @Test
    void unconfirmedRefundStaysPendingForRecoveryRetry() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        try (Harness h = new Harness(tiered(), 100, 100,
                step -> {
                    throw new IllegalStateException("injected domain failure");
                }, 3)) {
            h.economy.refundResult = RefundOutcome.UNKNOWN;

            ClaimOutcome outcome = h.run(claim(owner, actor, world, 2, 2, "Home"));

            assertEquals(ClaimOutcome.Status.FAILED, outcome.status());
            assertEquals("claim.compensation_pending", outcome.diagnosticKey());
            assertEquals("COMPENSATION_PENDING", allLedgers(h.ledger).get(0).state());

            // Startup recovery retries the same compensation and completes it.
            h.economy.refundResult = RefundOutcome.REFUNDED;
            List<com.smile.chunkland.persistence.RecoveryResult> results =
                    new CrashRecoveryScanner(h.ledger, RecoveryHandlers.of(
                            entry -> CompletableFuture.completedFuture(
                                    com.smile.chunkland.persistence.PaymentLookup.unknown()),
                            h.economy::refund,
                            (entry, payload) -> CompletableFuture.failedFuture(
                                    new UnsupportedOperationException("no replay")),
                            h.rebuilder::rebuildForRecovery)).scan()
                            .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("COMPENSATED", results.get(0).resultingState());
            assertEquals("COMPENSATED", allLedgers(h.ledger).get(0).state());
        }
    }

    @Test
    void publishFailureNeverRefundsAndRecoveryRebuildsToActive() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        RuntimeRegistryRebuilder failingRebuilder;
        try (Harness h = new Harness(tiered(), 100, 100)) {
            failingRebuilder = new RuntimeRegistryRebuilder(new FailingLands(), h.registryStore);
            ClaimSaga failingSaga = new ClaimSaga(
                    request -> new SnapshotClaimValidator(h.registryStore,
                            ClaimValidator.RevisionSource.none(), chunk -> 64,
                            o -> h.quota.chunkCommitted(o)).validate(request),
                    h.quota, tiered(), h.reservations, h.ledger, h.economy,
                    failingRebuilder, h.clock, h.async, 3);

            ClaimOutcome outcome = failingSaga.claim(claim(owner, actor, world, 9, 9, "Home"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.DEGRADED, outcome.status());
            assertEquals("claim.publish_failed", outcome.diagnosticKey());
            assertNotNull(outcome.landId());
            List<LedgerEntry> rows = allLedgers(h.ledger);
            assertEquals(1, rows.size());
            assertEquals("DOMAIN_COMMITTED", rows.get(0).state());
            assertTrue(h.economy.refundCalls.isEmpty(), "publish failure must never refund");
            // Durable truth survived; quota committed because the domain write is real.
            assertEquals(1, landCount(h.store));
            assertEquals(1, h.quota.chunkCommitted(owner));
            assertEquals(0, h.reservations.size());

            // Startup recovery rebuilds the runtime from the database and advances to ACTIVE.
            List<com.smile.chunkland.persistence.RecoveryResult> results =
                    new CrashRecoveryScanner(h.ledger, RecoveryHandlers.of(
                            entry -> CompletableFuture.completedFuture(
                                    com.smile.chunkland.persistence.PaymentLookup.unknown()),
                            h.economy::refund,
                            (entry, payload) -> CompletableFuture.failedFuture(
                                    new UnsupportedOperationException("no replay")),
                            h.rebuilder::rebuildForRecovery)).scan()
                            .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("ACTIVE", results.get(0).resultingState());
            assertEquals("ACTIVE", allLedgers(h.ledger).get(0).state());
            assertNotNull(h.registryStore.snapshot().findLand(world, 9, 9));
        }
    }

    @Test
    void asyncExecutorRejectionAfterCommitDegradesWithoutRefundOrCompensation() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        try (Harness h = new Harness(tiered(), 100, 100)) {
            AtomicBoolean rejectAsync = new AtomicBoolean(false);
            Executor gate = task -> {
                if (rejectAsync.get()) {
                    throw new RejectedExecutionException("injected executor rejection");
                }
                h.async.execute(task);
            };
            // Arm rejection exactly when the publish read runs, so every earlier
            // stage (charge, durable commit) uses the live executor and only the
            // post-commit continuation is rejected.
            LandRepository arming = new LandRepository() {
                @Override
                public CompletionStage<Void> save(LandSnapshot land) {
                    return h.lands.save(land);
                }

                @Override
                public CompletionStage<Optional<LandSnapshot>> findById(LandId id) {
                    return h.lands.findById(id);
                }

                @Override
                public CompletionStage<List<LandSnapshot>> findByOwner(OwnerRef findOwner) {
                    return h.lands.findByOwner(findOwner);
                }

                @Override
                public CompletionStage<List<LandSnapshot>> findAll() {
                    rejectAsync.set(true);
                    // Return an already-completed stage with authoritative data so the
                    // post-commit continuation hits the executor synchronously: a
                    // deferred rejection would be contained by the future chain and
                    // could not prove the compensation-escape defect.
                    try {
                        List<LandSnapshot> snapshots = h.lands.findAll()
                                .toCompletableFuture().get(10, TimeUnit.SECONDS);
                        return CompletableFuture.completedFuture(snapshots);
                    } catch (Exception failure) {
                        return CompletableFuture.failedFuture(failure);
                    }
                }

                @Override
                public CompletionStage<Void> delete(LandId id) {
                    return h.lands.delete(id);
                }
            };
            RuntimeRegistryRebuilder rebuilder = new RuntimeRegistryRebuilder(arming, h.registryStore);
            ClaimSaga saga = new ClaimSaga(
                    request -> new SnapshotClaimValidator(h.registryStore,
                            ClaimValidator.RevisionSource.none(), chunk -> 64,
                            o -> h.quota.chunkCommitted(o)).validate(request),
                    h.quota, tiered(), h.reservations, h.ledger, h.economy,
                    rebuilder, h.clock, gate, 3);

            ClaimOutcome outcome = saga.claim(claim(owner, actor, world, 9, 9, "Home"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.DEGRADED, outcome.status());
            assertEquals("claim.publish_failed", outcome.diagnosticKey());
            assertNotNull(outcome.landId());
            List<LedgerEntry> rows = allLedgers(h.ledger);
            assertEquals(1, rows.size());
            assertEquals("DOMAIN_COMMITTED", rows.get(0).state());
            assertEquals(0, rows.get(0).compensationAttempts());
            assertTrue(h.economy.refundCalls.isEmpty(),
                    "post-commit executor failure must never refund");
            assertEquals(1, landCount(h.store));
            assertEquals(0, h.reservations.size());
        }
    }

    @Test
    void publishSideEffectFailureAfterCommitDegradesWithoutRefundOrCompensation() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        try (Harness h = new Harness(tiered(), 100, 100)) {
            ClaimSaga saga = new ClaimSaga(
                    request -> new SnapshotClaimValidator(h.registryStore,
                            ClaimValidator.RevisionSource.none(), chunk -> 64,
                            o -> h.quota.chunkCommitted(o)).validate(request),
                    h.quota, tiered(), h.reservations, h.ledger, h.economy,
                    h.rebuilder, h.clock, h.async, 3,
                    attempt -> {
                        throw new IllegalStateException("injected publish guard failure");
                    });

            ClaimOutcome outcome = saga.claim(claim(owner, actor, world, 9, 9, "Home"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.DEGRADED, outcome.status());
            assertEquals("claim.publish_failed", outcome.diagnosticKey());
            assertNotNull(outcome.landId());
            List<LedgerEntry> rows = allLedgers(h.ledger);
            assertEquals(1, rows.size());
            assertEquals("DOMAIN_COMMITTED", rows.get(0).state());
            assertEquals(0, rows.get(0).compensationAttempts());
            assertTrue(h.economy.refundCalls.isEmpty(),
                    "post-commit guard failure must never refund");
            assertEquals(1, landCount(h.store));
        }
    }

    // ------------------------------------------------------------------
    // Economy contract, zero price, server land
    // ------------------------------------------------------------------

    @Test
    void zeroPricePlayerClaimFailsClosedWithoutSideEffects() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        try (Harness h = new Harness(freeTable(), 100, 100)) {
            ClaimOutcome outcome = h.run(claim(owner, actor, world, 1, 2, "Free"));

            // A placeholder zero table must never create a silent free land:
            // player claims fail closed before any ledger, charge, or domain row.
            assertEquals(ClaimOutcome.Status.REJECTED, outcome.status());
            assertEquals("pricing.unavailable", outcome.diagnosticKey());
            assertTrue(h.economy.charges.isEmpty(), "zero price must not touch Economy");
            assertEquals(0, ledgerCount(h.ledger), "zero-price reject must not create a ledger row");
            assertEquals(0, landCount(h.store));
            assertEquals(0, h.reservations.size());
            assertTrue(h.registryStore.snapshot().isEmpty());
        }
    }

    @Test
    void zeroPriceDomainFailureCompensatesWithoutBridgeCall() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        AtomicBoolean bridgeTouched = new AtomicBoolean(false);
        VaultBridge stubBridge = new VaultBridge() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String providerId() {
                return "stub-economy";
            }

            @Override
            public Response withdraw(UUID playerId, double amount, UUID operationId) {
                bridgeTouched.set(true);
                return Response.ok();
            }

            @Override
            public Response deposit(UUID playerId, double amount, UUID operationId) {
                bridgeTouched.set(true);
                return Response.ok();
            }
        };
        VaultClaimEconomy vault = new VaultClaimEconomy(stubBridge, EMC);
        // Zero charge short-circuits before the bridge.
        ClaimRequest request = claim(owner, actor, world, 1, 2, "Free");
        ClaimEconomy.ChargeResult charged = vault
                .charge(UUID.randomUUID(), request, Money.zero(EMC))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(charged.success());
        assertFalse(bridgeTouched.get());
        // Zero refund resolves without a deposit.
        LedgerEntry zeroEntry = new LedgerEntry(UUID.randomUUID(), "CLAIM", "COMPENSATION_PENDING", actor,
                world, null, 0L, "stub-economy", "charge:ref", "{}", 1, NOW, NOW);
        assertEquals(RefundOutcome.REFUNDED,
                vault.refund(zeroEntry).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertFalse(bridgeTouched.get());
    }

    @Test
    void serverLandIsFreeAndOutsidePlayerQuota() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef server = OwnerRef.server();
        OwnerRef actorAsOwner = OwnerRef.player(actor);
        try (Harness h = new Harness(tiered(), 1, 1)) {
            ClaimOutcome outcome = h.run(claim(server, actor, world, 4, 4, "Spawn"));

            assertEquals(ClaimOutcome.Status.SUCCESS, outcome.status());
            assertTrue(h.economy.charges.isEmpty(), "server land must never charge");
            List<LedgerEntry> rows = allLedgers(h.ledger);
            assertEquals(1, rows.size());
            assertEquals("ACTIVE", rows.get(0).state());
            assertEquals(0L, rows.get(0).priceMinorUnits());
            assertEquals(0, h.quota.landCommitted(actorAsOwner));
            assertEquals(0, h.quota.chunkCommitted(actorAsOwner));
            List<LandSnapshot> lands = new SqliteLandRepository(h.store).findAll()
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(1, lands.size());
            assertTrue(lands.get(0).ownerRef() instanceof OwnerRef.ServerOwnerRef);
        }
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    void disconnectedSelectionIsRejectedBeforeAnySideEffect() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        try (Harness h = new Harness(tiered(), 100, 100)) {
            Set<ChunkKey> chunks = Set.of(new ChunkKey(world, 0, 0), new ChunkKey(world, 5, 5));
            ClaimOutcome outcome = h.run(new ClaimRequest(owner, actor, world, chunks, "Split"));

            assertEquals(ClaimOutcome.Status.REJECTED, outcome.status());
            assertEquals("selection.disconnected", outcome.diagnosticKey());
            assertEquals(0, ledgerCount(h.ledger));
            assertTrue(h.economy.charges.isEmpty());
            assertEquals(0, h.reservations.size());
        }
    }

    @Test
    void collidingChunkIsRejectedBeforeAnySideEffect() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        OwnerRef other = OwnerRef.player(UUID.randomUUID());
        try (Harness h = new Harness(tiered(), 100, 100)) {
            LandSnapshot existing = new LandSnapshot(new LandId(UUID.randomUUID()), "Taken", "taken", other,
                    world, Set.of(new ChunkKey(world, 7, 7)), List.of(), 0, 0, NOW, NOW);
            h.registryStore.publish(LandRegistry.from(List.of(existing)));

            ClaimOutcome outcome = h.run(claim(owner, actor, world, 7, 7, "Home"));

            assertEquals(ClaimOutcome.Status.REJECTED, outcome.status());
            assertEquals("land.chunk.conflict", outcome.diagnosticKey());
            assertEquals(0, ledgerCount(h.ledger));
            assertTrue(h.economy.charges.isEmpty());
            assertEquals(0, h.reservations.size());
            assertEquals(0, h.quota.chunkReserved(owner));
        }
    }

    @Test
    void staleRevisionTokenIsRejected() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        try (Harness h = new Harness(tiered(), 100, 100)) {
            SnapshotClaimValidator revisioned = new SnapshotClaimValidator(h.registryStore,
                    actorUuid -> java.util.OptionalLong.of(7L), chunk -> 64,
                    o -> h.quota.chunkCommitted(o));
            ClaimSaga revisionedSaga = new ClaimSaga(request -> revisioned.validate(request),
                    h.quota, tiered(), h.reservations, h.ledger, h.economy,
                    h.rebuilder, h.clock, h.async, 3);

            ClaimRequest stale = new ClaimRequest(owner, actor, world,
                    Set.of(new ChunkKey(world, 1, 1)), "Home", 6L);
            ClaimOutcome rejected = revisionedSaga.claim(stale).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ClaimOutcome.Status.REJECTED, rejected.status());
            assertEquals("selection.stale", rejected.diagnosticKey());
            assertEquals(0, ledgerCount(h.ledger));

            ClaimRequest fresh = new ClaimRequest(owner, actor, world,
                    Set.of(new ChunkKey(world, 1, 1)), "Home", 7L);
            ClaimOutcome accepted = revisionedSaga.claim(fresh).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ClaimOutcome.Status.SUCCESS, accepted.status());
        }
    }

    // ------------------------------------------------------------------
    // Deterministic concurrency
    // ------------------------------------------------------------------

    @Test
    void quotaBarrierCapsParallelClaimsWithNoSideEffectsForLosers() throws Exception {
        int iterations = 100;
        int parties = 6;
        int remaining = 2;
        for (int iteration = 0; iteration < iterations; iteration++) {
            UUID world = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            try (Harness h = new Harness(tiered(), 100, 5 + remaining)) {
                h.quota.setChunkCommitted(owner, 5);
                h.quota.setLandCommitted(owner, 0);
                CyclicBarrier ready = new CyclicBarrier(parties);
                ExecutorService callers = Executors.newFixedThreadPool(parties, r -> {
                    Thread t = new Thread(r, "quota-burst");
                    t.setDaemon(true);
                    return t;
                });
                try {
                    List<Future<ClaimOutcome>> futures = new ArrayList<>();
                    for (int i = 0; i < parties; i++) {
                        final int x = iteration * 100 + i;
                        futures.add(callers.submit(() -> {
                            ready.await(10, TimeUnit.SECONDS);
                            return h.saga.claim(claim(owner, actor, world, x, 0, "Lot" + x))
                                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
                        }));
                    }
                    List<ClaimOutcome> outcomes = new ArrayList<>();
                    for (Future<ClaimOutcome> f : futures) {
                        outcomes.add(f.get(15, TimeUnit.SECONDS));
                    }
                    long successes = outcomes.stream()
                            .filter(o -> o.status() == ClaimOutcome.Status.SUCCESS).count();
                    long rejected = outcomes.stream()
                            .filter(o -> o.status() == ClaimOutcome.Status.REJECTED).count();
                    assertEquals(remaining, successes, "iteration " + iteration + ": exactly R claims win");
                    assertEquals(parties - remaining, rejected, "iteration " + iteration);
                    assertTrue(outcomes.stream()
                            .filter(o -> o.status() == ClaimOutcome.Status.REJECTED)
                            .allMatch(o -> "limit.reached".equals(o.diagnosticKey())),
                            "iteration " + iteration + ": losers fail at the quota barrier");
                    // No over-limit durable commit, no negative counts.
                    assertEquals(5 + remaining, h.quota.chunkCommitted(owner));
                    assertEquals(remaining, h.quota.landCommitted(owner));
                    assertEquals(0, h.quota.chunkReserved(owner));
                    assertEquals(0, h.quota.landReserved(owner));
                    assertEquals(0, h.reservations.size());
                    // Losers left no ledger rows and were never charged.
                    assertEquals(remaining, ledgerCount(h.ledger));
                    assertEquals(remaining, totalChunks(h.store));
                    assertEquals(remaining, h.economy.charges.size());
                } finally {
                    callers.shutdownNow();
                    assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS), "caller pool must terminate");
                }
            }
        }
    }

    @Test
    void sameChunkRaceYieldsExactlyOneWinner() throws Exception {
        int parties = 4;
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        try (Harness h = new Harness(tiered(), 100, 100)) {
            CyclicBarrier ready = new CyclicBarrier(parties);
            ExecutorService callers = Executors.newFixedThreadPool(parties, r -> {
                Thread t = new Thread(r, "chunk-race");
                t.setDaemon(true);
                return t;
            });
            try {
                List<Future<ClaimOutcome>> futures = new ArrayList<>();
                for (int i = 0; i < parties; i++) {
                    final int index = i;
                    futures.add(callers.submit(() -> {
                        ready.await(10, TimeUnit.SECONDS);
                        return h.saga.claim(claim(owner, actor, world, 11, 11, "Race" + index))
                                .toCompletableFuture().get(10, TimeUnit.SECONDS);
                    }));
                }
                List<ClaimOutcome> outcomes = new ArrayList<>();
                for (Future<ClaimOutcome> f : futures) {
                    outcomes.add(f.get(15, TimeUnit.SECONDS));
                }
                long successes = outcomes.stream()
                        .filter(o -> o.status() == ClaimOutcome.Status.SUCCESS).count();
                long conflicts = outcomes.stream()
                        .filter(o -> o.status() == ClaimOutcome.Status.REJECTED
                                && "reservation.conflict".equals(o.diagnosticKey()))
                        .count();
                assertEquals(1, successes);
                assertEquals(parties - 1, conflicts);
                assertEquals(1, ledgerCount(h.ledger));
                assertEquals(1, h.economy.charges.size());
                assertEquals(0, h.reservations.size());
                assertEquals(0, h.quota.chunkReserved(owner));
            } finally {
                callers.shutdownNow();
                assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS), "caller pool must terminate");
            }
        }
    }

    // ------------------------------------------------------------------
    // Value-object sanity
    // ------------------------------------------------------------------

    @Test
    void requestAndOutcomeAreImmutable() {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Set<ChunkKey> input = new HashSet<>();
        input.add(new ChunkKey(world, 1, 1));
        ClaimRequest request = new ClaimRequest(OwnerRef.player(actor), actor, world, input, "Home");
        input.add(new ChunkKey(world, 2, 2));
        assertEquals(1, request.chunks().size());
        assertThrows(UnsupportedOperationException.class,
                () -> request.chunks().add(new ChunkKey(world, 3, 3)));
        assertThrows(IllegalArgumentException.class, () -> new ClaimRequest(OwnerRef.player(actor),
                actor, world, Set.of(), "Home"));
        assertThrows(IllegalArgumentException.class, () -> new ClaimRequest(OwnerRef.player(actor),
                actor, world, Set.of(new ChunkKey(world, 1, 1)), "  "));

        ClaimOutcome ok = ClaimOutcome.success(new LandId(UUID.randomUUID()));
        assertEquals(ClaimOutcome.Status.SUCCESS, ok.status());
        assertEquals("limit.reached", ClaimOutcome.rejected("limit.reached").diagnosticKey());
        assertEquals("economy.failed", ClaimOutcome.failed("economy.failed").diagnosticKey());
    }

    @Test
    void costBasisAllocationStaysExact() {
        // Cross-check the calculator contract the saga relies on: exact sum, deterministic order.
        Set<ChunkCoordinate> coords = Set.of(
                new ChunkCoordinate(2, 0), new ChunkCoordinate(0, 0), new ChunkCoordinate(1, 0));
        var allocation = com.smile.chunkland.api.money.CostBasisCalculator.allocate(new Money(600, EMC), coords);
        long sum = allocation.allocations().values().stream().mapToLong(Money::minorUnits).sum();
        assertEquals(600L, sum);
        assertEquals(3, allocation.size());
    }
}

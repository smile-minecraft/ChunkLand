package com.smile.chunkland.refund;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.persistence.AtomicCommitStep;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.ChunkRepository;
import com.smile.chunkland.persistence.LandRepository;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundCommit;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteAuditRepository;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Refund saga coverage: domain commit strictly before the Economy deposit with
 * the deposit outside any SQL transaction, durable cost-basis amounts that
 * never consult the pricing table, commit-failure isolation from Economy,
 * deposit-failure compensation with retry accounting, operation-id
 * idempotency (including a 100-way concurrent duplicate), fail-closed
 * validation, and refund-only audit discipline.
 */
class RefundSagaTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    static final class FakeDeposits implements RefundEconomy {
        final List<LedgerEntry> deposits = Collections.synchronizedList(new ArrayList<>());
        final List<String> depositThreads = Collections.synchronizedList(new ArrayList<>());
        final List<String> depositLedgerStates = Collections.synchronizedList(new ArrayList<>());
        final List<Boolean> chunksGoneAtDeposit = Collections.synchronizedList(new ArrayList<>());
        final List<Boolean> auditPresentAtDeposit = Collections.synchronizedList(new ArrayList<>());
        volatile RefundOutcome next = RefundOutcome.REFUNDED;
        volatile RuntimeException throwOnDeposit;
        volatile boolean returnNullStage;
        volatile boolean returnNullOutcome;
        volatile boolean available = true;
        volatile String provider = "test-economy";
        volatile OperationLedger ledgerProbe;
        volatile PersistenceStore storeProbe;
        volatile LandId landProbe;

        @Override
        public CompletionStage<RefundOutcome> deposit(LedgerEntry entry) {
            if (throwOnDeposit != null) {
                throw throwOnDeposit;
            }
            if (returnNullStage) {
                return null;
            }
            depositThreads.add(Thread.currentThread().getName());
            OperationLedger probe = ledgerProbe;
            if (probe != null) {
                try {
                    // A blocking ledger read proves the deposit runs off the
                    // persistence thread: inside a SQL transaction callback
                    // this get would deadlock the single persistence thread.
                    depositLedgerStates.add(probe.find(entry.operationId())
                            .toCompletableFuture().get(10, TimeUnit.SECONDS).state());
                } catch (Exception failure) {
                    depositLedgerStates.add("lookup-failed");
                }
            }
            PersistenceStore store = storeProbe;
            if (store != null && landProbe != null) {
                try {
                    boolean gone = new SqliteChunkRepository(store).listByLand(landProbe)
                            .toCompletableFuture().get(10, TimeUnit.SECONDS).isEmpty();
                    chunksGoneAtDeposit.add(gone);
                    boolean audit = !new SqliteAuditRepository(store)
                            .findByAction(RefundCommit.REFUND_AUDIT_ACTION, 100)
                            .toCompletableFuture().get(10, TimeUnit.SECONDS).isEmpty();
                    auditPresentAtDeposit.add(audit);
                } catch (Exception failure) {
                    chunksGoneAtDeposit.add(null);
                    auditPresentAtDeposit.add(null);
                }
            }
            deposits.add(entry);
            if (returnNullOutcome) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.completedFuture(next);
        }

        @Override
        public String providerId() {
            return provider;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    final class Harness implements AutoCloseable {
        final PersistenceStore store;
        final OperationLedger ledger;
        final SqliteLandRepository lands;
        final SqliteChunkRepository chunks;
        final SqliteAuditRepository audits;
        final LogicalReservationRegistry reservations = new LogicalReservationRegistry();
        final LandRegistryStore registryStore = new LandRegistryStore();
        final RuntimeRegistryRebuilder rebuilder;
        final FakeDeposits economy = new FakeDeposits();
        final RefundValidator validator;
        final RefundSaga saga;
        final ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "refund-saga-test");
            t.setDaemon(true);
            return t;
        });
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        Harness(int retryLimit) {
            this(retryLimit, null);
        }

        Harness(int retryLimit, java.util.function.Consumer<AtomicCommitStep> injector) {
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = injector == null ? new OperationLedger(store) : new OperationLedger(store, injector);
            lands = new SqliteLandRepository(store);
            chunks = new SqliteChunkRepository(store);
            audits = new SqliteAuditRepository(store);
            rebuilder = new RuntimeRegistryRebuilder(lands, registryStore);
            RefundValidator durable = new DurableRefundValidator(lands, chunks, EMC);
            validator = request -> {
                order.add("validate");
                return durable.validate(request);
            };
            RefundEconomy live = new RefundEconomy() {
                @Override
                public CompletionStage<RefundOutcome> deposit(LedgerEntry entry) {
                    order.add("deposit");
                    return economy.deposit(entry);
                }

                @Override
                public String providerId() {
                    return economy.providerId();
                }

                @Override
                public boolean isAvailable() {
                    return economy.available;
                }
            };
            economy.ledgerProbe = ledger;
            saga = new RefundSaga(validator, reservations, ledger, live, rebuilder,
                    clock, async, retryLimit);
        }

        RefundResult run(RefundRequest request) throws Exception {
            return saga.refund(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
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

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private record ClaimedLand(LandId landId, UUID actor, UUID world, OwnerRef owner, List<ChunkKey> chunkKeys) {
    }

    private ClaimedLand claimLand(Harness h, OwnerRef ownerHint, UUID actor, UUID world,
            String name, long... bases) throws Exception {
        OwnerRef owner = ownerHint != null ? ownerHint : OwnerRef.player(actor);
        LandId landId = new LandId(UUID.randomUUID());
        Set<ChunkKey> keys = new java.util.LinkedHashSet<>();
        for (int i = 0; i < bases.length; i++) {
            keys.add(new ChunkKey(world, i, 0));
        }
        LandSnapshot snap = new LandSnapshot(landId, name, LandName.normalize(name), owner,
                world, keys, List.of(), 0, 0, NOW, NOW);
        h.lands.save(snap).toCompletableFuture().get(10, TimeUnit.SECONDS);
        int index = 0;
        for (ChunkKey key : keys) {
            h.chunks.addChunk(landId, key, 64, UUID.randomUUID(), bases[index++])
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        return new ClaimedLand(landId, actor, world, owner, List.copyOf(keys));
    }

    private static RefundRequest refund(UUID operationId, ClaimedLand land, List<ChunkKey> keys,
            long numerator, long denominator) {
        return new RefundRequest(operationId, land.actor(), land.world(), land.landId(),
                Set.copyOf(keys), numerator, denominator);
    }

    private static LedgerEntry singleRow(Harness h) {
        List<LedgerEntry> rows = h.ledger.findAll().toCompletableFuture().join();
        assertEquals(1, rows.size(), "expected a single refund ledger row");
        return rows.get(0);
    }

    private static int auditRefundCount(Harness h) {
        return h.audits.findByAction(RefundCommit.REFUND_AUDIT_ACTION, 100)
                .toCompletableFuture().join().size();
    }

    private static int chunkCount(Harness h, LandId landId) {
        return h.chunks.listByLand(landId).toCompletableFuture().join().size();
    }

    private static boolean landExists(Harness h, LandId landId) {
        return h.lands.findById(landId).toCompletableFuture().join().isPresent();
    }

    // ------------------------------------------------------------------
    // 1) Domain-first order with Economy outside any SQL transaction
    // ------------------------------------------------------------------

    @Test
    void domainCommitPrecedesDepositOutsideSqlTransaction() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 101L, 100L);
            h.economy.storeProbe = h.store;
            h.economy.landProbe = land.landId();
            UUID operationId = UUID.randomUUID();

            RefundResult result = h.run(refund(operationId, land, land.chunkKeys(), 1, 2));

            assertEquals(RefundResult.Status.SUCCESS, result.status());
            // (101 + 100) * 1 / 2 = 100.5 rounds half-up to 101, in integer minor units.
            assertEquals(101L, result.refundMinorUnits());
            assertEquals(List.of("validate", "deposit"), h.order);
            // The durable boundary precedes Economy: the deposit ran while the row
            // was DOMAIN_COMMITTED, with the chunks already released and the
            // refund audit already durable.
            assertEquals(List.of("DOMAIN_COMMITTED"), h.economy.depositLedgerStates);
            assertEquals(List.of(true), h.economy.chunksGoneAtDeposit);
            assertEquals(List.of(true), h.economy.auditPresentAtDeposit);
            for (String thread : h.economy.depositThreads) {
                assertNotEquals("chunkland-persistence", thread);
            }
            LedgerEntry row = singleRow(h);
            assertEquals("COMPENSATED", row.state());
            assertEquals("refund:" + operationId, row.economyTransactionRef());
            assertEquals(101L, row.priceMinorUnits());
            assertEquals(1, h.economy.deposits.size());
            assertEquals(operationId, h.economy.deposits.get(0).operationId());
        }
    }

    // ------------------------------------------------------------------
    // 2) Durable cost basis only; the pricing table is never consulted
    // ------------------------------------------------------------------

    @Test
    void refundAmountComesFromDurableCostBasisOnly() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home",
                    101L, 100L, 99L);
            // Uneven basis proves per-chunk summation: (101 + 100 + 99) / 2 = 150.
            RefundResult half = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));
            assertEquals(RefundResult.Status.SUCCESS, half.status());
            assertEquals(150L, half.refundMinorUnits());
            assertEquals(150L, h.economy.deposits.get(0).priceMinorUnits());
        }
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Full", 40L, 60L);
            RefundResult full = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 1));
            assertEquals(RefundResult.Status.SUCCESS, full.status());
            assertEquals(100L, full.refundMinorUnits());
        }
        // The saga has no pricing input at all: no field may carry pricing state.
        for (var field : RefundSaga.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().contains("Pricing"),
                    "RefundSaga must not depend on pricing: " + field);
        }
    }

    // ------------------------------------------------------------------
    // 3) Domain-commit failure never touches Economy or compensation
    // ------------------------------------------------------------------

    @Test
    void domainCommitFailureSkipsDepositAndCompensation() throws Exception {
        java.util.function.Consumer<AtomicCommitStep> injector = step -> {
            if (step == AtomicCommitStep.AFTER_CHUNKS) {
                throw new IllegalStateException("injected commit failure");
            }
        };
        try (Harness h = new Harness(3, injector)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            RefundResult result = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));

            assertEquals(RefundResult.Status.FAILED, result.status());
            assertEquals("refund.commit_failed", result.diagnosticKey());
            assertTrue(h.economy.deposits.isEmpty(), "no deposit may run before the domain commit");
            LedgerEntry row = singleRow(h);
            assertEquals("CREATED", row.state(), "a rolled-back commit must not claim compensation");
            assertEquals(0, row.compensationAttempts());
            assertEquals(1, chunkCount(h, land.landId()), "the domain write rolled back");
            assertEquals(0, auditRefundCount(h));
        }
    }

    @Test
    void lateCommitFailureStillRollsBack() throws Exception {
        java.util.function.Consumer<AtomicCommitStep> injector = step -> {
            if (step == AtomicCommitStep.AFTER_AUDIT) {
                throw new IllegalStateException("injected late failure");
            }
        };
        try (Harness h = new Harness(3, injector)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            RefundResult result = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));

            assertEquals(RefundResult.Status.FAILED, result.status());
            assertTrue(h.economy.deposits.isEmpty());
            assertEquals("CREATED", singleRow(h).state());
            assertEquals(1, chunkCount(h, land.landId()));
            assertEquals(0, auditRefundCount(h));
        }
    }

    // ------------------------------------------------------------------
    // 4) Deposit success settles durable compensation
    // ------------------------------------------------------------------

    @Test
    void partialRefundKeepsLandAndWritesRefundAudit() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home",
                    100L, 100L, 100L);
            List<ChunkKey> released = List.of(land.chunkKeys().get(0));
            RefundResult result = h.run(refund(UUID.randomUUID(), land, released, 1, 2));

            assertEquals(RefundResult.Status.SUCCESS, result.status());
            assertEquals(50L, result.refundMinorUnits());
            assertEquals(2, chunkCount(h, land.landId()));
            assertTrue(landExists(h, land.landId()), "a partially refunded land survives");
            assertEquals("COMPENSATED", singleRow(h).state());
            List<AuditEntry> audits = h.audits.findByAction(RefundCommit.REFUND_AUDIT_ACTION, 100)
                    .toCompletableFuture().join();
            assertEquals(1, audits.size());
            assertEquals(land.landId(), audits.get(0).landId());
            assertTrue(audits.get(0).metadataJson().contains("50"));
        }
    }

    @Test
    void fullRefundDeletesEmptiedLand() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            RefundResult result = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));

            assertEquals(RefundResult.Status.SUCCESS, result.status());
            assertFalse(landExists(h, land.landId()), "a fully refunded land is removed atomically");
            assertEquals(1, auditRefundCount(h));
            assertEquals("COMPENSATED", singleRow(h).state());
        }
    }

    // ------------------------------------------------------------------
    // 5) Deposit failure parks without rollback; retry settles or escalates
    // ------------------------------------------------------------------

    @Test
    void depositFailureParksForRetryThenCompensates() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            UUID operationId = UUID.randomUUID();
            h.economy.next = RefundOutcome.FAILED;

            RefundResult parked = h.run(refund(operationId, land, land.chunkKeys(), 1, 2));

            assertEquals(RefundResult.Status.COMPENSATION_PENDING, parked.status());
            assertEquals(50L, parked.refundMinorUnits());
            LedgerEntry row = singleRow(h);
            assertEquals("COMPENSATION_PENDING", row.state());
            assertEquals(1, row.compensationAttempts());
            assertEquals("refund:" + operationId, row.economyTransactionRef());
            assertEquals(0, chunkCount(h, land.landId()), "the domain is never rolled back");
            assertEquals(1, auditRefundCount(h));

            h.economy.next = RefundOutcome.REFUNDED;
            RefundResult retried = h.saga.retry(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(RefundResult.Status.SUCCESS, retried.status());
            assertEquals(50L, retried.refundMinorUnits());
            assertEquals("COMPENSATED", singleRow(h).state());
            assertEquals(2, h.economy.deposits.size(), "exactly one deposit per attempt");
            assertEquals(1, auditRefundCount(h), "no second audit on retry");
            assertEquals(0, chunkCount(h, land.landId()), "no second domain commit on retry");
        }
    }

    @Test
    void unconfirmedDepositsParkWithoutRollback() throws Exception {
        // UNKNOWN outcome.
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "A", 100L);
            h.economy.next = RefundOutcome.UNKNOWN;
            RefundResult result = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));
            assertEquals(RefundResult.Status.COMPENSATION_PENDING, result.status());
            assertEquals("COMPENSATION_PENDING", singleRow(h).state());
            assertEquals(0, chunkCount(h, land.landId()));
        }
        // Thrown exception.
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "B", 100L);
            h.economy.throwOnDeposit = new RuntimeException("bridge down");
            RefundResult result = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));
            assertEquals(RefundResult.Status.COMPENSATION_PENDING, result.status());
            assertEquals(0, chunkCount(h, land.landId()));
        }
        // Null stage.
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "C", 100L);
            h.economy.returnNullStage = true;
            RefundResult result = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));
            assertEquals(RefundResult.Status.COMPENSATION_PENDING, result.status());
            assertEquals(0, chunkCount(h, land.landId()));
        }
        // Null outcome.
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "D", 100L);
            h.economy.returnNullOutcome = true;
            RefundResult result = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));
            assertEquals(RefundResult.Status.COMPENSATION_PENDING, result.status());
            assertEquals(0, chunkCount(h, land.landId()));
        }
    }

    @Test
    void retryLimitEscalatesToReconciliation() throws Exception {
        try (Harness h = new Harness(2)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            UUID operationId = UUID.randomUUID();
            h.economy.next = RefundOutcome.FAILED;

            RefundResult first = h.run(refund(operationId, land, land.chunkKeys(), 1, 2));
            assertEquals(RefundResult.Status.COMPENSATION_PENDING, first.status());
            assertEquals(1, singleRow(h).compensationAttempts());

            RefundResult second = h.saga.retry(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(RefundResult.Status.NEEDS_RECONCILIATION, second.status());
            assertEquals("refund.reconciliation", second.diagnosticKey());
            assertEquals("NEEDS_RECONCILIATION", singleRow(h).state());

            RefundResult third = h.saga.retry(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(RefundResult.Status.NEEDS_RECONCILIATION, third.status());
            assertEquals(2, h.economy.deposits.size(), "a terminal row never deposits again");
        }
    }

    // ------------------------------------------------------------------
    // 6) Operation-id idempotency, including a 100-way concurrent duplicate
    // ------------------------------------------------------------------

    @Test
    void duplicateOperationIdDoesNotDoubleRefund() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            UUID operationId = UUID.randomUUID();
            RefundRequest request = refund(operationId, land, land.chunkKeys(), 1, 2);

            RefundResult first = h.run(request);
            RefundResult second = h.run(request);

            assertEquals(RefundResult.Status.SUCCESS, first.status());
            assertEquals(RefundResult.Status.SUCCESS, second.status());
            assertEquals(50L, second.refundMinorUnits());
            assertEquals(1, h.economy.deposits.size());
            assertEquals(1, auditRefundCount(h));
            assertEquals(1, h.ledger.findAll().toCompletableFuture().join().size());
        }
    }

    @Test
    void concurrentDuplicatesShareOneExecution() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            UUID operationId = UUID.randomUUID();
            RefundRequest request = refund(operationId, land, land.chunkKeys(), 1, 2);
            int parties = 100;
            ExecutorService callers = Executors.newFixedThreadPool(parties, r -> {
                Thread thread = new Thread(r, "refund-duplicate");
                thread.setDaemon(true);
                return thread;
            });
            try {
                CyclicBarrier barrier = new CyclicBarrier(parties);
                List<Future<RefundResult>> futures = new ArrayList<>(parties);
                for (int i = 0; i < parties; i++) {
                    futures.add(callers.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return h.saga.refund(request).toCompletableFuture().get(15, TimeUnit.SECONDS);
                    }));
                }
                for (Future<RefundResult> future : futures) {
                    RefundResult result = future.get(20, TimeUnit.SECONDS);
                    assertEquals(RefundResult.Status.SUCCESS, result.status());
                    assertEquals(50L, result.refundMinorUnits());
                }
            } finally {
                callers.shutdownNow();
            }
            assertEquals(1, h.economy.deposits.size(), "one shared execution, one deposit");
            assertEquals(1, auditRefundCount(h));
            assertEquals(1, h.ledger.findAll().toCompletableFuture().join().size());
            assertEquals("COMPENSATED", singleRow(h).state());
        }
    }

    // ------------------------------------------------------------------
    // 7) Fail-closed validation paths
    // ------------------------------------------------------------------

    @Test
    void unknownLandIsRejectedWithoutLedgerRow() throws Exception {
        try (Harness h = new Harness(3)) {
            UUID world = UUID.randomUUID();
            RefundRequest request = new RefundRequest(UUID.randomUUID(), UUID.randomUUID(), world,
                    new LandId(UUID.randomUUID()), Set.of(new ChunkKey(world, 0, 0)), 1, 2);
            RefundResult result = h.run(request);

            assertEquals(RefundResult.Status.REJECTED, result.status());
            assertEquals("refund.unknown_land", result.diagnosticKey());
            assertTrue(h.ledger.findAll().toCompletableFuture().join().isEmpty());
            assertEquals(0, auditRefundCount(h));
            assertTrue(h.economy.deposits.isEmpty());
        }
    }

    @Test
    void serverLandIsRejectedWithoutRefund() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, OwnerRef.server(), UUID.randomUUID(),
                    UUID.randomUUID(), "Spawn", 100L);
            RefundResult result = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));

            assertEquals(RefundResult.Status.REJECTED, result.status());
            assertEquals("refund.server_land_no_refund", result.diagnosticKey());
            assertTrue(h.ledger.findAll().toCompletableFuture().join().isEmpty());
            assertEquals(1, chunkCount(h, land.landId()));
        }
    }

    @Test
    void staleChunkIsRejectedWithoutPayout() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            ChunkKey foreign = new ChunkKey(land.world(), 9, 9);
            RefundResult result = h.run(refund(UUID.randomUUID(), land, List.of(foreign), 1, 2));

            assertEquals(RefundResult.Status.REJECTED, result.status());
            assertEquals("refund.stale", result.diagnosticKey());
            assertTrue(h.economy.deposits.isEmpty());
        }
    }

    @Test
    void alreadyRefundedChunkCannotBeRefundedAgain() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home",
                    100L, 100L);
            List<ChunkKey> first = List.of(land.chunkKeys().get(0));
            RefundResult once = h.run(refund(UUID.randomUUID(), land, first, 1, 2));
            assertEquals(RefundResult.Status.SUCCESS, once.status());

            RefundResult twice = h.run(refund(UUID.randomUUID(), land, first, 1, 2));
            assertEquals(RefundResult.Status.REJECTED, twice.status());
            assertEquals("refund.stale", twice.diagnosticKey());
            assertEquals(1, h.economy.deposits.size(), "no second payout for a new operation id");
            assertEquals(1, auditRefundCount(h));
        }
    }

    @Test
    void zeroBasisTakesZeroValuePathWithoutDeposit() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Free", 0L);
            RefundResult result = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));

            assertEquals(RefundResult.Status.SUCCESS, result.status());
            assertEquals(0L, result.refundMinorUnits());
            assertTrue(h.economy.deposits.isEmpty(), "a zero refund never calls Economy");
            LedgerEntry row = singleRow(h);
            assertEquals("COMPENSATED", row.state());
            assertEquals(RefundSaga.ZERO_VALUE_TRANSACTION_REF, row.economyTransactionRef());
            assertEquals(1, auditRefundCount(h), "the accepted domain path is still audited");
        }
    }

    @Test
    void zeroRatioRefundsNothingWithoutDeposit() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            RefundResult result = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 0, 2));

            assertEquals(RefundResult.Status.SUCCESS, result.status());
            assertEquals(0L, result.refundMinorUnits());
            assertTrue(h.economy.deposits.isEmpty());
            assertEquals(0, chunkCount(h, land.landId()), "the domain still releases the chunks");
        }
    }

    @Test
    void negativeBasisIsRejectedFailClosed() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            h.chunks.addChunk(land.landId(), land.chunkKeys().get(0), 64, UUID.randomUUID(), -5L)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            RefundResult result = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));

            assertEquals(RefundResult.Status.REJECTED, result.status());
            assertEquals("refund.invalid_basis", result.diagnosticKey());
            assertTrue(h.economy.deposits.isEmpty());
            assertTrue(h.ledger.findAll().toCompletableFuture().join().isEmpty());
        }
    }

    @Test
    void invalidRatiosAreRejectedAtTheSeam() {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        Set<ChunkKey> keys = Set.of(new ChunkKey(world, 0, 0));
        LandId landId = new LandId(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> new RefundRequest(UUID.randomUUID(), actor, world, landId, keys, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RefundRequest(UUID.randomUUID(), actor, world, landId, keys, -1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new RefundRequest(UUID.randomUUID(), actor, world, landId, keys, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RefundRequest(UUID.randomUUID(), actor, world, landId, Set.of(), 1, 2));
    }

    @Test
    void unavailableOrUnknownProviderIsRejectedBeforeLedger() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            h.economy.available = false;
            RefundResult result = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));

            assertEquals(RefundResult.Status.REJECTED, result.status());
            assertEquals("refund.economy_unavailable", result.diagnosticKey());
            assertTrue(h.ledger.findAll().toCompletableFuture().join().isEmpty());
        }
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            h.economy.provider = "  ";
            RefundResult result = h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));

            assertEquals(RefundResult.Status.REJECTED, result.status());
            assertEquals("refund.unknown_provider", result.diagnosticKey());
            assertTrue(h.ledger.findAll().toCompletableFuture().join().isEmpty());
        }
    }

    @Test
    void foreignWorldRequestIsRejected() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            UUID otherWorld = UUID.randomUUID();
            ChunkKey other = new ChunkKey(otherWorld, 0, 0);
            RefundRequest request = new RefundRequest(UUID.randomUUID(), land.actor(), otherWorld,
                    land.landId(), Set.of(other), 1, 2);
            RefundResult result = h.run(request);

            assertEquals(RefundResult.Status.REJECTED, result.status());
            assertTrue(h.economy.deposits.isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // 8) Audit discipline + retry seam + structural separation
    // ------------------------------------------------------------------

    @Test
    void failedPathsWriteNoRefundAudit() throws Exception {
        try (Harness h = new Harness(3)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            h.economy.available = false;
            h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));
            assertEquals(0, auditRefundCount(h));
        }
        java.util.function.Consumer<AtomicCommitStep> injector = step -> {
            if (step == AtomicCommitStep.AFTER_CHUNKS) {
                throw new IllegalStateException("boom");
            }
        };
        try (Harness h = new Harness(3, injector)) {
            ClaimedLand land = claimLand(h, null, UUID.randomUUID(), UUID.randomUUID(), "Home", 100L);
            h.run(refund(UUID.randomUUID(), land, land.chunkKeys(), 1, 2));
            assertEquals(0, auditRefundCount(h));
        }
    }

    @Test
    void retryOnUnknownOperationFailsClosed() throws Exception {
        try (Harness h = new Harness(3)) {
            RefundResult result = h.saga.retry(UUID.randomUUID())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(RefundResult.Status.FAILED, result.status());
            assertEquals("refund.unknown_operation", result.diagnosticKey());
        }
    }

    @Test
    void retryOnForeignOperationTypeIsRejected() throws Exception {
        try (Harness h = new Harness(3)) {
            UUID world = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            UUID operationId = UUID.randomUUID();
            OperationPayload claim = OperationPayload.claim(operationId, actor, world,
                    new LandId(UUID.randomUUID()),
                    List.of(new OperationPayload.Chunk(new ChunkKey(world, 0, 0), 64,
                            UUID.randomUUID(), 100L)),
                    100L, "test-economy", NOW, "Home");
            h.ledger.insert(LedgerEntry.fromPayload(claim,
                    com.smile.chunkland.persistence.LedgerState.CREATED))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            RefundResult result = h.saga.retry(operationId)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(RefundResult.Status.REJECTED, result.status());
            assertEquals("refund.unsupported", result.diagnosticKey());
            assertTrue(h.economy.deposits.isEmpty());
        }
    }

    @Test
    void refundAndClaimKeepIndependentOrderLogic() {
        assertHasMethod(RefundSaga.class, "depositThenPublish");
        assertHasMethod(RefundSaga.class, "parkForCompensation");
        assertHasMethod(RefundSaga.class, "commitDomain");
        assertHasMethod(com.smile.chunkland.claim.ClaimSaga.class, "chargeStep");
        assertMissingMethod(RefundSaga.class, "chargeStep");
        assertMissingMethod(com.smile.chunkland.claim.ClaimSaga.class, "depositThenPublish");
        assertMissingMethod(com.smile.chunkland.claim.ClaimSaga.class, "parkForCompensation");
    }

    private static void assertHasMethod(Class<?> type, String name) {
        for (var method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return;
            }
        }
        fail(type.getSimpleName() + " must declare " + name);
    }

    private static void assertMissingMethod(Class<?> type, String name) {
        for (var method : type.getDeclaredMethods()) {
            assertNotEquals(name, method.getName(),
                    type.getSimpleName() + " must not share order logic " + name);
        }
    }
}

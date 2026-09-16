package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.CrashRecoveryScanner;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.LedgerState;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PaymentLookup;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RecoveryHandlers;
import com.smile.chunkland.persistence.RecoveryResult;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteAuditRepository;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.persistence.SqliteSubLandRepository;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DELETE compensation and startup-recovery contract.
 *
 * <p>A failed deposit parks the DELETE row through the shared refund
 * compensation path without rolling back the committed {@code LAND_DELETE};
 * the startup scanner retries the same DELETE payload and settles it as
 * compensated, with no second domain mutation and no duplicate refund. A
 * {@code DOMAIN_COMMITTED} row left behind by a publish failure is recovered
 * the same way: the runtime is rebuilt from the authoritative database (the
 * land stays gone) before the refund is retried.
 */
class DeleteCompensationRecoveryTest {

    @TempDir java.nio.file.Path temp;

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final class FakeEconomy implements ClaimEconomy {
        final List<LedgerEntry> refunds = new CopyOnWriteArrayList<>();
        volatile RefundOutcome refundOutcome = RefundOutcome.REFUNDED;

        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request,
                Money price) {
            return CompletableFuture.completedFuture(ChargeResult.ok());
        }

        @Override
        public CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
            refunds.add(entry);
            RefundOutcome outcome = refundOutcome;
            if (outcome == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("refund failed"));
            }
            return CompletableFuture.completedFuture(outcome);
        }

        @Override
        public String providerId() {
            return "test-economy";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    final class Harness implements AutoCloseable {
        final PersistenceStore store;
        final OperationLedger ledger;
        final SqliteLandRepository lands;
        final SqliteChunkRepository chunks;
        final SqliteSubLandRepository sublands;
        final SqliteAuditRepository audits;
        final OwnerQuotaService quota;
        final LogicalReservationRegistry reservations = new LogicalReservationRegistry();
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        final RuntimeRegistryRebuilder rebuilder;
        final SelectionSessionManager selections;
        final SnapshotDeleteValidator validator;
        final DeleteSaga saga;
        final AtomicInteger rebuildCalls = new AtomicInteger();

        Harness(int maxLands, int maxChunks) {
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = new OperationLedger(store);
            lands = new SqliteLandRepository(store);
            chunks = new SqliteChunkRepository(store);
            sublands = new SqliteSubLandRepository(store);
            audits = new SqliteAuditRepository(store);
            quota = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), new LimitSettings(maxLands, maxChunks, 128, 16),
                            0L, Map.of())));
            rebuilder = new RuntimeRegistryRebuilder(lands, registryStore);
            SelectionStructureRevisionLookup liveStructures = landId -> {
                try {
                    var snapshot = registryStore.snapshot();
                    if (snapshot == null) {
                        return OptionalLong.empty();
                    }
                    var land = snapshot.land(landId);
                    if (land == null) {
                        return OptionalLong.empty();
                    }
                    return OptionalLong.of(land.structureRevision());
                } catch (RuntimeException unresolved) {
                    return OptionalLong.empty();
                }
            };
            selections = new SelectionSessionManager(
                    (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                    SelectionVisualizationTaskController.noop(),
                    SelectionNotifier.noop(),
                    (SelectionClock) () -> NOW,
                    Duration.ofMinutes(10),
                    ignored -> Optional.of("world"),
                    liveStructures);
            SelectionStructureRevisionLookup structures = liveStructures;
            validator = new SnapshotDeleteValidator(registryStore, structures::currentRevision);
            saga = new DeleteSaga(validator, chunks, reservations, ledger,
                    economy, rebuilder, quota, selections, CLOCK, Runnable::run, 3);
        }

        void insertLand(LandId landId, OwnerRef owner, UUID world, Set<ChunkKey> chunkSet,
                long structureRevision, long costBasis) throws Exception {
            String displayName = "Home " + landId.value().toString().substring(0, 8);
            LandSnapshot snapshot = new LandSnapshot(landId, displayName,
                    LandName.normalize(displayName), owner, world, chunkSet, List.of(),
                    structureRevision, 0, NOW, NOW);
            lands.save(snapshot).toCompletableFuture().get(10, TimeUnit.SECONDS);
            for (ChunkKey key : chunkSet) {
                chunks.addChunk(landId, key, 64, UUID.randomUUID(), costBasis)
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }

        void rebuild() throws Exception {
            rebuilder.rebuild().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        DeleteOutcome run(DeleteRequest request) throws Exception {
            return saga.delete(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        List<LedgerEntry> ledgerRows() throws Exception {
            return ledger.findAll().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        RecoveryHandlers recoveryHandlers() {
            return RecoveryHandlers.of(
                    entry -> CompletableFuture.completedFuture(PaymentLookup.unknown()),
                    entry -> economy.refund(entry),
                    (entry, payload) -> CompletableFuture.failedFuture(
                            new IllegalStateException("claim replay is never used for DELETE")),
                    (entry, payload) -> {
                        rebuildCalls.incrementAndGet();
                        return rebuilder.rebuild().thenApply(ignored -> null);
                    });
        }

        List<RecoveryResult> scan(int retryLimit) throws Exception {
            return new CrashRecoveryScanner(ledger, recoveryHandlers(), CLOCK, retryLimit)
                    .scan().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static ChunkKey chunk(UUID world, int x, int z) {
        return new ChunkKey(world, x, z);
    }

    @Test
    void deleteCompensationParksAndRecoverySettlesWithoutDuplicateRefund() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0,
                    1000L);
            h.rebuild();
            h.economy.refundOutcome = RefundOutcome.FAILED;

            DeleteOutcome outcome = h.run(new DeleteRequest(owner, actor, world, land, 0L));
            assertEquals(DeleteOutcome.Status.COMPENSATION_PENDING, outcome.status());

            // The parked row walks the shared refund compensation contract.
            List<LedgerEntry> rows = h.ledgerRows();
            assertEquals(1, rows.size(), "exactly one DELETE ledger row");
            LedgerEntry parked = rows.get(0);
            assertEquals("DELETE", parked.operationType());
            assertEquals(2000L, parked.priceMinorUnits(),
                    "ledger amount stays the full durable basis");
            assertEquals(2000L, outcome.refundMinorUnits());
            assertEquals(LedgerState.COMPENSATION_PENDING.name(), parked.state());
            assertEquals("delete:" + parked.operationId(), parked.economyTransactionRef(),
                    "the parked row must carry the delete idempotency reference");
            assertEquals(1, parked.compensationAttempts(),
                    "the saga records one compensation attempt through recordCompensationFailure");

            // The payload contract survives the park: DELETE type, full amount.
            OperationPayload payload = OperationPayload.fromJson(parked.payloadJson());
            assertEquals("DELETE", payload.operationType());
            assertEquals(2000L, payload.priceMinorUnits());

            // The committed delete is never rolled back for the refund.
            assertTrue(h.lands.findById(land).toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .isEmpty(), "compensation must never resurrect the land");
            assertTrue(h.registryStore.snapshot().findLandId(world, 0, 0) == null,
                    "deleted chunks stay wilderness through compensation");

            // Restart recovery retries the same row and settles it.
            h.economy.refundOutcome = RefundOutcome.REFUNDED;
            List<RecoveryResult> results = h.scan(3);
            assertEquals(1, results.size());
            assertEquals(LedgerState.COMPENSATED.name(), results.get(0).resultingState());
            assertEquals(LedgerState.RecoveryClassification.RETRY_COMPENSATION,
                    results.get(0).classification());
            assertEquals(LedgerState.COMPENSATED.name(), h.ledgerRows().get(0).state());
            assertEquals(2, h.economy.refunds.size(),
                    "exactly one saga attempt plus one recovery retry, no duplicate refund");
            assertEquals(2000L, h.economy.refunds.get(1).priceMinorUnits());
            assertEquals(1, h.rebuildCalls.get(), "a settled delete must republish the runtime");

            // Recovery never replays the domain: still one land gone, one audit, one row.
            assertTrue(h.lands.findById(land).toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .isEmpty(), "recovery must never resurrect the land");
            assertEquals(1, h.ledgerRows().size(), "recovery must not write a second ledger row");
        }
    }

    @Test
    void domainCommittedDeleteRowRecoversPublishAndRefund() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 750L);
            h.rebuild();
            // Simulate the crash window between the atomic delete commit and
            // the saga's publish: the land row is already gone durably while
            // the ledger still waits at DOMAIN_COMMITTED.
            UUID operationId = UUID.randomUUID();
            ChunkKey key = chunk(world, 0, 0);
            var facts = h.chunks.factsByLand(land).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            Long basis = facts.get(key).costBasisMinorUnits();
            UUID lot = facts.get(key).claimLotId();
            OperationPayload payload = OperationPayload.delete(operationId, actor, world, land,
                    List.of(new OperationPayload.Chunk(key, 64, lot, basis)), basis,
                    "test-economy", NOW, "Home");
            h.ledger.create(payload).toCompletableFuture().get(10, TimeUnit.SECONDS);
            com.smile.chunkland.persistence.AuditEntry audit = new com.smile.chunkland.persistence.AuditEntry(
                    0, NOW, actor, "LAND_DELETE", land, world, key.pack(),
                    OperationPayload.CURRENT_SCHEMA_VERSION, null, payload.toJson(),
                    "{\"refundMinorUnits\":" + basis + "}",
                    new ArrayList<>(List.of(key)));
            h.ledger.commitDeleteAtomically(new com.smile.chunkland.persistence.DeleteCommit(
                    operationId, land, world, owner, 0,
                    List.of(new OperationPayload.Chunk(key, 64, lot, basis)), basis, audit))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(LedgerState.DOMAIN_COMMITTED.name(),
                    h.ledgerRows().get(0).state());
            assertTrue(h.registryStore.snapshot().findLandId(world, 0, 0) != null,
                    "test premise: the runtime still shows the land until recovery rebuilds");

            List<RecoveryResult> results = h.scan(3);
            assertEquals(1, results.size());
            assertEquals(LedgerState.COMPENSATED.name(), results.get(0).resultingState());
            assertTrue(h.registryStore.snapshot().findLandId(world, 0, 0) == null,
                    "recovery must publish the deletion back to wilderness");
            assertEquals(1, h.economy.refunds.size(), "recovery retries the parked refund once");
            assertEquals(basis, h.economy.refunds.get(0).priceMinorUnits());
            assertEquals("delete:" + operationId,
                    h.ledgerRows().get(0).economyTransactionRef(),
                    "recovery must park the delete row with the delete reference, "
                            + "matching the saga park/settle prefix");
        }
    }

    @Test
    void exhaustedDeleteCompensationReachesReconciliationWithoutResurrecting() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            h.economy.refundOutcome = RefundOutcome.FAILED;

            DeleteOutcome outcome = h.run(new DeleteRequest(owner, actor, world, land, 0L));
            assertEquals(DeleteOutcome.Status.COMPENSATION_PENDING, outcome.status());

            // A retry limit of one exhausts the shared compensation path on the
            // next scan: the row is quarantined, never resurrected, never refunded twice.
            List<RecoveryResult> results = h.scan(1);
            assertEquals(1, results.size());
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(), results.get(0).resultingState());
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(), h.ledgerRows().get(0).state());
            assertEquals("DELETE", h.ledgerRows().get(0).operationType());
            assertTrue(h.lands.findById(land).toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .isEmpty(), "reconciliation must never resurrect the land");
        }
    }
}

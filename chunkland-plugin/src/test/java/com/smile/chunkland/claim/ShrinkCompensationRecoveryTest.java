package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.CostBasisCalculator;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.AuditEntry;
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
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionUpdate;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * SHRINK compensation and startup-recovery contract.
 *
 * <p>A failed deposit parks the SHRINK row through the shared refund
 * compensation path ({@code parkForRefundCompensation} /
 * {@code recordCompensationFailure}) without rolling back the committed
 * {@code CHUNK_REMOVE}; the startup scanner retries the same SHRINK payload
 * and settles it as compensated, with no second domain mutation and no
 * duplicate refund. The ledger payload always carries operation type
 * {@code SHRINK} with the refund derived from the durable per-chunk cost
 * bases times the shrink ratio.
 */
class ShrinkCompensationRecoveryTest {

    @TempDir java.nio.file.Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
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
        final SnapshotShrinkValidator validator;
        final ShrinkSaga saga;
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
            validator = new SnapshotShrinkValidator(registryStore,
                    actorUuid -> selections.sessionFor(actorUuid)
                            .map(session -> OptionalLong.of(session.selectionRevision()))
                            .orElseGet(OptionalLong::empty),
                    actorUuid -> selections.sessionFor(actorUuid)
                            .map(session -> OptionalLong.of(session.sessionGeneration()))
                            .orElseGet(OptionalLong::empty),
                    structures::currentRevision);
            saga = new ShrinkSaga(validator, chunks, EMC, reservations, ledger,
                    economy, rebuilder, quota, CLOCK, Runnable::run, 3);
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

        SelectionSession selectDelta(UUID actor, UUID world, LandId target, Set<ChunkKey> delta) {
            SelectionSession initial = SelectionSession.initial(
                    actor, world, SelectionMode.CREATE_LAND,
                    Optional.of(target), Optional.empty(),
                    Optional.of(new SelectionPoint(world, 0, 64, 0)),
                    Optional.of(new SelectionPoint(world, 16, 64, 16)),
                    0, NOW);
            SelectionSession stamped = selections.start(initial);
            return selections.updateSelection(actor, stamped, new SelectionUpdate(
                            initial.pointA(), initial.pointB(), delta, Map.of()))
                    .orElseThrow(() -> new IllegalStateException("selection update must succeed"));
        }

        ShrinkRequest requestFor(SelectionSession session, OwnerRef owner) {
            return new ShrinkRequest(owner, session.playerId(), session.worldId(),
                    session.targetLandId().orElseThrow(), session.selectedChunks(),
                    session.selectionRevision(), session.sessionGeneration(),
                    session.baseStructureRevision());
        }

        ShrinkOutcome run(ShrinkRequest request) throws Exception {
            return saga.shrink(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        List<LedgerEntry> ledgerRows() throws Exception {
            return ledger.findAll().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        List<String> auditActions(LandId landId) throws Exception {
            List<String> actions = new ArrayList<>();
            for (AuditEntry entry : audits.findByLand(landId, 100, 0).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)) {
                actions.add(entry.action());
            }
            return List.copyOf(actions);
        }

        RecoveryHandlers recoveryHandlers() {
            return RecoveryHandlers.of(
                    entry -> CompletableFuture.completedFuture(PaymentLookup.unknown()),
                    entry -> economy.refund(entry),
                    (entry, payload) -> CompletableFuture.failedFuture(
                            new IllegalStateException("claim replay is never used for SHRINK")),
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
    void shrinkCompensationParksAndRecoverySettlesWithoutDuplicateRefund() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0,
                    1000L);
            h.rebuild();
            h.economy.refundOutcome = RefundOutcome.FAILED;

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ShrinkOutcome outcome = h.run(h.requestFor(session, owner));
            assertEquals(ShrinkOutcome.Status.COMPENSATION_PENDING, outcome.status());

            // The parked row walks the shared refund compensation contract.
            List<LedgerEntry> rows = h.ledgerRows();
            assertEquals(1, rows.size(), "exactly one SHRINK ledger row");
            LedgerEntry parked = rows.get(0);
            assertEquals("SHRINK", parked.operationType());
            long expectedRefund =
                    CostBasisCalculator.refund(new Money(1000L, EMC), 1, 2).minorUnits();
            assertEquals(500L, expectedRefund, "test premise: half of 1000");
            assertEquals(expectedRefund, parked.priceMinorUnits(),
                    "ledger amount stays durable-basis x ratio");
            assertEquals(expectedRefund, outcome.refundMinorUnits());
            assertEquals(LedgerState.COMPENSATION_PENDING.name(), parked.state());
            assertEquals("shrink:" + parked.operationId(), parked.economyTransactionRef(),
                    "the parked row must carry the shrink idempotency reference");
            assertEquals(1, parked.compensationAttempts(),
                    "the saga records one compensation attempt through recordCompensationFailure");

            // The payload contract survives the park: SHRINK type plus the ratio.
            OperationPayload payload = OperationPayload.fromJson(parked.payloadJson());
            assertEquals("SHRINK", payload.operationType());
            assertEquals(expectedRefund, payload.priceMinorUnits());
            assertEquals(1L, payload.refundNumerator());
            assertEquals(2L, payload.refundDenominator());

            // The committed removal is never rolled back for the refund.
            assertEquals(1, h.chunks.factsByLand(land).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).size());
            assertEquals(1L, h.lands.findById(land).toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .orElseThrow().structureRevision());
            assertEquals(List.of("CHUNK_REMOVE"), h.auditActions(land));
            assertTrue(h.lands.findById(land).toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .isPresent(), "compensation must never delete the land");

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
            assertEquals(expectedRefund, h.economy.refunds.get(1).priceMinorUnits());
            assertEquals(1, h.rebuildCalls.get(), "a settled shrink must republish the runtime");

            // Recovery never replays the domain: still one removal, one audit.
            assertEquals(1, h.chunks.factsByLand(land).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).size());
            assertEquals(List.of("CHUNK_REMOVE"), h.auditActions(land));
            assertEquals(1, h.ledgerRows().size(), "recovery must not write a second ledger row");
            assertTrue(h.lands.findById(land).toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .isPresent(), "recovery must never delete the land");
        }
    }

    @Test
    void exhaustedShrinkCompensationReachesReconciliationWithoutDeleting() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0,
                    100L);
            h.rebuild();
            h.economy.refundOutcome = RefundOutcome.FAILED;

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ShrinkOutcome outcome = h.run(h.requestFor(session, owner));
            assertEquals(ShrinkOutcome.Status.COMPENSATION_PENDING, outcome.status());

            // A retry limit of one exhausts the shared compensation path on the
            // next scan: the row is quarantined, never deleted, never refunded twice.
            List<RecoveryResult> results = h.scan(1);
            assertEquals(1, results.size());
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(), results.get(0).resultingState());
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(), h.ledgerRows().get(0).state());
            assertEquals("SHRINK", h.ledgerRows().get(0).operationType());
            assertEquals(1, h.chunks.factsByLand(land).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).size());
            assertEquals(List.of("CHUNK_REMOVE"), h.auditActions(land));
            assertTrue(h.lands.findById(land).toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .isPresent(), "reconciliation must never delete the land");
        }
    }
}

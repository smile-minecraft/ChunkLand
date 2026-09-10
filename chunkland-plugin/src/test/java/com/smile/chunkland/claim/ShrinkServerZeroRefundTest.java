package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
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
import com.smile.chunkland.persistence.ShrinkCommit;
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
 * Server Land shrink never moves money, so neither the saga nor startup
 * recovery may call any Economy method for a zero refund.
 */
class ShrinkServerZeroRefundTest {

    @TempDir java.nio.file.Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final class CountingEconomy implements ClaimEconomy {
        final List<LedgerEntry> refunds = new CopyOnWriteArrayList<>();
        final AtomicInteger providerIdCalls = new AtomicInteger();
        final AtomicInteger isAvailableCalls = new AtomicInteger();
        final AtomicInteger refundCalls = new AtomicInteger();
        volatile RefundOutcome refundOutcome = RefundOutcome.REFUNDED;
        volatile boolean available = true;

        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request,
                Money price) {
            return CompletableFuture.completedFuture(ChargeResult.ok());
        }

        @Override
        public CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
            refundCalls.incrementAndGet();
            refunds.add(entry);
            RefundOutcome outcome = refundOutcome;
            if (outcome == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("refund failed"));
            }
            return CompletableFuture.completedFuture(outcome);
        }

        @Override
        public String providerId() {
            providerIdCalls.incrementAndGet();
            return "test-economy";
        }

        @Override
        public boolean isAvailable() {
            isAvailableCalls.incrementAndGet();
            return available;
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
        final CountingEconomy economy = new CountingEconomy();
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

        List<LedgerEntry> ledgerRows() throws Exception {
            return ledger.findAll().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        RecoveryHandlers recoveryHandlers() {
            return RecoveryHandlers.of(
                    entry -> CompletableFuture.completedFuture(PaymentLookup.unknown()),
                    economy::refund,
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
    void serverShrinkNeverCallsAnyEconomyMethod() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.server();
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 0L);
            h.rebuild();
            // Even with the provider down, Server Land must still shrink: there
            // is no money to move, so availability must never be consulted.
            h.economy.available = false;

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ShrinkOutcome outcome = h.saga.shrink(h.requestFor(session, owner))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ShrinkOutcome.Status.SUCCESS, outcome.status());
            assertEquals(0L, outcome.refundMinorUnits());
            assertEquals(0, h.economy.providerIdCalls.get(), "server shrink must not read provider identity");
            assertEquals(0, h.economy.isAvailableCalls.get(), "server shrink must not check availability");
            assertEquals(0, h.economy.refundCalls.get(), "server shrink must not deposit");
            assertTrue(h.economy.refunds.isEmpty());

            List<LedgerEntry> rows = h.ledgerRows();
            assertEquals(1, rows.size());
            assertEquals(LedgerState.COMPENSATED.name(), rows.get(0).state());
            assertEquals(0L, rows.get(0).priceMinorUnits());
            assertEquals(ShrinkSaga.SERVER_LAND_PROVIDER_ID, rows.get(0).economyProviderId());
            assertEquals(ShrinkSaga.ZERO_VALUE_TRANSACTION_REF, rows.get(0).economyTransactionRef());
            // The committed removal is published immediately from durable truth.
            assertTrue(h.registryStore.snapshot().findLandId(world, 1, 0) == null);
            assertEquals(land, h.registryStore.snapshot().findLandId(world, 0, 0));

            // A restart after the settle is a no-op and still touches no Economy.
            List<RecoveryResult> rescanned = h.scan(3);
            assertEquals(1, rescanned.size());
            assertEquals(LedgerState.COMPENSATED.name(), rescanned.get(0).resultingState());
            assertEquals(0, h.economy.providerIdCalls.get());
            assertEquals(0, h.economy.isAvailableCalls.get());
            assertEquals(0, h.economy.refundCalls.get());
        }
    }

    @Test
    void serverDomainCommittedRecoverySettlesZeroWithoutEconomy() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            h.insertLand(land, OwnerRef.server(), world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 0L);
            h.rebuild();

            // Simulate a crash after the atomic domain commit but before the
            // zero settle: the durable row waits in DOMAIN_COMMITTED.
            UUID operationId = UUID.randomUUID();
            ChunkKey removed = chunk(world, 1, 0);
            OperationPayload.Chunk payloadChunk =
                    new OperationPayload.Chunk(removed, 64, UUID.randomUUID(), 0L);
            String displayName = "Home " + land.value().toString().substring(0, 8);
            OperationPayload payload = OperationPayload.shrink(operationId, actor, world, land,
                    List.of(payloadChunk), 0L, ShrinkSaga.SERVER_LAND_PROVIDER_ID, NOW,
                    displayName, ShrinkSaga.REFUND_NUMERATOR, ShrinkSaga.REFUND_DENOMINATOR);
            h.ledger.create(payload).toCompletableFuture().get(10, TimeUnit.SECONDS);
            AuditEntry audit = new AuditEntry(0, NOW, actor, "CHUNK_REMOVE", land, world,
                    removed.pack(), OperationPayload.CURRENT_SCHEMA_VERSION, null,
                    payload.toJson(),
                    "{\"refundMinorUnits\":0,\"costBasisMinorUnits\":0,\"chunks\":1}",
                    List.of(removed));
            h.ledger.commitShrinkAtomically(new ShrinkCommit(operationId, land, world,
                    OwnerRef.server(), 0L, List.of(payloadChunk), 0L, audit))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(LedgerState.DOMAIN_COMMITTED.name(), h.ledgerRows().get(0).state());

            List<RecoveryResult> results = h.scan(3);
            assertEquals(1, results.size());
            assertEquals(LedgerState.COMPENSATED.name(), results.get(0).resultingState());
            assertEquals(0, h.economy.refundCalls.get(),
                    "zero-amount recovery must settle without calling Economy");
            assertTrue(h.economy.refunds.isEmpty());
            assertEquals(LedgerState.COMPENSATED.name(), h.ledgerRows().get(0).state());
            assertEquals(ShrinkSaga.ZERO_VALUE_TRANSACTION_REF,
                    h.ledgerRows().get(0).economyTransactionRef());
            assertEquals(1, h.rebuildCalls.get(), "a settled shrink must republish the runtime");
            assertTrue(h.registryStore.snapshot().findLandId(world, 1, 0) == null);
            assertEquals(land, h.registryStore.snapshot().findLandId(world, 0, 0));

            // A second restart is idempotent and still touches no Economy.
            List<RecoveryResult> repeat = h.scan(3);
            assertEquals(1, repeat.size());
            assertEquals(LedgerState.COMPENSATED.name(), repeat.get(0).resultingState());
            assertEquals(0, h.economy.refundCalls.get());
            assertEquals(1, h.rebuildCalls.get());
        }
    }

    @Test
    void legacyParkedServerZeroSettlesWithoutEconomy() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            h.insertLand(land, OwnerRef.server(), world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 0L);
            h.rebuild();

            // A zero row parked by the old code still waits in
            // COMPENSATION_PENDING; recovery must settle it without Economy.
            UUID operationId = UUID.randomUUID();
            ChunkKey removed = chunk(world, 1, 0);
            OperationPayload.Chunk payloadChunk =
                    new OperationPayload.Chunk(removed, 64, UUID.randomUUID(), 0L);
            String displayName = "Home " + land.value().toString().substring(0, 8);
            OperationPayload payload = OperationPayload.shrink(operationId, actor, world, land,
                    List.of(payloadChunk), 0L, ShrinkSaga.SERVER_LAND_PROVIDER_ID, NOW,
                    displayName, ShrinkSaga.REFUND_NUMERATOR, ShrinkSaga.REFUND_DENOMINATOR);
            h.ledger.create(payload).toCompletableFuture().get(10, TimeUnit.SECONDS);
            AuditEntry audit = new AuditEntry(0, NOW, actor, "CHUNK_REMOVE", land, world,
                    removed.pack(), OperationPayload.CURRENT_SCHEMA_VERSION, null,
                    payload.toJson(),
                    "{\"refundMinorUnits\":0,\"costBasisMinorUnits\":0,\"chunks\":1}",
                    List.of(removed));
            h.ledger.commitShrinkAtomically(new ShrinkCommit(operationId, land, world,
                    OwnerRef.server(), 0L, List.of(payloadChunk), 0L, audit))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            h.ledger.parkForRefundCompensation(operationId, "shrink:" + operationId, NOW)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(LedgerState.COMPENSATION_PENDING.name(), h.ledgerRows().get(0).state());

            List<RecoveryResult> results = h.scan(3);
            assertEquals(1, results.size());
            assertEquals(LedgerState.COMPENSATED.name(), results.get(0).resultingState());
            assertEquals(0, h.economy.refundCalls.get(),
                    "a parked zero row must settle without calling Economy");
            assertEquals(ShrinkSaga.ZERO_VALUE_TRANSACTION_REF,
                    h.ledgerRows().get(0).economyTransactionRef());
            assertEquals(1, h.rebuildCalls.get());
        }
    }

    @Test
    void playerShrinkStillUsesEconomyCompensation() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ShrinkOutcome outcome = h.saga.shrink(h.requestFor(session, owner))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ShrinkOutcome.Status.SUCCESS, outcome.status());
            assertEquals(50L, outcome.refundMinorUnits());
            assertTrue(h.economy.providerIdCalls.get() > 0, "player shrink still records provider identity");
            assertTrue(h.economy.isAvailableCalls.get() > 0, "player shrink still gates on availability");
            assertEquals(1, h.economy.refundCalls.get(), "player shrink still deposits once");
            assertEquals(LedgerState.COMPENSATED.name(), h.ledgerRows().get(0).state());
        }
    }
}

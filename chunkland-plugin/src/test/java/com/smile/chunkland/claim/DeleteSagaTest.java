package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.ChunkRepository;
import com.smile.chunkland.persistence.LandRepository;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.LedgerState;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production {@code /land delete} flow: full durable-basis refund, the atomic
 * {@code LAND_DELETE} commit, publish-before-refund ordering, and every
 * fail-closed rejection with zero side effects.
 */
class DeleteSagaTest {

    @TempDir java.nio.file.Path temp;

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    static final class FakeEconomy implements ClaimEconomy {
        final List<LedgerEntry> refunds = Collections.synchronizedList(new ArrayList<>());
        volatile RefundOutcome refundOutcome = RefundOutcome.REFUNDED;
        volatile boolean available = true;

        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request,
                com.smile.chunkland.api.money.Money price) {
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
            return available;
        }
    }

    static final class RecordingViz implements SelectionVisualizationTaskController {
        final List<UUID> stopped = new CopyOnWriteArrayList<>();

        @Override
        public void stop(UUID playerId) {
            stopped.add(playerId);
        }
    }

    /** Land repository whose reads fail, so the runtime publish fails. */
    static final class FailingLandRepository implements LandRepository {
        private <T> CompletionStage<T> fail() {
            return CompletableFuture.failedFuture(new IllegalStateException("storage down"));
        }

        @Override
        public CompletionStage<Void> save(LandSnapshot land) {
            return fail();
        }

        @Override
        public CompletionStage<Optional<LandSnapshot>> findById(LandId id) {
            return fail();
        }

        @Override
        public CompletionStage<List<LandSnapshot>> findByOwner(OwnerRef owner) {
            return fail();
        }

        @Override
        public CompletionStage<List<LandSnapshot>> findAll() {
            return fail();
        }

        @Override
        public CompletionStage<Void> delete(LandId id) {
            return fail();
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
        final SqliteSubLandRepository sublands;
        final SqliteAuditRepository audits;
        final OwnerQuotaService quota;
        final LogicalReservationRegistry reservations = new LogicalReservationRegistry();
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        RuntimeRegistryRebuilder rebuilder;
        final SelectionSessionManager selections;
        final RecordingViz viz = new RecordingViz();
        final SnapshotDeleteValidator validator;
        DeleteSaga saga;

        Harness(int maxLands, int maxChunks) {
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = new OperationLedger(store);
            lands = new SqliteLandRepository(store);
            chunks = new SqliteChunkRepository(store);
            sublands = new SqliteSubLandRepository(store);
            audits = new SqliteAuditRepository(store);
            quota = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), new LimitSettings(maxLands, maxChunks, 128, 16), 0L, Map.of())));
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
                    viz,
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

        void withFailingPublish() {
            rebuilder = new RuntimeRegistryRebuilder(new FailingLandRepository(), registryStore);
            saga = new DeleteSaga(validator, chunks, reservations, ledger,
                    economy, rebuilder, quota, selections, CLOCK, Runnable::run, 3);
        }

        /** Insert a land row plus its chunk rows through the public repositories. */
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

        /** Session targeting the land, so delete cleanup can be observed. */
        void selectTarget(UUID actor, UUID world, LandId target) {
            SelectionSession initial = SelectionSession.initial(
                    actor, world, SelectionMode.CREATE_LAND,
                    Optional.of(target), Optional.empty(),
                    Optional.of(new SelectionPoint(world, 0, 64, 0)),
                    Optional.of(new SelectionPoint(world, 16, 64, 16)),
                    0, NOW);
            selections.start(initial);
        }

        DeleteRequest requestFor(OwnerRef owner, UUID actor, UUID world, LandId target,
                long structureRevision) {
            return new DeleteRequest(owner, actor, world, target, structureRevision);
        }

        DeleteOutcome run(DeleteRequest request) throws Exception {
            return saga.delete(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        boolean landExists(LandId landId) throws Exception {
            return lands.findById(landId).toCompletableFuture().get(10, TimeUnit.SECONDS).isPresent();
        }

        List<String> auditActions(LandId landId) throws Exception {
            List<String> actions = new ArrayList<>();
            for (AuditEntry entry : audits.findByLand(landId, 100, 0).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)) {
                actions.add(entry.action());
            }
            return List.copyOf(actions);
        }

        List<LedgerEntry> ledgerRows() throws Exception {
            return ledger.findAll().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static ChunkKey chunk(UUID world, int x, int z) {
        return new ChunkKey(world, x, z);
    }

    // ------------------------------------------------------------------
    // 1) Player delete refunds the full durable basis
    // ------------------------------------------------------------------

    @Test
    void playerDeleteRefundsFullBasisAndPublishesWilderness() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 7, 100L);
            h.rebuild();
            h.quota.setLandCommitted(owner, 1);
            h.quota.setChunkCommitted(owner, 2);
            h.selectTarget(actor, world, land);

            DeleteOutcome outcome = h.run(h.requestFor(owner, actor, world, land, 7));

            assertEquals(DeleteOutcome.Status.SUCCESS, outcome.status());
            assertEquals(land, outcome.landId());
            // Full refund: the durable basis of every chunk, no shrink ratio.
            assertEquals(200L, outcome.refundMinorUnits());
            assertEquals(1, h.economy.refunds.size());
            assertEquals(200L, h.economy.refunds.get(0).priceMinorUnits());
            // Durable commit: land gone, LAND_DELETE audited, history retained.
            assertTrue(!h.landExists(land), "deleted land must be gone durably");
            assertEquals(List.of("LAND_DELETE"), h.auditActions(land));
            assertEquals(1, h.ledgerRows().size());
            assertEquals(LedgerState.COMPENSATED.name(), h.ledgerRows().get(0).state());
            assertEquals("DELETE", h.ledgerRows().get(0).operationType());
            assertEquals(land, h.ledgerRows().get(0).targetLandId());
            assertEquals(200L, h.ledgerRows().get(0).priceMinorUnits());
            OperationPayload payload = OperationPayload.fromJson(h.ledgerRows().get(0).payloadJson());
            assertEquals("DELETE", payload.operationType());
            assertEquals(200L, payload.priceMinorUnits());
            // Publish: every chunk reads as wilderness immediately.
            assertTrue(h.registryStore.snapshot().findLandId(world, 0, 0) == null,
                    "deleted chunks must read as wilderness immediately");
            assertTrue(h.registryStore.snapshot().findLandId(world, 1, 0) == null,
                    "deleted chunks must read as wilderness immediately");
            // Quota released: the whole land freed its one land slot and every
            // committed chunk under it; reservations released.
            assertEquals(0, h.quota.landCommitted(owner),
                    "the deleted land slot must be released, not leaked");
            assertEquals(0, h.quota.chunkCommitted(owner));
            assertEquals(0, h.reservations.size());
            // Selection session cleared and visualization stopped.
            assertTrue(h.selections.sessionFor(actor).isEmpty(),
                    "sessions targeting the deleted land must be cleared");
            assertEquals(List.of(actor), List.copyOf(h.viz.stopped));
        }
    }

    @Test
    void serverDeleteMovesNoMoney() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID steward = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            h.insertLand(land, OwnerRef.server(), world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();

            DeleteOutcome outcome = h.run(h.requestFor(OwnerRef.server(), steward, world, land, 0));

            assertEquals(DeleteOutcome.Status.SUCCESS, outcome.status());
            assertEquals(0L, outcome.refundMinorUnits());
            assertTrue(h.economy.refunds.isEmpty(), "server delete must never touch Economy");
            assertTrue(!h.landExists(land), "server land must be gone durably");
            assertEquals(List.of("LAND_DELETE"), h.auditActions(land));
            assertEquals(1, h.ledgerRows().size());
            assertEquals("server-land", h.ledgerRows().get(0).economyProviderId());
            assertEquals(LedgerState.COMPENSATED.name(), h.ledgerRows().get(0).state());
            assertTrue(h.registryStore.snapshot().findLandId(world, 0, 0) == null);
        }
    }

    // ------------------------------------------------------------------
    // 2) Failure ordering: no rollback, no premature refund
    // ------------------------------------------------------------------

    @Test
    void refundFailureQuarantinesForReconciliationWithoutRollback() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();
            h.economy.refundOutcome = RefundOutcome.FAILED;

            DeleteOutcome outcome = h.run(h.requestFor(owner, actor, world, land, 0));

            // The deposit ran exactly once behind a durably parked intent;
            // the unconfirmed outcome quarantines instead of resending,
            // because a resend could double-credit.
            assertEquals(DeleteOutcome.Status.NEEDS_RECONCILIATION, outcome.status());
            assertEquals(200L, outcome.refundMinorUnits());
            assertEquals(1, h.economy.refunds.size(), "exactly one deposit attempt");
            // The committed delete is never rolled back for the refund.
            assertTrue(!h.landExists(land), "compensation must never resurrect the land");
            assertEquals(List.of("LAND_DELETE"), h.auditActions(land));
            List<LedgerEntry> rows = h.ledgerRows();
            assertEquals(1, rows.size(), "exactly one DELETE ledger row");
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(), rows.get(0).state());
            assertEquals("delete:" + rows.get(0).operationId(), rows.get(0).economyTransactionRef(),
                    "the quarantined row must carry the delete idempotency reference");
            assertEquals(0, rows.get(0).compensationAttempts(),
                    "no automatic retry is scheduled after a parked attempt");
            // The runtime already published the deletion before the refund ran.
            assertTrue(h.registryStore.snapshot().findLandId(world, 0, 0) == null);
        }
    }

    @Test
    void publishFailureIsDegradedWithoutRefund() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            h.withFailingPublish();

            DeleteOutcome outcome = h.run(h.requestFor(owner, actor, world, land, 0));

            assertEquals(DeleteOutcome.Status.DEGRADED, outcome.status());
            assertEquals("delete.publish_failed", outcome.diagnosticKey());
            assertTrue(h.economy.refunds.isEmpty(),
                    "a publish failure must not trigger any refund");
            assertTrue(!h.landExists(land), "the delete stays durably committed");
            assertEquals(List.of("LAND_DELETE"), h.auditActions(land));
            assertEquals(1, h.ledgerRows().size());
            assertEquals(LedgerState.DOMAIN_COMMITTED.name(), h.ledgerRows().get(0).state(),
                    "recovery completes the publish and the refund from DOMAIN_COMMITTED");
        }
    }

    // ------------------------------------------------------------------
    // 3) Rejections fail closed with zero side effects
    // ------------------------------------------------------------------

    private void assertRejectedZeroSideEffects(Harness h, DeleteRequest request, String key)
            throws Exception {
        DeleteOutcome outcome = h.run(request);
        assertEquals(DeleteOutcome.Status.REJECTED, outcome.status(), "expected rejection " + key);
        assertEquals(key, outcome.diagnosticKey());
        assertTrue(h.ledgerRows().isEmpty(), "rejection must leave no ledger row");
        assertTrue(h.economy.refunds.isEmpty(), "rejection must never deposit");
        assertEquals(0, h.reservations.size(), "rejection must leave no reservation");
    }

    @Test
    void staleStructureRevisionIsRejected() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 3, 100L);
            h.rebuild();

            assertRejectedZeroSideEffects(h, h.requestFor(owner, actor, world, land, 2),
                    "structure.stale");
            assertTrue(h.landExists(land), "rejection must not touch the land");
        }
    }

    @Test
    void unknownLandIsRejected() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            h.rebuild();

            // Nothing is published for the ghost land, so its live revision
            // is unresolvable and the request fails closed before any
            // target lookup runs.
            assertRejectedZeroSideEffects(h,
                    h.requestFor(OwnerRef.player(actor), actor, world, land, 0),
                    "structure.unavailable");
        }
    }

    @Test
    void ownerMismatchIsRejected() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            h.insertLand(land, OwnerRef.player(actor), world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();

            assertRejectedZeroSideEffects(h,
                    h.requestFor(OwnerRef.player(UUID.randomUUID()), UUID.randomUUID(), world, land, 0),
                    "delete.owner_mismatch");
            assertTrue(h.landExists(land));
        }
    }

    @Test
    void economyUnavailableIsRejected() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            h.economy.available = false;

            assertRejectedZeroSideEffects(h, h.requestFor(owner, actor, world, land, 0),
                    "delete.economy_unavailable");
            assertTrue(h.landExists(land));
        }
    }

    // ------------------------------------------------------------------
    // 4) Idempotency: no second row, no second refund
    // ------------------------------------------------------------------

    @Test
    void retryAfterDeleteFailsClosedWithOneRowAndOneRefund() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            h.quota.setLandCommitted(owner, 1);
            h.quota.setChunkCommitted(owner, 1);

            DeleteOutcome first = h.run(h.requestFor(owner, actor, world, land, 0));
            assertEquals(DeleteOutcome.Status.SUCCESS, first.status());

            // The land is gone from the published index, so the live
            // structures source no longer resolves it: the retry rejects.
            DeleteOutcome second = h.run(h.requestFor(owner, actor, world, land, 0));
            assertEquals(DeleteOutcome.Status.REJECTED, second.status());
            assertEquals(1, h.ledgerRows().size(), "a retry must not write a second ledger row");
            assertEquals(1, h.economy.refunds.size(), "a retry must not refund twice");
            assertEquals(List.of("LAND_DELETE"), h.auditActions(land));
            // The rejected retry must not release the quota a second time, so
            // both counters stay at zero rather than going negative.
            assertEquals(0, h.quota.landCommitted(owner), "quota must not go negative on retry");
            assertEquals(0, h.quota.chunkCommitted(owner), "quota must not go negative on retry");
        }
    }

    @Test
    void commitRaceMapsToStaleOrUnknownRejections() {
        // A land deleted between validation and commit (or a chunk set that
        // moved under the request) must reject, never partially commit.
        DeleteOutcome unknown = DeleteSaga.mapCommitFailure(
                new java.sql.SQLException("unknown deleted land 00000000-0000-0000-0000-000000000000"));
        assertEquals(DeleteOutcome.Status.REJECTED, unknown.status());
        assertEquals("delete.unknown_land", unknown.diagnosticKey());

        DeleteOutcome stale = DeleteSaga.mapCommitFailure(
                new java.sql.SQLException("delete chunk set changed since validation"));
        assertEquals(DeleteOutcome.Status.REJECTED, stale.status());
        assertEquals("delete.stale", stale.diagnosticKey());

        DeleteOutcome revision = DeleteSaga.mapCommitFailure(
                new java.sql.SQLException("stale land structure revision: expected 4 but moved"));
        assertEquals(DeleteOutcome.Status.REJECTED, revision.status());
        assertEquals("structure.stale", revision.diagnosticKey());
    }

    @Test
    void reservationConflictIsRejectedWithZeroSideEffects() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();
            // A concurrent mutation holding the same chunk reservations wins;
            // the delete must reject without touching durable state.
            java.util.Set<String> keys = java.util.Set.of(
                    world + ":0:0", world + ":1:0");
            UUID holder = UUID.randomUUID();
            assertTrue(h.reservations.tryAcquire(keys, holder));
            try {
                DeleteOutcome outcome = h.run(h.requestFor(owner, actor, world, land, 0));
                assertEquals(DeleteOutcome.Status.REJECTED, outcome.status());
                assertEquals("delete.reservation_conflict", outcome.diagnosticKey());
                assertTrue(h.ledgerRows().isEmpty(), "conflict must leave no ledger row");
                assertTrue(h.economy.refunds.isEmpty(), "conflict must never deposit");
                assertTrue(h.landExists(land), "conflict must not touch the land");
            } finally {
                h.reservations.release(keys, holder);
            }
        }
    }

    @Test
    void missingCostBasisFailsClosedWithoutLedgerRow() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            ChunkKey key = chunk(world, 0, 0);
            h.insertLand(land, owner, world, Set.of(key), 0, 100L);
            h.rebuild();
            // Legacy row without a durable basis: the saga must fail closed
            // before any ledger row exists, never guessing a refund amount.
            ChunkRepository basisLess = new ChunkRepository() {
                @Override
                public CompletionStage<Void> addChunk(LandId landId, ChunkKey chunk,
                        int storedMinY, UUID claimLotId, long costBasisMinor) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletionStage<List<ChunkKey>> listByLand(LandId landId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletionStage<Map<ChunkKey, Integer>> listDepthsByLand(LandId landId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletionStage<Optional<LandId>> findLandByChunk(ChunkKey chunk) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletionStage<Map<ChunkKey, ChunkFact>> factsByLand(LandId landId) {
                    return CompletableFuture.completedFuture(
                            Map.of(key, new ChunkFact(null, 64, UUID.randomUUID())));
                }

                @Override
                public CompletionStage<Void> removeChunk(ChunkKey chunk) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletionStage<Void> deleteByLand(LandId landId) {
                    throw new UnsupportedOperationException();
                }
            };
            DeleteSaga saga = new DeleteSaga(h.validator, basisLess, h.reservations, h.ledger,
                    h.economy, h.rebuilder, h.quota, h.selections, CLOCK, Runnable::run, 3);
            DeleteOutcome outcome = saga.delete(h.requestFor(owner, actor, world, land, 0))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(DeleteOutcome.Status.REJECTED, outcome.status());
            assertEquals("delete.missing_basis", outcome.diagnosticKey());
            assertTrue(h.ledgerRows().isEmpty(), "a basis failure must leave no ledger row");
            assertTrue(h.economy.refunds.isEmpty(), "a basis failure must never deposit");
            assertTrue(h.landExists(land), "a basis failure must not touch the land");
        }
    }
}

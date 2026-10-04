package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.money.CostBasisCalculator;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.command.ShrinkCommandHandler;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.AtomicCommitStep;
import com.smile.chunkland.persistence.AuditRepository;
import com.smile.chunkland.persistence.ChunkRepository;
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
import com.smile.chunkland.selection.SelectionUpdate;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production {@code /land shrink} and {@code /land unclaim} flow:
 * validation, durable cost-basis refund, the atomic {@code CHUNK_REMOVE}
 * commit and the runtime publish — plus every fail-closed rejection with
 * zero side effects.
 */
class ShrinkSagaTest {

    @TempDir java.nio.file.Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
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
        public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
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
        final RuntimeRegistryRebuilder rebuilder;
        final SelectionSessionManager selections;
        final SnapshotShrinkValidator validator;
        final ShrinkSaga saga;

        Harness(int maxLands, int maxChunks) {
            this(maxLands, maxChunks, null);
        }

        Harness(int maxLands, int maxChunks,
                java.util.function.Consumer<AtomicCommitStep> failureInjector) {
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = failureInjector == null
                    ? new OperationLedger(store)
                    : new OperationLedger(store, failureInjector);
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

        void insertSubLand(SubLandSnapshot sub) throws Exception {
            sublands.save(sub).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        void rebuild() throws Exception {
            rebuilder.rebuild().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        /** Targeted shrink session: the delta plus the full token triple. */
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

        long structureRevision(LandId landId) throws Exception {
            return lands.findById(landId).toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .orElseThrow(() -> new IllegalStateException("unknown land " + landId))
                    .structureRevision();
        }

        boolean landExists(LandId landId) throws Exception {
            return lands.findById(landId).toCompletableFuture().get(10, TimeUnit.SECONDS).isPresent();
        }

        Map<ChunkKey, ChunkRepository.ChunkFact> facts(LandId landId) throws Exception {
            return chunks.factsByLand(landId).toCompletableFuture().get(10, TimeUnit.SECONDS);
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
    // 1) Valid removal succeeds end to end
    // ------------------------------------------------------------------

    @Test
    void validRemovalCommitsRefundsAuditsAndPublishes() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();
            h.quota.setChunkCommitted(owner, 2);

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ShrinkOutcome outcome = h.run(h.requestFor(session, owner));

            assertEquals(ShrinkOutcome.Status.SUCCESS, outcome.status());
            assertEquals(land, outcome.landId());
            // Refund uses the durable basis of the removed chunk only: 100 * 1/2.
            long expectedRefund = CostBasisCalculator.refund(new Money(100L, EMC), 1, 2).minorUnits();
            assertEquals(expectedRefund, outcome.refundMinorUnits());
            assertEquals(1, h.economy.refunds.size());
            assertEquals(expectedRefund, h.economy.refunds.get(0).priceMinorUnits());
            // Durable commit: delta gone, revision bumped, CHUNK_REMOVE audited.
            assertEquals(1L, h.structureRevision(land));
            assertEquals(List.of("CHUNK_REMOVE"), h.auditActions(land));
            assertEquals(1, h.ledgerRows().size());
            assertEquals(LedgerState.COMPENSATED.name(), h.ledgerRows().get(0).state());
            assertEquals("SHRINK", h.ledgerRows().get(0).operationType());
            assertEquals(land, h.ledgerRows().get(0).targetLandId());
            // Publish: removed chunk is wilderness, the rest stays protected.
            assertTrue(h.registryStore.snapshot().findLandId(world, 1, 0) == null,
                    "removed chunk must read as wilderness immediately");
            assertEquals(land, h.registryStore.snapshot().findLandId(world, 0, 0));
            // Quota released, reservations released, remaining basis untouched.
            assertEquals(1, h.quota.chunkCommitted(owner));
            assertEquals(0, h.reservations.size());
            Map<ChunkKey, ChunkRepository.ChunkFact> facts = h.facts(land);
            assertEquals(Set.of(chunk(world, 0, 0)), facts.keySet());
            assertEquals(100L, facts.get(chunk(world, 0, 0)).costBasisMinorUnits());
        }
    }

    @Test
    void centralHoleIsLegal() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            Set<ChunkKey> ring = new HashSet<>();
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    ring.add(chunk(world, x, z));
                }
            }
            h.insertLand(land, owner, world, Set.copyOf(ring), 0, 100L);
            h.rebuild();

            // Removing the centre chunk leaves a hole, which is legal and no split.
            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 1)));
            ShrinkOutcome outcome = h.run(h.requestFor(session, owner));

            assertEquals(ShrinkOutcome.Status.SUCCESS, outcome.status());
            assertEquals(1L, h.structureRevision(land));
            assertEquals(8, h.facts(land).size());
            assertTrue(h.registryStore.snapshot().findLandId(world, 1, 1) == null);
            assertEquals(land, h.registryStore.snapshot().findLandId(world, 0, 0));
        }
    }

    // ------------------------------------------------------------------
    // 2) Rejections fail closed with zero side effects
    // ------------------------------------------------------------------

    private void assertRejectedZeroSideEffects(Harness h, ShrinkRequest request, String key) throws Exception {
        ShrinkOutcome outcome = h.run(request);
        assertEquals(ShrinkOutcome.Status.REJECTED, outcome.status(), "expected rejection " + key);
        assertEquals(key, outcome.diagnosticKey());
        assertTrue(h.ledgerRows().isEmpty(), "rejection must leave no ledger row");
        assertTrue(h.economy.refunds.isEmpty(), "rejection must never deposit");
        assertEquals(0, h.reservations.size(), "rejection must leave no reservation");
    }

    @Test
    void splitRemovalIsRejected() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0), chunk(world, 2, 0)), 0, 100L);
            h.rebuild();

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            assertRejectedZeroSideEffects(h, h.requestFor(session, owner), "shrink.split");
            assertEquals(0L, h.structureRevision(land));
            assertEquals(3, h.facts(land).size());
        }
    }

    @Test
    void removingLastChunkRoutesToDeleteWithZeroMutation() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 0, 0)));
            assertRejectedZeroSideEffects(h, h.requestFor(session, owner), "shrink.delete_required");
            // No empty land row is left behind: the land still exists untouched.
            assertTrue(h.landExists(land), "zero-chunk removal must not write an empty land");
            assertEquals(0L, h.structureRevision(land));
            assertEquals(1, h.facts(land).size());
        }
    }

    @Test
    void foreignChunkIsRejected() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            LandId other = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.insertLand(other, owner, world, Set.of(chunk(world, 5, 5)), 0, 100L);
            h.rebuild();

            SelectionSession session = h.selectDelta(actor, world, land,
                    Set.of(chunk(world, 1, 0), chunk(world, 5, 5)));
            assertRejectedZeroSideEffects(h, h.requestFor(session, owner), "shrink.foreign_chunk");
        }
    }

    @Test
    void sublandOverlapBlocksWithoutOrphans() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            // Precise subland covering exactly chunk (1, 0): blocks x 16..31, z 0..15.
            SubLandSnapshot sub = new SubLandSnapshot(new SubLandId(UUID.randomUUID()), land, "shop",
                    new Cuboid(16, 64, 0, 31, 64, 15), world);
            h.insertSubLand(sub);
            h.rebuild();

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            assertRejectedZeroSideEffects(h, h.requestFor(session, owner), "shrink.subland_overlap");
            // The subland row survives and no orphan is produced.
            assertEquals(1, h.sublands.findByLand(land).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).size());
            assertEquals(2, h.facts(land).size());
        }
    }

    @Test
    void staleTokensUnknownLandAndOwnerMismatchReject() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ShrinkRequest fresh = h.requestFor(session, owner);
            // Stale selection revision.
            assertRejectedZeroSideEffects(h, new ShrinkRequest(owner, actor, world, land,
                    Set.of(chunk(world, 1, 0)), fresh.selectionRevision() + 99,
                    fresh.sessionGeneration(), fresh.structureRevision()), "shrink.stale");
            // Stale session generation.
            assertRejectedZeroSideEffects(h, new ShrinkRequest(owner, actor, world, land,
                    Set.of(chunk(world, 1, 0)), fresh.selectionRevision(),
                    fresh.sessionGeneration() + 99, fresh.structureRevision()), "shrink.stale");
            // Stale structure revision.
            ShrinkOutcome staleStructure = h.run(new ShrinkRequest(owner, actor, world, land,
                    Set.of(chunk(world, 1, 0)), fresh.selectionRevision(),
                    fresh.sessionGeneration(), fresh.structureRevision() + 99));
            assertEquals(ShrinkOutcome.Status.REJECTED, staleStructure.status());
            assertEquals("structure.stale", staleStructure.diagnosticKey());
            assertTrue(h.ledgerRows().isEmpty());
            // Unknown land.
            assertRejectedZeroSideEffects(h, new ShrinkRequest(owner, actor, world,
                    new LandId(UUID.randomUUID()), Set.of(chunk(world, 1, 0)),
                    fresh.selectionRevision(), fresh.sessionGeneration(), 0L),
                    "shrink.unknown_land");
            // Owner mismatch.
            OwnerRef stranger = OwnerRef.player(UUID.randomUUID());
            assertRejectedZeroSideEffects(h, new ShrinkRequest(stranger, actor, world, land,
                    Set.of(chunk(world, 1, 0)), fresh.selectionRevision(),
                    fresh.sessionGeneration(), fresh.structureRevision()), "shrink.owner_mismatch");
        }
    }

    // ------------------------------------------------------------------
    // 3) World-disabled shrink, original-basis refund
    // ------------------------------------------------------------------

    @Test
    void worldDisabledStillShrinksWithRefund() throws Exception {
        // The shrink validator takes no WorldClaimPolicy: even a deny-all
        // world still allows shrink with a refund. Constructing the deny-all
        // policy here documents the contrast with claim/expand.
        WorldClaimPolicy disabled = WorldClaimPolicy.denyAll("world.claim_disabled");
        assertNotNull(disabled);
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ShrinkOutcome outcome = h.run(h.requestFor(session, owner));
            assertEquals(ShrinkOutcome.Status.SUCCESS, outcome.status());
        }
    }

    @Test
    void refundUsesOriginalBasisNotCurrentTier() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            // Durable basis 1000 per chunk: the refund must be half of that
            // even though no pricing table is consulted anywhere in shrink.
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 1000L);
            h.rebuild();

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ShrinkOutcome outcome = h.run(h.requestFor(session, owner));
            assertEquals(ShrinkOutcome.Status.SUCCESS, outcome.status());
            long expected = CostBasisCalculator.refund(new Money(1000L, EMC), 1, 2).minorUnits();
            assertEquals(500L, expected, "test premise: half of 1000");
            assertEquals(expected, outcome.refundMinorUnits());
            assertEquals(expected, h.economy.refunds.get(0).priceMinorUnits());
        }
    }

    @Test
    void serverLandShrinksWithZeroRefund() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.server();
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 0L);
            h.rebuild();
            h.economy.available = false;

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ShrinkOutcome outcome = h.run(h.requestFor(session, owner));
            assertEquals(ShrinkOutcome.Status.SUCCESS, outcome.status());
            assertEquals(0L, outcome.refundMinorUnits());
            assertTrue(h.economy.refunds.isEmpty(), "server land must never touch Economy");
        }
    }

    // ------------------------------------------------------------------
    // 4) Failures: domain commit, deposit, compensation
    // ------------------------------------------------------------------

    @Test
    void domainCommitFailureNeverDeposits() throws Exception {
        java.util.function.Consumer<AtomicCommitStep> injector = step -> {
            if (step == AtomicCommitStep.AFTER_CHUNKS) {
                throw new RuntimeException("injected commit failure");
            }
        };
        try (Harness h = new Harness(10, 100, injector)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ShrinkOutcome outcome = h.run(h.requestFor(session, owner));
            assertEquals(ShrinkOutcome.Status.FAILED, outcome.status());
            assertEquals("shrink.commit_failed", outcome.diagnosticKey());
            assertTrue(h.economy.refunds.isEmpty(), "a rolled-back commit must never deposit");
            // The domain is untouched: both chunks remain.
            assertEquals(2, h.facts(land).size());
            assertEquals(0L, h.structureRevision(land));
        }
    }

    @Test
    void depositFailureQuarantinesForReconciliationWithoutRollback() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();
            h.economy.refundOutcome = RefundOutcome.FAILED;

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ShrinkOutcome outcome = h.run(h.requestFor(session, owner));
            // The deposit ran exactly once behind a durably parked intent;
            // the unconfirmed outcome quarantines instead of resending,
            // because a resend could double-credit.
            assertEquals(ShrinkOutcome.Status.NEEDS_RECONCILIATION, outcome.status());
            assertEquals(1, h.economy.refunds.size(), "exactly one deposit attempt");
            // The committed removal is never rolled back for the refund.
            assertEquals(1, h.facts(land).size());
            assertEquals(1L, h.structureRevision(land));
            assertEquals(List.of("CHUNK_REMOVE"), h.auditActions(land));
            assertEquals(1, h.ledgerRows().size());
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(), h.ledgerRows().get(0).state());
            assertEquals(0, h.ledgerRows().get(0).compensationAttempts(),
                    "no automatic retry is scheduled after a parked attempt");
            // The runtime publish waits for a confirmed deposit (same as the
            // refund saga): an unconfirmed deposit quarantines for operator
            // reconciliation instead of publishing or resending. The durable
            // domain above is already authoritative.
        }
    }

    @Test
    void economyUnavailableRejectsBeforeAnyMutation() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();
            h.economy.available = false;

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            assertRejectedZeroSideEffects(h, h.requestFor(session, owner), "shrink.economy_unavailable");
            assertEquals(2, h.facts(land).size());
        }
    }

    // ------------------------------------------------------------------
    // 5) Serialization with expansion: deterministic barrier
    // ------------------------------------------------------------------

    @Test
    void expandThenShrinkOnStaleRevisionIsRejected() throws Exception {
        // Sequential barrier: an expansion that wins the revision forces a
        // shrink built on the old revision to fail closed with no lost update.
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();

            SelectionSession shrinkSession = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ShrinkRequest staleShrink = h.requestFor(shrinkSession, owner);

            // A concurrent expansion wins first (simulated by bumping the
            // durable revision behind the shrink's back).
            h.lands.findById(land).toCompletableFuture().get(10, TimeUnit.SECONDS);
            forceBumpStructure(h, land);

            ShrinkOutcome outcome = h.run(staleShrink);
            assertEquals(ShrinkOutcome.Status.REJECTED, outcome.status());
            assertEquals("structure.stale", outcome.diagnosticKey());
            assertTrue(h.ledgerRows().isEmpty(), "stale shrink must leave no ledger row");
            assertTrue(h.economy.refunds.isEmpty());
        }
    }

    @Test
    void concurrentShrinksSerializeToOneWinner() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0), chunk(world, 2, 0)), 0, 100L);
            h.rebuild();

            // Two actors race to remove different edge chunks from the same
            // revision. Both deltas are individually valid; exactly one may win.
            UUID actorB = UUID.randomUUID();
            SelectionSession sessionA = h.selectDelta(actor, world, land, Set.of(chunk(world, 0, 0)));
            SelectionSession sessionB = h.selectDelta(actorB, world, land, Set.of(chunk(world, 2, 0)));
            // Actor B must share the owner key so ownership agrees.
            ShrinkRequest requestA = h.requestFor(sessionA, owner);
            ShrinkRequest requestB = new ShrinkRequest(owner, actorB, world, land,
                    Set.of(chunk(world, 2, 0)), sessionB.selectionRevision(),
                    sessionB.sessionGeneration(), sessionB.baseStructureRevision());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch gate = new CountDownLatch(1);
                CompletableFuture<ShrinkOutcome> futureA = CompletableFuture.supplyAsync(() -> {
                    try {
                        gate.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    try {
                        return h.saga.shrink(requestA).toCompletableFuture().get(15, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }, pool);
                CompletableFuture<ShrinkOutcome> futureB = CompletableFuture.supplyAsync(() -> {
                    try {
                        gate.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    try {
                        return h.saga.shrink(requestB).toCompletableFuture().get(15, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }, pool);
                gate.countDown();
                ShrinkOutcome outcomeA = futureA.get(20, TimeUnit.SECONDS);
                ShrinkOutcome outcomeB = futureB.get(20, TimeUnit.SECONDS);

                long successes = List.of(outcomeA, outcomeB).stream()
                        .filter(outcome -> outcome.status() == ShrinkOutcome.Status.SUCCESS)
                        .count();
                assertEquals(1L, successes, "exactly one racer must win, got " + outcomeA + " / " + outcomeB);
                // No lost update: two chunks remain, revision bumped exactly once.
                assertEquals(2, h.facts(land).size());
                assertEquals(1L, h.structureRevision(land));
                // No duplicate refund: exactly one deposit for the single winner.
                assertEquals(1, h.economy.refunds.size());
                assertEquals(1, h.auditActions(land).stream()
                        .filter(action -> action.equals("CHUNK_REMOVE")).count());
                assertEquals(0, h.reservations.size());
                h.rebuild();
                assertEquals(2, h.registryStore.snapshot().land(land).chunks().size());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static void forceBumpStructure(Harness h, LandId land) throws Exception {
        // Direct durable bump behind the shrink's back: delete nothing, only
        // move the revision so the shrink's compare-and-set goes stale.
        // Uses the public repository read plus a raw ledger-free update path
        // through a second shrink-shaped write is overkill; instead perform a
        // no-op rename-free revision move via the saga-unaware store handle.
        // The harness store executes raw SQL through the ledger's seams is not
        // public, so emulate the race by publishing a rebuilt snapshot with a
        // bumped revision: reload, replace chunks identically, rebuild.
        LandSnapshot current = h.lands.findById(land).toCompletableFuture()
                .get(10, TimeUnit.SECONDS).orElseThrow();
        LandSnapshot bumped = current.replaceChunks(new HashSet<>(current.chunks()));
        h.lands.save(bumped).toCompletableFuture().get(10, TimeUnit.SECONDS);
        h.rebuild();
    }

    // ------------------------------------------------------------------
    // 6) Alias wiring is covered by ShrinkAliasWiringTest (same package as
    // the plugin entry point, so it can call the package-visible assembly).
    // ------------------------------------------------------------------
}

package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.command.ExpandCommandHandler;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.ReplySink;import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.ChunkRepository;
import com.smile.chunkland.persistence.ExpandCommit;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.LedgerState;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteAuditRepository;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production {@code /land expand} flow: validation, pricing, limits,
 * reservations, ledger, Economy, the atomic {@code CHUNK_ADD} commit and the
 * runtime publish — plus every fail-closed rejection with zero side effects.
 */
class ExpandSagaTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    static final class FakeEconomy implements ClaimEconomy {
        record ChargeCall(UUID operationId, Money price) {
        }

        final List<ChargeCall> charges = Collections.synchronizedList(new ArrayList<>());
        final List<LedgerEntry> refunds = Collections.synchronizedList(new ArrayList<>());
        volatile boolean chargeOk = true;
        volatile String chargeKey = "economy.failed";
        volatile RefundOutcome refundOutcome = RefundOutcome.REFUNDED;

        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
            charges.add(new ChargeCall(operationId, price));
            if (chargeOk) {
                return CompletableFuture.completedFuture(ChargeResult.ok());
            }
            return CompletableFuture.completedFuture(ChargeResult.failed(chargeKey));
        }

        @Override
        public CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
            refunds.add(entry);
            return CompletableFuture.completedFuture(refundOutcome);
        }

        @Override
        public String providerId() {
            return "test-economy";
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

        void awaitReply() throws Exception {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "handler must reply exactly once");
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
        final OwnerQuotaService quota;
        final LogicalReservationRegistry reservations = new LogicalReservationRegistry();
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        final RuntimeRegistryRebuilder rebuilder;
        final SelectionSessionManager selections;
        final PricingTable pricing;
        final ExpandSaga saga;
        final SnapshotExpandValidator validator;
        final WorldClaimPolicy policy;

        Harness(int maxLands, int maxChunks, WorldClaimPolicy policy, int compensationRetries) {
            this.policy = policy == null ? WorldClaimPolicy.allowAll() : policy;
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = new OperationLedger(store);
            lands = new SqliteLandRepository(store);
            chunks = new SqliteChunkRepository(store);
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
            pricing = PricingTable.of(List.of(
                    PricingTier.of(5, new Money(100, EMC)),
                    PricingTier.of(PricingTier.UNBOUNDED, new Money(200, EMC))));
            SelectionStructureRevisionLookup structures = liveStructures;
            validator = new SnapshotExpandValidator(registryStore,
                    actorUuid -> selections.sessionFor(actorUuid)
                            .map(session -> OptionalLong.of(session.selectionRevision()))
                            .orElseGet(OptionalLong::empty),
                    actorUuid -> selections.sessionFor(actorUuid)
                            .map(session -> OptionalLong.of(session.sessionGeneration()))
                            .orElseGet(OptionalLong::empty),
                    chunk -> 64,
                    quota::chunkCommitted,
                    structures::currentRevision,
                    this.policy);
            saga = new ExpandSaga(validator, quota, pricing, reservations, ledger,
                    economy, rebuilder, CLOCK, Runnable::run, compensationRetries);
        }

        Harness(int maxLands, int maxChunks) {
            this(maxLands, maxChunks, null, 3);
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

        /** Targeted expand session: the delta plus the full token triple. */
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

        ExpandRequest requestFor(SelectionSession session, OwnerRef owner) {
            return new ExpandRequest(owner, session.playerId(), session.worldId(),
                    session.targetLandId().orElseThrow(), session.selectedChunks(),
                    session.selectionRevision(), session.sessionGeneration(),
                    session.baseStructureRevision());
        }

        ClaimOutcome run(ExpandRequest request) throws Exception {
            return saga.expand(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        long structureRevision(LandId landId) throws Exception {
            return lands.findById(landId).toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .orElseThrow(() -> new IllegalStateException("unknown land " + landId))
                    .structureRevision();
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

    private static ChunkKey chunk(UUID world, int x, int z) {
        return new ChunkKey(world, x, z);
    }

    // ------------------------------------------------------------------
    // 1) Valid expansion succeeds end to end
    // ------------------------------------------------------------------

    @Test
    void validDeltaCommitsPricedAuditedAndPublished() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            h.quota.setChunkCommitted(owner, 1);

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ClaimOutcome outcome = h.run(h.requestFor(session, owner));

            assertEquals(ClaimOutcome.Status.SUCCESS, outcome.status());
            assertEquals(land, outcome.landId());
            // Pricing uses the global basis (1) plus the delta (1).
            Money expected = h.pricing.priceForClaim(1, 1);
            assertEquals(1, h.economy.charges.size());
            assertEquals(expected, h.economy.charges.get(0).price());
            // Durable commit: union present, revision bumped, CHUNK_ADD audited.
            assertEquals(1L, h.structureRevision(land));
            assertEquals(List.of("CHUNK_ADD"), h.auditActions(land));
            assertEquals(1, h.ledgerRows().size());
            assertEquals(LedgerState.ACTIVE.name(), h.ledgerRows().get(0).state());
            assertEquals("EXPAND", h.ledgerRows().get(0).operationType());
            assertEquals(land, h.ledgerRows().get(0).targetLandId());
            // Publish: the runtime snapshot carries the union.
            assertEquals(land, h.registryStore.snapshot().findLandId(world, 1, 0));
            assertEquals(land, h.registryStore.snapshot().findLandId(world, 0, 0));
            // Quotas committed, reservations released.
            assertEquals(2, h.quota.chunkCommitted(owner));
            assertEquals(0, h.reservations.size());
            // Existing basis untouched, new chunk priced with depth 64.
            Map<ChunkKey, ChunkRepository.ChunkFact> facts = h.facts(land);
            assertEquals(100L, facts.get(chunk(world, 0, 0)).costBasisMinorUnits());
            assertEquals(64, facts.get(chunk(world, 1, 0)).storedMinProtectedY());
            assertEquals(expected.minorUnits(), facts.get(chunk(world, 1, 0)).costBasisMinorUnits());
        }
    }

    // ------------------------------------------------------------------
    // 2) Rejections fail closed with zero side effects
    // ------------------------------------------------------------------

    private void assertRejectedZeroSideEffects(Harness h, ExpandRequest request, String key) throws Exception {
        ClaimOutcome outcome = h.run(request);
        assertEquals(ClaimOutcome.Status.REJECTED, outcome.status(), "expected rejection " + key);
        assertEquals(key, outcome.diagnosticKey());
        assertTrue(h.ledgerRows().isEmpty(), "rejection must leave no ledger row");
        assertTrue(h.economy.charges.isEmpty(), "rejection must never charge");
        assertEquals(0, h.reservations.size(), "rejection must leave no reservation");
    }

    @Test
    void overlapDisconnectedAndForeignCollisionRejectWithoutSideEffects() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            LandId foreign = new LandId(UUID.randomUUID());
            h.insertLand(foreign, OwnerRef.player(UUID.randomUUID()), world, Set.of(chunk(world, 9, 9)), 0, 50L);
            h.rebuild();

            SelectionSession overlap = h.selectDelta(actor, world, land, Set.of(chunk(world, 0, 0)));
            assertRejectedZeroSideEffects(h, h.requestFor(overlap, owner), "expand.overlap");

            SelectionSession far = h.selectDelta(actor, world, land, Set.of(chunk(world, 5, 5)));
            assertRejectedZeroSideEffects(h, h.requestFor(far, owner), "expand.disconnected");

            SelectionSession collision = h.selectDelta(actor, world, land,
                    Set.of(chunk(world, 8, 9), chunk(world, 9, 9)));
            // Delta (8,9) is free and adjacent to foreign (9,9), but (9,9) is
            // occupied by a third party: the union check collides first.
            assertRejectedZeroSideEffects(h, h.requestFor(collision, owner), "land.chunk.conflict");
        }
    }

    @Test
    void ringUnionHoleRejects() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            Set<ChunkKey> ring = new HashSet<>();
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    if (x == 1 && z == 1) {
                        continue;
                    }
                    ring.add(chunk(world, x, z));
                }
            }
            h.insertLand(land, owner, world, ring, 0, 100L);
            h.rebuild();

            SelectionSession lid = h.selectDelta(actor, world, land, Set.of(chunk(world, 3, 1)));
            assertRejectedZeroSideEffects(h, h.requestFor(lid, owner), "expand.hole");
        }
    }

    @Test
    void ownOtherLandAdjacencyHintsNoAutoMerge() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            LandId land = new LandId(UUID.randomUUID());
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            LandId sibling = new LandId(UUID.randomUUID());
            h.insertLand(sibling, owner, world, Set.of(chunk(world, 2, 0)), 0, 100L);
            h.rebuild();

            // Delta bridges the gap but steals the sibling chunk: V1 refuses
            // to merge automatically instead of absorbing it.
            SelectionSession merge = h.selectDelta(actor, world, land,
                    Set.of(chunk(world, 1, 0), chunk(world, 2, 0)));
            assertRejectedZeroSideEffects(h, h.requestFor(merge, owner), "expand.merge_unsupported");
        }
    }

    @Test
    void staleTokensUnknownLandAndDisabledWorldReject() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(actor);

        try (Harness h = new Harness(10, 100)) {
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));

            ExpandRequest staleGeneration = new ExpandRequest(owner, actor, world, land,
                    Set.of(chunk(world, 1, 0)), session.selectionRevision(),
                    session.sessionGeneration() + 1, session.baseStructureRevision());
            assertRejectedZeroSideEffects(h, staleGeneration, "expand.stale");

            ExpandRequest staleRevision = new ExpandRequest(owner, actor, world, land,
                    Set.of(chunk(world, 1, 0)), session.selectionRevision() + 1,
                    session.sessionGeneration(), session.baseStructureRevision());
            assertRejectedZeroSideEffects(h, staleRevision, "expand.stale");

            ExpandRequest staleStructure = new ExpandRequest(owner, actor, world, land,
                    Set.of(chunk(world, 1, 0)), session.selectionRevision(),
                    session.sessionGeneration(), session.baseStructureRevision() + 1);
            assertRejectedZeroSideEffects(h, staleStructure, "structure.stale");

            ExpandRequest unknown = new ExpandRequest(owner, actor, world,
                    new LandId(UUID.randomUUID()), Set.of(chunk(world, 1, 0)),
                    session.selectionRevision(), session.sessionGeneration(),
                    session.baseStructureRevision());
            assertRejectedZeroSideEffects(h, unknown, "expand.unknown_land");
        }

        try (Harness h = new Harness(10, 100, WorldClaimPolicy.denyAll("world.claim_disabled"), 3)) {
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            assertRejectedZeroSideEffects(h, h.requestFor(session, owner), "world.claim_disabled");
        }
    }

    // ------------------------------------------------------------------
    // 3) Concurrency: one winner, no double charge
    // ------------------------------------------------------------------

    @Test
    void concurrentSameTargetCommitsExactlyOnce() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ExpandRequest first = h.requestFor(session, owner);
            ExpandRequest second = h.requestFor(session, owner);

            // Deterministic serialization without sleeps: the first attempt
            // runs to ACTIVE and republishes, so the second attempt's live
            // structure token is stale and rejects before any charge.
            ClaimOutcome won = h.run(first);
            assertEquals(ClaimOutcome.Status.SUCCESS, won.status());
            ClaimOutcome lost = h.run(second);

            assertEquals(ClaimOutcome.Status.REJECTED, lost.status());
            assertEquals("structure.stale", lost.diagnosticKey());
            assertEquals(1L, h.structureRevision(land));
            assertEquals(1, h.economy.charges.size(), "the stale loser must never charge");
            assertTrue(h.economy.refunds.isEmpty(), "no charge means no refund");
            assertEquals(1, h.ledgerRows().size());
            assertEquals(0, h.reservations.size());
        }
    }

    @Test
    void durableRevisionCompareAndSetSerializesRacingCommits() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();

            // Two CHARGED operations racing on revision 0 with disjoint deltas:
            // the database admits exactly one; the loser fails instead of a
            // lost update, even though both passed validation.
            UUID op1 = UUID.randomUUID();
            UUID op2 = UUID.randomUUID();
            List<OperationPayload.Chunk> delta1 = List.of(
                    new OperationPayload.Chunk(chunk(world, 1, 0), 64, UUID.randomUUID(), 100L));
            List<OperationPayload.Chunk> delta2 = List.of(
                    new OperationPayload.Chunk(chunk(world, 0, 1), 64, UUID.randomUUID(), 100L));
            OperationPayload payload1 = OperationPayload.expand(
                    op1, actor, world, land, delta1, 100L, "test-economy", NOW, "Home");
            OperationPayload payload2 = OperationPayload.expand(
                    op2, actor, world, land, delta2, 100L, "test-economy", NOW, "Home");
            h.ledger.createPaymentPending(payload1).toCompletableFuture().get(10, TimeUnit.SECONDS);
            h.ledger.createPaymentPending(payload2).toCompletableFuture().get(10, TimeUnit.SECONDS);
            h.ledger.transitionToCharged(op1, "charge:" + op1, NOW).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            h.ledger.transitionToCharged(op2, "charge:" + op2, NOW).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            ExpandCommit commit1 = new ExpandCommit(op1, land, world,
                    owner, 0, delta1, new AuditEntry(0, NOW, actor, "CHUNK_ADD", land, world,
                            chunk(world, 1, 0).pack(), OperationPayload.CURRENT_SCHEMA_VERSION,
                            null, payload1.toJson(), "{}", List.of(chunk(world, 1, 0))));
            ExpandCommit commit2 = new ExpandCommit(op2, land, world,
                    owner, 0, delta2, new AuditEntry(0, NOW, actor, "CHUNK_ADD", land, world,
                            chunk(world, 0, 1).pack(), OperationPayload.CURRENT_SCHEMA_VERSION,
                            null, payload2.toJson(), "{}", List.of(chunk(world, 0, 1))));

            h.ledger.commitExpandAtomically(commit1).toCompletableFuture().get(10, TimeUnit.SECONDS);
            boolean loserFailed = false;
            try {
                h.ledger.commitExpandAtomically(commit2).toCompletableFuture().get(10, TimeUnit.SECONDS);
            } catch (Exception expected) {
                loserFailed = true;
            }
            assertTrue(loserFailed, "the second commit on a moved revision must fail");
            assertEquals(1L, h.structureRevision(land));
            assertEquals(Set.of(chunk(world, 0, 0), chunk(world, 1, 0)),
                    new HashSet<>(h.chunks.listByLand(land).toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)));
        }
    }

    @Test
    void reservationCollisionNeverChargesTwice() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));

            // A foreign reservation on the delta chunk rejects before pricing.
            h.reservations.tryAcquire(Set.of(world + ":1:0"), UUID.randomUUID());
            try {
                ClaimOutcome outcome = h.run(h.requestFor(session, owner));
                assertEquals(ClaimOutcome.Status.REJECTED, outcome.status());
                assertEquals("reservation.conflict", outcome.diagnosticKey());
                assertTrue(h.economy.charges.isEmpty(), "reservation conflict must never charge");
                assertTrue(h.ledgerRows().isEmpty(), "reservation conflict must leave no ledger row");
            } finally {
                assertEquals(1, h.reservations.size(), "foreign reservation stays untouched");
            }
        }
    }

    // ------------------------------------------------------------------
    // 4) Payment failures follow the claim compensation contract
    // ------------------------------------------------------------------

    @Test
    void chargeRejectedFailsWithoutRefund() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            h.economy.chargeOk = false;
            h.economy.chargeKey = "economy.insufficient";

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ClaimOutcome outcome = h.run(h.requestFor(session, owner));

            assertEquals(ClaimOutcome.Status.FAILED, outcome.status());
            assertEquals("economy.insufficient", outcome.diagnosticKey());
            assertTrue(h.economy.refunds.isEmpty(), "uncharged failure must never refund");
            assertEquals(LedgerState.FAILED.name(), h.ledgerRows().get(0).state());
            assertEquals(0L, h.structureRevision(land));
            assertEquals(0, h.reservations.size());
        }
    }

    @Test
    void commitFailureRefundsOnce() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            // Break the domain commit behind the saga: the land row vanishes
            // after validation, so the atomic commit fails post-charge.
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ExpandRequest request = h.requestFor(session, owner);
            h.lands.delete(land).toCompletableFuture().get(10, TimeUnit.SECONDS);

            ClaimOutcome outcome = h.run(request);
            assertEquals(ClaimOutcome.Status.FAILED, outcome.status());
            assertEquals("claim.compensated", outcome.diagnosticKey());
            assertEquals(1, h.economy.refunds.size(), "post-charge commit failure refunds once");
            assertEquals(LedgerState.COMPENSATED.name(), h.ledgerRows().get(0).state());
            assertEquals(0, h.reservations.size());
        }
    }

    @Test
    void unconfirmedRefundReconcilesAtTheRetryLimit() throws Exception {
        try (Harness h = new Harness(10, 100, null, 1)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ExpandRequest request = h.requestFor(session, owner);
            h.economy.refundOutcome = RefundOutcome.UNKNOWN;
            h.lands.delete(land).toCompletableFuture().get(10, TimeUnit.SECONDS);

            ClaimOutcome outcome = h.run(request);
            assertEquals(ClaimOutcome.Status.FAILED, outcome.status());
            assertEquals("claim.reconciliation", outcome.diagnosticKey());
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(), h.ledgerRows().get(0).state());
        }
    }

    // ------------------------------------------------------------------
    // 5) Server land stays free and outside quotas
    // ------------------------------------------------------------------

    @Test
    void serverLandExpandsFreeWithoutQuota() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef server = OwnerRef.server();
            h.insertLand(land, server, world, Set.of(chunk(world, 0, 0)), 0, 0L);
            h.rebuild();

            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            ExpandRequest request = new ExpandRequest(server, actor, world, land,
                    Set.of(chunk(world, 1, 0)), session.selectionRevision(),
                    session.sessionGeneration(), session.baseStructureRevision());
            ClaimOutcome outcome = h.run(request);

            assertEquals(ClaimOutcome.Status.SUCCESS, outcome.status());
            assertTrue(h.economy.charges.isEmpty(), "server land must not charge");
            assertEquals(0, h.quota.chunkCommitted(server));
            assertEquals(1L, h.structureRevision(land));
            assertEquals(List.of("CHUNK_ADD"), h.auditActions(land));
        }
    }

    // ------------------------------------------------------------------
    // 6) Command handler: gate, tokens and replies
    // ------------------------------------------------------------------

    @Test
    void expandHandlerRunsSagaAndRepliesOnce() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));

            ExpandCommandHandler handler = new ExpandCommandHandler(h.selections, h.saga::expand,
                    null, sender -> Optional.of(land));
            LatchSink sink = new LatchSink();
            handler.handle(player(actor), new String[]{"expand"}, sink);
            sink.awaitReply();

            assertEquals(1, sink.replies.size());
            assertEquals("command.land.expand.success", sink.replies.get(0).key);
            assertEquals(1, sink.replies.get(0).vars.get("chunk_count"));
        }
    }

    @Test
    void expandHandlerFailsClosedWithoutSagaSideEffects() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));

            // Wrong standing position: no saga entry, no ledger, no charge.
            ExpandCommandHandler misplaced = new ExpandCommandHandler(h.selections, h.saga::expand,
                    null, sender -> Optional.of(new LandId(UUID.randomUUID())));
            LatchSink misplacedSink = new LatchSink();
            misplaced.handle(player(actor), new String[]{"expand"}, misplacedSink);
            misplacedSink.awaitReply();
            assertEquals("command.land.expand.no_target", misplacedSink.replies.get(0).key);
            assertTrue(h.ledgerRows().isEmpty());
            assertTrue(h.economy.charges.isEmpty());

            // Console sender and missing saga stay fail-closed the same way.
            LatchSink consoleSink = new LatchSink();
            CommandSender console = (CommandSender) Proxy.newProxyInstance(
                    CommandSender.class.getClassLoader(),
                    new Class[]{CommandSender.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("hasPermission")) {
                            return true;
                        }
                        return null;
                    });
            misplaced.handle(console, new String[]{"expand"}, consoleSink);
            consoleSink.awaitReply();
            assertEquals("command.land.expand.console", consoleSink.replies.get(0).key);

            ExpandCommandHandler unwired = new ExpandCommandHandler(h.selections, null,
                    null, sender -> Optional.of(land));
            LatchSink unwiredSink = new LatchSink();
            unwired.handle(player(actor), new String[]{"expand"}, unwiredSink);
            unwiredSink.awaitReply();
            assertEquals("command.land.expand.failed", unwiredSink.replies.get(0).key);
            assertEquals("expand.unavailable", unwiredSink.replies.get(0).vars.get("reason"));
            assertTrue(h.ledgerRows().isEmpty());
        }
    }

    @Test
    void expandHandlerSubtractsTargetChunksFromTheWandRectangle() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            ChunkKey existing = chunk(world, 0, 0);
            ChunkKey added = chunk(world, 1, 0);
            h.insertLand(land, owner, world, Set.of(existing), 0, 100L);
            h.rebuild();
            // The wand rectangle anchored on the actor's own land covers the
            // existing chunk plus the wilderness the actor is adding.
            h.selectDelta(actor, world, land, Set.of(existing, added));

            ExpandCommandHandler handler = new ExpandCommandHandler(h.selections, h.saga::expand,
                    null, sender -> Optional.of(land), target -> Optional.of(Set.of(existing)));
            LatchSink sink = new LatchSink();
            handler.handle(player(actor), new String[]{"expand"}, sink);
            sink.awaitReply();

            assertEquals(1, sink.replies.size());
            assertEquals("command.land.expand.success", sink.replies.get(0).key);
            assertEquals(1, sink.replies.get(0).vars.get("chunk_count"),
                    "only the wilderness delta must reach the saga, not the target chunks");
            assertEquals(List.of("CHUNK_ADD"), h.auditActions(land));
            assertEquals(1L, h.structureRevision(land), "exactly one chunk was added to the target");
        }
    }

    @Test
    void expandHandlerFailsClosedWhenTheTargetCannotBeResolved() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world, Set.of(chunk(world, 0, 0)), 0, 100L);
            h.rebuild();
            h.selectDelta(actor, world, land, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)));

            ExpandCommandHandler handler = new ExpandCommandHandler(h.selections, h.saga::expand,
                    null, sender -> Optional.of(land), target -> Optional.empty());
            LatchSink sink = new LatchSink();
            handler.handle(player(actor), new String[]{"expand"}, sink);
            sink.awaitReply();

            assertEquals("command.land.expand.failed", sink.replies.get(0).key);
            assertEquals("expand.unknown_land", sink.replies.get(0).vars.get("reason"));
            assertTrue(h.ledgerRows().isEmpty(), "an unknown target must not reach the saga");
            assertTrue(h.economy.charges.isEmpty());
        }
    }

    @Test
    void expandHandlerTreatsATargetOnlySelectionAsEmptyDelta() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            ChunkKey existing = chunk(world, 0, 0);
            h.insertLand(land, owner, world, Set.of(existing), 0, 100L);
            h.rebuild();
            h.selectDelta(actor, world, land, Set.of(existing));

            ExpandCommandHandler handler = new ExpandCommandHandler(h.selections, h.saga::expand,
                    null, sender -> Optional.of(land), target -> Optional.of(Set.of(existing)));
            LatchSink sink = new LatchSink();
            handler.handle(player(actor), new String[]{"expand"}, sink);
            sink.awaitReply();

            assertEquals("command.land.expand.no_selection", sink.replies.get(0).key);
            assertTrue(h.ledgerRows().isEmpty());
            assertTrue(h.economy.charges.isEmpty());
        }
    }

    @Test
    void expandDispatchWithoutGateNeverReachesTheHandler() {
        Map<String, List<String>> seen = new HashMap<>();
        LandCommand.Handler probe = (sender, args, sink) -> seen.put("expand", List.of(args));
        Map<String, LandCommand.Handler> handlers = new HashMap<>(LandCommand.defaultStubHandlers());
        handlers.put("expand", probe);
        Player sender = player(UUID.randomUUID());
        ReplySink silent = new ReplySink() {
            @Override
            public void reply(String messageKey, Map<String, Object> vars) {
            }

            @Override
            public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
            }
        };
        // No gate resolver: management subcommands fail closed without invoking the handler.
        LandCommand command = new LandCommand(Map.copyOf(handlers), (s, pipeline) -> silent);
        assertTrue(command.dispatch(sender, new String[]{"expand"}, null));
        assertTrue(seen.isEmpty(), "expand without a gate must never reach the handler");
    }

    // ------------------------------------------------------------------
    // (Plugin wiring is covered in the root-package production wiring tests,
    // which share ChunkLandPlugin's package for the package-visible assembly.)
    // ------------------------------------------------------------------
}

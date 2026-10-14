package com.smile.chunkland.runtime.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.event.ChunkLandEventBus;
import com.smile.chunkland.api.event.ChunkLandEventListener;
import com.smile.chunkland.api.event.LandChunkAddPostEvent;
import com.smile.chunkland.api.event.LandChunkRemovePostEvent;
import com.smile.chunkland.api.event.LandCreatePostEvent;
import com.smile.chunkland.api.event.LandDeletePostEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.api.mutation.ExpectedVersion;
import com.smile.chunkland.api.mutation.MutationKind;
import com.smile.chunkland.api.mutation.MutationOutcome;
import com.smile.chunkland.api.mutation.TypedMutationRequest;
import com.smile.chunkland.api.mutation.TypedMutationResult;
import com.smile.chunkland.claim.ClaimEconomy;
import com.smile.chunkland.claim.ClaimOutcome;
import com.smile.chunkland.claim.ClaimRecoveryHandlers;
import com.smile.chunkland.claim.ClaimRequest;
import com.smile.chunkland.claim.ClaimSaga;
import com.smile.chunkland.claim.ClaimValidator;
import com.smile.chunkland.claim.DeleteOutcome;
import com.smile.chunkland.claim.DeleteRequest;
import com.smile.chunkland.claim.DeleteSaga;
import com.smile.chunkland.claim.DeleteValidator;
import com.smile.chunkland.claim.ExpandSaga;
import com.smile.chunkland.claim.ExpandValidator;
import com.smile.chunkland.claim.ShrinkSaga;
import com.smile.chunkland.claim.ShrinkValidator;
import com.smile.chunkland.claim.SnapshotClaimValidator;
import com.smile.chunkland.claim.SnapshotDeleteValidator;
import com.smile.chunkland.claim.SnapshotExpandValidator;
import com.smile.chunkland.claim.SnapshotShrinkValidator;
import com.smile.chunkland.claim.WorldClaimPolicy;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.CrashRecoveryScanner;
import com.smile.chunkland.persistence.LandRepository;
import com.smile.chunkland.persistence.LandRenameRepository;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RecoveryHandlers;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.rename.LandRenameResult;
import com.smile.chunkland.rename.LandRenameService;
import com.smile.chunkland.runtime.index.LandRegistryStore;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Typed public mutations run the production sagas with all five safety
 * fields enforced: actor versus target owner, expected version, operation
 * id, fixed pricing with confirmation, and ledger-backed idempotency.
 */
class SagaBackedMutationsTest {

    @TempDir java.nio.file.Path temp;

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
        volatile boolean available = true;
        volatile ClaimEconomy.ChargeResult nextCharge = ClaimEconomy.ChargeResult.ok();

        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
            charges.add(new ChargeCall(operationId, price));
            ClaimEconomy.ChargeResult scripted = nextCharge;
            return CompletableFuture.completedFuture(
                    scripted == null ? ChargeResult.ok() : scripted);
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

        @Override
        public boolean isAvailable() {
            return available;
        }

        long chargesFor(UUID operationId) {
            return charges.stream().filter(call -> call.operationId().equals(operationId)).count();
        }

        long refundsFor(UUID operationId) {
            return refunds.stream().filter(entry -> entry.operationId().equals(operationId)).count();
        }
    }

    static final class RecordingBus implements ChunkLandEventBus {
        final List<Object> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public <E> void register(Class<E> eventType, ChunkLandEventListener<? super E> listener) {
        }

        @Override
        public <E> void unregister(Class<E> eventType, ChunkLandEventListener<? super E> listener) {
        }

        @Override
        public <E> void publish(E event) {
            events.add(event);
        }

        long totalPosts() {
            return events.stream().filter(event ->
                    event instanceof LandCreatePostEvent
                            || event instanceof LandChunkAddPostEvent
                            || event instanceof LandChunkRemovePostEvent
                            || event instanceof LandDeletePostEvent).count();
        }
    }

    static final class FailingLandRepository implements LandRepository {
        private <T> CompletionStage<T> fail() {
            return CompletableFuture.failedFuture(new IllegalStateException("storage down"));
        }

        @Override
        public CompletionStage<Void> save(com.smile.chunkland.api.land.LandSnapshot land) {
            return fail();
        }

        @Override
        public CompletionStage<Optional<com.smile.chunkland.api.land.LandSnapshot>> findById(LandId id) {
            return fail();
        }

        @Override
        public CompletionStage<List<com.smile.chunkland.api.land.LandSnapshot>> findByOwner(OwnerRef owner) {
            return fail();
        }

        @Override
        public CompletionStage<List<com.smile.chunkland.api.land.LandSnapshot>> findAll() {
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
        final OwnerQuotaService quota;
        final LogicalReservationRegistry reservations = new LogicalReservationRegistry();
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        final SqliteLandRepository lands;
        final SqliteChunkRepository chunks;
        final RecordingBus bus = new RecordingBus();
        final PublicEvents events = PublicEvents.create(bus, null);
        final SelectionSessionManager selections;
        final Map<UUID, Long> revisions = new ConcurrentHashMap<>();
        final Map<UUID, Long> generations = new ConcurrentHashMap<>();
        final Set<UUID> stewards = ConcurrentHashMap.newKeySet();
        final Set<UUID> bypassed = ConcurrentHashMap.newKeySet();
        RuntimeRegistryRebuilder rebuilder;
        ClaimSaga claimSaga;
        ExpandSaga expandSaga;
        ShrinkSaga shrinkSaga;
        DeleteSaga deleteSaga;
        LandRenameService renameService;
        SagaBackedMutations facade;

        Harness(int maxLands, int maxChunks) {
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = new OperationLedger(store);
            quota = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), new LimitSettings(maxLands, maxChunks, 128, 16), 0L, Map.of())));
            lands = new SqliteLandRepository(store);
            chunks = new SqliteChunkRepository(store);
            rebuilder = new RuntimeRegistryRebuilder(lands, registryStore);
            ClaimValidator.RevisionSource revisionSource = actor -> {
                Long held = revisions.get(actor);
                return held == null ? OptionalLong.empty() : OptionalLong.of(held);
            };
            ClaimValidator.SessionGenerationSource generationSource = actor -> {
                Long held = generations.get(actor);
                return held == null ? OptionalLong.empty() : OptionalLong.of(held);
            };
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
                    new SelectionVisualizationTaskController() {
                        @Override
                        public void stop(UUID playerId) {
                        }
                    },
                    SelectionNotifier.noop(),
                    (SelectionClock) () -> NOW,
                    Duration.ofMinutes(10),
                    ignored -> Optional.of("world"),
                    liveStructures);
            rebuildFlows();
        }

        private void rebuildFlows() {
            PricingTable pricing = PricingTable.of(List.of(
                    PricingTier.of(5, new Money(100, EMC)),
                    PricingTier.of(PricingTier.UNBOUNDED, new Money(200, EMC))));
            ClaimValidator claimValidator = new SnapshotClaimValidator(registryStore,
                    (ClaimValidator.RevisionSource) actor -> {
                        Long held = revisions.get(actor);
                        return held == null ? OptionalLong.empty() : OptionalLong.of(held);
                    },
                    (ClaimValidator.SessionGenerationSource) actor -> {
                        Long held = generations.get(actor);
                        return held == null ? OptionalLong.empty() : OptionalLong.of(held);
                    },
                    chunk -> 64,
                    quota::chunkCommitted,
                    target -> {
                        try {
                            var snapshot = registryStore.snapshot();
                            if (snapshot == null) {
                                return OptionalLong.empty();
                            }
                            var land = snapshot.land(target);
                            return land == null
                                    ? OptionalLong.empty()
                                    : OptionalLong.of(land.structureRevision());
                        } catch (RuntimeException unresolved) {
                            return OptionalLong.empty();
                        }
                    },
                    WorldClaimPolicy.allowAll());
            ExpandValidator expandValidator = new SnapshotExpandValidator(registryStore,
                    (ClaimValidator.RevisionSource) actor -> {
                        Long held = revisions.get(actor);
                        return held == null ? OptionalLong.empty() : OptionalLong.of(held);
                    },
                    (ClaimValidator.SessionGenerationSource) actor -> {
                        Long held = generations.get(actor);
                        return held == null ? OptionalLong.empty() : OptionalLong.of(held);
                    },
                    chunk -> 64,
                    quota::chunkCommitted,
                    target -> {
                        try {
                            var snapshot = registryStore.snapshot();
                            if (snapshot == null) {
                                return OptionalLong.empty();
                            }
                            var land = snapshot.land(target);
                            return land == null
                                    ? OptionalLong.empty()
                                    : OptionalLong.of(land.structureRevision());
                        } catch (RuntimeException unresolved) {
                            return OptionalLong.empty();
                        }
                    },
                    WorldClaimPolicy.allowAll());
            ShrinkValidator shrinkValidator = new SnapshotShrinkValidator(registryStore,
                    (ClaimValidator.RevisionSource) actor -> {
                        Long held = revisions.get(actor);
                        return held == null ? OptionalLong.empty() : OptionalLong.of(held);
                    },
                    (ClaimValidator.SessionGenerationSource) actor -> {
                        Long held = generations.get(actor);
                        return held == null ? OptionalLong.empty() : OptionalLong.of(held);
                    },
                    target -> {
                        try {
                            var snapshot = registryStore.snapshot();
                            if (snapshot == null) {
                                return OptionalLong.empty();
                            }
                            var land = snapshot.land(target);
                            return land == null
                                    ? OptionalLong.empty()
                                    : OptionalLong.of(land.structureRevision());
                        } catch (RuntimeException unresolved) {
                            return OptionalLong.empty();
                        }
                    });
            DeleteValidator deleteValidator = new SnapshotDeleteValidator(registryStore, target -> {
                try {
                    var snapshot = registryStore.snapshot();
                    if (snapshot == null) {
                        return OptionalLong.empty();
                    }
                    var land = snapshot.land(target);
                    return land == null
                            ? OptionalLong.empty()
                            : OptionalLong.of(land.structureRevision());
                } catch (RuntimeException unresolved) {
                    return OptionalLong.empty();
                }
            });
            claimSaga = new ClaimSaga(claimValidator, quota, pricing, reservations, ledger,
                    economy, rebuilder, CLOCK, Runnable::run, 3, events);
            expandSaga = new ExpandSaga(expandValidator, quota, pricing, reservations, ledger,
                    economy, rebuilder, CLOCK, Runnable::run, 3, events);
            shrinkSaga = new ShrinkSaga(shrinkValidator, chunks, EMC, reservations, ledger,
                    economy, rebuilder, quota, CLOCK, Runnable::run, 3, events);
            deleteSaga = new DeleteSaga(deleteValidator, chunks, reservations, ledger,
                    economy, rebuilder, quota, selections, CLOCK, Runnable::run, 3, events);
            renameService = new LandRenameService(new LandRenameRepository(store),
                    registryStore::snapshot, rebuilder, CLOCK);
            facade = new SagaBackedMutations(
                    request -> {
                        throw new IllegalStateException("legacy must stay rejected");
                    },
                    Set.of(),
                    claimSaga, expandSaga, shrinkSaga, deleteSaga, renameService, ledger,
                    registryStore::snapshot,
                    () -> new SnapshotPermissionContextProvider(null, null),
                    stewards::contains,
                    bypassed::contains,
                    CLOCK);
        }

        void withFailingPublish() {
            rebuilder = new RuntimeRegistryRebuilder(new FailingLandRepository(), registryStore);
            rebuildFlows();
        }

        TypedMutationResult call(TypedMutationRequest request) throws Exception {
            return facade.submitTyped(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        long revisionOf(LandId landId) {
            return registryStore.snapshot().land(landId).structureRevision();
        }

        boolean ledgerHas(UUID operationId) throws Exception {
            return ledger.findById(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS).isPresent();
        }

        String ledgerState(UUID operationId) throws Exception {
            return ledger.find(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS).state();
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static Set<ChunkKey> chunksOf(UUID world, int... coords) {
        if (coords.length % 2 != 0) {
            throw new IllegalArgumentException("coords must be x/z pairs");
        }
        java.util.LinkedHashSet<ChunkKey> out = new java.util.LinkedHashSet<>();
        for (int i = 0; i < coords.length; i += 2) {
            out.add(new ChunkKey(world, coords[i], coords[i + 1]));
        }
        return Collections.unmodifiableSet(out);
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    @Test
    void typedCreateCommitsThroughTheProductionClaimFlow() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, operationId,
                    world, chunksOf(world, 0, 0), "Home"));
            assertEquals(MutationOutcome.SUCCESS, result.outcome());
            assertNotNull(result.landId());
            assertEquals(operationId, result.operationId());
            assertTrue(result.submitted());
            assertEquals(1, h.economy.chargesFor(operationId));
            assertEquals("ACTIVE", h.ledgerState(operationId));
            assertEquals(1, h.bus.totalPosts());
            assertNotNull(h.registryStore.snapshot().land(result.landId()));
        }
    }

    @Test
    void typedCreateMatchesChatPathOutcomeAndPrice() throws Exception {
        UUID world = UUID.randomUUID();
        ClaimOutcome chatOutcome;
        Money chatPrice;
        try (Harness chat = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            ClaimRequest request = new ClaimRequest(OwnerRef.player(actor), actor, world,
                    chunksOf(world, 3, 0), "ChatHome", null, null, null, null, UUID.randomUUID());
            chatOutcome = chat.claimSaga.claim(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ClaimOutcome.Status.SUCCESS, chatOutcome.status());
            chatPrice = chat.economy.charges.get(0).price();
        }
        try (Harness typed = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = typed.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, operationId,
                    world, chunksOf(world, 3, 0), "ChatHome"));
            assertEquals(MutationOutcome.SUCCESS, result.outcome());
            assertEquals(chatOutcome.status().name(), result.outcome().name());
            assertEquals(chatPrice, typed.economy.charges.get(0).price());
        }
    }

    @Test
    void typedCreateForAnotherPlayerRejectsWithoutSideEffects() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(other), null, null, operationId,
                    world, chunksOf(world, 0, 0), "Hijack"));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("claim.not_allowed", result.diagnosticKey());
            assertTrue(h.economy.charges.isEmpty());
            assertTrue(h.bus.events.isEmpty());
            assertTrue(!h.ledgerHas(operationId));
        }
    }

    @Test
    void typedCreateCollisionRejectsWithoutCharge() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult first = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0), "First"));
            assertEquals(MutationOutcome.SUCCESS, first.outcome());
            TypedMutationResult clash = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0), "Second"));
            assertEquals(MutationOutcome.REJECTED, clash.outcome());
            assertEquals("land.chunk.conflict", clash.diagnosticKey());
            assertEquals(1, h.economy.charges.size());
        }
    }

    @Test
    void typedCreateDisconnectedRejects() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0, 5, 5), "Split"));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("selection.disconnected", result.diagnosticKey());
            assertTrue(h.economy.charges.isEmpty());
        }
    }

    @Test
    void typedCreateBeyondQuotaRejects() throws Exception {
        try (Harness h = new Harness(0, 0)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0), "OverQuota"));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("limit.reached", result.diagnosticKey());
            assertTrue(h.economy.charges.isEmpty());
        }
    }

    @Test
    void typedCreateWithoutEconomyRejectsBeforeAnyRow() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            h.economy.available = false;
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, operationId,
                    world, chunksOf(world, 0, 0), "NoVault"));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("economy.unavailable", result.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
        }
    }

    // ------------------------------------------------------------------
    // Expand / shrink / delete / rename
    // ------------------------------------------------------------------

    @Test
    void typedExpandShrinkDeleteRenameChainOnOneLand() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0, 1, 0), "Chain"));
            assertEquals(MutationOutcome.SUCCESS, created.outcome());
            LandId landId = created.landId();

            TypedMutationResult renamed = h.call(new TypedMutationRequest.LandRename(
                    actor, OwnerRef.player(actor), landId,
                    ExpectedVersion.structure(h.revisionOf(landId)),
                    UUID.randomUUID(), "Chain Renamed"));
            assertEquals(MutationOutcome.SUCCESS, renamed.outcome());
            assertEquals(landId, renamed.landId());

            TypedMutationResult expanded = h.call(new TypedMutationRequest.LandChunkAdd(
                    actor, OwnerRef.player(actor), landId,
                    ExpectedVersion.structure(h.revisionOf(landId)),
                    UUID.randomUUID(), world, chunksOf(world, 2, 0)));
            assertEquals(MutationOutcome.SUCCESS, expanded.outcome());

            TypedMutationResult shrunk = h.call(new TypedMutationRequest.LandChunkRemove(
                    actor, OwnerRef.player(actor), landId,
                    ExpectedVersion.structure(h.revisionOf(landId)),
                    UUID.randomUUID(), world, chunksOf(world, 2, 0)));
            assertEquals(MutationOutcome.SUCCESS, shrunk.outcome());

            TypedMutationResult deleted = h.call(new TypedMutationRequest.LandDelete(
                    actor, OwnerRef.player(actor), landId,
                    ExpectedVersion.structure(h.revisionOf(landId)), UUID.randomUUID()));
            assertEquals(MutationOutcome.SUCCESS, deleted.outcome());
            assertEquals(2, h.economy.refunds.size());
            assertEquals(2, h.economy.refunds.stream().map(LedgerEntry::operationId).distinct().count());
        }
    }

    @Test
    void strangerCannotExpandOrShrinkOrDeleteOrRename() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID owner = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    owner, OwnerRef.player(owner), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0, 1, 0), "Owned"));
            LandId landId = created.landId();
            long revision = h.revisionOf(landId);
            int charges = h.economy.charges.size();
            int posts = h.bus.events.size();

            TypedMutationResult expand = h.call(new TypedMutationRequest.LandChunkAdd(
                    stranger, OwnerRef.player(owner), landId, ExpectedVersion.structure(revision),
                    UUID.randomUUID(), world, chunksOf(world, 2, 0)));
            assertEquals(MutationOutcome.REJECTED, expand.outcome());
            assertEquals("expand.owner_mismatch", expand.diagnosticKey());

            TypedMutationResult shrink = h.call(new TypedMutationRequest.LandChunkRemove(
                    stranger, OwnerRef.player(owner), landId, ExpectedVersion.structure(revision),
                    UUID.randomUUID(), world, chunksOf(world, 1, 0)));
            assertEquals(MutationOutcome.REJECTED, shrink.outcome());
            assertEquals("shrink.owner_mismatch", shrink.diagnosticKey());

            TypedMutationResult delete = h.call(new TypedMutationRequest.LandDelete(
                    stranger, OwnerRef.player(owner), landId,
                    ExpectedVersion.structure(revision), UUID.randomUUID()));
            assertEquals(MutationOutcome.REJECTED, delete.outcome());
            assertEquals("delete.owner_mismatch", delete.diagnosticKey());

            TypedMutationResult rename = h.call(new TypedMutationRequest.LandRename(
                    stranger, OwnerRef.player(owner), landId,
                    ExpectedVersion.structure(revision), UUID.randomUUID(), "Stolen"));
            assertEquals(MutationOutcome.REJECTED, rename.outcome());
            assertEquals("rename.not_allowed", rename.diagnosticKey());

            assertEquals(charges, h.economy.charges.size());
            assertTrue(h.economy.refunds.isEmpty());
            assertEquals(posts, h.bus.events.size());
            assertEquals(2, h.registryStore.snapshot().land(landId).chunks().size());
        }
    }

    @Test
    void stewardCanExpandAndRenameServerLandWhileStrangerCannot() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID steward = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            h.stewards.add(steward);
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    steward, OwnerRef.server(), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0), "Server Post"));
            assertEquals(MutationOutcome.SUCCESS, created.outcome());
            assertTrue(h.economy.charges.isEmpty());
            LandId landId = created.landId();

            TypedMutationResult rename = h.call(new TypedMutationRequest.LandRename(
                    steward, OwnerRef.server(), landId,
                    ExpectedVersion.structure(h.revisionOf(landId)),
                    UUID.randomUUID(), "Server Post Renamed"));
            assertEquals(MutationOutcome.SUCCESS, rename.outcome());

            TypedMutationResult denied = h.call(new TypedMutationRequest.LandRename(
                    stranger, OwnerRef.server(), landId,
                    ExpectedVersion.structure(h.revisionOf(landId)),
                    UUID.randomUUID(), "Stolen"));
            assertEquals(MutationOutcome.REJECTED, denied.outcome());
        }
    }

    @Test
    void staleExpectedVersionRejectsWithoutSideEffects() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0, 1, 0), "Versioned"));
            LandId landId = created.landId();
            long live = h.revisionOf(landId);
            int charges = h.economy.charges.size();
            int posts = h.bus.events.size();

            TypedMutationResult stale = h.call(new TypedMutationRequest.LandChunkAdd(
                    actor, OwnerRef.player(actor), landId, ExpectedVersion.structure(live + 5),
                    UUID.randomUUID(), world, chunksOf(world, 2, 0)));
            assertEquals(MutationOutcome.REJECTED, stale.outcome());
            assertEquals(TypedMutationResult.STALE_EXPECTED_VERSION, stale.diagnosticKey());
            assertEquals(charges, h.economy.charges.size());
            assertEquals(posts, h.bus.events.size());
            assertEquals(2, h.registryStore.snapshot().land(landId).chunks().size());
        }
    }

    @Test
    void typedDeleteMatchesChatPathResult() throws Exception {
        UUID world = UUID.randomUUID();
        DeleteOutcome chatOutcome;
        try (Harness chat = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            TypedMutationResult created = chat.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 9, 9), "Bye"));
            LandId landId = created.landId();
            chatOutcome = chat.deleteSaga.delete(new DeleteRequest(OwnerRef.player(actor), actor,
                    world, landId, chat.revisionOf(landId), UUID.randomUUID()))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(DeleteOutcome.Status.SUCCESS, chatOutcome.status());
        }
        try (Harness typed = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            TypedMutationResult created = typed.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 9, 9), "Bye"));
            TypedMutationResult deleted = typed.call(new TypedMutationRequest.LandDelete(
                    actor, OwnerRef.player(actor), created.landId(),
                    ExpectedVersion.structure(typed.revisionOf(created.landId())), UUID.randomUUID()));
            assertEquals(MutationOutcome.SUCCESS, deleted.outcome());
            assertEquals(chatOutcome.status().name(), deleted.outcome().name());
        }
    }

    @Test
    void typedRenameMatchesServiceResult() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 4, 4), "Before"));
            LandId landId = created.landId();
            LandRenameResult direct = h.renameService.rename(
                    actor, landId, "Direct Rename", false)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(LandRenameResult.Status.SUCCESS, direct.status());
        }
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 4, 4), "Before"));
            TypedMutationResult renamed = h.call(new TypedMutationRequest.LandRename(
                    actor, OwnerRef.player(actor), created.landId(),
                    ExpectedVersion.structure(h.revisionOf(created.landId())),
                    UUID.randomUUID(), "Direct Rename"));
            assertEquals(MutationOutcome.SUCCESS, renamed.outcome());
            assertEquals("Direct Rename",
                    h.registryStore.snapshot().land(created.landId()).displayName());
        }
    }

    // ------------------------------------------------------------------
    // Idempotency
    // ------------------------------------------------------------------

    @Test
    void sameOperationIdResubmittedReturnsRecordedResultOnce() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            UUID operationId = UUID.randomUUID();
            TypedMutationRequest.LandCreate create = new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, operationId,
                    world, chunksOf(world, 0, 0), "Once");
            TypedMutationResult first = h.call(create);
            assertEquals(MutationOutcome.SUCCESS, first.outcome());
            TypedMutationResult second = h.call(create);
            assertEquals(MutationOutcome.SUCCESS, second.outcome());
            assertEquals(first.landId(), second.landId());
            assertEquals(1, h.economy.chargesFor(operationId));
            assertEquals(1, h.bus.totalPosts());
        }
    }

    @Test
    void sameDeleteIdResubmittedRefundsOnlyOnce() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0), "Refund Once"));
            LandId landId = created.landId();
            UUID operationId = UUID.randomUUID();
            TypedMutationRequest.LandDelete delete = new TypedMutationRequest.LandDelete(
                    actor, OwnerRef.player(actor), landId,
                    ExpectedVersion.structure(h.revisionOf(landId)), operationId);
            TypedMutationResult first = h.call(delete);
            assertEquals(MutationOutcome.SUCCESS, first.outcome());
            TypedMutationResult second = h.call(delete);
            assertEquals(MutationOutcome.SUCCESS, second.outcome());
            assertEquals(1, h.economy.refundsFor(operationId));
            assertEquals(2, h.bus.totalPosts());
        }
    }

    @Test
    void sameIdWithDifferentContentRejectsAndRunsNothing() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            UUID operationId = UUID.randomUUID();
            TypedMutationResult first = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, operationId,
                    world, chunksOf(world, 0, 0), "Original"));
            assertEquals(MutationOutcome.SUCCESS, first.outcome());
            int charges = h.economy.charges.size();
            TypedMutationResult mismatched = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, operationId,
                    world, chunksOf(world, 7, 7), "Changed"));
            assertEquals(MutationOutcome.REJECTED, mismatched.outcome());
            assertEquals(SagaBackedMutations.OPERATION_ID_MISMATCH, mismatched.diagnosticKey());
            assertEquals(charges, h.economy.charges.size());
        }
    }

    @Test
    void differentIdWithSameContentRunsIndependently() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult first = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0), "Same Chunks"));
            assertEquals(MutationOutcome.SUCCESS, first.outcome());
            TypedMutationResult second = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0), "Same Chunks"));
            assertEquals(MutationOutcome.REJECTED, second.outcome());
            assertEquals("land.chunk.conflict", second.diagnosticKey());
            assertEquals(1, h.economy.charges.size());
        }
    }

    @Test
    void failedOperationResubmittedStaysFailedWithoutNewCharge() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            h.economy.nextCharge = ClaimEconomy.ChargeResult.failed("economy.insufficient_funds");
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            UUID operationId = UUID.randomUUID();
            TypedMutationRequest.LandCreate create = new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, operationId,
                    world, chunksOf(world, 0, 0), "Broke");
            TypedMutationResult first = h.call(create);
            assertEquals(MutationOutcome.FAILED, first.outcome());
            assertEquals("economy.insufficient_funds", first.diagnosticKey());
            assertTrue(!first.submitted());
            assertEquals("FAILED", h.ledgerState(operationId));
            TypedMutationResult second = h.call(create);
            assertEquals(MutationOutcome.FAILED, second.outcome());
            assertTrue(!second.submitted());
            assertEquals(1, h.economy.chargesFor(operationId));
            assertEquals(1, h.bus.events.size());
            assertEquals(0, h.bus.totalPosts());
        }
    }

    @Test
    void renameResubmittedReturnsRecordedResult() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 2, 2), "Rename Me"));
            LandId landId = created.landId();
            UUID operationId = UUID.randomUUID();
            TypedMutationRequest.LandRename rename = new TypedMutationRequest.LandRename(
                    actor, OwnerRef.player(actor), landId,
                    ExpectedVersion.structure(h.revisionOf(landId)), operationId, "Renamed");
            TypedMutationResult first = h.call(rename);
            assertEquals(MutationOutcome.SUCCESS, first.outcome());
            TypedMutationResult second = h.call(rename);
            assertEquals(MutationOutcome.SUCCESS, second.outcome());
            assertEquals(landId, second.landId());
            TypedMutationResult mismatched = h.call(new TypedMutationRequest.LandRename(
                    actor, OwnerRef.player(actor), landId,
                    ExpectedVersion.structure(h.revisionOf(landId)), operationId, "Other"));
            assertEquals(MutationOutcome.REJECTED, mismatched.outcome());
            assertEquals(SagaBackedMutations.OPERATION_ID_MISMATCH, mismatched.diagnosticKey());
            assertEquals("Renamed", h.registryStore.snapshot().land(landId).displayName());
        }
    }

    @Test
    void expandWithStaleClaimedOwnerRejectsWithoutSideEffects() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID owner = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    owner, OwnerRef.player(owner), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0), "Owned"));
            LandId landId = created.landId();
            long revision = h.revisionOf(landId);
            int charges = h.economy.charges.size();
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.LandChunkAdd(
                    owner, OwnerRef.server(), landId, ExpectedVersion.structure(revision),
                    operationId, world, chunksOf(world, 1, 0)));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("expand.owner_mismatch", result.diagnosticKey());
            assertEquals(charges, h.economy.charges.size());
            assertTrue(h.economy.refunds.isEmpty());
            assertTrue(!h.ledgerHas(operationId));
            assertEquals(1, h.registryStore.snapshot().land(landId).chunks().size());
        }
    }

    @Test
    void shrinkWithStaleClaimedOwnerRejectsWithoutSideEffects() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID owner = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    owner, OwnerRef.player(owner), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0, 1, 0), "Owned"));
            LandId landId = created.landId();
            long revision = h.revisionOf(landId);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.LandChunkRemove(
                    owner, OwnerRef.server(), landId, ExpectedVersion.structure(revision),
                    operationId, world, chunksOf(world, 1, 0)));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("shrink.owner_mismatch", result.diagnosticKey());
            assertTrue(h.economy.refunds.isEmpty());
            assertTrue(!h.ledgerHas(operationId));
            assertEquals(2, h.registryStore.snapshot().land(landId).chunks().size());
        }
    }

    @Test
    void deleteWithStaleClaimedOwnerRejectsWithoutSideEffects() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID owner = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    owner, OwnerRef.player(owner), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0), "Owned"));
            LandId landId = created.landId();
            long revision = h.revisionOf(landId);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.LandDelete(
                    owner, OwnerRef.server(), landId,
                    ExpectedVersion.structure(revision), operationId));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("delete.owner_mismatch", result.diagnosticKey());
            assertTrue(h.economy.refunds.isEmpty());
            assertTrue(!h.ledgerHas(operationId));
            assertNotNull(h.registryStore.snapshot().land(landId));
        }
    }

    @Test
    void renameWithStaleClaimedOwnerRejectsWithoutSideEffects() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID owner = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    owner, OwnerRef.player(owner), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0), "Owned"));
            LandId landId = created.landId();
            long revision = h.revisionOf(landId);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.LandRename(
                    owner, OwnerRef.server(), landId,
                    ExpectedVersion.structure(revision), operationId, "Hijacked"));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("rename.owner_mismatch", result.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
            assertEquals("Owned", h.registryStore.snapshot().land(landId).displayName());
        }
    }

    @Test
    void stewardClaimingPlayerOwnerOnServerLandRejects() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID steward = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            h.stewards.add(steward);
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    steward, OwnerRef.server(), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0), "Server Post"));
            LandId landId = created.landId();
            long revision = h.revisionOf(landId);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.LandRename(
                    steward, OwnerRef.player(steward), landId,
                    ExpectedVersion.structure(revision), operationId, "Hijacked"));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("rename.owner_mismatch", result.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
            assertEquals("Server Post", h.registryStore.snapshot().land(landId).displayName());
        }
    }

    @Test
    void resubmitClaimingDifferentOwnerRejectsAsMismatch() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            UUID operationId = UUID.randomUUID();
            TypedMutationResult first = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, operationId,
                    world, chunksOf(world, 0, 0), "Original"));
            assertEquals(MutationOutcome.SUCCESS, first.outcome());
            TypedMutationResult resubmitted = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.server(), null, null, operationId,
                    world, chunksOf(world, 0, 0), "Original"));
            assertEquals(MutationOutcome.REJECTED, resubmitted.outcome());
            assertEquals(SagaBackedMutations.OPERATION_ID_MISMATCH, resubmitted.diagnosticKey());
            assertEquals(1, h.economy.chargesFor(operationId));
            assertEquals(OwnerRef.player(actor).key(),
                    h.registryStore.snapshot().land(first.landId()).ownerRef().key());
        }
    }

    @Test
    void sameShrinkIdResubmittedRefundsOnlyOnce() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0, 1, 0), "Refund Once"));
            LandId landId = created.landId();
            UUID operationId = UUID.randomUUID();
            TypedMutationRequest.LandChunkRemove shrink = new TypedMutationRequest.LandChunkRemove(
                    actor, OwnerRef.player(actor), landId,
                    ExpectedVersion.structure(h.revisionOf(landId)),
                    operationId, world, chunksOf(world, 1, 0));
            TypedMutationResult first = h.call(shrink);
            assertEquals(MutationOutcome.SUCCESS, first.outcome());
            TypedMutationResult second = h.call(shrink);
            assertEquals(MutationOutcome.SUCCESS, second.outcome());
            assertEquals(first.landId(), second.landId());
            assertEquals(1, h.economy.refundsFor(operationId));
            assertEquals(1, h.registryStore.snapshot().land(landId).chunks().size());
        }
    }

    @Test
    void adminBypassCannotExpandAnotherOwnersLand() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID owner = UUID.randomUUID();
            UUID admin = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            h.bypassed.add(admin);
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    owner, OwnerRef.player(owner), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0), "Owned"));
            LandId landId = created.landId();
            long revision = h.revisionOf(landId);
            int charges = h.economy.charges.size();
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.LandChunkAdd(
                    admin, OwnerRef.player(owner), landId, ExpectedVersion.structure(revision),
                    operationId, world, chunksOf(world, 1, 0)));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("expand.owner_mismatch", result.diagnosticKey());
            assertEquals(charges, h.economy.charges.size());
            assertTrue(!h.ledgerHas(operationId));
            assertEquals(1, h.registryStore.snapshot().land(landId).chunks().size());
        }
    }

    @Test
    void missingOperationIdFailsFastAtConstruction() {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> new TypedMutationRequest.LandDelete(
                actor, OwnerRef.player(actor), new LandId(UUID.randomUUID()),
                ExpectedVersion.structure(0), null));
        assertThrows(NullPointerException.class, () -> new TypedMutationRequest.LandCreate(
                actor, OwnerRef.player(actor), null, null, null,
                world, chunksOf(world, 0, 0), "No Key"));
    }

    // ------------------------------------------------------------------
    // Lifecycle and failure visibility
    // ------------------------------------------------------------------

    @Test
    void closedHandleRejectsNewOperationsWithoutSideEffects() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            h.facade.close();
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, operationId,
                    world, chunksOf(world, 0, 0), "After Close"));
            assertEquals(MutationOutcome.FAILED, result.outcome());
            assertEquals(SagaBackedMutations.COORDINATOR_REJECTED, result.diagnosticKey());
            assertTrue(!result.submitted());
            assertTrue(!h.ledgerHas(operationId));
            assertTrue(h.economy.charges.isEmpty());
        }
    }

    @Test
    void publishFailureIsSubmittedAndRecoverable() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        LandId landId;
        try (Harness h = new Harness(10, 100)) {
            h.withFailingPublish();
            TypedMutationResult degraded = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, operationId,
                    world, chunksOf(world, 0, 0), "Degraded"));
            assertEquals(MutationOutcome.FAILED, degraded.outcome());
            assertTrue(degraded.submitted());
            assertEquals("claim.publish_failed", degraded.diagnosticKey());
            assertEquals("DOMAIN_COMMITTED", h.ledgerState(operationId));
            RecoveryHandlers handlers = ClaimRecoveryHandlers.forStartup(h.economy,
                    new RuntimeRegistryRebuilder(h.lands, h.registryStore));
            assertNotNull(handlers);
            var scanned = ClaimRecoveryHandlers.scanAtStartup(h.ledger, h.economy,
                            new RuntimeRegistryRebuilder(h.lands, h.registryStore))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(!scanned.isEmpty());
            assertEquals("ACTIVE", h.ledgerState(operationId));
            TypedMutationResult retried = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, operationId,
                    world, chunksOf(world, 0, 0), "Degraded"));
            assertEquals(MutationOutcome.SUCCESS, retried.outcome());
            assertEquals(1, h.economy.chargesFor(operationId));
        }
    }

    @Test
    void unsupportedKindsStayRejectedWithZeroSideEffects() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0), "Base"));
            LandId landId = created.landId();
            UUID operationId = UUID.randomUUID();
            TypedMutationResult permission = h.call(new TypedMutationRequest.PermissionChange(
                    actor, OwnerRef.player(actor), landId,
                    com.smile.chunkland.api.mutation.ExpectedVersion.policy(
                            h.registryStore.snapshot().land(landId).landPolicyRevision()),
                    operationId, null,
                    com.smile.chunkland.api.event.PermissionChangedEvent.ChangeKind.TRUST,
                    com.smile.chunkland.api.event.PermissionChangedEvent.SubjectKind.PLAYER,
                    UUID.randomUUID(), null, null, null));
            assertEquals(MutationOutcome.REJECTED, permission.outcome());
            assertEquals("mutation.unsupported", permission.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
            assertEquals(1, h.economy.charges.size());
        }
    }

    @Test
    void supportedKindsCoverAllFiveLandOperations() {
        assertTrue(MutationKind.valueOf("LAND_CREATE") != null);
        assertTrue(MutationKind.valueOf("LAND_DELETE") != null);
        assertTrue(MutationKind.valueOf("LAND_CHUNK_ADD") != null);
        assertTrue(MutationKind.valueOf("LAND_CHUNK_REMOVE") != null);
        assertTrue(MutationKind.valueOf("LAND_RENAME") != null);
    }

    // ------------------------------------------------------------------
    // Legacy entry still delegates
    // ------------------------------------------------------------------

    @Test
    void legacySubmitDelegatesToTheInjectedPipeline() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            AtomicLong calls = new AtomicLong();
            SagaBackedMutations onlyLegacy = new SagaBackedMutations(
                    request -> {
                        calls.incrementAndGet();
                        return CompletableFuture.completedFuture(
                                com.smile.chunkland.api.mutation.MutationResult.rejected("legacy.nope"));
                    },
                    Set.of(MutationKind.LAND_CREATE),
                    h.claimSaga, h.expandSaga, h.shrinkSaga, h.deleteSaga, h.renameService, h.ledger,
                    h.registryStore::snapshot,
                    () -> new SnapshotPermissionContextProvider(null, null),
                    h.stewards::contains,
                    uuid -> false,
                    CLOCK);
            com.smile.chunkland.api.mutation.MutationResult result = onlyLegacy.submit(
                    new com.smile.chunkland.api.mutation.MutationRequest(MutationKind.LAND_CREATE,
                            null, OwnerRef.player(UUID.randomUUID()), Set.of(), null))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("legacy.nope", result.diagnosticKey());
            assertEquals(1, calls.get());
        }
    }
}

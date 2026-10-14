package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.event.ChunkLandEventBus;
import com.smile.chunkland.api.event.ChunkLandEventListener;
import com.smile.chunkland.api.event.SubLandPostEvent;
import com.smile.chunkland.api.event.SubLandPreEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.api.mutation.ExpectedVersion;
import com.smile.chunkland.api.mutation.MutationOutcome;
import com.smile.chunkland.api.mutation.TypedMutationRequest;
import com.smile.chunkland.api.mutation.TypedMutationResult;
import com.smile.chunkland.binding.LandBindingService;
import com.smile.chunkland.claim.ClaimEconomy;
import com.smile.chunkland.claim.ClaimSaga;
import com.smile.chunkland.claim.DeleteSaga;
import com.smile.chunkland.claim.ExpandSaga;
import com.smile.chunkland.claim.ShrinkSaga;
import com.smile.chunkland.claim.WorldClaimPolicy;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.LandBindingRepository;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.rename.LandRenameService;
import com.smile.chunkland.rule.LandRuleMutationService;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
import com.smile.chunkland.runtime.mutation.ProductionMutationPorts;
import com.smile.chunkland.runtime.mutation.SagaBackedMutations;
import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import com.smile.chunkland.subland.SubLandConfirmService;
import com.smile.chunkland.subland.SubLandMutationRunner;
import com.smile.chunkland.trust.LandAuthorisationService;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Public typed mutations cover SubLand create/delete through the
 * production runner: the same containment, overlap, depth and
 * structure-lock validation the chat path runs, with all five safety
 * fields enforced before any ledger row.
 */
class PublicSubLandMutationTest {

    @TempDir java.nio.file.Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final class FakeEconomy implements ClaimEconomy {
        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId,
                com.smile.chunkland.claim.ClaimRequest request, Money price) {
            return CompletableFuture.completedFuture(ChargeResult.ok());
        }

        @Override
        public CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
            return CompletableFuture.completedFuture(RefundOutcome.REFUNDED);
        }

        @Override
        public String providerId() {
            return "test-economy";
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

        long sublandPosts() {
            return events.stream().filter(event -> event instanceof SubLandPostEvent).count();
        }

        long sublandCreates() {
            return events.stream().filter(event -> event instanceof SubLandPostEvent post
                    && post.operation() == SubLandPreEvent.Operation.CREATE).count();
        }

        long sublandDeletes() {
            return events.stream().filter(event -> event instanceof SubLandPostEvent post
                    && post.operation() == SubLandPreEvent.Operation.DELETE).count();
        }
    }

    final class Harness implements AutoCloseable {
        final PersistenceStore store;
        final OperationLedger ledger;
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        final RecordingBus bus = new RecordingBus();
        final PublicEvents events = PublicEvents.create(bus, null);
        final SelectionSessionManager selections;
        final Map<UUID, Long> revisions = new ConcurrentHashMap<>();
        final Map<UUID, Long> generations = new ConcurrentHashMap<>();
        final Set<UUID> stewards = ConcurrentHashMap.newKeySet();
        final Set<UUID> bypassed = ConcurrentHashMap.newKeySet();
        final LandAuthorisationService authorisations;
        final LandRuleMutationService ruleService;
        final LandBindingService bindingService;
        final SubLandMutationRunner sublandRunner;
        final ClaimSaga claimSaga;
        final ExpandSaga expandSaga;
        final ShrinkSaga shrinkSaga;
        final DeleteSaga deleteSaga;
        final LandRenameService renameService;
        final SagaBackedMutations facade;

        Harness() throws Exception {
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = new OperationLedger(store);
            OwnerQuotaService quotas = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), new LimitSettings(10, 100, 128, 16), 0L, Map.of())));
            LogicalReservationRegistry reservations = new LogicalReservationRegistry();
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            RuntimeRegistryRebuilder rebuilder = new RuntimeRegistryRebuilder(lands, registryStore);
            SelectionStructureRevisionLookup liveStructures = landId -> {
                try {
                    var snapshot = registryStore.snapshot();
                    if (snapshot == null) {
                        return OptionalLong.empty();
                    }
                    var land = snapshot.land(landId);
                    return land == null
                            ? OptionalLong.empty()
                            : OptionalLong.of(land.structureRevision());
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
            PricingTable pricing = PricingTable.of(List.of(
                    PricingTier.of(5, new Money(100, EMC)),
                    PricingTier.of(PricingTier.UNBOUNDED, new Money(200, EMC))));
            ClaimSaga claimSaga = ChunkLandPlugin.buildClaimSaga(registryStore, selections,
                    quotas, pricing, reservations, ledger, economy, rebuilder, Runnable::run,
                    liveStructures, WorldClaimPolicy.allowAll(), events);
            ExpandSaga expandSaga = ChunkLandPlugin.buildExpandSaga(registryStore, selections,
                    quotas, pricing, reservations, ledger, economy, rebuilder, Runnable::run,
                    liveStructures, WorldClaimPolicy.allowAll(), events);
            ShrinkSaga shrinkSaga = ChunkLandPlugin.buildShrinkSaga(registryStore, selections,
                    quotas, reservations, ledger, economy, rebuilder, Runnable::run,
                    liveStructures, chunks, EMC, events);
            DeleteSaga deleteSaga = ChunkLandPlugin.buildDeleteSaga(registryStore, selections,
                    quotas, reservations, ledger, economy, rebuilder, Runnable::run,
                    liveStructures, chunks, events);
            LandRenameService renameService = ChunkLandPlugin.buildLandRename(
                    store, registryStore, rebuilder);
            this.claimSaga = claimSaga;
            this.expandSaga = expandSaga;
            this.shrinkSaga = shrinkSaga;
            this.deleteSaga = deleteSaga;
            this.renameService = renameService;
            authorisations = ChunkLandPlugin.buildLandAuthorisations(store, events);
            ruleService = ChunkLandPlugin.buildLandRules(store, authorisations.cache(), events);
            bindingService = new LandBindingService(new LandBindingRepository(store),
                    authorisations.cache(), CLOCK, events);
            authorisations.refresh().toCompletableFuture().get(10, TimeUnit.SECONDS);
            bindingService.refresh().toCompletableFuture().get(10, TimeUnit.SECONDS);
            sublandRunner = ChunkLandPlugin.buildSubLandRunner(store, registryStore, selections,
                    new SubLandConfirmService(),
                    ChunkLandPlugin.buildSubLandDepthSource(registryStore),
                    new LimitSettings(10, 100, 128, 16), Clock.systemUTC(), events, null, null);
            assertNotNull(sublandRunner);
            facade = new SagaBackedMutations(
                    request -> {
                        throw new IllegalStateException("legacy must stay rejected");
                    },
                    Set.of(),
                    claimSaga, expandSaga, shrinkSaga, deleteSaga, renameService,
                    ProductionMutationPorts.sublands(sublandRunner, Clock.systemUTC()),
                    ProductionMutationPorts.permissions(authorisations, bindingService),
                    ProductionMutationPorts.rules(ruleService),
                    authorisations.cache()::snapshot,
                    ledger,
                    registryStore::snapshot,
                    () -> new SnapshotPermissionContextProvider(null, null),
                    stewards::contains,
                    bypassed::contains,
                    CLOCK);
        }

        TypedMutationResult call(TypedMutationRequest request) throws Exception {
            return facade.submitTyped(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        LandId createParent(UUID actor, OwnerRef owner, UUID world) throws Exception {
            TypedMutationResult created = call(new TypedMutationRequest.LandCreate(
                    actor, owner, null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0, 1, 0), "Parent"));
            assertEquals(MutationOutcome.SUCCESS, created.outcome());
            assertNotNull(created.landId());
            return created.landId();
        }

        TypedMutationRequest.SublandCreate sublandCreate(UUID actor, OwnerRef owner, LandId parent,
                long structure, UUID operationId, UUID world, String name, Cuboid cuboid) {
            return new TypedMutationRequest.SublandCreate(
                    actor, owner, parent, ExpectedVersion.structure(structure), operationId,
                    world, name, cuboid.coveredChunks(world), cuboid);
        }

        long revisionOf(LandId landId) {
            return registryStore.snapshot().land(landId).structureRevision();
        }

        boolean ledgerHas(UUID operationId) throws Exception {
            return ledger.findById(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS).isPresent();
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

    private static Cuboid chunkCuboid(int chunkX, int chunkZ, int minY, int maxY) {
        return new Cuboid(chunkX * 16, minY, chunkZ * 16, chunkX * 16 + 15, maxY, chunkZ * 16 + 15);
    }

    @Test
    void sublandCreateCommitsThroughTheProductionRunner() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId parent = h.createParent(actor, OwnerRef.player(actor), world);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(h.sublandCreate(actor, OwnerRef.player(actor),
                    parent, h.revisionOf(parent), operationId, world, "Cellar",
                    chunkCuboid(0, 0, 64, 320)));
            assertEquals(MutationOutcome.SUCCESS, result.outcome());
            assertEquals(parent, result.landId());
            assertEquals(operationId, result.operationId());
            var live = h.registryStore.snapshot().land(parent);
            assertEquals(1, live.subLands().size());
            assertEquals("Cellar", live.subLands().get(0).name());
            assertEquals(1, h.bus.sublandCreates());
        }
    }

    @Test
    void sublandDeleteCommitsThroughTheProductionRunner() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId parent = h.createParent(actor, OwnerRef.player(actor), world);
            TypedMutationResult created = h.call(h.sublandCreate(actor, OwnerRef.player(actor),
                    parent, h.revisionOf(parent), UUID.randomUUID(), world, "Cellar",
                    chunkCuboid(0, 0, 64, 320)));
            assertEquals(MutationOutcome.SUCCESS, created.outcome());
            var stored = h.registryStore.snapshot().land(parent).subLands().get(0);
            TypedMutationResult deleted = h.call(new TypedMutationRequest.SublandDelete(
                    actor, OwnerRef.player(actor), parent,
                    ExpectedVersion.structure(h.revisionOf(parent)),
                    UUID.randomUUID(), stored.id()));
            assertEquals(MutationOutcome.SUCCESS, deleted.outcome());
            assertEquals(parent, deleted.landId());
            assertTrue(h.registryStore.snapshot().land(parent).subLands().isEmpty());
            assertEquals(1, h.bus.sublandCreates());
            assertEquals(1, h.bus.sublandDeletes());
        }
    }

    @Test
    void sublandCreateWithStaleClaimedOwnerRejectsWithoutSideEffects() throws Exception {
        try (Harness h = new Harness()) {
            UUID owner = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId parent = h.createParent(owner, OwnerRef.player(owner), world);
            long revision = h.revisionOf(parent);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(h.sublandCreate(owner, OwnerRef.server(),
                    parent, revision, operationId, world, "Hijack",
                    chunkCuboid(0, 0, 64, 320)));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("subland.owner_mismatch", result.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
            assertEquals(0, h.bus.sublandPosts());
            assertTrue(h.registryStore.snapshot().land(parent).subLands().isEmpty());
        }
    }

    @Test
    void strangerCannotCreateOrDeleteSubland() throws Exception {
        try (Harness h = new Harness()) {
            UUID owner = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId parent = h.createParent(owner, OwnerRef.player(owner), world);
            long revision = h.revisionOf(parent);
            UUID createId = UUID.randomUUID();
            TypedMutationResult create = h.call(h.sublandCreate(stranger,
                    OwnerRef.player(owner), parent, revision, createId, world, "Theft",
                    chunkCuboid(0, 0, 64, 320)));
            assertEquals(MutationOutcome.REJECTED, create.outcome());
            assertTrue(!h.ledgerHas(createId));
            assertTrue(h.registryStore.snapshot().land(parent).subLands().isEmpty());

            TypedMutationResult owned = h.call(h.sublandCreate(owner, OwnerRef.player(owner),
                    parent, h.revisionOf(parent), UUID.randomUUID(), world, "Cellar",
                    chunkCuboid(0, 0, 64, 320)));
            assertEquals(MutationOutcome.SUCCESS, owned.outcome());
            var stored = h.registryStore.snapshot().land(parent).subLands().get(0);
            UUID deleteId = UUID.randomUUID();
            TypedMutationResult delete = h.call(new TypedMutationRequest.SublandDelete(
                    stranger, OwnerRef.player(owner), parent,
                    ExpectedVersion.structure(h.revisionOf(parent)), deleteId, stored.id()));
            assertEquals(MutationOutcome.REJECTED, delete.outcome());
            assertTrue(!h.ledgerHas(deleteId));
            assertEquals(1, h.registryStore.snapshot().land(parent).subLands().size());
            assertEquals(0, h.bus.sublandDeletes());
        }
    }

    @Test
    void stewardSublandOnServerLandSucceedsWhileSpoofedOwnerRejects() throws Exception {
        try (Harness h = new Harness()) {
            UUID steward = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            h.stewards.add(steward);
            LandId parent = h.createParent(steward, OwnerRef.server(), world);
            TypedMutationResult created = h.call(h.sublandCreate(steward, OwnerRef.server(),
                    parent, h.revisionOf(parent), UUID.randomUUID(), world, "Vault",
                    chunkCuboid(0, 0, 64, 320)));
            assertEquals(MutationOutcome.SUCCESS, created.outcome());

            UUID spoofId = UUID.randomUUID();
            TypedMutationResult spoofed = h.call(h.sublandCreate(steward,
                    OwnerRef.player(steward), parent, h.revisionOf(parent), spoofId, world,
                    "Hijack", chunkCuboid(1, 0, 64, 320)));
            assertEquals(MutationOutcome.REJECTED, spoofed.outcome());
            assertEquals("subland.owner_mismatch", spoofed.diagnosticKey());
            assertTrue(!h.ledgerHas(spoofId));
            assertEquals(1, h.registryStore.snapshot().land(parent).subLands().size());
        }
    }

    @Test
    void nonStewardCannotTouchServerLandSublands() throws Exception {
        try (Harness h = new Harness()) {
            UUID steward = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            h.stewards.add(steward);
            LandId parent = h.createParent(steward, OwnerRef.server(), world);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(h.sublandCreate(stranger, OwnerRef.server(),
                    parent, h.revisionOf(parent), operationId, world, "Theft",
                    chunkCuboid(0, 0, 64, 320)));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertTrue(!h.ledgerHas(operationId));
            assertTrue(h.registryStore.snapshot().land(parent).subLands().isEmpty());
            assertEquals(0, h.bus.sublandPosts());
        }
    }

    @Test
    void sublandOutsideParentIsRejectedByTheExistingValidator() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId parent = h.createParent(actor, OwnerRef.player(actor), world);
            TypedMutationResult result = h.call(h.sublandCreate(actor, OwnerRef.player(actor),
                    parent, h.revisionOf(parent), UUID.randomUUID(), world, "Outside",
                    new Cuboid(0, 64, 16, 15, 320, 31)));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("subland.invalid", result.diagnosticKey());
            assertTrue(h.registryStore.snapshot().land(parent).subLands().isEmpty());
            assertEquals(0, h.bus.sublandPosts());
        }
    }

    @Test
    void overlappingSublandIsRejectedByTheExistingValidator() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId parent = h.createParent(actor, OwnerRef.player(actor), world);
            TypedMutationResult first = h.call(h.sublandCreate(actor, OwnerRef.player(actor),
                    parent, h.revisionOf(parent), UUID.randomUUID(), world, "First",
                    chunkCuboid(0, 0, 64, 320)));
            assertEquals(MutationOutcome.SUCCESS, first.outcome());
            TypedMutationResult overlap = h.call(new TypedMutationRequest.SublandCreate(
                    actor, OwnerRef.player(actor), parent,
                    ExpectedVersion.structure(h.revisionOf(parent)), UUID.randomUUID(), world,
                    "Overlap", new Cuboid(8, 64, 0, 23, 320, 15).coveredChunks(world),
                    new Cuboid(8, 64, 0, 23, 320, 15)));
            assertEquals(MutationOutcome.REJECTED, overlap.outcome());
            assertEquals("subland.invalid", overlap.diagnosticKey());
            assertEquals(1, h.registryStore.snapshot().land(parent).subLands().size());
            assertEquals(1, h.bus.sublandCreates());
        }
    }

    @Test
    void staleStructureRevisionRejectsWithoutSideEffects() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId parent = h.createParent(actor, OwnerRef.player(actor), world);
            long live = h.revisionOf(parent);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(h.sublandCreate(actor, OwnerRef.player(actor),
                    parent, live + 5, operationId, world, "Stale",
                    chunkCuboid(0, 0, 64, 320)));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals(TypedMutationResult.STALE_EXPECTED_VERSION, result.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
            assertTrue(h.registryStore.snapshot().land(parent).subLands().isEmpty());
        }
    }

    @Test
    void chunkOnlyGeometryWithoutCuboidIsRejectedSpecifically() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId parent = h.createParent(actor, OwnerRef.player(actor), world);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.SublandCreate(
                    actor, OwnerRef.player(actor), parent,
                    ExpectedVersion.structure(h.revisionOf(parent)), operationId, world,
                    "Legacy", chunksOf(world, 0, 0), null));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("subland.legacy-geometry-unsupported", result.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
            assertTrue(h.registryStore.snapshot().land(parent).subLands().isEmpty());
        }
    }

    @Test
    void depthExtensionWithoutConfirmationIsRejectedSpecifically() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId parent = h.createParent(actor, OwnerRef.player(actor), world);
            TypedMutationResult result = h.call(h.sublandCreate(actor, OwnerRef.player(actor),
                    parent, h.revisionOf(parent), UUID.randomUUID(), world, "Deep",
                    new Cuboid(0, 0, 0, 15, 320, 15)));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("subland.confirm_depth", result.diagnosticKey());
            assertTrue(h.registryStore.snapshot().land(parent).subLands().isEmpty());
            assertEquals(0, h.bus.sublandPosts());
        }
    }

    @Test
    void blankSublandNameFailsFastAtConstruction() {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        Cuboid cuboid = chunkCuboid(0, 0, 64, 320);
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.SublandCreate(
                actor, OwnerRef.player(actor), new LandId(UUID.randomUUID()),
                ExpectedVersion.structure(0), UUID.randomUUID(), world, "  ",
                cuboid.coveredChunks(world), cuboid));
    }

    @Test
    void deleteUnknownSublandRejectsWithoutSideEffects() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId parent = h.createParent(actor, OwnerRef.player(actor), world);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.SublandDelete(
                    actor, OwnerRef.player(actor), parent,
                    ExpectedVersion.structure(h.revisionOf(parent)), operationId,
                    new com.smile.chunkland.api.land.SubLandId(UUID.randomUUID())));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("subland.unknown_subland", result.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
        }
    }

    @Test
    void sameOperationIdResubmittedCreatesOnlyOnce() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId parent = h.createParent(actor, OwnerRef.player(actor), world);
            long revision = h.revisionOf(parent);
            UUID operationId = UUID.randomUUID();
            TypedMutationRequest.SublandCreate create = h.sublandCreate(actor,
                    OwnerRef.player(actor), parent, revision, operationId, world, "Once",
                    chunkCuboid(0, 0, 64, 320));
            TypedMutationResult first = h.call(create);
            assertEquals(MutationOutcome.SUCCESS, first.outcome());
            TypedMutationResult second = h.call(create);
            assertEquals(MutationOutcome.SUCCESS, second.outcome());
            assertEquals(parent, second.landId());
            assertEquals(1, h.registryStore.snapshot().land(parent).subLands().size());
            assertEquals(1, h.bus.sublandCreates());
        }
    }

    @Test
    void differentIdWithSameGeometryFailsOverlapInsteadOfDuplicating() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId parent = h.createParent(actor, OwnerRef.player(actor), world);
            TypedMutationResult first = h.call(h.sublandCreate(actor, OwnerRef.player(actor),
                    parent, h.revisionOf(parent), UUID.randomUUID(), world, "First",
                    chunkCuboid(0, 0, 64, 320)));
            assertEquals(MutationOutcome.SUCCESS, first.outcome());
            TypedMutationResult second = h.call(h.sublandCreate(actor, OwnerRef.player(actor),
                    parent, h.revisionOf(parent), UUID.randomUUID(), world, "Second",
                    chunkCuboid(0, 0, 64, 320)));
            assertEquals(MutationOutcome.REJECTED, second.outcome());
            assertEquals(1, h.registryStore.snapshot().land(parent).subLands().size());
            assertEquals(1, h.bus.sublandCreates());
        }
    }

    @Test
    void unwiredSublandPortsStayRejectedWithoutSideEffects() throws Exception {
        try (Harness h = new Harness()) {
            SagaBackedMutations unwired = new SagaBackedMutations(
                    request -> {
                        throw new IllegalStateException("legacy must stay rejected");
                    },
                    Set.of(),
                    h.claimSaga, h.expandSaga, h.shrinkSaga, h.deleteSaga,
                    h.renameService, h.ledger,
                    h.registryStore::snapshot,
                    () -> new SnapshotPermissionContextProvider(null, null),
                    h.stewards::contains,
                    h.bypassed::contains,
                    CLOCK);
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId parent = h.createParent(actor, OwnerRef.player(actor), world);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = unwired.submitTyped(h.sublandCreate(actor,
                    OwnerRef.player(actor), parent, h.revisionOf(parent), operationId, world,
                    "Nowhere", chunkCuboid(0, 0, 64, 320)))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("mutation.unsupported", result.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
            assertTrue(h.registryStore.snapshot().land(parent).subLands().isEmpty());
        }
    }
}

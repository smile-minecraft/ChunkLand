package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.event.ChunkLandEventBus;
import com.smile.chunkland.api.event.ChunkLandEventListener;
import com.smile.chunkland.api.event.PermissionChangedEvent;
import com.smile.chunkland.api.event.RuleChangedEvent;
import com.smile.chunkland.api.event.SubLandPostEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.api.mutation.ChunkLandMutations;
import com.smile.chunkland.api.mutation.ExpectedVersion;
import com.smile.chunkland.api.mutation.MutationKind;
import com.smile.chunkland.api.mutation.MutationOutcome;
import com.smile.chunkland.api.mutation.TypedMutationRequest;
import com.smile.chunkland.api.mutation.TypedMutationResult;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
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
import com.smile.chunkland.persistence.PermissionProfileRepository;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.profile.PermissionProfileService;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The public mutation handle is assembled from the production saga
 * builders — the same builders the chat handlers use — and executes
 * typed requests end to end.
 */
class ProductionMutationApiWiringTest {

    @TempDir java.nio.file.Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    static final class FakeEconomy implements ClaimEconomy {
        final List<LedgerEntry> refunds = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId,
                com.smile.chunkland.claim.ClaimRequest request, Money price) {
            return CompletableFuture.completedFuture(ChargeResult.ok());
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

        long count(Class<?> type) {
            return events.stream().filter(type::isInstance).count();
        }
    }

    final class Wiring implements AutoCloseable {
        final PersistenceStore store;
        final OperationLedger ledger;
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        final RecordingBus bus = new RecordingBus();
        final SagaBackedMutations facade;
        final SagaBackedMutations typed;
        final LandAuthorisationService authorisations;
        final LandRuleMutationService ruleService;
        final LandBindingService bindingService;
        final PermissionProfileService profiles;

        Wiring() {
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
            SelectionSessionManager selections = new SelectionSessionManager(
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
                    liveStructures, WorldClaimPolicy.allowAll(), PublicEvents.noop());
            ExpandSaga expandSaga = ChunkLandPlugin.buildExpandSaga(registryStore, selections,
                    quotas, pricing, reservations, ledger, economy, rebuilder, Runnable::run,
                    liveStructures, WorldClaimPolicy.allowAll(), PublicEvents.noop());
            ShrinkSaga shrinkSaga = ChunkLandPlugin.buildShrinkSaga(registryStore, selections,
                    quotas, reservations, ledger, economy, rebuilder, Runnable::run,
                    liveStructures, chunks, EMC, PublicEvents.noop());
            DeleteSaga deleteSaga = ChunkLandPlugin.buildDeleteSaga(registryStore, selections,
                    quotas, reservations, ledger, economy, rebuilder, Runnable::run,
                    liveStructures, chunks, PublicEvents.noop());
            LandRenameService renameService = ChunkLandPlugin.buildLandRename(
                    store, registryStore, rebuilder);
            assertNotNull(claimSaga);
            assertNotNull(expandSaga);
            assertNotNull(shrinkSaga);
            assertNotNull(deleteSaga);
            assertNotNull(renameService);
            facade = new SagaBackedMutations(
                    request -> {
                        throw new IllegalStateException("legacy must stay rejected");
                    },
                    Set.of(),
                    claimSaga, expandSaga, shrinkSaga, deleteSaga, renameService, ledger,
                    registryStore::snapshot,
                    () -> new SnapshotPermissionContextProvider(null, null),
                    uuid -> false,
                    uuid -> false,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            PublicEvents busEvents = PublicEvents.create(bus, null);
            authorisations = ChunkLandPlugin.buildLandAuthorisations(store, busEvents);
            ruleService = ChunkLandPlugin.buildLandRules(store, authorisations.cache(), busEvents);
            bindingService = new LandBindingService(new LandBindingRepository(store),
                    authorisations.cache(), Clock.fixed(NOW, ZoneOffset.UTC), busEvents);
            profiles = new PermissionProfileService(new PermissionProfileRepository(store),
                    Clock.fixed(NOW, ZoneOffset.UTC));
            try {
                authorisations.refresh().toCompletableFuture().get(10, TimeUnit.SECONDS);
                bindingService.refresh().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            SubLandMutationRunner sublandRunner = ChunkLandPlugin.buildSubLandRunner(store,
                    registryStore, selections,
                    new SubLandConfirmService(),
                    ChunkLandPlugin.buildSubLandDepthSource(registryStore),
                    new LimitSettings(10, 100, 128, 16), Clock.systemUTC(), busEvents, null, null);
            assertNotNull(sublandRunner);
            typed = new SagaBackedMutations(
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
                    uuid -> false,
                    uuid -> false,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Override
        public void close() {
            store.close();
        }

        long policyOf(LandId landId) {
            return authorisations.cache().snapshot().policyRevision(landId).orElse(0L);
        }
    }

    @Test
    void buildersAssembleExecutableSagas() {
        try (Wiring wiring = new Wiring()) {
            assertNotNull(wiring.facade);
        }
    }

    @Test
    void assembledFlowsExecuteTypedCreateAndExpand() throws Exception {
        try (Wiring wiring = new Wiring()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = wiring.facade.submitTyped(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, Set.of(new ChunkKey(world, 0, 0)), "Wired"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.SUCCESS, created.outcome());
            LandId landId = created.landId();
            long revision = wiring.registryStore.snapshot().land(landId).structureRevision();
            TypedMutationResult expanded = wiring.facade.submitTyped(new TypedMutationRequest.LandChunkAdd(
                    actor, OwnerRef.player(actor), landId, ExpectedVersion.structure(revision),
                    UUID.randomUUID(), world, Set.of(new ChunkKey(world, 1, 0))))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.SUCCESS, expanded.outcome());
        }
    }

    @Test
    void assembledDeleteRefundsOnceOnResubmit() throws Exception {
        try (Wiring wiring = new Wiring()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = wiring.facade.submitTyped(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, Set.of(new ChunkKey(world, 6, 6)), "Wired Bye"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            LandId landId = created.landId();
            long revision = wiring.registryStore.snapshot().land(landId).structureRevision();
            UUID operationId = UUID.randomUUID();
            TypedMutationRequest.LandDelete delete = new TypedMutationRequest.LandDelete(
                    actor, OwnerRef.player(actor), landId,
                    ExpectedVersion.structure(revision), operationId);
            TypedMutationResult first = wiring.facade.submitTyped(delete)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.SUCCESS, first.outcome());
            TypedMutationResult second = wiring.facade.submitTyped(delete)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.SUCCESS, second.outcome());
            assertEquals(1, wiring.economy.refunds.stream()
                    .filter(entry -> entry.operationId().equals(operationId)).count());
        }
    }

    @Test
    void closedFacadeRejectsNewOperations() throws Exception {
        try (Wiring wiring = new Wiring()) {
            wiring.facade.close();
            assertTrue(wiring.facade.isClosed());
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult result = wiring.facade.submitTyped(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, Set.of(new ChunkKey(world, 0, 0)), "Closed"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.FAILED, result.outcome());
            assertEquals(SagaBackedMutations.COORDINATOR_REJECTED, result.diagnosticKey());
            assertTrue(!result.submitted());
        }
    }

    @Test
    void pluginExposesStableMutationsAccessor() throws Exception {
        var accessor = ChunkLandPlugin.class.getMethod("getMutations");
        assertEquals(ChunkLandMutations.class, accessor.getReturnType());
    }

    @Test
    void supportedKindsCoverAllNineOperations() {
        for (MutationKind kind : new MutationKind[]{
                MutationKind.LAND_CREATE, MutationKind.LAND_DELETE, MutationKind.LAND_CHUNK_ADD,
                MutationKind.LAND_CHUNK_REMOVE, MutationKind.LAND_RENAME,
                MutationKind.SUBLAND_CREATE, MutationKind.SUBLAND_DELETE,
                MutationKind.PERMISSION_CHANGE, MutationKind.RULE_CHANGE}) {
            assertNotNull(MutationKind.valueOf(kind.name()));
        }
        assertEquals(9, MutationKind.values().length);
    }

    @Test
    void wiredPortsExecuteAllNineKindsEndToEnd() throws Exception {
        try (Wiring wiring = new Wiring()) {
            UUID actor = UUID.randomUUID();
            UUID friend = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = wiring.typed.submitTyped(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, Set.of(new ChunkKey(world, 0, 0), new ChunkKey(world, 1, 0)), "Wired Nine"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.SUCCESS, created.outcome());
            LandId landId = created.landId();

            Cuboid cellar = new Cuboid(0, 64, 0, 15, 320, 15);
            TypedMutationResult sublandCreated = wiring.typed.submitTyped(
                    new TypedMutationRequest.SublandCreate(actor, OwnerRef.player(actor), landId,
                            ExpectedVersion.structure(
                                    wiring.registryStore.snapshot().land(landId).structureRevision()),
                            UUID.randomUUID(), world, "Cellar",
                            cellar.coveredChunks(world), cellar))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.SUCCESS, sublandCreated.outcome());
            SubLandId target = wiring.registryStore.snapshot().land(landId).subLands().get(0).id();

            TypedMutationResult renamed = wiring.typed.submitTyped(new TypedMutationRequest.LandRename(
                    actor, OwnerRef.player(actor), landId,
                    ExpectedVersion.structure(
                            wiring.registryStore.snapshot().land(landId).structureRevision()),
                    UUID.randomUUID(), "Wired Nine Renamed"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.SUCCESS, renamed.outcome());

            TypedMutationResult trusted = wiring.typed.submitTyped(
                    new TypedMutationRequest.PermissionChange(actor, OwnerRef.player(actor),
                            landId, ExpectedVersion.policy(wiring.policyOf(landId)),
                            UUID.randomUUID(), null,
                            PermissionChangedEvent.ChangeKind.TRUST,
                            PermissionChangedEvent.SubjectKind.PLAYER, friend, null, null, null))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.SUCCESS, trusted.outcome());

            UUID profile = wiring.profiles.create(actor, "Crew").toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).id();
            TypedMutationResult bound = wiring.typed.submitTyped(
                    new TypedMutationRequest.PermissionChange(actor, OwnerRef.player(actor),
                            landId, ExpectedVersion.policy(wiring.policyOf(landId)),
                            UUID.randomUUID(), null,
                            PermissionChangedEvent.ChangeKind.BIND,
                            PermissionChangedEvent.SubjectKind.PLAYER, friend, null, null, profile))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.SUCCESS, bound.outcome());

            TypedMutationResult unbound = wiring.typed.submitTyped(
                    new TypedMutationRequest.PermissionChange(actor, OwnerRef.player(actor),
                            landId, ExpectedVersion.policy(wiring.policyOf(landId)),
                            UUID.randomUUID(), null,
                            PermissionChangedEvent.ChangeKind.UNBIND,
                            PermissionChangedEvent.SubjectKind.PLAYER, friend, null, null, null))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.SUCCESS, unbound.outcome());

            TypedMutationResult ruled = wiring.typed.submitTyped(new TypedMutationRequest.RuleChange(
                    actor, OwnerRef.player(actor), landId,
                    ExpectedVersion.policy(wiring.policyOf(landId)),
                    UUID.randomUUID(), LandRuleType.PVP, PermissionState.DENY))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.SUCCESS, ruled.outcome());

            TypedMutationResult sublandDeleted = wiring.typed.submitTyped(
                    new TypedMutationRequest.SublandDelete(actor, OwnerRef.player(actor), landId,
                            ExpectedVersion.structure(
                                    wiring.registryStore.snapshot().land(landId).structureRevision()),
                            UUID.randomUUID(), target))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.SUCCESS, sublandDeleted.outcome());

            TypedMutationResult shrunk = wiring.typed.submitTyped(
                    new TypedMutationRequest.LandChunkRemove(actor, OwnerRef.player(actor), landId,
                            ExpectedVersion.structure(
                                    wiring.registryStore.snapshot().land(landId).structureRevision()),
                            UUID.randomUUID(), world, Set.of(new ChunkKey(world, 1, 0))))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.SUCCESS, shrunk.outcome());

            TypedMutationResult deleted = wiring.typed.submitTyped(new TypedMutationRequest.LandDelete(
                    actor, OwnerRef.player(actor), landId,
                    ExpectedVersion.structure(
                            wiring.registryStore.snapshot().land(landId).structureRevision()),
                    UUID.randomUUID()))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(MutationOutcome.SUCCESS, deleted.outcome());

            assertEquals(2, wiring.bus.count(SubLandPostEvent.class));
            assertTrue(wiring.bus.count(PermissionChangedEvent.class) >= 3);
            assertEquals(1, wiring.bus.count(RuleChangedEvent.class));
        }
    }
}

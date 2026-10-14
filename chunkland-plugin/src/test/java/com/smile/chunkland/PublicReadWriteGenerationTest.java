package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.event.ChunkLandEventBus;
import com.smile.chunkland.api.event.ChunkLandEventListener;
import com.smile.chunkland.api.event.PermissionChangedEvent;
import com.smile.chunkland.api.event.RuleChangedEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.api.mutation.ExpectedVersion;
import com.smile.chunkland.api.mutation.MutationOutcome;
import com.smile.chunkland.api.mutation.TypedMutationRequest;
import com.smile.chunkland.api.mutation.TypedMutationResult;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
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
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.rename.LandRenameService;
import com.smile.chunkland.rule.LandRuleMutationService;
import com.smile.chunkland.runtime.api.ChunkLandReadApi;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
import com.smile.chunkland.runtime.mutation.PermissionWritePort;
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
 * The public read API and the public write entry must agree on the
 * policy generation: a caller that records the generation through
 * {@code getLandSnapshot} and sends it back must be able to chain
 * consecutive permission and rule changes.
 */
class PublicReadWriteGenerationTest {

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
    }

    final class Harness implements AutoCloseable {
        final PersistenceStore store;
        final OperationLedger ledger;
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        final RecordingBus bus = new RecordingBus();
        final PublicEvents events = PublicEvents.create(bus, null);
        final SelectionSessionManager selections;
        final Set<UUID> stewards = ConcurrentHashMap.newKeySet();
        final Set<UUID> bypassed = ConcurrentHashMap.newKeySet();
        final LandAuthorisationService authorisations;
        final PermissionWritePort permissionPort;
        final SagaBackedMutations facade;
        final ChunkLandReadApi readApi;

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
            authorisations = ChunkLandPlugin.buildLandAuthorisations(store, events);
            LandRuleMutationService ruleService =
                    ChunkLandPlugin.buildLandRules(store, authorisations.cache(), events);
            LandBindingService bindingService = new LandBindingService(
                    new LandBindingRepository(store), authorisations.cache(), CLOCK, events);
            authorisations.refresh().toCompletableFuture().get(10, TimeUnit.SECONDS);
            bindingService.refresh().toCompletableFuture().get(10, TimeUnit.SECONDS);
            SubLandMutationRunner sublandRunner = ChunkLandPlugin.buildSubLandRunner(store,
                    registryStore, selections, new SubLandConfirmService(),
                    ChunkLandPlugin.buildSubLandDepthSource(registryStore),
                    new LimitSettings(10, 100, 128, 16), Clock.systemUTC(), events, null, null);
            permissionPort =
                    ProductionMutationPorts.permissions(authorisations, bindingService);
            facade = new SagaBackedMutations(
                    request -> {
                        throw new IllegalStateException("legacy must stay rejected");
                    },
                    Set.of(),
                    claimSaga, expandSaga, shrinkSaga, deleteSaga, renameService,
                    ProductionMutationPorts.sublands(sublandRunner, Clock.systemUTC()),
                    permissionPort,
                    ProductionMutationPorts.rules(ruleService),
                    authorisations.cache()::snapshot,
                    ledger,
                    registryStore::snapshot,
                    () -> new SnapshotPermissionContextProvider(null, null),
                    stewards::contains,
                    bypassed::contains,
                    CLOCK);
            readApi = new ChunkLandReadApi(registryStore::snapshot,
                    (actor, landId, action, snapshot) -> null,
                    (landId, rule, snapshot) -> Optional.empty(),
                    (landId, snapshot) -> Optional.empty(),
                    () -> true,
                    (actor, worldId, x, y, z, action, snapshot) -> null,
                    authorisations.cache()::snapshot);
        }

        TypedMutationResult call(TypedMutationRequest request) throws Exception {
            return facade.submitTyped(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        long readPolicy(LandId landId) {
            LandSnapshot snapshot = readApi.getLandSnapshot(landId)
                    .orElseThrow(() -> new IllegalStateException("no public snapshot for " + landId));
            return snapshot.landPolicyRevision();
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static Set<ChunkKey> chunksOf(UUID world, int... coords) {
        java.util.LinkedHashSet<ChunkKey> out = new java.util.LinkedHashSet<>();
        for (int i = 0; i < coords.length; i += 2) {
            out.add(new ChunkKey(world, coords[i], coords[i + 1]));
        }
        return Collections.unmodifiableSet(out);
    }

    private TypedMutationRequest.PermissionChange trust(UUID actor, LandId land, long policy,
            UUID subject) {
        return new TypedMutationRequest.PermissionChange(
                actor, OwnerRef.player(actor), land, ExpectedVersion.policy(policy),
                UUID.randomUUID(), null, PermissionChangedEvent.ChangeKind.TRUST,
                PermissionChangedEvent.SubjectKind.PLAYER, subject, null, null, null);
    }

    @Test
    void consecutiveChangesChainedThroughPublicReadsBothSucceed() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0, 1, 0), "Chained"));
            assertEquals(MutationOutcome.SUCCESS, created.outcome());
            LandId land = created.landId();

            TypedMutationResult first = h.call(trust(actor, land, h.readPolicy(land),
                    UUID.randomUUID()));
            assertEquals(MutationOutcome.SUCCESS, first.outcome());

            TypedMutationResult second = h.call(trust(actor, land, h.readPolicy(land),
                    UUID.randomUUID()));
            assertEquals(MutationOutcome.SUCCESS, second.outcome());
        }
    }

    @Test
    void differentKindsChainedThroughPublicReadsBothSucceed() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 4, 4), "Mixed"));
            assertEquals(MutationOutcome.SUCCESS, created.outcome());
            LandId land = created.landId();

            TypedMutationResult trusted = h.call(trust(actor, land, h.readPolicy(land),
                    UUID.randomUUID()));
            assertEquals(MutationOutcome.SUCCESS, trusted.outcome());

            TypedMutationResult ruled = h.call(new TypedMutationRequest.RuleChange(
                    actor, OwnerRef.player(actor), land,
                    ExpectedVersion.policy(h.readPolicy(land)),
                    UUID.randomUUID(), LandRuleType.PVP, PermissionState.DENY));
            assertEquals(MutationOutcome.SUCCESS, ruled.outcome());
            assertEquals(PermissionState.DENY,
                    h.authorisations.cache().snapshot().landRule(land, LandRuleType.PVP));
        }
    }

    @Test
    void defaultSeamRejectsAStaleRequestedRevision() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            TypedMutationResult created = h.call(new TypedMutationRequest.LandCreate(
                    actor, OwnerRef.player(actor), null, null, UUID.randomUUID(),
                    world, chunksOf(world, 7, 7), "Defaults"));
            assertEquals(MutationOutcome.SUCCESS, created.outcome());
            LandId land = created.landId();
            long pinned = h.readPolicy(land);

            TypedMutationResult first = h.call(new TypedMutationRequest.PermissionChange(
                    actor, OwnerRef.player(actor), land, ExpectedVersion.policy(pinned),
                    UUID.randomUUID(), null, PermissionChangedEvent.ChangeKind.DEFAULT_SET,
                    null, null, ProtectionActionType.CONTAINER_OPEN, PermissionState.ALLOW, null));
            assertEquals(MutationOutcome.SUCCESS, first.outcome());

            TypedMutationRequest.PermissionChange stale = new TypedMutationRequest.PermissionChange(
                    actor, OwnerRef.player(actor), land, ExpectedVersion.policy(pinned),
                    UUID.randomUUID(), null, PermissionChangedEvent.ChangeKind.DEFAULT_SET,
                    null, null, ProtectionActionType.CONTAINER_OPEN, PermissionState.DENY, null);
            LandSnapshot live = h.registryStore.snapshot().land(land);
            var outcome = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> h.permissionPort.apply(stale, live).toCompletableFuture().get(10,
                            TimeUnit.SECONDS));
            Throwable root = outcome.getCause();
            while (root instanceof java.util.concurrent.CompletionException && root.getCause() != null) {
                root = root.getCause();
            }
            assertTrue(root instanceof com.smile.chunkland.persistence.StaleAuthorisationException,
                    "stale requested revision must fail the conditional write, got " + root);
            assertEquals(PermissionState.ALLOW, h.authorisations.cache().snapshot()
                    .landDefault(land, ProtectionActionType.CONTAINER_OPEN));
        }
    }
}

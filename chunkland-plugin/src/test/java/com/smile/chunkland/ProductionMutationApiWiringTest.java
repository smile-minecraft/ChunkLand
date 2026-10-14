package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.api.mutation.ChunkLandMutations;
import com.smile.chunkland.api.mutation.ExpectedVersion;
import com.smile.chunkland.api.mutation.MutationOutcome;
import com.smile.chunkland.api.mutation.TypedMutationRequest;
import com.smile.chunkland.api.mutation.TypedMutationResult;
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
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.rename.LandRenameService;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
import com.smile.chunkland.runtime.mutation.SagaBackedMutations;
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

    final class Wiring implements AutoCloseable {
        final PersistenceStore store;
        final OperationLedger ledger;
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        final SagaBackedMutations facade;

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
        }

        @Override
        public void close() {
            store.close();
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
}

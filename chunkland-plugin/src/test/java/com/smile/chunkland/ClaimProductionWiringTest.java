package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.claim.ClaimEconomy;
import com.smile.chunkland.claim.ClaimOutcome;
import com.smile.chunkland.claim.ClaimRequest;
import com.smile.chunkland.claim.ClaimSaga;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundOutcome;
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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The production saga assembly ({@code buildClaimSaga}) runs a formal claim
 * end to end: live-selection revision pinning, priced charge with the
 * operation id carried into Economy and the ledger, and depth fallback
 * persisted on the payload.
 */
class ClaimProductionWiringTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    static final class FakeEconomy implements ClaimEconomy {
        record ChargeCall(UUID operationId, ClaimRequest request, Money price) {
        }

        final List<ChargeCall> charges = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
            charges.add(new ChargeCall(operationId, request, price));
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

    @Test
    void factorySagaRunsPricedClaimWithOperationIdAndDepth() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        FakeEconomy economy = new FakeEconomy();
        ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "claim-wiring-test");
            thread.setDaemon(true);
            return thread;
        });
        try (PersistenceStore store = PersistenceStore.open(temp.resolve("wiring.db"))) {
            OperationLedger ledger = new OperationLedger(store);
            OwnerQuotaService quotas = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), new LimitSettings(100, 100, 128, 16), 0L, Map.of())));
            LogicalReservationRegistry reservations = new LogicalReservationRegistry();
            LandRegistryStore registryStore = new LandRegistryStore();
            RuntimeRegistryRebuilder rebuilder =
                    new RuntimeRegistryRebuilder(new SqliteLandRepository(store), registryStore);
            SelectionSessionManager selections = new SelectionSessionManager(
                    (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                    SelectionVisualizationTaskController.noop(),
                    SelectionNotifier.noop(),
                    (SelectionClock) () -> NOW,
                    Duration.ofMinutes(10),
                    ignored -> Optional.of("world"),
                    SelectionStructureRevisionLookup.unavailable());
            PricingTable pricing = PricingTable.of(List.of(
                    PricingTier.of(5, new Money(100, EMC)),
                    PricingTier.of(PricingTier.UNBOUNDED, new Money(200, EMC))));

            ClaimSaga saga = ChunkLandPlugin.buildClaimSaga(registryStore, selections, quotas,
                    pricing, reservations, ledger, economy, rebuilder, async);

            SelectionSession initial = SelectionSession.initial(
                    actor, world, SelectionMode.CREATE_LAND,
                    Optional.empty(), Optional.empty(),
                    Optional.of(new SelectionPoint(world, 0, 64, 0)),
                    Optional.of(new SelectionPoint(world, 16, 64, 16)),
                    0, NOW);
            selections.start(initial);
            SelectionSession live = selections.updateSelection(actor, initial, new SelectionUpdate(
                            initial.pointA(), initial.pointB(),
                            Set.of(new ChunkKey(world, 3, 4)), Map.of()))
                    .orElseThrow();
            ClaimRequest request = new ClaimRequest(owner, actor, world,
                    live.selectedChunks(), "Home", live.selectionRevision());

            ClaimOutcome outcome = saga.claim(request).toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.SUCCESS, outcome.status());
            assertEquals(1, economy.charges.size());
            List<LedgerEntry> rows = ledger.findAll().toCompletableFuture().join();
            assertEquals(1, rows.size());
            assertEquals("ACTIVE", rows.get(0).state());
            assertEquals(rows.get(0).operationId(), economy.charges.get(0).operationId());
            assertEquals(new Money(100, EMC), economy.charges.get(0).price());
            OperationPayload recorded = OperationPayload.fromJson(rows.get(0).payloadJson());
            assertEquals(1, recorded.chunkSet().size());
            assertEquals(ChunkLandPlugin.CLAIM_DEPTH_FALLBACK,
                    recorded.chunkSet().get(0).storedMinProtectedY());
            assertNotNull(registryStore.snapshot().findLand(world, 3, 4));
        } finally {
            async.shutdownNow();
        }
    }
}

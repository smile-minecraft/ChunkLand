package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.chunkland.api.event.LandChunkAddPreEvent;
import com.smile.chunkland.api.event.LandChunkRemovePreEvent;
import com.smile.chunkland.api.event.LandCreatePreEvent;
import com.smile.chunkland.api.event.LandDeletePreEvent;
import com.smile.chunkland.api.event.PermissionChangedEvent;
import com.smile.chunkland.api.event.SubLandPreEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.claim.ClaimEconomy;
import com.smile.chunkland.claim.ClaimOutcome;
import com.smile.chunkland.claim.ClaimRequest;
import com.smile.chunkland.claim.DeleteOutcome;
import com.smile.chunkland.claim.DeleteRequest;
import com.smile.chunkland.claim.DeleteSaga;
import com.smile.chunkland.claim.ExpandRequest;
import com.smile.chunkland.claim.ExpandSaga;
import com.smile.chunkland.claim.ClaimSaga;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.claim.ShrinkOutcome;
import com.smile.chunkland.claim.ShrinkRequest;
import com.smile.chunkland.claim.ShrinkSaga;
import com.smile.chunkland.claim.WorldClaimPolicy;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.event.PublicEventBus;
import com.smile.chunkland.event.PublicEventCancelledException;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.LandAuthorisationRepository;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteAuditRepository;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.persistence.SqliteSubLandRepository;
import com.smile.chunkland.persistence.SubLandAtomicCommit;
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
import com.smile.chunkland.subland.DepthExtensionPort;
import com.smile.chunkland.subland.SubLandConfirmService;
import com.smile.chunkland.subland.SubLandDepthSource;
import com.smile.chunkland.subland.SubLandMutationRunner;
import com.smile.chunkland.trust.LandAuthorisationService;
import java.nio.file.Path;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production assembly forwards the shared public-event facade: every
 * builder overload that accepts {@link PublicEvents} delivers Pre vetoes
 * (and Post events) through the same bus instance the caller passed in.
 * One veto scenario per seam proves the wiring without re-covering saga
 * semantics (covered by the per-seam event tests).
 */
class PublicEventsProductionWiringTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final class FakeEconomy implements ClaimEconomy {
        final List<UUID> charges = Collections.synchronizedList(new ArrayList<>());
        final List<LedgerEntry> refunds = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
            charges.add(operationId);
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

    private SelectionSessionManager selections() {
        return selections(SelectionStructureRevisionLookup.unavailable());
    }

    private SelectionSessionManager selections(SelectionStructureRevisionLookup lookup) {
        return new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                (SelectionClock) () -> NOW,
                Duration.ofMinutes(10),
                ignored -> Optional.of("world"),
                lookup);
    }

    private static SelectionStructureRevisionLookup liveStructures(LandRegistryStore registryStore) {
        return landId -> {
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
    }

    private OwnerQuotaService quotas() {
        return new OwnerQuotaService(new LimitResolver(
                new ChunkLandConfig(Map.of(), new LimitSettings(100, 100, 128, 16), 0L, Map.of())));
    }

    private PricingTable pricing() {
        return PricingTable.of(List.of(
                PricingTier.of(5, new Money(100, EMC)),
                PricingTier.of(PricingTier.UNBOUNDED, new Money(200, EMC))));
    }

    private void insertLand(SqliteLandRepository lands, SqliteChunkRepository chunks,
            LandId landId, OwnerRef owner, UUID world, Set<ChunkKey> chunkSet,
            long revision, long basis) throws Exception {
        String displayName = "Home " + landId.value().toString().substring(0, 8);
        lands.save(new LandSnapshot(landId, displayName, LandName.normalize(displayName),
                owner, world, chunkSet, List.of(), revision, 0, NOW, NOW))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        for (ChunkKey key : chunkSet) {
            chunks.addChunk(landId, key, 64, UUID.randomUUID(), basis)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private SelectionSession selectChunks(SelectionSessionManager selections, UUID actor,
            UUID world, LandId target, Set<ChunkKey> delta) {
        SelectionSession initial = SelectionSession.initial(
                actor, world, SelectionMode.CREATE_LAND,
                Optional.ofNullable(target), Optional.empty(),
                Optional.of(new SelectionPoint(world, 0, 64, 0)),
                Optional.of(new SelectionPoint(world, 16, 64, 16)),
                0, NOW);
        SelectionSession stamped = selections.start(initial);
        return selections.updateSelection(actor, stamped, new SelectionUpdate(
                        initial.pointA(), initial.pointB(), delta, Map.of()))
                .orElseThrow(() -> new IllegalStateException("selection update must succeed"));
    }

    @Test
    void claimBuilderForwardsSharedBus() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        PublicEventBus bus = new PublicEventBus();
        AtomicInteger preCalls = new AtomicInteger();
        bus.register(LandCreatePreEvent.class, event -> {
            preCalls.incrementAndGet();
            event.setCancelled(true);
        });
        ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "wiring-claim");
            thread.setDaemon(true);
            return thread;
        });
        try (PersistenceStore store = PersistenceStore.open(temp.resolve("w-claim.db"))) {
            OperationLedger ledger = new OperationLedger(store);
            OwnerQuotaService quota = quotas();
            LogicalReservationRegistry reservations = new LogicalReservationRegistry();
            LandRegistryStore registryStore = new LandRegistryStore();
            RuntimeRegistryRebuilder rebuilder =
                    new RuntimeRegistryRebuilder(new SqliteLandRepository(store), registryStore);
            SelectionSessionManager selections = selections();
            FakeEconomy economy = new FakeEconomy();

            ClaimSaga saga = ChunkLandPlugin.buildClaimSaga(registryStore, selections, quota,
                    pricing(), reservations, ledger, economy, rebuilder, async,
                    SelectionStructureRevisionLookup.unavailable(), WorldClaimPolicy.allowAll(),
                    PublicEvents.create(bus, null));

            SelectionSession live = selectChunks(selections, actor, world, null,
                    Set.of(new ChunkKey(world, 3, 4)));
            ClaimRequest request = new ClaimRequest(owner, actor, world,
                    live.selectedChunks(), "Home", live.selectionRevision());
            ClaimOutcome outcome = saga.claim(request).toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.REJECTED, outcome.status());
            assertEquals(PublicEventCancelledException.DIAGNOSTIC_KEY, outcome.diagnosticKey());
            assertEquals(1, preCalls.get());
            assertTrue(economy.charges.isEmpty());
        } finally {
            async.shutdownNow();
        }
    }

    @Test
    void expandBuilderForwardsSharedBus() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        LandId land = new LandId(UUID.randomUUID());
        PublicEventBus bus = new PublicEventBus();
        AtomicInteger preCalls = new AtomicInteger();
        bus.register(LandChunkAddPreEvent.class, event -> {
            preCalls.incrementAndGet();
            event.setCancelled(true);
        });
        try (PersistenceStore store = PersistenceStore.open(temp.resolve("w-expand.db"))) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            insertLand(lands, chunks, land, owner, world, Set.of(new ChunkKey(world, 0, 0)), 0, 100L);
            OperationLedger ledger = new OperationLedger(store);
            OwnerQuotaService quota = quotas();
            quota.setChunkCommitted(owner, 1);
            LogicalReservationRegistry reservations = new LogicalReservationRegistry();
            LandRegistryStore registryStore = new LandRegistryStore();
            RuntimeRegistryRebuilder rebuilder = new RuntimeRegistryRebuilder(lands, registryStore);
            rebuilder.rebuild().toCompletableFuture().get(10, TimeUnit.SECONDS);
            SelectionStructureRevisionLookup structures = liveStructures(registryStore);
            SelectionSessionManager selections = selections(structures);
            FakeEconomy economy = new FakeEconomy();

            ExpandSaga saga = ChunkLandPlugin.buildExpandSaga(registryStore, selections, quota,
                    pricing(), reservations, ledger, economy, rebuilder, Runnable::run,
                    structures, WorldClaimPolicy.allowAll(),
                    PublicEvents.create(bus, null));

            SelectionSession session = selectChunks(selections, actor, world, land,
                    Set.of(new ChunkKey(world, 1, 0)));
            ExpandRequest request = new ExpandRequest(owner, session.playerId(), session.worldId(),
                    session.targetLandId().orElseThrow(), session.selectedChunks(),
                    session.selectionRevision(), session.sessionGeneration(),
                    session.baseStructureRevision());
            ClaimOutcome outcome = saga.expand(request).toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.REJECTED, outcome.status());
            assertEquals(PublicEventCancelledException.DIAGNOSTIC_KEY, outcome.diagnosticKey());
            assertEquals(1, preCalls.get());
            assertTrue(economy.charges.isEmpty());
        }
    }

    @Test
    void shrinkBuilderForwardsSharedBus() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        LandId land = new LandId(UUID.randomUUID());
        PublicEventBus bus = new PublicEventBus();
        AtomicInteger preCalls = new AtomicInteger();
        bus.register(LandChunkRemovePreEvent.class, event -> {
            preCalls.incrementAndGet();
            event.setCancelled(true);
        });
        try (PersistenceStore store = PersistenceStore.open(temp.resolve("w-shrink.db"))) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            new SqliteSubLandRepository(store);
            new SqliteAuditRepository(store);
            insertLand(lands, chunks, land, owner, world,
                    Set.of(new ChunkKey(world, 0, 0), new ChunkKey(world, 1, 0)), 0, 100L);
            OperationLedger ledger = new OperationLedger(store);
            OwnerQuotaService quota = quotas();
            LogicalReservationRegistry reservations = new LogicalReservationRegistry();
            LandRegistryStore registryStore = new LandRegistryStore();
            RuntimeRegistryRebuilder rebuilder = new RuntimeRegistryRebuilder(lands, registryStore);
            rebuilder.rebuild().toCompletableFuture().get(10, TimeUnit.SECONDS);
            SelectionStructureRevisionLookup structures = liveStructures(registryStore);
            SelectionSessionManager selections = selections(structures);
            FakeEconomy economy = new FakeEconomy();

            ShrinkSaga saga = ChunkLandPlugin.buildShrinkSaga(registryStore, selections, quota,
                    reservations, ledger, economy, rebuilder, Runnable::run,
                    structures, chunks, EMC,
                    PublicEvents.create(bus, null));

            SelectionSession session = selectChunks(selections, actor, world, land,
                    Set.of(new ChunkKey(world, 1, 0)));
            ShrinkRequest request = new ShrinkRequest(owner, session.playerId(), session.worldId(),
                    session.targetLandId().orElseThrow(), session.selectedChunks(),
                    session.selectionRevision(), session.sessionGeneration(),
                    session.baseStructureRevision());
            ShrinkOutcome outcome = saga.shrink(request).toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ShrinkOutcome.Status.REJECTED, outcome.status());
            assertEquals(PublicEventCancelledException.DIAGNOSTIC_KEY, outcome.diagnosticKey());
            assertEquals(1, preCalls.get());
            assertTrue(economy.refunds.isEmpty());
        }
    }

    @Test
    void deleteBuilderForwardsSharedBus() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        LandId land = new LandId(UUID.randomUUID());
        PublicEventBus bus = new PublicEventBus();
        AtomicInteger preCalls = new AtomicInteger();
        bus.register(LandDeletePreEvent.class, event -> {
            preCalls.incrementAndGet();
            event.setCancelled(true);
        });
        try (PersistenceStore store = PersistenceStore.open(temp.resolve("w-delete.db"))) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            new SqliteSubLandRepository(store);
            new SqliteAuditRepository(store);
            insertLand(lands, chunks, land, owner, world, Set.of(new ChunkKey(world, 0, 0)), 7, 100L);
            OperationLedger ledger = new OperationLedger(store);
            OwnerQuotaService quota = quotas();
            LogicalReservationRegistry reservations = new LogicalReservationRegistry();
            LandRegistryStore registryStore = new LandRegistryStore();
            RuntimeRegistryRebuilder rebuilder = new RuntimeRegistryRebuilder(lands, registryStore);
            rebuilder.rebuild().toCompletableFuture().get(10, TimeUnit.SECONDS);
            SelectionStructureRevisionLookup structures = liveStructures(registryStore);
            SelectionSessionManager selections = selections(structures);
            FakeEconomy economy = new FakeEconomy();

            DeleteSaga saga = ChunkLandPlugin.buildDeleteSaga(registryStore, selections, quota,
                    reservations, ledger, economy, rebuilder, Runnable::run,
                    structures, chunks,
                    PublicEvents.create(bus, null));

            DeleteOutcome outcome = saga.delete(new DeleteRequest(owner, actor, world, land, 7L))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(DeleteOutcome.Status.REJECTED, outcome.status());
            assertEquals(PublicEventCancelledException.DIAGNOSTIC_KEY, outcome.diagnosticKey());
            assertEquals(1, preCalls.get());
            assertTrue(economy.refunds.isEmpty());
        }
    }

    @Test
    void sublandBuilderForwardsSharedBus() {
        PublicEventBus bus = new PublicEventBus();
        AtomicInteger preCalls = new AtomicInteger();
        bus.register(SubLandPreEvent.class, event -> {
            preCalls.incrementAndGet();
            event.setCancelled(true);
        });
        try (PersistenceStore store = PersistenceStore.open(temp.resolve("w-subland.db"))) {
            UUID world = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            SqliteLandRepository lands = new SqliteLandRepository(store);
            new SqliteSubLandRepository(store);
            new SqliteAuditRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            LandId parent = new LandId(UUID.randomUUID());
            LandName name = LandName.of("Home");
            lands.save(new LandSnapshot(parent, name.displayName(), name.nameKey(),
                    OwnerRef.player(actor), world, Set.of(new ChunkKey(world, 0, 0)),
                    List.of(), 0, 0, NOW, NOW)).toCompletableFuture().join();
            chunks.addChunk(parent, new ChunkKey(world, 0, 0), 50, UUID.randomUUID(), 0L)
                    .toCompletableFuture().join();
            LandRegistryStore registry = new LandRegistryStore();
            SelectionSessionManager selections = selections();
            SubLandConfirmService confirm = new SubLandConfirmService();
            SelectionSession session = selections.start(SelectionSession.initial(
                    actor, world, SelectionMode.CREATE_SUBLAND, Optional.of(parent), Optional.empty(),
                    Optional.of(new SelectionPoint(world, 0, 60, 0)),
                    Optional.of(new SelectionPoint(world, 15, 70, 15)), 0L, NOW));
            SubLandConfirmService.Accepted accepted = confirm.accept(actor,
                    session.sessionGeneration(), session.selectionRevision(), selections,
                    landId -> OptionalLong.of(0L)).orElseThrow();

            SubLandMutationRunner runner = ChunkLandPlugin.buildSubLandRunner(store, registry,
                    selections, confirm, SubLandDepthSource.constant(50),
                    LimitSettings.defaults(), CLOCK, PublicEvents.create(bus, null));

            SubLandSnapshot want = new SubLandSnapshot(new SubLandId(UUID.randomUUID()), parent,
                    "den", new Cuboid(0, 60, 0, 15, 70, 15), world);
            try {
                runner.create(actor, accepted, want).toCompletableFuture().join();
                fail("vetoed subland create must fail");
            } catch (CompletionException expected) {
                assertTrue(expected.getCause() instanceof PublicEventCancelledException);
            }
            assertEquals(1, preCalls.get());
        }
    }

    @Test
    void authorisationsBuilderForwardsSharedBus() {
        PublicEventBus bus = new PublicEventBus();
        AtomicInteger calls = new AtomicInteger();
        bus.register(PermissionChangedEvent.class, event -> calls.incrementAndGet());
        try (PersistenceStore store = PersistenceStore.open(temp.resolve("w-auth.db"))) {
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            new SqliteLandRepository(store).save(new LandSnapshot(land, "Home", "home",
                    OwnerRef.player(owner), world, Set.of(new ChunkKey(world, 0, 0)),
                    List.of(), 0, 0, NOW, NOW)).toCompletableFuture().join();

            LandAuthorisationService service =
                    ChunkLandPlugin.buildLandAuthorisations(store, PublicEvents.create(bus, null));
            service.trust(owner, land, UUID.randomUUID()).toCompletableFuture().join();
            assertEquals(1, calls.get());
        }
    }
}

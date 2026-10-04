package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.event.LandChunkRemovePostEvent;
import com.smile.chunkland.api.event.LandChunkRemovePreEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.event.PublicEventBus;
import com.smile.chunkland.event.PublicEventCancelledException;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.AtomicCommitStep;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Public Pre/Post for shrinks: veto and listener failure before any
 * reservation/ledger/refund side effect; Post exactly once after commit,
 * refund and publish; Post failure isolated; compensation never replays Post.
 */
class ShrinkSagaPublicEventTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final class FakeEconomy implements ClaimEconomy {
        final List<LedgerEntry> refunds = Collections.synchronizedList(new ArrayList<>());
        volatile boolean available = true;
        volatile RefundOutcome refundOutcome = RefundOutcome.REFUNDED;

        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
            return CompletableFuture.completedFuture(ChargeResult.ok());
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

        @Override
        public boolean isAvailable() {
            return available;
        }
    }

    final class Harness implements AutoCloseable {
        final PersistenceStore store;
        final OperationLedger ledger;
        final SqliteLandRepository lands;
        final SqliteChunkRepository chunks;
        final OwnerQuotaService quota;
        final LogicalReservationRegistry reservations = new LogicalReservationRegistry();
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        final RuntimeRegistryRebuilder rebuilder;
        final SelectionSessionManager selections;
        final SnapshotShrinkValidator validator;
        final ShrinkSaga saga;
        final PublicEventBus bus = new PublicEventBus();
        final PublicEvents events;
        final AtomicInteger preCalls = new AtomicInteger();
        final AtomicInteger postCalls = new AtomicInteger();
        final List<LandChunkRemovePostEvent> posts = Collections.synchronizedList(new ArrayList<>());

        Harness() {
            this(null);
        }

        Harness(java.util.function.Consumer<AtomicCommitStep> injector) {
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = injector == null ? new OperationLedger(store) : new OperationLedger(store, injector);
            lands = new SqliteLandRepository(store);
            chunks = new SqliteChunkRepository(store);
            new SqliteSubLandRepository(store);
            new SqliteAuditRepository(store);
            quota = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), new LimitSettings(10, 100, 128, 16), 0L, Map.of())));
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
            bus.register(LandChunkRemovePreEvent.class, event -> preCalls.incrementAndGet());
            bus.register(LandChunkRemovePostEvent.class, event -> {
                postCalls.incrementAndGet();
                posts.add(event);
            });
            events = PublicEvents.create(bus, null);
            saga = new ShrinkSaga(validator, chunks, EMC, reservations, ledger,
                    economy, rebuilder, quota, CLOCK, Runnable::run, 3, events);
        }

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

        ShrinkOutcome run(ShrinkRequest request) throws Exception {
            return saga.shrink(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
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

    private ShrinkRequest requestFor(SelectionSession session, OwnerRef owner) {
        return new ShrinkRequest(owner, session.playerId(), session.worldId(),
                session.targetLandId().orElseThrow(), session.selectedChunks(),
                session.selectionRevision(), session.sessionGeneration(),
                session.baseStructureRevision());
    }

    @Test
    void preVetoProducesZeroSideEffects() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();
            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            h.bus.register(LandChunkRemovePreEvent.class, event -> event.setCancelled(true));

            ShrinkOutcome outcome = h.run(requestFor(session, owner));

            assertEquals(ShrinkOutcome.Status.REJECTED, outcome.status());
            assertEquals(PublicEventCancelledException.DIAGNOSTIC_KEY, outcome.diagnosticKey());
            assertEquals(1, h.preCalls.get());
            assertEquals(0, h.postCalls.get());
            assertTrue(h.economy.refunds.isEmpty());
            assertTrue(h.ledgerRows().isEmpty());
            assertEquals(0, h.reservations.size());
        }
    }

    @Test
    void throwingPreListenerFailsClosedWithZeroSideEffects() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();
            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            h.bus.register(LandChunkRemovePreEvent.class, event -> {
                throw new IllegalStateException("broken listener");
            });

            ShrinkOutcome outcome = h.run(requestFor(session, owner));

            assertEquals(ShrinkOutcome.Status.REJECTED, outcome.status());
            assertEquals(PublicEventCancelledException.DIAGNOSTIC_KEY, outcome.diagnosticKey());
            assertTrue(h.economy.refunds.isEmpty());
            assertTrue(h.ledgerRows().isEmpty());
            assertEquals(0, h.postCalls.get());
        }
    }

    @Test
    void postFiresExactlyOnceAfterCommitRefundAndPublish() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();
            h.quota.setChunkCommitted(owner, 2);
            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));

            ShrinkOutcome outcome = h.run(requestFor(session, owner));

            assertEquals(ShrinkOutcome.Status.SUCCESS, outcome.status());
            assertEquals(1, h.preCalls.get());
            assertEquals(1, h.postCalls.get());
            LandChunkRemovePostEvent post = h.posts.get(0);
            assertEquals(land, post.landId());
            assertEquals(actor, post.actorUuid());
            assertEquals(Set.of(chunk(world, 1, 0)), post.removedChunks());
            assertEquals(50L, post.refundMinorUnits());
        }
    }

    @Test
    void throwingPostListenerDoesNotChangeOutcome() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();
            h.quota.setChunkCommitted(owner, 2);
            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));
            h.bus.register(LandChunkRemovePostEvent.class, event -> {
                throw new IllegalStateException("broken post listener");
            });

            ShrinkOutcome outcome = h.run(requestFor(session, owner));

            assertEquals(ShrinkOutcome.Status.SUCCESS, outcome.status());
            assertEquals(1, h.postCalls.get());
        }
    }

    @Test
    void failedRefundQuarantinesWithoutPost() throws Exception {
        // Contract pin: unlike delete/create/expand posts (commit+publish,
        // before refund/finalize), the shrink Post fires only after a
        // confirmed refund. A failed refund quarantines for reconciliation
        // and must not fire, even though the domain commit itself succeeded.
        try (Harness h = new Harness()) {
            h.economy.refundOutcome = RefundOutcome.FAILED;
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();
            h.quota.setChunkCommitted(owner, 2);
            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));

            ShrinkOutcome outcome = h.run(requestFor(session, owner));

            assertEquals(ShrinkOutcome.Status.NEEDS_RECONCILIATION, outcome.status());
            assertEquals(1, h.preCalls.get());
            assertEquals(0, h.postCalls.get());
            assertTrue(h.posts.isEmpty());
        }
    }

    @Test
    void failedCommitProducesNoPost() throws Exception {
        try (Harness h = new Harness(step -> {
            if (step == AtomicCommitStep.AFTER_LAND) {
                throw new IllegalStateException("commit down");
            }
        })) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(actor);
            h.insertLand(land, owner, world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();
            h.quota.setChunkCommitted(owner, 2);
            SelectionSession session = h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));

            ShrinkOutcome outcome = h.run(requestFor(session, owner));

            assertEquals(ShrinkOutcome.Status.FAILED, outcome.status());
            assertEquals(1, h.preCalls.get());
            assertEquals(0, h.postCalls.get());
        }
    }
}

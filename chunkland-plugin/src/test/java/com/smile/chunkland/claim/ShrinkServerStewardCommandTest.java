package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.command.ShrinkCommandHandler;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
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
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Command-to-saga path for a Server Land steward.
 *
 * <p>A steward passes the management gate on Server Land (gate behaviour is
 * pinned by {@code ManagementPermissionGateTest}), but the handler builds the
 * saga request from the target snapshot's owner: Server-owned targets carry
 * the Server owner so validation passes with a zero refund, while
 * player-owned targets still carry the actor's player owner so a non-owner
 * fails the validator's owner check.
 */
class ShrinkServerStewardCommandTest {

    @TempDir java.nio.file.Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final class FakeEconomy implements ClaimEconomy {
        final List<LedgerEntry> refunds = new CopyOnWriteArrayList<>();
        volatile RefundOutcome refundOutcome = RefundOutcome.REFUNDED;
        volatile boolean available = true;

        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request,
                Money price) {
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
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);

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
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = new OperationLedger(store);
            lands = new SqliteLandRepository(store);
            chunks = new SqliteChunkRepository(store);
            sublands = new SqliteSubLandRepository(store);
            audits = new SqliteAuditRepository(store);
            quota = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), new LimitSettings(maxLands, maxChunks, 128, 16),
                            0L, Map.of())));
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

        Function<LandId, Optional<OwnerRef>> targetOwner() {
            return landId -> {
                var snapshot = registryStore.snapshot();
                if (snapshot == null || landId == null) {
                    return Optional.empty();
                }
                var land = snapshot.land(landId);
                if (land == null || land.ownerRef() == null) {
                    return Optional.empty();
                }
                return Optional.of(land.ownerRef());
            };
        }

        int ledgerCount() {
            return ledger.findAll().toCompletableFuture().join().size();
        }

        int factCount(LandId landId) throws Exception {
            return chunks.factsByLand(landId).toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .size();
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static ChunkKey chunk(UUID world, int x, int z) {
        return new ChunkKey(world, x, z);
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
                    if (name.equals("equals") || name.equals("hashCode")
                            || name.equals("toString")) {
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

    @Test
    void serverStewardShrinkViaHandlerSucceedsWithZeroRefund() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID steward = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            h.insertLand(land, OwnerRef.server(), world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 0L);
            h.rebuild();
            h.selectDelta(steward, world, land, Set.of(chunk(world, 1, 0)));

            AtomicReference<ShrinkRequest> seen = new AtomicReference<>();
            ShrinkCommandHandler handler = new ShrinkCommandHandler(h.selections,
                    request -> {
                        seen.set(request);
                        return h.saga.shrink(request);
                    },
                    null, sender -> Optional.of(land), h.targetOwner());
            LatchSink sink = new LatchSink();
            handler.handle(player(steward), new String[]{"shrink"}, sink);
            sink.awaitReply();

            assertEquals(1, sink.replies.size());
            assertEquals("command.land.shrink.success", sink.replies.get(0).key,
                    "steward on Server Land must succeed, got " + sink.replies.get(0).key
                            + " vars=" + sink.replies.get(0).vars);
            assertEquals(0L, sink.replies.get(0).vars.get("refund"));
            assertTrue(seen.get() != null && seen.get().owner() instanceof OwnerRef.ServerOwnerRef,
                    "the saga request must carry the Server owner");
            assertTrue(h.economy.refunds.isEmpty(), "server shrink must never touch Economy");
            assertEquals(1, h.factCount(land), "the delta chunk must be committed away");
            assertTrue(h.registryStore.snapshot().findLandId(world, 1, 0) == null,
                    "removed chunk must read as wilderness immediately");
            assertEquals(land, h.registryStore.snapshot().findLandId(world, 0, 0));
        }
    }

    @Test
    void legacyPlayerOnlyPathStillMismatchesOnServerLand() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID steward = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            h.insertLand(land, OwnerRef.server(), world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 0L);
            h.rebuild();
            h.selectDelta(steward, world, land, Set.of(chunk(world, 1, 0)));

            // No snapshot owner view: the handler keeps the player-only request,
            // so Server Land fails closed in the validator with zero side effects.
            ShrinkCommandHandler handler = new ShrinkCommandHandler(h.selections, h.saga::shrink,
                    null, sender -> Optional.of(land));
            LatchSink sink = new LatchSink();
            handler.handle(player(steward), new String[]{"shrink"}, sink);
            sink.awaitReply();

            assertEquals("command.land.shrink.rejected", sink.replies.get(0).key);
            assertEquals("shrink.owner_mismatch", sink.replies.get(0).vars.get("reason"));
            assertEquals(0, h.ledgerCount(), "rejection must leave no ledger row");
            assertTrue(h.economy.refunds.isEmpty(), "rejection must never deposit");
            assertEquals(2, h.factCount(land), "rejection must leave the domain untouched");
        }
    }

    @Test
    void playerOwnerPathUnchanged() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            h.insertLand(land, OwnerRef.player(actor), world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();
            h.selectDelta(actor, world, land, Set.of(chunk(world, 1, 0)));

            AtomicReference<ShrinkRequest> seen = new AtomicReference<>();
            ShrinkCommandHandler handler = new ShrinkCommandHandler(h.selections,
                    request -> {
                        seen.set(request);
                        return h.saga.shrink(request);
                    },
                    null, sender -> Optional.of(land), h.targetOwner());
            LatchSink sink = new LatchSink();
            handler.handle(player(actor), new String[]{"shrink"}, sink);
            sink.awaitReply();

            assertEquals("command.land.shrink.success", sink.replies.get(0).key);
            assertTrue(seen.get() != null && seen.get().owner() instanceof OwnerRef.PlayerOwnerRef,
                    "a player land request must still carry the actor's player owner");
            assertEquals(50L, sink.replies.get(0).vars.get("refund"),
                    "refund stays durable-basis x ratio (100 x 1/2)");
            assertEquals(1, h.economy.refunds.size(), "player shrink still deposits once");
            assertEquals(50L, h.economy.refunds.get(0).priceMinorUnits());
        }
    }

    @Test
    void nonOwnerOnPlayerLandStillRejected() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID owner = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            h.insertLand(land, OwnerRef.player(owner), world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 100L);
            h.rebuild();
            h.selectDelta(stranger, world, land, Set.of(chunk(world, 1, 0)));

            // The handler copies no owner identity: a stranger's request still
            // carries their own player owner and fails the validator's check.
            ShrinkCommandHandler handler = new ShrinkCommandHandler(h.selections, h.saga::shrink,
                    null, sender -> Optional.of(land), h.targetOwner());
            LatchSink sink = new LatchSink();
            handler.handle(player(stranger), new String[]{"shrink"}, sink);
            sink.awaitReply();

            assertEquals("command.land.shrink.rejected", sink.replies.get(0).key);
            assertEquals("shrink.owner_mismatch", sink.replies.get(0).vars.get("reason"));
            assertEquals(0, h.ledgerCount(), "rejection must leave no ledger row");
            assertTrue(h.economy.refunds.isEmpty(), "rejection must never deposit");
            assertEquals(2, h.factCount(land), "rejection must leave the domain untouched");
        }
    }

    @Test
    void missingTargetOwnerFailsClosedWithoutTouchingSaga() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID steward = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            h.insertLand(land, OwnerRef.server(), world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 0L);
            h.rebuild();
            h.selectDelta(steward, world, land, Set.of(chunk(world, 1, 0)));

            AtomicBoolean sagaTouched = new AtomicBoolean(false);
            ShrinkCommandHandler handler = new ShrinkCommandHandler(h.selections,
                    request -> {
                        sagaTouched.set(true);
                        return h.saga.shrink(request);
                    },
                    null, sender -> Optional.of(land), target -> Optional.empty());
            LatchSink sink = new LatchSink();
            handler.handle(player(steward), new String[]{"shrink"}, sink);
            sink.awaitReply();

            assertEquals("command.land.shrink.failed", sink.replies.get(0).key);
            assertEquals("shrink.failed", sink.replies.get(0).vars.get("reason"));
            assertTrue(!sagaTouched.get(), "missing owner must never reach the saga");
            assertEquals(0, h.ledgerCount(), "fail-closed path must leave no ledger row");
            assertEquals(2, h.factCount(land), "fail-closed path must leave the domain untouched");
        }
    }

    @Test
    void throwingTargetOwnerFailsClosedWithoutTouchingSaga() throws Exception {
        try (Harness h = new Harness(10, 100)) {
            UUID steward = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            h.insertLand(land, OwnerRef.server(), world,
                    Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), 0, 0L);
            h.rebuild();
            h.selectDelta(steward, world, land, Set.of(chunk(world, 1, 0)));

            AtomicBoolean sagaTouched = new AtomicBoolean(false);
            ShrinkCommandHandler handler = new ShrinkCommandHandler(h.selections,
                    request -> {
                        sagaTouched.set(true);
                        return h.saga.shrink(request);
                    },
                    null, sender -> Optional.of(land), target -> {
                        throw new IllegalStateException("snapshot lookup failed");
                    });
            LatchSink sink = new LatchSink();
            handler.handle(player(steward), new String[]{"shrink"}, sink);
            sink.awaitReply();

            assertEquals("command.land.shrink.failed", sink.replies.get(0).key);
            assertEquals("shrink.failed", sink.replies.get(0).vars.get("reason"));
            assertTrue(!sagaTouched.get(), "failing lookup must never reach the saga");
            assertEquals(0, h.ledgerCount(), "fail-closed path must leave no ledger row");
            assertEquals(2, h.factCount(land), "fail-closed path must leave the domain untouched");
        }
    }
}

package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.ClaimCommandHandler;
import com.smile.chunkland.command.LandPermissions;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
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
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production {@code /land claim} wiring: the handler resolves the player's
 * live selection into a {@link ClaimRequest} and runs the formal
 * {@link ClaimSaga} with the operation id carried into Economy and the ledger.
 * Missing, stale or rejecting inputs reply fail-closed without side effects.
 */
class ClaimCommandHandlerTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

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

    static final class Reply {
        final String key;
        final Map<String, Object> vars;

        Reply(String key, Map<String, Object> vars) {
            this.key = key;
            this.vars = Map.copyOf(vars);
        }
    }

    /** Reply sink that releases a latch on every reply so tests wait deterministically. */
    static final class LatchSink implements ReplySink {
        final List<Reply> replies = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

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
        final OwnerQuotaService quota;
        final LogicalReservationRegistry reservations = new LogicalReservationRegistry();
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        final RuntimeRegistryRebuilder rebuilder;
        final SelectionSessionManager selections;
        final ClaimSaga saga;
        final ClaimCommandHandler handler;
        final ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "claim-handler-test");
            t.setDaemon(true);
            return t;
        });
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        Harness(int maxLands, int maxChunks, ClaimValidator.RevisionSource revisions) {
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = new OperationLedger(store);
            quota = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), new LimitSettings(maxLands, maxChunks, 128, 16), 0L, Map.of())));
            rebuilder = new RuntimeRegistryRebuilder(new SqliteLandRepository(store), registryStore);
            selections = new SelectionSessionManager(
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
            ClaimValidator.RevisionSource effective = revisions != null ? revisions
                    : actorUuid -> selections.sessionFor(actorUuid)
                            .map(session -> OptionalLong.of(session.selectionRevision()))
                            .orElseGet(OptionalLong::empty);
            ClaimValidator.SessionGenerationSource generations =
                    actorUuid -> selections.sessionFor(actorUuid)
                            .map(session -> OptionalLong.of(session.sessionGeneration()))
                            .orElseGet(OptionalLong::empty);
            ClaimValidator validator = request -> new SnapshotClaimValidator(registryStore,
                    effective, generations, chunk -> 64, owner -> quota.chunkCommitted(owner),
                    ClaimValidator.StructureRevisionSource.none()).validate(request);
            saga = new ClaimSaga(validator, quota, pricing, reservations, ledger,
                    economy, rebuilder, clock, async, 3);
            handler = new ClaimCommandHandler(selections, saga::claim);
        }

        Harness(int maxLands, int maxChunks) {
            this(maxLands, maxChunks, null);
        }

        /** Start a claim session for the actor with exactly one selected chunk. */
        SelectionSession selectSingleChunk(UUID actor, UUID world, int x, int z) {
            SelectionSession initial = SelectionSession.initial(
                    actor, world, SelectionMode.CREATE_LAND,
                    Optional.empty(), Optional.empty(),
                    Optional.of(new SelectionPoint(world, 0, 64, 0)),
                    Optional.of(new SelectionPoint(world, 16, 64, 16)),
                    0, NOW);
            SelectionSession stamped = selections.start(initial);
            return selections.updateSelection(actor, stamped, new SelectionUpdate(
                            initial.pointA(), initial.pointB(),
                            Set.of(new ChunkKey(world, x, z)), Map.of()))
                    .orElseThrow(() -> new IllegalStateException("selection update must succeed"));
        }

        LandCommand command(LatchSink sink) {
            Map<String, LandCommand.Handler> handlers = new HashMap<>(LandCommand.defaultStubHandlers());
            handlers.put("claim", handler);
            return new LandCommand(handlers, (sender, pipeline) -> sink);
        }

        @Override
        public void close() {
            async.shutdownNow();
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

    private static CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class[]{CommandSender.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("hasPermission")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "Console";
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

    private static int ledgerCount(OperationLedger ledger) {
        return ledger.findAll().toCompletableFuture().join().size();
    }

    // ------------------------------------------------------------------
    // Success and rejection through the formal saga
    // ------------------------------------------------------------------

    @Test
    void successRunsFormalSagaWithOperationId() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (Harness h = new Harness(100, 100)) {
            h.selectSingleChunk(actor, world, 3, 4);
            LatchSink sink = new LatchSink();

            assertTrue(h.command(sink).dispatch(player(actor), new String[]{"claim", "Home"}, null));
            sink.awaitReply();

            assertEquals(1, sink.replies.size());
            assertEquals("command.land.claim.success", sink.replies.get(0).key);
            assertEquals("Home", sink.replies.get(0).vars.get("land_name"));
            assertEquals(1, sink.replies.get(0).vars.get("chunk_count"));
            // Exactly one charge, and its operation id is the ledger row's id.
            assertEquals(1, h.economy.charges.size());
            assertEquals(1, ledgerCount(h.ledger));
            LedgerEntry row = h.ledger.findAll().toCompletableFuture().join().get(0);
            assertEquals("ACTIVE", row.state());
            assertEquals(row.operationId(), h.economy.charges.get(0).operationId());
            assertEquals(actor, h.economy.charges.get(0).request().actorUuid());
            assertNotNull(h.registryStore.snapshot().findLand(world, 3, 4));
            assertEquals(1, h.quota.landCommitted(OwnerRef.player(actor)));
        }
    }

    @Test
    void limitRejectionRepliesWithoutSideEffects() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (Harness h = new Harness(1, 100)) {
            h.quota.setLandCommitted(OwnerRef.player(actor), 1);
            h.selectSingleChunk(actor, world, 1, 1);
            LatchSink sink = new LatchSink();

            assertTrue(h.command(sink).dispatch(player(actor), new String[]{"claim", "Home"}, null));
            sink.awaitReply();

            assertEquals(1, sink.replies.size());
            assertEquals("command.land.claim.rejected", sink.replies.get(0).key);
            assertEquals("limit.reached", sink.replies.get(0).vars.get("reason"));
            assertEquals(0, ledgerCount(h.ledger));
            assertTrue(h.economy.charges.isEmpty());
            assertEquals(0, h.reservations.size());
        }
    }

    @Test
    void staleRevisionRepliesRejected() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        // Validator pins an older revision while the live session moved on.
        try (Harness h = new Harness(100, 100, actorUuid -> OptionalLong.of(0L))) {
            h.selectSingleChunk(actor, world, 2, 2);
            LatchSink sink = new LatchSink();

            assertTrue(h.command(sink).dispatch(player(actor), new String[]{"claim", "Cabin"}, null));
            sink.awaitReply();

            assertEquals(1, sink.replies.size());
            assertEquals("command.land.claim.rejected", sink.replies.get(0).key);
            assertEquals("selection.stale", sink.replies.get(0).vars.get("reason"));
            assertEquals(0, ledgerCount(h.ledger));
            assertTrue(h.economy.charges.isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // Fail-closed inputs
    // ------------------------------------------------------------------

    @Test
    void missingSelectionRepliesUsageWithoutTouchingSaga() throws Exception {
        UUID actor = UUID.randomUUID();
        try (Harness h = new Harness(100, 100)) {
            LatchSink sink = new LatchSink();

            assertTrue(h.command(sink).dispatch(player(actor), new String[]{"claim", "Home"}, null));
            sink.awaitReply();

            assertEquals("command.land.claim.no_selection", sink.replies.get(0).key);
            assertEquals(0, ledgerCount(h.ledger));
            assertTrue(h.economy.charges.isEmpty());
        }
    }

    @Test
    void consoleSenderRepliesConsoleWithoutTouchingSaga() throws Exception {
        try (Harness h = new Harness(100, 100)) {
            LatchSink sink = new LatchSink();

            assertTrue(h.command(sink).dispatch(console(), new String[]{"claim", "Home"}, null));
            sink.awaitReply();

            assertEquals("command.land.claim.console", sink.replies.get(0).key);
            assertEquals(0, ledgerCount(h.ledger));
            assertTrue(h.economy.charges.isEmpty());
        }
    }

    @Test
    void blankNameRepliesUsageWithoutTouchingSaga() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (Harness h = new Harness(100, 100)) {
            h.selectSingleChunk(actor, world, 5, 5);
            LatchSink sink = new LatchSink();

            assertTrue(h.command(sink).dispatch(player(actor), new String[]{"claim"}, null));
            sink.awaitReply();

            assertEquals("command.land.usage", sink.replies.get(0).key);
            assertEquals(0, ledgerCount(h.ledger));
            assertTrue(h.economy.charges.isEmpty());
        }
    }

    @Test
    void unavailableSagaRepliesFailed() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (Harness h = new Harness(100, 100)) {
            h.selectSingleChunk(actor, world, 6, 6);
            ClaimCommandHandler unavailable = new ClaimCommandHandler(h.selections, null);
            Map<String, LandCommand.Handler> handlers = new HashMap<>(LandCommand.defaultStubHandlers());
            handlers.put("claim", unavailable);
            LatchSink sink = new LatchSink();
            LandCommand command = new LandCommand(handlers, (sender, pipeline) -> sink);

            assertTrue(command.dispatch(player(actor), new String[]{"claim", "Home"}, null));
            sink.awaitReply();

            assertEquals("command.land.claim.failed", sink.replies.get(0).key);
            assertEquals("claim.unavailable", sink.replies.get(0).vars.get("reason"));
            assertEquals(0, ledgerCount(h.ledger));
            assertTrue(h.economy.charges.isEmpty());
        }
    }

    @Test
    void claimPermissionDeniedNeverReachesHandler() throws Exception {
        UUID actor = UUID.randomUUID();
        try (Harness h = new Harness(100, 100)) {
            List<String> keys = new ArrayList<>();
            ReplySink recording = new ReplySink() {
                @Override
                public void reply(String messageKey, Map<String, Object> vars) {
                    keys.add(messageKey);
                }

                @Override
                public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
                    keys.add(messageKey);
                }
            };
            Map<String, LandCommand.Handler> handlers = new HashMap<>(LandCommand.defaultStubHandlers());
            handlers.put("claim", h.handler);
            LandCommand command = new LandCommand(handlers, (sender, pipeline) -> recording);
            Player denied = (Player) Proxy.newProxyInstance(
                    Player.class.getClassLoader(),
                    new Class[]{Player.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getUniqueId")) {
                            return actor;
                        }
                        if (method.getName().equals("hasPermission")) {
                            return !LandPermissions.CLAIM.equals(args[0]);
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

            assertTrue(command.dispatch(denied, new String[]{"claim", "Home"}, null));
            assertTrue(keys.contains("command.land.denied"));
            assertEquals(0, ledgerCount(h.ledger));
            assertTrue(h.economy.charges.isEmpty());
        }
    }
}

package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.claim.ClaimEconomy;
import com.smile.chunkland.claim.ClaimOutcome;
import com.smile.chunkland.claim.ClaimRejectedException;
import com.smile.chunkland.claim.ClaimRequest;
import com.smile.chunkland.claim.ClaimSaga;
import com.smile.chunkland.claim.ClaimValidator;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.claim.SnapshotClaimValidator;
import com.smile.chunkland.claim.WorldClaimPolicy;
import com.smile.chunkland.command.ClaimCommandHandler;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigLoader;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.config.ConfigService;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.config.ReloadDiff;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.protection.ProtectionEngine;
import com.smile.chunkland.runtime.index.LandRegistry;
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
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production wiring for the world claim gate.
 *
 * <p>A player claim in a {@code claim-enabled:false} world is rejected by the
 * formal saga assembly before any quota, reservation, ledger or economy side
 * effect; enabled worlds keep the established success path; unresolvable
 * worlds fail closed. Existing lands in a disabled world keep the
 * {@link ProtectionEngine} decision (wilderness stays vanilla {@code ALLOW}),
 * reload keeps the epoch/selection-invalidation contract, and
 * Expand/Shrink/Delete stay out of scope.
 */
class WorldPolicyProductionWiringTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    static final class FakeEconomy implements ClaimEconomy {
        record ChargeCall(UUID operationId, ClaimRequest request, Money price) {
        }

        final List<ChargeCall> charges = Collections.synchronizedList(new ArrayList<>());
        volatile boolean available = true;

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

        @Override
        public boolean isAvailable() {
            return available;
        }
    }

    static final class MutableYamlLoader implements ConfigLoader {
        final AtomicReference<String> yaml = new AtomicReference<>("worlds: {}\n");

        @Override
        public ChunkLandConfig load() {
            return ConfigSchema.parseYamlText(yaml.get());
        }

        @Override
        public String describe() {
            return "world-policy-test";
        }
    }

    final class Harness implements AutoCloseable {
        final PersistenceStore store;
        final OperationLedger ledger;
        final OwnerQuotaService quotas;
        final LogicalReservationRegistry reservations = new LogicalReservationRegistry();
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        final RuntimeRegistryRebuilder rebuilder;
        final SelectionSessionManager selections;
        final ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "world-policy-test");
            thread.setDaemon(true);
            return thread;
        });
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        final PricingTable pricing = PricingTable.of(List.of(
                PricingTier.of(5, new Money(100, EMC)),
                PricingTier.of(PricingTier.UNBOUNDED, new Money(200, EMC))));
        final Function<UUID, Optional<String>> names;

        Harness(Function<UUID, Optional<String>> names) {
            this.names = names;
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = new OperationLedger(store);
            quotas = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), new LimitSettings(100, 100, 128, 16), 0L, Map.of())));
            rebuilder = new RuntimeRegistryRebuilder(new SqliteLandRepository(store), registryStore);
            selections = new SelectionSessionManager(
                    (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                    SelectionVisualizationTaskController.noop(),
                    SelectionNotifier.noop(),
                    (SelectionClock) () -> NOW,
                    Duration.ofMinutes(10),
                    names::apply,
                    SelectionStructureRevisionLookup.unavailable());
        }

        ClaimSaga saga(WorldClaimPolicy policy) {
            return ChunkLandPlugin.buildClaimSaga(registryStore, selections, quotas,
                    pricing, reservations, ledger, economy, rebuilder, async,
                    SelectionStructureRevisionLookup.unavailable(), policy);
        }

        ClaimRequest playerClaim(UUID actor, UUID world, String name) {
            return new ClaimRequest(OwnerRef.player(actor), actor, world,
                    Set.of(new ChunkKey(world, 3, 4)), name);
        }

        int ledgerCount() {
            return ledger.findAll().toCompletableFuture().join().size();
        }

        void assertZeroSideEffects(OwnerRef owner, Set<ChunkKey> chunks) {
            assertTrue(economy.charges.isEmpty(), "disabled/unknown world must not charge");
            assertEquals(0, ledgerCount(), "disabled/unknown world must not write a ledger row");
            assertEquals(0, quotas.chunkCommitted(owner), "disabled/unknown world must not commit quota");
            assertEquals(0, quotas.landCommitted(owner), "disabled/unknown world must not commit quota");
            assertEquals(0, reservations.size(), "disabled/unknown world must not hold reservations");
            Set<String> keys = new java.util.HashSet<>();
            for (ChunkKey chunk : chunks) {
                keys.add(chunk.worldId() + ":" + chunk.chunkX() + ":" + chunk.chunkZ());
            }
            UUID probe = UUID.randomUUID();
            assertTrue(reservations.tryAcquire(keys, probe), "reservation keys must be free");
            reservations.release(keys, probe);
        }

        @Override
        public void close() {
            async.shutdownNow();
            store.close();
        }
    }

    private static ConfigService configService(String yaml, MutableYamlLoader loader) {
        loader.yaml.set(yaml);
        return new ConfigService(loader);
    }

    private static final String DISABLED_YAML =
            "worlds:\n  world:\n    claim-enabled: true\n  world_nether:\n    claim-enabled: false\n";

    // ------------------------------------------------------------------
    // Saga gate
    // ------------------------------------------------------------------

    @Test
    void disabledWorldPlayerClaimRejectedWithZeroSideEffects() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        MutableYamlLoader loader = new MutableYamlLoader();
        ConfigService config = configService(DISABLED_YAML, loader);
        try (Harness h = new Harness(id -> Optional.of("world_nether"))) {
            WorldClaimPolicy policy = ChunkLandPlugin.buildWorldClaimPolicy(config, h.names);
            ClaimRequest request = h.playerClaim(actor, world, "NetherHome");

            ClaimOutcome outcome = h.saga(policy).claim(request)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.REJECTED, outcome.status());
            assertEquals("world.claim_disabled", outcome.diagnosticKey());
            h.assertZeroSideEffects(owner, request.chunks());
        }
    }

    @Test
    void enabledWorldPlayerClaimStillSucceeds() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        MutableYamlLoader loader = new MutableYamlLoader();
        ConfigService config = configService(DISABLED_YAML, loader);
        try (Harness h = new Harness(id -> Optional.of("world"))) {
            WorldClaimPolicy policy = ChunkLandPlugin.buildWorldClaimPolicy(config, h.names);
            ClaimRequest request = h.playerClaim(actor, world, "Home");

            ClaimOutcome outcome = h.saga(policy).claim(request)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.SUCCESS, outcome.status(),
                    "enabled world must keep the established success path");
            assertFalse(h.economy.charges.isEmpty(), "enabled claim charges through Economy");
        }
    }

    @Test
    void unknownWorldFailsClosedWithZeroSideEffects() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        MutableYamlLoader loader = new MutableYamlLoader();
        ConfigService config = configService(DISABLED_YAML, loader);
        try (Harness h = new Harness(id -> Optional.empty())) {
            WorldClaimPolicy policy = ChunkLandPlugin.buildWorldClaimPolicy(config, h.names);
            ClaimRequest request = h.playerClaim(actor, world, "LostHome");

            ClaimOutcome outcome = h.saga(policy).claim(request)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.REJECTED, outcome.status());
            assertEquals("world.unknown", outcome.diagnosticKey());
            h.assertZeroSideEffects(owner, request.chunks());
        }
    }

    @Test
    void throwingNameResolverFailsClosed() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        MutableYamlLoader loader = new MutableYamlLoader();
        ConfigService config = configService(DISABLED_YAML, loader);
        Function<UUID, Optional<String>> throwing = id -> {
            throw new RuntimeException("name service boom");
        };
        try (Harness h = new Harness(throwing)) {
            WorldClaimPolicy policy = ChunkLandPlugin.buildWorldClaimPolicy(config, h.names);
            ClaimOutcome outcome = h.saga(policy)
                    .claim(h.playerClaim(actor, world, "LostHome"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.REJECTED, outcome.status());
            assertEquals("world.unknown", outcome.diagnosticKey());
        }
    }

    @Test
    void nullPolicyInputsFailClosed() {
        MutableYamlLoader loader = new MutableYamlLoader();
        ConfigService config = configService(DISABLED_YAML, loader);
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        for (WorldClaimPolicy policy : List.of(
                ChunkLandPlugin.buildWorldClaimPolicy(null, id -> Optional.of("world")),
                ChunkLandPlugin.buildWorldClaimPolicy(config, null))) {
            SnapshotClaimValidator validator = new SnapshotClaimValidator(
                    new LandRegistryStore(),
                    ClaimValidator.RevisionSource.none(),
                    ClaimValidator.SessionGenerationSource.none(),
                    chunk -> 64,
                    owner -> 0L,
                    ClaimValidator.StructureRevisionSource.none(),
                    policy);
            ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                    () -> validator.validate(new ClaimRequest(OwnerRef.player(actor), actor, world,
                            Set.of(new ChunkKey(world, 3, 4)), "Home")));
            assertEquals("world.unknown", rejected.diagnosticKey());
        }
    }

    @Test
    void serverClaimBypassesDisabledWorld() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        MutableYamlLoader loader = new MutableYamlLoader();
        ConfigService config = configService(DISABLED_YAML, loader);
        try (Harness h = new Harness(id -> Optional.of("world_nether"))) {
            WorldClaimPolicy policy = ChunkLandPlugin.buildWorldClaimPolicy(config, h.names);
            ClaimRequest request = new ClaimRequest(OwnerRef.server(), actor, world,
                    Set.of(new ChunkKey(world, 3, 4)), "Spawn");

            ClaimOutcome outcome = h.saga(policy).claim(request)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.SUCCESS, outcome.status(),
                    "server-owned land keeps its existing semantics in a disabled world");
        }
    }

    // ------------------------------------------------------------------
    // Existing land + wilderness in a disabled world
    // ------------------------------------------------------------------

    @Test
    void existingLandInDisabledWorldKeepsEngineProtection() {
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandName name = LandName.of("NetherKeep");
        LandSnapshot land = new LandSnapshot(landId, name.displayName(), name.nameKey(),
                OwnerRef.player(owner), world, Set.of(new ChunkKey(world, 0, 0)),
                List.of(), 0L, 0L, NOW, NOW);
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land)));
        ProtectionEngine engine = ChunkLandPlugin.buildProtectionEngine(store);

        // The world policy never reaches the engine: the stranger is still
        // denied inside the existing land, wilderness stays vanilla ALLOW,
        // and the registry still carries the land (nothing deleted or hidden).
        assertEquals(PermissionState.DENY,
                engine.decideAt(stranger, world, 0, 0, ProtectionActionType.DELETE_LAND).outcome());
        assertEquals(PermissionState.ALLOW,
                engine.decideAt(stranger, world, 9, 9, ProtectionActionType.DELETE_LAND).outcome());
        assertNotNull(store.snapshot().land(landId),
                "disabling claim must not delete or hide existing lands");
    }

    // ------------------------------------------------------------------
    // Reload epoch + selection invalidation
    // ------------------------------------------------------------------

    private static SelectionSession startSession(SelectionSessionManager manager, UUID player, UUID world) {
        SelectionSession initial = SelectionSession.initial(
                player, world, SelectionMode.CREATE_LAND,
                Optional.empty(), Optional.empty(),
                Optional.of(new SelectionPoint(world, 0, 64, 0)),
                Optional.of(new SelectionPoint(world, 15, 64, 15)),
                0, NOW);
        return manager.start(initial);
    }

    @Test
    void reloadWorldPolicyChangeBumpsEpochAndInvalidatesOnlyAffected() {
        UUID worldA = UUID.randomUUID();
        UUID worldB = UUID.randomUUID();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        Map<UUID, String> names = Map.of(worldA, "world_a", worldB, "world_b");
        SelectionSessionManager manager = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                (SelectionClock) () -> NOW,
                Duration.ofMinutes(10),
                id -> Optional.ofNullable(names.get(id)),
                SelectionStructureRevisionLookup.unavailable());
        startSession(manager, playerA, worldA);
        startSession(manager, playerB, worldB);

        MutableYamlLoader loader = new MutableYamlLoader();
        ConfigService config = configService(
                "worlds:\n  world_a:\n    claim-enabled: true\n  world_b:\n    claim-enabled: true\n", loader);
        long epochBefore = config.current().worldPolicyEpoch("world_b");
        List<ReloadDiff> received = new ArrayList<>();
        config.addListener(received::add);
        loader.yaml.set("worlds:\n  world_a:\n    claim-enabled: true\n  world_b:\n    claim-enabled: false\n");
        ChunkLandConfig next = config.reload();

        assertEquals(epochBefore + 1, next.worldPolicyEpoch("world_b").longValue());
        assertEquals(1, received.size());
        assertEquals(Set.of("world_b"), received.get(0).changedWorlds());
        assertFalse(received.get(0).globalPolicyChanged());
        manager.onConfigReload(received.get(0));

        assertTrue(manager.sessionFor(playerA).isPresent(),
                "sessions in untouched worlds must survive a world-only policy change");
        assertTrue(manager.sessionFor(playerB).isEmpty(),
                "sessions in the changed world must be invalidated");
    }

    @Test
    void verticalModeOnlyChangeIsWorldScoped() {
        UUID worldA = UUID.randomUUID();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        UUID worldB = UUID.randomUUID();
        Map<UUID, String> names = Map.of(worldA, "world_a", worldB, "world_b");
        SelectionSessionManager manager = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                (SelectionClock) () -> NOW,
                Duration.ofMinutes(10),
                id -> Optional.ofNullable(names.get(id)),
                SelectionStructureRevisionLookup.unavailable());
        startSession(manager, playerA, worldA);
        startSession(manager, playerB, worldB);

        MutableYamlLoader loader = new MutableYamlLoader();
        ConfigService config = configService(
                "worlds:\n  world_a:\n    claim-enabled: true\n  world_b:\n    claim-enabled: true\n", loader);
        List<ReloadDiff> received = new ArrayList<>();
        config.addListener(received::add);
        loader.yaml.set("worlds:\n  world_a:\n    claim-enabled: true\n    vertical-mode: FULL_HEIGHT\n"
                + "  world_b:\n    claim-enabled: true\n");
        config.reload();

        assertEquals(1, received.size());
        assertFalse(received.get(0).globalPolicyChanged(),
                "a vertical-mode-only change must not read as a global policy change");
        assertEquals(Set.of("world_a"), received.get(0).changedWorlds());
        manager.onConfigReload(received.get(0));
        assertTrue(manager.sessionFor(playerA).isEmpty());
        assertTrue(manager.sessionFor(playerB).isPresent());
    }

    // ------------------------------------------------------------------
    // Scope guard: Delete left the stub pool with expand and shrink; Expand
    // is wired by CL-M2-21
    // ------------------------------------------------------------------

    @Test
    void deleteStaysAnUnimplementedStubWhileExpandIsWired() {
        SelectionSessionManager selections = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                (SelectionClock) () -> NOW,
                Duration.ofMinutes(10),
                id -> Optional.empty(),
                SelectionStructureRevisionLookup.unavailable());
        ClaimCommandHandler.ClaimRunner runner = request ->
                CompletableFuture.completedFuture(ClaimOutcome.failed("claim.failed"));
        Map<String, LandCommand.Handler> handlers = ChunkLandPlugin.buildLandHandlers(
                selections, runner, null, SelectionStructureRevisionLookup.unavailable(), null);

        List<String> keys = new ArrayList<>();
        Map<String, Map<String, Object>> varsByKey = new HashMap<>();
        ReplySink sink = new ReplySink() {
            @Override
            public void reply(String messageKey, Map<String, Object> vars) {
                keys.add(messageKey);
                varsByKey.put(messageKey, vars);
            }

            @Override
            public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
                keys.add(messageKey);
                varsByKey.put(messageKey, vars);
            }
        };
        // Delete left the stub pool with expand and shrink: without a runner
        // it fails closed as unavailable instead of pretending it is coming
        // soon. Delete is owned by the land-delete flow; this scope guard only
        // pins the fail-closed shape while unwired.
        assertNotNull(handlers.get("delete"), "delete must stay registered");
        handlers.get("delete").handle(null, new String[]{"delete"}, sink);
        assertEquals(List.of("command.land.delete.failed"), keys,
                "delete must fail closed while unwired");
        assertEquals("delete.unavailable",
                varsByKey.get("command.land.delete.failed").get("reason"));
        // Expand left the stub pool: without a runner it fails closed as
        // unavailable instead of pretending it is coming soon.
        assertNotNull(handlers.get("expand"), "expand must stay registered");
        handlers.get("expand").handle(null, new String[]{"expand"}, sink);
        assertEquals("command.land.expand.failed", keys.get(1));
        assertEquals("expand.unavailable", varsByKey.get("command.land.expand.failed").get("reason"));
        // Shrink left the stub pool with expand: without a runner both aliases
        // fail closed as unavailable instead of pretending they are coming
        // soon. Shrink is owned by the shrink task; this scope guard only pins
        // the fail-closed shape while unwired.
        assertNotNull(handlers.get("shrink"), "shrink must stay registered");
        assertTrue(handlers.get("shrink") == handlers.get("unclaim"),
                "shrink and unclaim must share one handler");
        handlers.get("shrink").handle(null, new String[]{"shrink"}, sink);
        assertEquals("command.land.shrink.failed", keys.get(2));
        assertEquals("shrink.unavailable", varsByKey.get("command.land.shrink.failed").get("reason"));
    }

    // ------------------------------------------------------------------
    // Bilingual world-policy copy
    // ------------------------------------------------------------------

    @Test
    void worldPolicyCopyRendersBilingualWithoutRawKey() throws Exception {
        Map<String, String> outerByKey = Map.of(
                "world.claim_disabled", "command.land.claim.rejected",
                "world.unknown", "command.land.claim.rejected");
        for (Map.Entry<String, String> entry : outerByKey.entrySet()) {
            String diagnostic = entry.getKey();
            String outer = entry.getValue();

            CapturingSender enSender = new CapturingSender();
            com.smile.chunkland.message.ChunkLandMessagePipeline enPipe =
                    buildPipeline(Locale.US, enSender);
            org.bukkit.entity.Player enPlayer = playerWithLocale(UUID.randomUUID(), Locale.US);
            new com.smile.chunkland.command.PipelineReplySink(enPlayer, enPipe)
                    .reply(outer, Map.of("reason", diagnostic));
            assertNotNull(enSender.lastChat, "player path must send for " + diagnostic);
            String enPlain = plain(enSender.lastChat);
            assertFalse(enPlain.contains(diagnostic), "en output leaks raw key: " + enPlain);

            Locale zh = Locale.forLanguageTag("zh-TW");
            CapturingSender zhSender = new CapturingSender();
            com.smile.chunkland.message.ChunkLandMessagePipeline zhPipe = buildPipeline(zh, zhSender);
            org.bukkit.entity.Player zhPlayer = playerWithLocale(UUID.randomUUID(), zh);
            new com.smile.chunkland.command.PipelineReplySink(zhPlayer, zhPipe)
                    .reply(outer, Map.of("reason", diagnostic), zh);
            assertNotNull(zhSender.lastChat, "player path must send for " + diagnostic + " (zh)");
            String zhPlain = plain(zhSender.lastChat);
            assertFalse(zhPlain.contains(diagnostic), "zh output leaks raw key: " + zhPlain);
            assertNotEquals(enPlain, zhPlain, "zh render must differ from en for " + diagnostic);
        }
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(component);
    }

    private static org.bukkit.entity.Player playerWithLocale(UUID id, Locale locale) {
        return (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.entity.Player.class.getClassLoader(),
                new Class<?>[] {org.bukkit.entity.Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return id;
                    }
                    if (name.equals("locale")) {
                        return locale;
                    }
                    if (name.equals("isOnline")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "TestPlayer";
                    }
                    if (name.equals("hashCode")) {
                        return id.hashCode();
                    }
                    if (name.equals("equals") && args != null && args.length == 1) {
                        return proxy == args[0];
                    }
                    if (name.equals("toString")) {
                        return "FakePlayer:" + id;
                    }
                    Class<?> ret = method.getReturnType();
                    if (ret == void.class) {
                        return null;
                    }
                    if (ret == boolean.class) {
                        return false;
                    }
                    if (ret == int.class) {
                        return 0;
                    }
                    if (ret == long.class) {
                        return 0L;
                    }
                    if (ret == double.class) {
                        return 0.0;
                    }
                    return null;
                });
    }

    private static com.smile.chunkland.message.ChunkLandMessagePipeline buildPipeline(
            Locale def, CapturingSender sender) throws Exception {
        Map<Locale, Map<String, String>> data = new HashMap<>();
        for (String tag : new String[] {"en_US", "zh_TW"}) {
            org.bukkit.configuration.file.YamlConfiguration cfg =
                    new org.bukkit.configuration.file.YamlConfiguration();
            cfg.load(new java.io.File("src/main/resources/lang/" + tag + ".yml"));
            Map<String, String> flat = new HashMap<>();
            for (String key : cfg.getKeys(true)) {
                Object value = cfg.get(key);
                if (value instanceof String text) {
                    flat.put(key, text);
                }
            }
            Locale locale = tag.equals("en_US") ? Locale.US : Locale.forLanguageTag("zh-TW");
            data.put(locale, flat);
        }
        data.put(new Locale("en", "US"), data.get(Locale.US));
        com.smile.chunkland.message.ChunkLandMessagePipeline.LangProvider provider =
                (locale, key) -> {
                    Map<String, String> scoped = data.get(locale);
                    if (scoped != null && scoped.containsKey(key)) {
                        return Optional.of(scoped.get(key));
                    }
                    Map<String, String> fallback = data.get(Locale.US);
                    if (fallback != null && fallback.containsKey(key)) {
                        return Optional.of(fallback.get(key));
                    }
                    return Optional.empty();
                };
        com.smile.chunkland.message.ChunkLandMessagePipeline.MessageParser parser =
                (template, vars) -> {
                    net.kyori.adventure.text.minimessage.tag.resolver.TagResolver resolver;
                    if (vars == null || vars.isEmpty()) {
                        resolver = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.empty();
                    } else {
                        var resolvers = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[vars.size()];
                        int i = 0;
                        for (var nested : vars.entrySet()) {
                            resolvers[i++] = net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                                    nested.getKey(), String.valueOf(nested.getValue()));
                        }
                        resolver = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(resolvers);
                    }
                    return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize(template, resolver);
                };
        var ctor = com.smile.chunkland.message.ChunkLandMessagePipeline.class.getDeclaredConstructor(
                com.smile.chunkland.message.ChunkLandMessagePipeline.PipelineSender.class,
                com.smile.chunkland.message.ChunkLandMessagePipeline.MessageParser.class,
                com.smile.chunkland.message.ChunkLandMessagePipeline.LangProvider.class,
                com.smile.acelib.bedrock.BedrockService.class,
                Locale.class);
        ctor.setAccessible(true);
        return (com.smile.chunkland.message.ChunkLandMessagePipeline) ctor.newInstance(
                sender, parser, provider, null, def);
    }

    static final class CapturingSender
            implements com.smile.chunkland.message.ChunkLandMessagePipeline.PipelineSender {
        volatile net.kyori.adventure.text.Component lastChat;

        @Override
        public void sendChat(org.bukkit.entity.Player player,
                net.kyori.adventure.text.Component message) {
            lastChat = message;
        }

        @Override
        public void sendChatWithFallback(org.bukkit.entity.Player player,
                net.kyori.adventure.text.Component message, Locale locale) {
            lastChat = message;
        }

        @Override
        public void sendActionBar(org.bukkit.entity.Player player,
                net.kyori.adventure.text.Component message) {
        }

        @Override
        public void sendActionBarWithFallback(org.bukkit.entity.Player player,
                net.kyori.adventure.text.Component message, Locale locale) {
        }

        @Override
        public void sendTitle(org.bukkit.entity.Player player,
                net.kyori.adventure.text.Component title,
                net.kyori.adventure.text.Component subtitle) {
        }

        @Override
        public void sendTitleWithFallback(org.bukkit.entity.Player player,
                net.kyori.adventure.text.Component title,
                net.kyori.adventure.text.Component subtitle, Locale locale) {
        }

        @Override
        public void broadcastWithFallback(net.kyori.adventure.text.Component message, Locale locale) {
        }
    }
}

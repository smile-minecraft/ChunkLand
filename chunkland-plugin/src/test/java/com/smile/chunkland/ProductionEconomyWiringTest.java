package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.claim.ClaimOutcome;
import com.smile.chunkland.claim.ClaimRequest;
import com.smile.chunkland.claim.ClaimSaga;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.claim.VaultClaimEconomy;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.economy.BukkitVaultBridge;
import com.smile.chunkland.economy.UnavailableVaultBridge;
import com.smile.chunkland.economy.VaultBridge;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
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
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * Production economy wiring: Vault discovery reaches the bootstrap and the
 * sagas, typed pricing drives charges, and server land never touches Economy.
 */
class ProductionEconomyWiringTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    static final class CountingBridge implements VaultBridge {
        final String providerId;
        final List<Double> withdraws = new CopyOnWriteArrayList<>();
        final List<Double> deposits = new CopyOnWriteArrayList<>();

        CountingBridge(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public Response withdraw(UUID playerId, double amount, UUID operationId) {
            withdraws.add(amount);
            return Response.ok();
        }

        @Override
        public Response deposit(UUID playerId, double amount, UUID operationId) {
            deposits.add(amount);
            return Response.ok();
        }
    }

    private static Economy fakeEconomy(boolean enabled, String name) {
        return (Economy) Proxy.newProxyInstance(ProductionEconomyWiringTest.class.getClassLoader(),
                new Class<?>[] {Economy.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> enabled;
                    case "getName" -> name;
                    default -> null;
                });
    }

    private static Plugin fakePlugin() {
        return (Plugin) Proxy.newProxyInstance(ProductionEconomyWiringTest.class.getClassLoader(),
                new Class<?>[] {Plugin.class}, (proxy, method, args) -> null);
    }

    private static ServicesManager servicesReturning(Object registration) {
        return (ServicesManager) Proxy.newProxyInstance(
                ProductionEconomyWiringTest.class.getClassLoader(),
                new Class<?>[] {ServicesManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getRegistration")
                            && args != null && args.length == 1) {
                        return registration;
                    }
                    return null;
                });
    }

    private static PricingTable defaultTiers() {
        return PricingTable.of(List.of(
                PricingTier.of(20, new Money(100, EMC)),
                PricingTier.of(50, new Money(200, EMC)),
                PricingTier.of(100, new Money(400, EMC)),
                PricingTier.of(PricingTier.UNBOUNDED, new Money(800, EMC))));
    }

    private ClaimSaga buildSaga(LandRegistryStore registryStore, SelectionSessionManager selections,
            OwnerQuotaService quotas, PricingTable pricing, LogicalReservationRegistry reservations,
            OperationLedger ledger, VaultClaimEconomy economy,
            RuntimeRegistryRebuilder rebuilder, ExecutorService async) {
        return ChunkLandPlugin.buildClaimSaga(registryStore, selections, quotas, pricing,
                reservations, ledger, economy, rebuilder, async);
    }

    private ClaimRequest playerClaimRequest(SelectionSessionManager selections, UUID actor, UUID world)
            throws Exception {
        SelectionSession initial = SelectionSession.initial(
                actor, world, SelectionMode.CREATE_LAND,
                Optional.empty(), Optional.empty(),
                Optional.of(new SelectionPoint(world, 0, 64, 0)),
                Optional.of(new SelectionPoint(world, 16, 64, 16)),
                0, NOW);
        SelectionSession stamped = selections.start(initial);
        SelectionSession live = selections.updateSelection(actor, stamped, new SelectionUpdate(
                        initial.pointA(), initial.pointB(),
                        Set.of(new ChunkKey(world, 3, 4)), Map.of()))
                .orElseThrow();
        return new ClaimRequest(OwnerRef.player(actor), actor, world,
                live.selectedChunks(), "Home", live.selectionRevision());
    }

    private SelectionSessionManager newSelections() {
        return new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                (SelectionClock) () -> NOW,
                Duration.ofMinutes(10),
                ignored -> Optional.of("world"),
                SelectionStructureRevisionLookup.unavailable());
    }

    private OwnerQuotaService newQuotas() {
        return new OwnerQuotaService(new LimitResolver(
                new ChunkLandConfig(Map.of(), new com.smile.chunkland.config.LimitSettings(
                        100, 100, 128, 16), 0L, Map.of())));
    }

    @Test
    void liveRegistrationResolvesToUsableBridge() {
        RegisteredServiceProvider<Economy> registration = new RegisteredServiceProvider<>(
                Economy.class, fakeEconomy(true, "AceEconomy"), ServicePriority.Normal, fakePlugin());

        VaultBridge bridge =
                ChunkLandPlugin.resolveEconomyBridge(servicesReturning(registration), id -> null);

        assertInstanceOf(BukkitVaultBridge.class, bridge);
        assertTrue(bridge.isAvailable());
        assertEquals("vault:AceEconomy", bridge.providerId());
    }

    @Test
    void absentServiceStaysUnavailable() {
        VaultBridge bridge = ChunkLandPlugin.resolveEconomyBridge(servicesReturning(null), id -> null);

        assertInstanceOf(UnavailableVaultBridge.class, bridge);
        assertFalse(bridge.isAvailable());
        assertEquals("economy.unavailable",
                bridge.withdraw(UUID.randomUUID(), 1.0, UUID.randomUUID()).errorKey());
    }

    @Test
    void bootstrapCarriesDiscoveredProviderIdentity() {
        Path databasePath = temp.resolve("wiring-bootstrap.db");
        CountingBridge bridge = new CountingBridge("vault:AceEconomy");

        ClaimStartupBootstrap bootstrap = ClaimStartupBootstrap.start(
                databasePath, new LandRegistryStore(), Logger.getLogger("Test"), bridge, EMC);
        try {
            assertTrue(bootstrap.economy().isAvailable());
            assertEquals("vault:AceEconomy", bootstrap.economy().providerId());
        } finally {
            bootstrap.close();
        }
    }

    @Test
    void playerClaimUsesTypedPriceAndRecordsProvider() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        CountingBridge bridge = new CountingBridge("vault:AceEconomy");
        VaultClaimEconomy economy = new VaultClaimEconomy(bridge, EMC);
        ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "wiring-claim-test");
            thread.setDaemon(true);
            return thread;
        });
        try (PersistenceStore store = PersistenceStore.open(temp.resolve("wiring-claim.db"))) {
            OperationLedger ledger = new OperationLedger(store);
            OwnerQuotaService quotas = newQuotas();
            LogicalReservationRegistry reservations = new LogicalReservationRegistry();
            LandRegistryStore registryStore = new LandRegistryStore();
            RuntimeRegistryRebuilder rebuilder =
                    new RuntimeRegistryRebuilder(new SqliteLandRepository(store), registryStore);
            SelectionSessionManager selections = newSelections();
            ClaimSaga saga = buildSaga(registryStore, selections, quotas, defaultTiers(),
                    reservations, ledger, economy, rebuilder, async);

            ClaimOutcome outcome = saga.claim(playerClaimRequest(selections, actor, world))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.SUCCESS, outcome.status());
            assertEquals(List.of(1.0), bridge.withdraws, "one charge at the tier-1 price");
            List<LedgerEntry> rows = ledger.findAll().toCompletableFuture().join();
            assertEquals(1, rows.size());
            assertEquals("ACTIVE", rows.get(0).state());
            assertEquals("vault:AceEconomy", rows.get(0).economyProviderId());
            assertEquals(100L, rows.get(0).priceMinorUnits());
        } finally {
            async.shutdownNow();
        }
    }

    @Test
    void serverLandClaimMakesZeroEconomyCalls() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        CountingBridge bridge = new CountingBridge("vault:AceEconomy");
        VaultClaimEconomy economy = new VaultClaimEconomy(bridge, EMC);
        ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "wiring-server-test");
            thread.setDaemon(true);
            return thread;
        });
        try (PersistenceStore store = PersistenceStore.open(temp.resolve("wiring-server.db"))) {
            OperationLedger ledger = new OperationLedger(store);
            OwnerQuotaService quotas = newQuotas();
            LogicalReservationRegistry reservations = new LogicalReservationRegistry();
            LandRegistryStore registryStore = new LandRegistryStore();
            RuntimeRegistryRebuilder rebuilder =
                    new RuntimeRegistryRebuilder(new SqliteLandRepository(store), registryStore);
            SelectionSessionManager selections = newSelections();
            ClaimSaga saga = buildSaga(registryStore, selections, quotas, defaultTiers(),
                    reservations, ledger, economy, rebuilder, async);
            SelectionSession initial = SelectionSession.initial(
                    actor, world, SelectionMode.CREATE_LAND,
                    Optional.empty(), Optional.empty(),
                    Optional.of(new SelectionPoint(world, 0, 64, 0)),
                    Optional.of(new SelectionPoint(world, 16, 64, 16)),
                    0, NOW);
            SelectionSession stamped = selections.start(initial);
            SelectionSession live = selections.updateSelection(actor, stamped, new SelectionUpdate(
                            initial.pointA(), initial.pointB(),
                            Set.of(new ChunkKey(world, 5, 6)), Map.of()))
                    .orElseThrow();
            ClaimRequest request = new ClaimRequest(OwnerRef.server(), actor, world,
                    live.selectedChunks(), "Spawn", live.selectionRevision());

            ClaimOutcome outcome =
                    saga.claim(request).toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.SUCCESS, outcome.status());
            assertTrue(bridge.withdraws.isEmpty(), "server land must never charge");
            assertTrue(bridge.deposits.isEmpty(), "server land must never refund");
            List<LedgerEntry> rows = ledger.findAll().toCompletableFuture().join();
            assertEquals(1, rows.size());
            assertEquals("ACTIVE", rows.get(0).state());
        } finally {
            async.shutdownNow();
        }
    }

    @Test
    void bootstrapRefundUsesConfiguredCurrencyScale() throws Exception {
        Currency milliEmc = Currency.of("TST", 3);
        UUID operationId = UUID.randomUUID();
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000021");
        UUID world = UUID.fromString("00000000-0000-0000-0000-000000000022");
        Path databasePath = temp.resolve("wiring-currency.db");
        try (PersistenceStore store = PersistenceStore.open(databasePath)) {
            OperationLedger ledger = new OperationLedger(store);
            OperationPayload payload = OperationPayload.claim(operationId, actor, world,
                    new LandId(UUID.nameUUIDFromBytes((operationId + "-land").getBytes())),
                    List.of(new OperationPayload.Chunk(new ChunkKey(world, 3, 8), 12,
                            UUID.nameUUIDFromBytes((operationId + "-lot").getBytes()), 417L)),
                    417L, "vault:FakeEco", Instant.parse("2026-03-01T00:00:00Z"), "Recovery land");
            ledger.createPaymentPending(payload).toCompletableFuture().get(10, TimeUnit.SECONDS);
            ledger.transitionToCharged(operationId, "charge:" + operationId,
                            Instant.parse("2026-03-01T00:00:00Z"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        CountingBridge bridge = new CountingBridge("vault:FakeEco");

        ClaimStartupBootstrap bootstrap = ClaimStartupBootstrap.start(
                databasePath, new LandRegistryStore(), Logger.getLogger("Test"), bridge, milliEmc);
        try {
            bootstrap.scanFuture().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(List.of(0.417), bridge.deposits,
                    "417 minor at scale 3 refunds as 0.417 major, not 4.17 at scale 2");
        } finally {
            bootstrap.close();
        }
    }

    @Test
    void economyCurrencyFollowsTypedConfig() {
        ChunkLandConfig withEconomy = ConfigSchema.parseYamlText("""
                economy:
                  currency:
                    code: EMC
                    scale: 2
                  pricing:
                    tiers:
                      - until: 20
                        price-per-chunk: 1.00
                      - until: unbounded
                        price-per-chunk: 2.00
                """);
        ChunkLandConfig withoutEconomy =
                ConfigSchema.parseYamlText("limits:\n  max-lands-per-player: 5\n");

        assertEquals(EMC, ChunkLandPlugin.economyCurrency(withEconomy));
        assertEquals(ClaimStartupBootstrap.CLAIM_CURRENCY,
                ChunkLandPlugin.economyCurrency(withoutEconomy));
    }

    @Test
    void bundledConfigCarriesAdjustableEmcDefaults() throws Exception {
        ChunkLandConfig config;
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            if (in == null) {
                throw new IllegalStateException("/config.yml not on test classpath");
            }
            config = ConfigSchema.parseAndValidate(new Yaml().load(in));
        }

        assertTrue(config.economy() != null, "bundled config must carry an economy section");
        assertEquals(EMC, config.economy().currency());
        assertEquals(4, config.economy().pricing().tiers().size());
        assertEquals(2000L, config.economy().pricing().priceForClaim(0, 20).minorUnits());
        assertEquals(3000L, config.economy().pricing().priceForClaim(0, 25).minorUnits());
        assertEquals(800L, config.economy().pricing().marginalPrice(100).minorUnits());
    }
}

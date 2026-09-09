package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.ClaimCommit;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RecoveryResult;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the production startup caller: {@code onEnable} opens persistence,
 * rebuilds a durable {@code DOMAIN_COMMITTED} row into the shared protection
 * store through the real bootstrap (never by calling the recovery factory
 * directly), and {@code onDisable} closes everything without resurrection.
 */
class ClaimStartupRecoveryTest {

    @TempDir Path temp;

    private static final Instant TIME = Instant.parse("2026-03-01T00:00:00Z");

    @SuppressWarnings("unchecked")
    private static ChunkLandPlugin allocatePlugin() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        ChunkLandPlugin plugin = (ChunkLandPlugin) unsafe.allocateInstance(ChunkLandPlugin.class);
        for (var field : ChunkLandPlugin.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            long offset = unsafe.objectFieldOffset(field);
            Object value = unsafe.getObject(plugin, offset);
            if (field.getType() == java.util.Optional.class && value == null) {
                unsafe.putObject(plugin, offset, java.util.Optional.empty());
            } else if (field.getType() == com.smile.chunkland.adapter.AceLibBridge.class && value == null) {
                unsafe.putObject(plugin, offset, new com.smile.chunkland.adapter.AceLibBridge());
            }
        }
        return plugin;
    }

    private static com.smile.acelib.AceLibApi readyApi() {
        return com.smile.acelib.AceLibApi.ready(
                "1.2.0",
                com.smile.acelib.platform.Platform.PAPER,
                () -> true,
                () -> {});
    }

    private static void wireServer(ChunkLandPlugin plugin, File dataFolder, PluginManager pluginManager)
            throws Exception {
        com.smile.acelib.AceLibApi ready = readyApi();
        com.smile.acelib.AceLibApi.AceLibProvider provider =
                (com.smile.acelib.AceLibApi.AceLibProvider) Proxy.newProxyInstance(
                        com.smile.acelib.AceLibApi.AceLibProvider.class.getClassLoader(),
                        new Class[]{com.smile.acelib.AceLibApi.AceLibProvider.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("api")) {
                                return ready;
                            }
                            return null;
                        });
        org.bukkit.plugin.ServicesManager servicesManager =
                (org.bukkit.plugin.ServicesManager) Proxy.newProxyInstance(
                        org.bukkit.plugin.ServicesManager.class.getClassLoader(),
                        new Class[]{org.bukkit.plugin.ServicesManager.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getRegistration")) {
                                return new org.bukkit.plugin.RegisteredServiceProvider<>(
                                        com.smile.acelib.AceLibApi.AceLibProvider.class, provider,
                                        org.bukkit.plugin.ServicePriority.Normal, null);
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
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class[]{Server.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getServicesManager")) {
                        return servicesManager;
                    }
                    if (name.equals("getPluginManager")) {
                        return pluginManager;
                    }
                    if (name.equals("getLogger")) {
                        return java.util.logging.Logger.getLogger("Test");
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
        Field serverField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(plugin, server);
        Field loggerField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        loggerField.set(plugin, java.util.logging.Logger.getLogger("Test"));
        try {
            Field dataFolderField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("dataFolder");
            dataFolderField.setAccessible(true);
            dataFolderField.set(plugin, dataFolder);
        } catch (NoSuchFieldException ignored) {
        }
    }

    private static PluginManager quietPluginManager() {
        return (PluginManager) Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class[]{PluginManager.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("registerEvents") || name.equals("disablePlugin")) {
                        return null;
                    }
                    if (name.equals("isPluginEnabled")) {
                        return true;
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

    private static OperationPayload payload(UUID operationId, Instant time) {
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID world = UUID.fromString("00000000-0000-0000-0000-000000000012");
        String displayName = "Startup land " + operationId.toString().substring(28);
        return OperationPayload.claim(operationId, actor, world,
                new LandId(UUID.nameUUIDFromBytes((operationId + "-land").getBytes())),
                List.of(new OperationPayload.Chunk(new ChunkKey(world, 7, -3), 12,
                        UUID.nameUUIDFromBytes((operationId + "-lot").getBytes()), 417L)),
                417L, "startup-economy", time, displayName);
    }

    private static ClaimCommit commit(OperationPayload payload) {
        ChunkKey chunk = payload.chunkSet().get(0).chunk();
        LandId landId = payload.targetLandId();
        LandSnapshot land = new LandSnapshot(landId, payload.landDisplayName(),
                LandName.normalize(payload.landDisplayName()), OwnerRef.player(payload.actorUuid()),
                payload.worldUuid(), Set.of(chunk), List.of(), 0, 0,
                payload.createdAt(), payload.updatedAt());
        AuditEntry audit = new AuditEntry(0, payload.updatedAt(), payload.actorUuid(), "LAND_CREATE",
                landId, payload.worldUuid(), null, payload.schemaVersion(), null,
                payload.toJson(), "{}", List.of(chunk));
        return new ClaimCommit(payload.operationId(), land, payload.chunkSet(), audit);
    }

    private static UUID seedDomainCommitted(Path databasePath) throws Exception {
        UUID operationId = UUID.randomUUID();
        try (PersistenceStore store = PersistenceStore.open(databasePath)) {
            OperationLedger ledger = new OperationLedger(store);
            OperationPayload payload = payload(operationId, TIME);
            ledger.createPaymentPending(payload).toCompletableFuture().get(10, TimeUnit.SECONDS);
            ledger.transitionToCharged(operationId, "startup-ref", payload.updatedAt())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            ledger.commitClaimAtomically(commit(payload)).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("DOMAIN_COMMITTED",
                    ledger.find(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS).state());
        }
        return operationId;
    }

    @Test
    void onEnableStartsRecoveryThroughProductionCaller() throws Exception {
        File dataFolder = temp.resolve("data").toFile();
        assertTrue(dataFolder.mkdirs());
        Path databasePath = dataFolder.toPath().resolve(ClaimStartupBootstrap.DATABASE_FILE_NAME);
        UUID operationId = seedDomainCommitted(databasePath);

        ChunkLandPlugin plugin = allocatePlugin();
        wireServer(plugin, dataFolder, quietPluginManager());

        plugin.onEnable();

        ClaimStartupBootstrap bootstrap = plugin.getClaimStartup();
        assertNotNull(bootstrap, "onEnable must start the production recovery caller");
        List<RecoveryResult> results =
                bootstrap.scanFuture().toCompletableFuture().get(15, TimeUnit.SECONDS);
        assertEquals(1, results.size());
        assertEquals("ACTIVE", results.get(0).resultingState());
        LedgerEntry row = bootstrap.ledger().find(operationId)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals("ACTIVE", row.state());
        assertEquals(0, row.compensationAttempts());
        OperationPayload payload = OperationPayload.fromJson(row.payloadJson());
        assertNotNull(plugin.getProtectionStore().snapshot().findLand(payload.worldUuid(), 7, -3),
                "shared protection store must publish the rebuilt runtime");

        plugin.onDisable();
        assertNull(plugin.getClaimStartup(), "onDisable must release the bootstrap");
        assertNull(plugin.getProtectionStore(), "onDisable must clear the shared store");
        assertTrue(bootstrap.isClosed(), "bootstrap close must be observed");
        assertTrue(bootstrap.store().isClosed(), "owned persistence must be closed");
    }

    @Test
    void onDisableDuringFlightSettlesWithoutResurrection() throws Exception {
        File dataFolder = temp.resolve("racing").toFile();
        assertTrue(dataFolder.mkdirs());
        Path databasePath = dataFolder.toPath().resolve(ClaimStartupBootstrap.DATABASE_FILE_NAME);
        UUID operationId = seedDomainCommitted(databasePath);

        ChunkLandPlugin plugin = allocatePlugin();
        wireServer(plugin, dataFolder, quietPluginManager());
        plugin.onEnable();
        ClaimStartupBootstrap bootstrap = plugin.getClaimStartup();
        assertNotNull(bootstrap, "onEnable must start the production recovery caller");

        // Close while the scan may still be in flight, then require settlement.
        plugin.onDisable();
        List<RecoveryResult> results = null;
        boolean scanFailed = false;
        try {
            results = bootstrap.scanFuture().toCompletableFuture().get(15, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException expected) {
            scanFailed = true;
        }
        assertTrue(bootstrap.isClosed());
        assertTrue(bootstrap.store().isClosed());

        // Reopen the same file: the durable row is either rebuilt ACTIVE (scan
        // won the race) or retained DOMAIN_COMMITTED (close won). It is never
        // refunded, failed, or rewritten by the late completion.
        try (PersistenceStore reopened = PersistenceStore.open(databasePath)) {
            OperationLedger ledger = new OperationLedger(reopened);
            LedgerEntry row = ledger.find(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(
                    "ACTIVE".equals(row.state()) || "DOMAIN_COMMITTED".equals(row.state()),
                    "late completion must not resurrect or refund; scanFailed=" + scanFailed
                            + " results=" + results + " state=" + row.state());
            assertEquals(0, row.compensationAttempts(), "recovery must never open compensation");
        }
    }

    @Test
    void bootstrapFailureKeepsPluginAliveWithEmptyRuntime() throws Exception {
        File dataFolder = temp.resolve("broken").toFile();
        assertTrue(dataFolder.mkdirs());
        // Occupy the database path with a directory so SQLite bootstrap fails.
        Path blocked = dataFolder.toPath().resolve(ClaimStartupBootstrap.DATABASE_FILE_NAME);
        Files.createDirectories(blocked);

        ChunkLandPlugin plugin = allocatePlugin();
        AtomicBoolean disabled = new AtomicBoolean(false);
        PluginManager managers = (PluginManager) Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class[]{PluginManager.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("disablePlugin")) {
                        disabled.set(true);
                        return null;
                    }
                    if (name.equals("isPluginEnabled")) {
                        return !disabled.get();
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
        wireServer(plugin, dataFolder, managers);

        plugin.onEnable();

        assertNull(plugin.getClaimStartup(), "failed bootstrap must not leave a half-open caller");
        assertNotNull(plugin.getProtectionStore(), "protection store must still exist");
        assertTrue(plugin.getProtectionStore().snapshot().isEmpty(),
                "failed recovery keeps the empty fail-closed runtime");
        plugin.onDisable();
    }
}

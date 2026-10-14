package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.ChunkLandApi;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.protection.LandAuthorisationCache;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import com.smile.chunkland.protection.PermissionDefaultsCache;
import com.smile.chunkland.protection.ProtectionEngine;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.rule.LandRuleService;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Production rule reads must reach real protection data.
 *
 * <p>Each test goes through {@code plugin.getReadApi()} so it exercises the
 * formal wiring, not a hand-injected fake rule source.
 */
class ProductionRuleReadApiTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private record Env(UUID world, LandId land, UUID owner, LandRegistry registry) {
    }

    private static Env env() {
        UUID world = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistry registry = LandRegistry.from(List.of(
                new LandSnapshot(land, "Home", "home", OwnerRef.player(owner),
                        world, Set.of(new ChunkKey(world, 0, 0)),
                        List.of(), 0, 0, NOW, NOW)));
        return new Env(world, land, owner, registry);
    }

    private static LandAuthorisationSnapshot authWith(LandId land,
            Map<LandRuleType, PermissionState> rules) {
        EnumMap<LandRuleType, PermissionState> states = new EnumMap<>(LandRuleType.class);
        states.putAll(rules);
        return LandAuthorisationSnapshot.empty()
                .withDirect(Map.of(), Map.of(), Map.of(),
                        Map.of(land, Map.copyOf(states)),
                        Map.of(land, 0L));
    }

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

    private static void inject(ChunkLandPlugin plugin, LandRegistryStore store,
            PermissionDefaultsCache defaults, LandAuthorisationCache authCache,
            ProtectionEngine engine) throws Exception {
        Field storeField = ChunkLandPlugin.class.getDeclaredField("protectionStore");
        storeField.setAccessible(true);
        storeField.set(plugin, store);
        Field lookupField = ChunkLandPlugin.class.getDeclaredField("protectionDepthLookup");
        lookupField.setAccessible(true);
        lookupField.set(plugin, null);
        Field defaultsField = ChunkLandPlugin.class.getDeclaredField("permissionDefaults");
        defaultsField.setAccessible(true);
        defaultsField.set(plugin, defaults);
        Field authField = ChunkLandPlugin.class.getDeclaredField("landAuthorisationCache");
        authField.setAccessible(true);
        authField.set(plugin, authCache);
        Field engineField = ChunkLandPlugin.class.getDeclaredField("protectionEngine");
        engineField.setAccessible(true);
        engineField.set(plugin, engine);
    }

    private static ChunkLandApi wiredApi(Env env, LandAuthorisationSnapshot auth) throws Exception {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(env.registry());
        PermissionDefaultsCache defaults = new PermissionDefaultsCache(null, null, null);
        LandAuthorisationCache authCache = new LandAuthorisationCache();
        authCache.publish(auth);
        defaults.attachLandAuthorisation(authCache::snapshot);
        SnapshotPermissionContextProvider contexts = defaults.provider();
        AtomicBoolean hydrated = new AtomicBoolean(true);
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, contexts, hydrated::get);
        ChunkLandPlugin plugin = allocatePlugin();
        inject(plugin, store, defaults, authCache, engine);
        return plugin.getReadApi();
    }

    private static ProtectionActionType actionFor(LandRuleType rule) {
        return switch (rule) {
            case PVP -> ProtectionActionType.PLAYER_DAMAGE_PLAYER;
            case EXPLOSION_TERRAIN -> ProtectionActionType.EXPLOSION_TERRAIN;
            case EXPLOSION_ENTITY -> ProtectionActionType.EXPLOSION_ENTITY;
            case FIRE_SPREAD -> ProtectionActionType.FIRE_SPREAD;
            case FIRE_BURN -> ProtectionActionType.FIRE_BURN;
            case MOB_GRIEFING -> ProtectionActionType.MOB_GRIEFING;
            case FLUID_FLOW -> ProtectionActionType.FLUID_FLOW;
            case PISTON -> ProtectionActionType.PISTON_MOVE;
            case HOPPER_TRANSFER -> ProtectionActionType.HOPPER_TRANSFER;
            case HOSTILE_MOB_SPAWN -> ProtectionActionType.HOSTILE_MOB_SPAWN;
            case PASSIVE_MOB_SPAWN -> ProtectionActionType.PASSIVE_MOB_SPAWN;
        };
    }

    @Test
    void explicitAllowReachesThePublicQuery() throws Exception {
        Env env = env();
        ChunkLandApi api = wiredApi(env, authWith(env.land(), Map.of(LandRuleType.PVP, PermissionState.ALLOW)));
        assertEquals(Optional.of(PermissionState.ALLOW), api.getRule(env.land(), LandRuleType.PVP));
    }

    @Test
    void explicitDenyReachesThePublicQuery() throws Exception {
        Env env = env();
        ChunkLandApi api = wiredApi(env, authWith(env.land(), Map.of(LandRuleType.PVP, PermissionState.DENY)));
        assertEquals(Optional.of(PermissionState.DENY), api.getRule(env.land(), LandRuleType.PVP));
    }

    @Test
    void inheritedRuleFallsThroughToBuiltIn() throws Exception {
        Env env = env();
        ChunkLandApi api = wiredApi(env, authWith(env.land(), Map.of()));
        for (LandRuleType rule : LandRuleType.values()) {
            Optional<PermissionState> found = api.getRule(env.land(), rule);
            assertTrue(found.isPresent(), rule + " must resolve, never stay empty for a known land");
            assertEquals(LandRuleService.builtInDefaults().get(rule), found.get(),
                    rule + " with nothing set must read the built-in default");
        }
    }

    @Test
    void unknownLandAnswersEmpty() throws Exception {
        Env env = env();
        ChunkLandApi api = wiredApi(env, authWith(env.land(), Map.of(LandRuleType.PVP, PermissionState.ALLOW)));
        assertTrue(api.getRule(new LandId(UUID.randomUUID()), LandRuleType.PVP).isEmpty());
    }

    @Test
    void unloadedAuthorisationAnswersEmpty() throws Exception {
        Env env = env();
        ChunkLandApi api = wiredApi(env, LandAuthorisationSnapshot.unloaded());
        assertTrue(api.getRule(env.land(), LandRuleType.PVP).isEmpty(),
                "an unloaded durable snapshot must not show a world or built-in ALLOW");
    }

    @Test
    void publicRuleMatchesTheEngineForEveryRule() throws Exception {
        Env env = env();
        UUID stranger = UUID.randomUUID();
        // Three shapes: explicit DENY everywhere, explicit ALLOW everywhere, full inherit.
        List<Map<LandRuleType, PermissionState>> shapes = List.of(
                denyAll(), allowAll(), Map.of());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(env.registry());
        for (Map<LandRuleType, PermissionState> shape : shapes) {
            LandAuthorisationSnapshot auth = authWith(env.land(), shape);
            ChunkLandApi api = wiredApi(env, auth);
            PermissionDefaultsCache defaults = new PermissionDefaultsCache(null, null, null);
            LandAuthorisationCache authCache = new LandAuthorisationCache();
            authCache.publish(auth);
            defaults.attachLandAuthorisation(authCache::snapshot);
            ProtectionEngine engine = new ProtectionEngine(store::snapshot,
                    defaults.provider(), () -> true);
            for (LandRuleType rule : LandRuleType.values()) {
                Optional<PermissionState> shown = api.getRule(env.land(), rule);
                assertTrue(shown.isPresent(), rule + " must resolve for a loaded land");
                PermissionState decided = engine.decide(stranger, env.land(), actionFor(rule)).outcome();
                assertEquals(decided, shown.get(),
                        rule + ": the public query must equal the engine decision");
            }
        }
    }

    private static Map<LandRuleType, PermissionState> denyAll() {
        EnumMap<LandRuleType, PermissionState> out = new EnumMap<>(LandRuleType.class);
        for (LandRuleType rule : LandRuleType.values()) {
            out.put(rule, PermissionState.DENY);
        }
        return Map.copyOf(out);
    }

    private static Map<LandRuleType, PermissionState> allowAll() {
        EnumMap<LandRuleType, PermissionState> out = new EnumMap<>(LandRuleType.class);
        for (LandRuleType rule : LandRuleType.values()) {
            out.put(rule, PermissionState.ALLOW);
        }
        return Map.copyOf(out);
    }

    @Test
    void twoRulesOnTheSameLandResolveIndependently() throws Exception {
        Env env = env();
        ChunkLandApi api = wiredApi(env, authWith(env.land(),
                Map.of(LandRuleType.PVP, PermissionState.ALLOW,
                        LandRuleType.FIRE_SPREAD, PermissionState.DENY)));
        assertEquals(Optional.of(PermissionState.ALLOW), api.getRule(env.land(), LandRuleType.PVP));
        assertEquals(Optional.of(PermissionState.DENY), api.getRule(env.land(), LandRuleType.FIRE_SPREAD));
    }

    @Test
    void publicReadsStayMemoryOnlyAcrossThreads() throws Exception {
        Env env = env();
        AtomicInteger snapshotsTaken = new AtomicInteger();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(env.registry());
        PermissionDefaultsCache defaults = new PermissionDefaultsCache(null, null, null);
        LandAuthorisationCache authCache = new LandAuthorisationCache();
        authCache.publish(authWith(env.land(), Map.of(LandRuleType.PVP, PermissionState.ALLOW)));
        defaults.attachLandAuthorisation(authCache::snapshot);
        SnapshotPermissionContextProvider contexts = defaults.provider();
        ProtectionEngine engine = new ProtectionEngine(() -> {
            snapshotsTaken.incrementAndGet();
            return store.snapshot();
        }, contexts, () -> true);
        ChunkLandPlugin plugin = allocatePlugin();
        inject(plugin, store, defaults, authCache, engine);
        ChunkLandApi api = plugin.getReadApi();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger failures = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        ready.await(5, TimeUnit.SECONDS);
                        for (int n = 0; n < 50; n++) {
                            Optional<PermissionState> found = api.getRule(env.land(), LandRuleType.PVP);
                            if (!found.equals(Optional.of(PermissionState.ALLOW))) {
                                failures.incrementAndGet();
                            }
                            // Location reads ride the same store without I/O.
                            api.getLandAt(env.world(), 0, 0);
                        }
                    } catch (Exception failure) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(15, TimeUnit.SECONDS), "all reader threads must finish");
            assertEquals(0, failures.get(), "concurrent memory-only reads must all agree");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void disabledGenerationAnswersEmptyAndDenies() throws Exception {
        Env env = env();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(env.registry());
        PermissionDefaultsCache defaults = new PermissionDefaultsCache(null, null, null);
        LandAuthorisationCache authCache = new LandAuthorisationCache();
        authCache.publish(authWith(env.land(), Map.of(LandRuleType.PVP, PermissionState.ALLOW)));
        defaults.attachLandAuthorisation(authCache::snapshot);
        SnapshotPermissionContextProvider contexts = defaults.provider();
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, contexts, () -> true);
        ChunkLandPlugin plugin = allocatePlugin();
        inject(plugin, store, defaults, authCache, engine);
        ChunkLandApi api = plugin.getReadApi();
        assertEquals(Optional.of(PermissionState.ALLOW), api.getRule(env.land(), LandRuleType.PVP));
        // Deactivate the captured generation: the same holder must go fail-closed without touching storage.
        Field lifecycleField = ChunkLandPlugin.class.getDeclaredField("readApiLifecycle");
        lifecycleField.setAccessible(true);
        Object lifecycle = lifecycleField.get(plugin);
        Field activeField = lifecycle.getClass().getDeclaredField("active");
        activeField.setAccessible(true);
        activeField.setBoolean(lifecycle, false);
        assertFalse(api.isReady());
        assertTrue(api.getRule(env.land(), LandRuleType.PVP).isEmpty());
        assertFalse(api.can(env.owner(), env.land(), ProtectionActionType.BLOCK_BREAK));
        assertEquals(PermissionState.DENY,
                api.decideAtBlock(UUID.randomUUID(), env.world(), 3, 64, 3,
                        ProtectionActionType.BLOCK_BREAK).outcome());
    }

    @Test
    void unreadyIndexAnswersEmptyForRules() throws Exception {
        Env env = env();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(env.registry());
        PermissionDefaultsCache defaults = new PermissionDefaultsCache(null, null, null);
        LandAuthorisationCache authCache = new LandAuthorisationCache();
        authCache.publish(authWith(env.land(), Map.of(LandRuleType.PVP, PermissionState.ALLOW)));
        defaults.attachLandAuthorisation(authCache::snapshot);
        SnapshotPermissionContextProvider contexts = defaults.provider();
        AtomicBoolean hydrated = new AtomicBoolean(false);
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, contexts, hydrated::get);
        ChunkLandPlugin plugin = allocatePlugin();
        inject(plugin, store, defaults, authCache, engine);
        ChunkLandApi api = plugin.getReadApi();
        assertFalse(api.isReady());
        assertTrue(api.getRule(env.land(), LandRuleType.PVP).isEmpty(),
                "an unhydrated index must not show a rule value");
        assertFalse(api.can(env.owner(), env.land(), ProtectionActionType.BLOCK_BREAK));
    }

    @Test
    void locationQueryAgreesWithTheEngineOnTheSameCoordinates() throws Exception {
        Env env = env();
        UUID owner = env.owner();
        UUID stranger = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(env.registry());
        PermissionDefaultsCache defaults = new PermissionDefaultsCache(null, null, null);
        LandAuthorisationCache authCache = new LandAuthorisationCache();
        authCache.publish(authWith(env.land(), Map.of()));
        defaults.attachLandAuthorisation(authCache::snapshot);
        SnapshotPermissionContextProvider contexts = defaults.provider();
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, contexts, () -> true);
        ChunkLandPlugin plugin = allocatePlugin();
        inject(plugin, store, defaults, authCache, engine);
        ChunkLandApi api = plugin.getReadApi();
        // Inside the claimed chunk (0,0): block (3,64,3) sits in chunk (0,0).
        assertEquals(Optional.of(env.land()), api.getLandAt(env.world(), 0, 0));
        for (UUID actor : List.of(owner, stranger)) {
            for (ProtectionActionType action : List.of(ProtectionActionType.BLOCK_BREAK,
                    ProtectionActionType.PLAYER_DAMAGE_PLAYER)) {
                assertEquals(engine.decideAtBlock(actor, env.world(), 3, 64, 3, action).outcome(),
                        api.decideAtBlock(actor, env.world(), 3, 64, 3, action).outcome(),
                        "the public block decision must equal the engine for the same coordinates");
            }
        }
        // Wilderness chunk (9,9): block (150,64,150) sits in chunk (9,9).
        assertTrue(api.getLandAt(env.world(), 9, 9).isEmpty());
        assertEquals(PermissionState.ALLOW,
                api.decideAtBlock(stranger, env.world(), 150, 64, 150,
                        ProtectionActionType.BLOCK_BREAK).outcome(),
                "wilderness follows vanilla");
        assertEquals(engine.decideAtBlock(stranger, env.world(), 150, 64, 150,
                ProtectionActionType.BLOCK_BREAK).outcome(),
                api.decideAtBlock(stranger, env.world(), 150, 64, 150,
                        ProtectionActionType.BLOCK_BREAK).outcome());
    }

    @Test
    void sublandInsideOutsideAndDepthAgreeWithTheEngine() throws Exception {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        SubLandId subId = new SubLandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SubLandSnapshot sub = new SubLandSnapshot(subId, landId, "Den",
                new Cuboid(0, 60, 0, 15, 70, 15), world);
        LandSnapshot land = new LandSnapshot(landId, "Home", "home", OwnerRef.player(owner),
                world, Set.of(new ChunkKey(world, 0, 0), new ChunkKey(world, 6, 6)),
                List.of(sub), 0, 0, NOW, NOW);
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land)));
        PermissionBinding landBinding = new PermissionBinding(
                PermissionSubject.player(actor),
                new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW));
        PermissionBinding subBinding = new PermissionBinding(
                PermissionSubject.player(actor),
                new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.DENY));
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.copyOf(
                Map.of(), Map.of(), Map.of(),
                Map.of(landId, List.of(landBinding)),
                Map.of(subId, List.of(subBinding)),
                Map.of(), Map.of());
        PermissionDefaultsCache defaults = new PermissionDefaultsCache(null, null, null);
        LandAuthorisationCache authCache = new LandAuthorisationCache();
        authCache.publish(auth);
        defaults.attachLandAuthorisation(authCache::snapshot);
        SnapshotPermissionContextProvider contexts = defaults.provider();
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, contexts, () -> true);
        ChunkLandPlugin plugin = allocatePlugin();
        inject(plugin, store, defaults, authCache, engine);
        ChunkLandApi api = plugin.getReadApi();
        assertTrue(api.getSubLandSnapshot(subId).isPresent(),
                "the formal assembly must serve the subland snapshot");
        assertEquals(Optional.of(landId), api.getLandAt(world, 0, 0));
        // Inside the subland cuboid the explicit sub DENY wins over the land ALLOW.
        assertEquals(PermissionState.DENY,
                api.decideAtBlock(actor, world, 1, 64, 1,
                        ProtectionActionType.BLOCK_BREAK).outcome());
        assertEquals(engine.decideAtBlock(actor, world, 1, 64, 1,
                ProtectionActionType.BLOCK_BREAK).outcome(),
                api.decideAtBlock(actor, world, 1, 64, 1,
                        ProtectionActionType.BLOCK_BREAK).outcome(),
                "inside the subland the public decision must equal the engine");
        // Same XZ above the cuboid Y range falls back to the land ALLOW.
        assertEquals(PermissionState.ALLOW,
                api.decideAtBlock(actor, world, 1, 100, 1,
                        ProtectionActionType.BLOCK_BREAK).outcome());
        assertEquals(engine.decideAtBlock(actor, world, 1, 100, 1,
                ProtectionActionType.BLOCK_BREAK).outcome(),
                api.decideAtBlock(actor, world, 1, 100, 1,
                        ProtectionActionType.BLOCK_BREAK).outcome(),
                "above the subland the public decision must equal the engine");
        // Same land but outside every subland falls back to the land ALLOW.
        assertEquals(PermissionState.ALLOW,
                api.decideAtBlock(actor, world, 100, 64, 100,
                        ProtectionActionType.BLOCK_BREAK).outcome());
        assertEquals(engine.decideAtBlock(actor, world, 100, 64, 100,
                ProtectionActionType.BLOCK_BREAK).outcome(),
                api.decideAtBlock(actor, world, 100, 64, 100,
                        ProtectionActionType.BLOCK_BREAK).outcome(),
                "outside the subland the public decision must equal the engine");
    }
}

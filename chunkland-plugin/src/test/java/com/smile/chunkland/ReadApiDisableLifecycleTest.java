package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.ChunkLandApi;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.vertical.SnapshotProtectionDepthLookup;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Disable-safe cached read API.
 *
 * <p>A holder obtained while the plugin is enabled must not keep serving
 * pre-disable land after cleanup: every public read on the cached holder and
 * on any holder obtained after disable stays empty/fail-closed. A fresh
 * lifecycle may serve reads again, but the old handle never revives.
 */
class ReadApiDisableLifecycleTest {

    private static final Instant TIME = Instant.parse("2026-03-01T00:00:00Z");
    private static final UUID WORLD_A = UUID.randomUUID();
    private static final UUID WORLD_B = UUID.randomUUID();
    private static final int MIN_A = -64;
    private static final int MIN_B = -32;

    private static final String MIXED_YAML =
            "worlds:\n"
                    + "  world_a:\n    claim-enabled: true\n    vertical-mode: PER_CHUNK_DEPTH\n"
                    + "  world_b:\n    claim-enabled: true\n    vertical-mode: FULL_HEIGHT\n";

    private record Fixture(LandId landA, LandId landB, SubLandId subA, ChunkKey deepA,
            ChunkKey shallowA, ChunkKey chunkB, LandRegistry snapshot) {
        static Fixture create() {
            LandId landA = new LandId(UUID.randomUUID());
            LandId landB = new LandId(UUID.randomUUID());
            SubLandId subA = new SubLandId(UUID.randomUUID());
            ChunkKey deepA = new ChunkKey(WORLD_A, 3, 4);
            ChunkKey shallowA = new ChunkKey(WORLD_A, 4, 4);
            ChunkKey chunkB = new ChunkKey(WORLD_B, 1, 1);
            ChunkKey subChunk = new ChunkKey(WORLD_A, 3, 5);
            SubLandSnapshot sub = new SubLandSnapshot(subA, landA, "sub", 0, 255, Set.of(subChunk));
            LandSnapshot a = land(landA, "PerLand", WORLD_A, Set.of(deepA, shallowA, subChunk), List.of(sub));
            LandSnapshot b = land(landB, "FullLand", WORLD_B, Set.of(chunkB), List.of());
            LandRegistry snapshot = LandRegistry.fromWithDepths(List.of(a, b),
                    Map.of(deepA, 25, shallowA, -20, chunkB, 40, subChunk, 10));
            return new Fixture(landA, landB, subA, deepA, shallowA, chunkB, snapshot);
        }

        private static LandSnapshot land(LandId id, String name, UUID world, Set<ChunkKey> chunks,
                List<SubLandSnapshot> subs) {
            LandName normalized = LandName.of(name);
            return new LandSnapshot(id, normalized.displayName(), normalized.nameKey(),
                    OwnerRef.player(UUID.randomUUID()), world, chunks,
                    subs, 0L, 0L, TIME, TIME);
        }
    }

    private static Map<UUID, String> names() {
        return Map.of(WORLD_A, "world_a", WORLD_B, "world_b");
    }

    private static Map<UUID, Integer> mins() {
        return Map.of(WORLD_A, MIN_A, WORLD_B, MIN_B);
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
            SnapshotProtectionDepthLookup lookup) throws Exception {
        Field storeField = ChunkLandPlugin.class.getDeclaredField("protectionStore");
        storeField.setAccessible(true);
        storeField.set(plugin, store);
        Field lookupField = ChunkLandPlugin.class.getDeclaredField("protectionDepthLookup");
        lookupField.setAccessible(true);
        lookupField.set(plugin, lookup);
    }

    private static SnapshotProtectionDepthLookup mixedLookup(AtomicReference<ChunkLandConfig> configs) {
        return ChunkLandPlugin.buildProtectionDepthLookup(configs::get, names(), mins());
    }

    private static void assertNormalReads(ChunkLandApi api, Fixture f) {
        assertTrue(api.getLandSnapshot(f.landA()).isPresent(), "enabled holder must see land");
        assertTrue(api.getSubLandSnapshot(f.subA()).isPresent(), "enabled holder must see subland");
        assertTrue(api.getOwner(f.landA()).isPresent(), "enabled holder must see owner");
        assertFalse(api.can(UUID.randomUUID(), f.landA(), ProtectionActionType.BLOCK_BREAK),
                "production wiring has no grant source so can stays deny while enabled");
        assertTrue(api.getRule(f.landA(), LandRuleType.PVP).isEmpty(),
                "production wiring has no rule source so rule stays empty while enabled");
        assertEquals(Optional.of(-20), api.getProtectionDepth(f.landA()));
    }

    private static void assertEmptyReads(ChunkLandApi api, Fixture f) {
        assertTrue(api.getLandSnapshot(f.landA()).isEmpty(), "disabled holder must not serve stale land");
        assertTrue(api.getSubLandSnapshot(f.subA()).isEmpty(), "disabled holder must not serve stale subland");
        assertTrue(api.getOwner(f.landA()).isEmpty(), "disabled holder must not serve stale owner");
        assertFalse(api.can(UUID.randomUUID(), f.landA(), ProtectionActionType.BLOCK_BREAK),
                "disabled holder must stay deny");
        assertTrue(api.getRule(f.landA(), LandRuleType.PVP).isEmpty(),
                "disabled holder must stay empty on rules");
        assertTrue(api.getProtectionDepth(f.landA()).isEmpty(),
                "disabled holder must not serve stale depth");
        assertTrue(api.getLandSnapshot(f.landB()).isEmpty());
        assertTrue(api.getProtectionDepth(f.landB()).isEmpty());
    }

    @Test
    void cachedApiEmptyDuringCleanup() throws Exception {
        Fixture f = Fixture.create();
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        LandRegistryStore store = new LandRegistryStore();
        store.publish(f.snapshot());

        ChunkLandPlugin plugin = allocatePlugin();
        inject(plugin, store, mixedLookup(configs));
        ChunkLandApi cached = plugin.getReadApi();
        assertNormalReads(cached, f);

        // Deterministic mid-cleanup window: pause cleanup on the start hook
        // (no sleep, no timing race) and read while cleanup has started but
        // not yet returned. The store is still published at this point, so
        // only an immediate lifecycle invalidation keeps the read empty.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        plugin.cleanupStartedHookForTest = () -> {
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS), "cleanup barrier must release");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        AtomicReference<Throwable> cleanupError = new AtomicReference<>();
        Thread cleanupThread = new Thread(() -> {
            try {
                plugin.performFullCleanup();
            } catch (Throwable failure) {
                cleanupError.set(failure);
            }
        });
        cleanupThread.start();
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "cleanup must reach the start barrier");
            assertTrue(cleanupThread.isAlive(), "read must happen while cleanup is still paused");
            assertEmptyReads(cached, f);
        } finally {
            release.countDown();
            cleanupThread.join(10_000);
        }
        assertNull(cleanupError.get(), "cleanup must not fail");
        assertFalse(cleanupThread.isAlive(), "cleanup must have returned");
        assertEmptyReads(cached, f);
        ChunkLandApi freshAfterDisable = plugin.getReadApi();
        assertEmptyReads(freshAfterDisable, f);
    }

    @Test
    void cachedApiFailsClosedAfterCleanup() throws Exception {
        Fixture f = Fixture.create();
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        LandRegistryStore store = new LandRegistryStore();
        store.publish(f.snapshot());

        ChunkLandPlugin plugin = allocatePlugin();
        inject(plugin, store, mixedLookup(configs));
        ChunkLandApi cached = plugin.getReadApi();
        assertNormalReads(cached, f);

        plugin.performFullCleanup();

        assertEmptyReads(cached, f);
        ChunkLandApi freshAfterDisable = plugin.getReadApi();
        assertEmptyReads(freshAfterDisable, f);
    }

    @Test
    void freshLifecycleServesAgainWithoutRevivingOldHandle() throws Exception {
        Fixture f = Fixture.create();
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        LandRegistryStore store = new LandRegistryStore();
        store.publish(f.snapshot());

        ChunkLandPlugin first = allocatePlugin();
        inject(first, store, mixedLookup(configs));
        ChunkLandApi oldHandle = first.getReadApi();
        assertNormalReads(oldHandle, f);
        first.performFullCleanup();
        assertEmptyReads(oldHandle, f);

        ChunkLandPlugin second = allocatePlugin();
        LandRegistryStore nextStore = new LandRegistryStore();
        nextStore.publish(f.snapshot());
        inject(second, nextStore, mixedLookup(configs));
        ChunkLandApi nextHandle = second.getReadApi();
        assertNormalReads(nextHandle, f);

        assertEmptyReads(oldHandle, f);
        assertEquals(Optional.of(25), f.snapshot().storedDepth(f.deepA()),
                "reads must never rewrite stored depths");
    }

    @Test
    void reloadVerticalModeAppliesWhileEnabled() throws Exception {
        Fixture f = Fixture.create();
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        LandRegistryStore store = new LandRegistryStore();
        store.publish(f.snapshot());

        ChunkLandPlugin plugin = allocatePlugin();
        inject(plugin, store, mixedLookup(configs));
        ChunkLandApi api = plugin.getReadApi();

        assertEquals(Optional.of(-20), api.getProtectionDepth(f.landA()));
        configs.set(ConfigSchema.parseYamlText(
                "worlds:\n"
                        + "  world_a:\n    claim-enabled: true\n    vertical-mode: FULL_HEIGHT\n"
                        + "  world_b:\n    claim-enabled: true\n    vertical-mode: FULL_HEIGHT\n"));
        assertEquals(Optional.of(MIN_A), api.getProtectionDepth(f.landA()),
                "reload to FULL_HEIGHT must take effect without rebuilding the API");
        assertEquals(Optional.of(25), f.snapshot().storedDepth(f.deepA()),
                "a mode switch must never migrate stored depths");
    }
}

package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.ChunkLandApi;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.runtime.api.ChunkLandReadApi;
import com.smile.chunkland.runtime.api.ProtectionDepthLookup;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Production wiring for the formal read path.
 *
 * <p>The plugin-owned {@link ChunkLandReadApi} must delegate depth reads to
 * the production {@link SnapshotProtectionDepthLookup} built from the live
 * config snapshot, sharing the same {@link LandRegistryStore} snapshot. Mode
 * switches apply on reload without rewriting stored depths; missing worlds or
 * minima fall back explicitly without Bukkit or SQL on the hot path.
 */
class ProductionReadApiWiringTest {

    private static final Instant TIME = Instant.parse("2026-03-01T00:00:00Z");
    private static final UUID WORLD_A = UUID.randomUUID();
    private static final UUID WORLD_B = UUID.randomUUID();
    private static final int MIN_A = -64;
    private static final int MIN_B = -32;

    private static final String MIXED_YAML =
            "worlds:\n"
                    + "  world_a:\n    claim-enabled: true\n    vertical-mode: PER_CHUNK_DEPTH\n"
                    + "  world_b:\n    claim-enabled: true\n    vertical-mode: FULL_HEIGHT\n";

    private record Fixture(LandId landA, LandId landB, ChunkKey deepA,
            ChunkKey shallowA, ChunkKey chunkB, LandRegistry snapshot) {
        static Fixture create() {
            LandId landA = new LandId(UUID.randomUUID());
            LandId landB = new LandId(UUID.randomUUID());
            ChunkKey deepA = new ChunkKey(WORLD_A, 3, 4);
            ChunkKey shallowA = new ChunkKey(WORLD_A, 4, 4);
            ChunkKey chunkB = new ChunkKey(WORLD_B, 1, 1);
            LandSnapshot a = land(landA, "PerLand", WORLD_A, Set.of(deepA, shallowA));
            LandSnapshot b = land(landB, "FullLand", WORLD_B, Set.of(chunkB));
            LandRegistry snapshot = LandRegistry.fromWithDepths(List.of(a, b),
                    Map.of(deepA, 25, shallowA, -20, chunkB, 40));
            return new Fixture(landA, landB, deepA, shallowA, chunkB, snapshot);
        }

        private static LandSnapshot land(LandId id, String name, UUID world, Set<ChunkKey> chunks) {
            LandName normalized = LandName.of(name);
            return new LandSnapshot(id, normalized.displayName(), normalized.nameKey(),
                    OwnerRef.player(UUID.randomUUID()), world, chunks,
                    List.of(), 0L, 0L, TIME, TIME);
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

    @Test
    void pluginReadApiUsesProductionLookup() throws Exception {
        Fixture f = Fixture.create();
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        SnapshotProtectionDepthLookup lookup =
                ChunkLandPlugin.buildProtectionDepthLookup(configs::get, names(), mins());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(f.snapshot());

        ChunkLandPlugin plugin = allocatePlugin();
        inject(plugin, store, lookup);
        ChunkLandApi api = plugin.getReadApi();

        assertNotNull(api, "plugin must expose a production read API");
        assertEquals(Optional.of(-20), api.getProtectionDepth(f.landA()),
                "PER_CHUNK_DEPTH land must read the minimum stored depth");
        assertEquals(Optional.of(MIN_B), api.getProtectionDepth(f.landB()),
                "FULL_HEIGHT land must read the world minimum");
    }

    @Test
    void pluginReadApiReloadUpdatesModeWithoutRewritingStored() throws Exception {
        Fixture f = Fixture.create();
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        SnapshotProtectionDepthLookup lookup =
                ChunkLandPlugin.buildProtectionDepthLookup(configs::get, names(), mins());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(f.snapshot());

        ChunkLandPlugin plugin = allocatePlugin();
        inject(plugin, store, lookup);
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
        assertEquals(Optional.of(40), f.snapshot().storedDepth(f.chunkB()));
    }

    @Test
    void pluginReadApiSharesOneSnapshotWithLookup() throws Exception {
        Fixture f = Fixture.create();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(f.snapshot());
        AtomicInteger snapshotsTaken = new AtomicInteger();
        LandRegistry published = f.snapshot();
        Supplier<LandRegistry> counting = () -> {
            snapshotsTaken.incrementAndGet();
            return store.snapshot();
        };
        ProtectionDepthLookup spy = (landId, snapshot) -> {
            assertSame(published, snapshot, "lookup must use the exact snapshot the API observed");
            return snapshot.land(landId) == null
                    ? Optional.empty() : Optional.of(7);
        };
        ChunkLandReadApi api = ChunkLandPlugin.buildReadApi(counting, spy);

        assertEquals(Optional.of(7), api.getProtectionDepth(f.landA()));
        assertEquals(1, snapshotsTaken.get(), "each read must take exactly one snapshot");
        assertTrue(api.getProtectionDepth(new LandId(UUID.randomUUID())).isEmpty());
    }

    @Test
    void pluginReadApiFallsBackWithoutHotPathIo() throws Exception {
        Fixture f = Fixture.create();
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        // world_b is FULL_HEIGHT but has no registered minimum: the explicit
        // fallback applies instead of throwing on the read path.
        SnapshotProtectionDepthLookup lookup =
                ChunkLandPlugin.buildProtectionDepthLookup(configs::get, names(), Map.of(WORLD_A, MIN_A));
        LandRegistryStore store = new LandRegistryStore();
        store.publish(f.snapshot());

        ChunkLandPlugin plugin = allocatePlugin();
        inject(plugin, store, lookup);
        ChunkLandApi api = plugin.getReadApi();

        assertEquals(Optional.of(ChunkLandPlugin.WORLD_MIN_HEIGHT_FALLBACK),
                api.getProtectionDepth(f.landB()));
        assertTrue(api.getProtectionDepth(new LandId(UUID.randomUUID())).isEmpty(),
                "unknown land must stay empty without I/O");
        assertEquals(Optional.of(-20), api.getProtectionDepth(f.landA()));
    }
}

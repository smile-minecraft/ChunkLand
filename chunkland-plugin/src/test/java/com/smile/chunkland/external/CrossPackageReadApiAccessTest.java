package com.smile.chunkland.external;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.ChunkLandPlugin;
import com.smile.chunkland.api.ChunkLandApi;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.config.VerticalMode;
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
import org.junit.jupiter.api.Test;

/**
 * Cross-package consumer for the production read path.
 *
 * <p>Lives in a different package from the plugin on purpose: the read API
 * must be reachable through a public entry point, not through a same-package
 * seam or reflection. Every call below uses the public
 * {@link ChunkLandApi} contract directly.
 */
class CrossPackageReadApiAccessTest {

    private static final Instant TIME = Instant.parse("2026-03-01T00:00:00Z");
    private static final UUID WORLD_A = UUID.randomUUID();
    private static final UUID WORLD_B = UUID.randomUUID();
    private static final int MIN_A = -64;
    private static final int MIN_B = -32;

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

    private static SnapshotProtectionDepthLookup mixedLookup() {
        return new SnapshotProtectionDepthLookup(
                worldId -> WORLD_B.equals(worldId) ? VerticalMode.FULL_HEIGHT : VerticalMode.PER_CHUNK_DEPTH,
                worldId -> WORLD_A.equals(worldId) ? MIN_A : MIN_B);
    }

    @Test
    void externalConsumerReadsPerAndFullDepthThroughPublicContract() throws Exception {
        Fixture f = Fixture.create();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(f.snapshot());

        ChunkLandPlugin plugin = allocatePlugin();
        inject(plugin, store, mixedLookup());

        // Direct compile-time call across packages: fails while the getter
        // stays package-private, passes once it is public.
        ChunkLandApi api = plugin.getReadApi();

        assertNotNull(api, "external consumer must obtain the production read API");
        assertEquals(Optional.of(-20), api.getProtectionDepth(f.landA()),
                "PER_CHUNK_DEPTH land must read the minimum stored depth");
        assertEquals(Optional.of(MIN_B), api.getProtectionDepth(f.landB()),
                "FULL_HEIGHT land must read the world minimum");
        assertEquals(Optional.of(25), f.snapshot().storedDepth(f.deepA()),
                "reads must never rewrite stored depths");
        assertEquals(Optional.of(40), f.snapshot().storedDepth(f.chunkB()));
        assertTrue(api.getProtectionDepth(new LandId(UUID.randomUUID())).isEmpty(),
                "unknown land must stay empty without I/O");
    }

    @Test
    void externalConsumerObservesLiveSnapshotWithoutRebuilding() throws Exception {
        Fixture f = Fixture.create();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(f.snapshot());

        ChunkLandPlugin plugin = allocatePlugin();
        inject(plugin, store, mixedLookup());

        ChunkLandApi api = plugin.getReadApi();
        assertEquals(Optional.of(-20), api.getProtectionDepth(f.landA()));

        LandId lateLand = new LandId(UUID.randomUUID());
        ChunkKey lateChunk = new ChunkKey(WORLD_A, 9, 9);
        LandName normalized = LandName.of("LateLand");
        LandSnapshot late = new LandSnapshot(lateLand, normalized.displayName(), normalized.nameKey(),
                OwnerRef.player(UUID.randomUUID()), WORLD_A, Set.of(lateChunk),
                List.of(), 0L, 0L, TIME, TIME);
        LandRegistry next = LandRegistry.fromWithDepths(
                List.of(f.snapshot().land(f.landA()), f.snapshot().land(f.landB()), late),
                Map.of(f.deepA(), 25, f.shallowA(), -20, f.chunkB(), 40, lateChunk, 5));
        store.publish(next);

        assertEquals(Optional.of(5), api.getProtectionDepth(lateLand),
                "the same holder must observe the live store snapshot after publish");
        assertEquals(Optional.of(-20), api.getProtectionDepth(f.landA()),
                "existing lands keep their depth after a later publish");
    }
}

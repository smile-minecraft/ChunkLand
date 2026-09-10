package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.protection.LandAuthorisationCache;
import com.smile.chunkland.trust.LandAuthorisationService;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Disable must drop the land authorisation service and cache.
 *
 * <p>Cleanup clears both fields so no further trust or default mutation can
 * reach storage after disable, and repeats stay no-ops. Building a service
 * without a store is refused up front instead of opening a substitute, so a
 * missing store can never be masked.
 */
class LandAuthorisationCleanupLifecycleTest {

    @TempDir Path tmp;

    private static ChunkLandPlugin allocatePlugin() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        ChunkLandPlugin plugin = (ChunkLandPlugin) unsafe.allocateInstance(ChunkLandPlugin.class);
        for (var field : ChunkLandPlugin.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            long offset = unsafe.objectFieldOffset(field);
            Object value = unsafe.getObject(plugin, offset);
            if (field.getType() == Optional.class && value == null) {
                unsafe.putObject(plugin, offset, Optional.empty());
            } else if (field.getType()
                    == com.smile.chunkland.adapter.AceLibBridge.class && value == null) {
                unsafe.putObject(plugin, offset, new com.smile.chunkland.adapter.AceLibBridge());
            }
        }
        return plugin;
    }

    private static Object fieldOf(ChunkLandPlugin plugin, String name) throws Exception {
        Field field = ChunkLandPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(plugin);
    }

    private static void setField(ChunkLandPlugin plugin, String name, Object value) throws Exception {
        Field field = ChunkLandPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    @Test
    void buildRefusesNullStoreInsteadOfMaskingIt() {
        assertThrows(NullPointerException.class,
                () -> ChunkLandPlugin.buildLandAuthorisations(null),
                "a missing store must be refused, never replaced by a substitute");
    }

    @Test
    void cleanupClearsServiceAndCacheAndStaysRepeatable() throws Exception {
        Path path = tmp.resolve("cleanup.db");
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        LandAuthorisationCache liveCache;
        try (PersistenceStore store = PersistenceStore.open(path)) {
            new SqliteLandRepository(store).save(new LandSnapshot(land, "Home", "home",
                    OwnerRef.player(owner), world, Set.of(new ChunkKey(world, 0, 0)),
                    List.of(), 0, 0, Instant.now(), Instant.now()))
                    .toCompletableFuture().join();
            LandAuthorisationService service = ChunkLandPlugin.buildLandAuthorisations(store);
            service.trust(owner, land, target).toCompletableFuture().join();
            assertTrue(service.cache().snapshot().directAllows(target, land)
                    .contains(ProtectionActionType.BLOCK_BREAK));
            liveCache = service.cache();

            ChunkLandPlugin plugin = allocatePlugin();
            setField(plugin, "landAuthorisationService", service);
            setField(plugin, "landAuthorisationCache", liveCache);

            plugin.performFullCleanup();

            assertNull(fieldOf(plugin, "landAuthorisationService"),
                    "cleanup must clear the service field");
            assertNull(fieldOf(plugin, "landAuthorisationCache"),
                    "cleanup must clear the cache field");
            assertDoesNotThrow(plugin::performFullCleanup,
                    "repeated cleanup must stay idempotent");
            assertNull(fieldOf(plugin, "landAuthorisationService"));
            assertNull(fieldOf(plugin, "landAuthorisationCache"));
        }
        // The previously published snapshot keeps answering on its own after
        // disable; cleanup only stops future publishes.
        assertEquals(PermissionState.INHERIT,
                liveCache.snapshot().landDefault(land, ProtectionActionType.BLOCK_BREAK));
    }
}

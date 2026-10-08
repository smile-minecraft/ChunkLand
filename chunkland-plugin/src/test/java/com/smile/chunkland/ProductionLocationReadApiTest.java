package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.ProtectionEngine;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The production read API answers block decisions through the protection
 * engine, so an external caller sees what the listeners enforce.
 */
class ProductionLocationReadApiTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    private static LandRegistryStore store(LandId id) {
        LandSnapshot land = new LandSnapshot(id, "Wired", "wired", OwnerRef.player(OWNER), WORLD,
                Set.of(new ChunkKey(WORLD, 0, 0)), List.of(), 0, 0, Instant.now(), Instant.now());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land)));
        return store;
    }

    /** Owner guarantee for the owner, inherit-only (implicit DENY) for everyone else. */
    private static final PermissionContextProvider CONTEXTS = (actor, landId, action, snapshot) ->
            PermissionContext.builder(action).isOwner(OWNER.equals(actor)).build();

    @Test
    void blockDecisionsMatchTheEngine() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistryStore store = store(id);
        AtomicBoolean hydrated = new AtomicBoolean(true);
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, CONTEXTS, hydrated::get);
        var api = ChunkLandPlugin.buildReadApi(store::snapshot, null, CONTEXTS, engine, () -> true);

        assertTrue(api.isReady());
        assertEquals(PermissionState.ALLOW,
                api.decideAtBlock(OWNER, WORLD, 3, 64, 3, ProtectionActionType.BLOCK_BREAK).outcome());
        assertEquals(PermissionState.DENY,
                api.decideAtBlock(UUID.randomUUID(), WORLD, 3, 64, 3, ProtectionActionType.BLOCK_BREAK).outcome());
        assertEquals(PermissionState.ALLOW,
                api.decideAtBlock(UUID.randomUUID(), WORLD, 40, 64, 3, ProtectionActionType.BLOCK_BREAK).outcome(),
                "wilderness follows vanilla");
        assertTrue(api.can(OWNER, id, ProtectionActionType.BLOCK_BREAK));
        assertFalse(api.can(UUID.randomUUID(), id, ProtectionActionType.BLOCK_BREAK));

        hydrated.set(false);
        assertFalse(api.isReady());
        assertEquals(PermissionState.DENY,
                api.decideAtBlock(UUID.randomUUID(), WORLD, 40, 64, 3, ProtectionActionType.BLOCK_BREAK).outcome(),
                "an unhydrated index is not wilderness");
        assertFalse(api.can(OWNER, id, ProtectionActionType.BLOCK_BREAK));
    }

    @Test
    void inactiveGenerationOrMissingEngineIsUnreadyAndDenies() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistryStore store = store(id);
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, CONTEXTS, () -> true);
        var inactive = ChunkLandPlugin.buildReadApi(store::snapshot, null, CONTEXTS, engine, () -> false);
        assertFalse(inactive.isReady());
        assertEquals(PermissionState.DENY,
                inactive.decideAtBlock(OWNER, WORLD, 3, 64, 3, ProtectionActionType.BLOCK_BREAK).outcome());

        var noEngine = ChunkLandPlugin.buildReadApi(store::snapshot, null, null, null, () -> true);
        assertFalse(noEngine.isReady());
        assertEquals(PermissionState.DENY,
                noEngine.decideAtBlock(OWNER, WORLD, 40, 64, 3, ProtectionActionType.BLOCK_BREAK).outcome());
        assertFalse(noEngine.can(OWNER, id, ProtectionActionType.BLOCK_BREAK));
        assertEquals(java.util.Optional.of(id), noEngine.getLandAt(WORLD, 0, 0));
    }
}

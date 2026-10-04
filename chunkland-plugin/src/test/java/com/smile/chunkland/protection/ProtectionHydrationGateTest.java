package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Proves the hydration gate: while the runtime land registry has not been
 * confirmed complete, an empty index means "unknown", never "wilderness".
 * Every decision entry denies until readiness flips, and flips back to
 * normal the moment hydration completes, with no cached denial left behind.
 */
class ProtectionHydrationGateTest {

    private static final ProtectionActionType[] GATED_ACTIONS = {
            ProtectionActionType.BLOCK_BREAK,
            ProtectionActionType.BLOCK_PLACE,
            ProtectionActionType.CONTAINER_OPEN,
            ProtectionActionType.DOOR_USE,
    };

    private static LandSnapshot landAt(UUID worldId, LandId id, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(UUID.randomUUID()),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private static PermissionContextProvider grantAll() {
        return (actor, landId, action, snapshot) -> new PermissionContext(action, false,
                List.of(new PermissionBinding(PermissionSubject.player(actor),
                        new Permission(action, PermissionState.ALLOW))),
                PermissionState.INHERIT, PermissionState.INHERIT);
    }

    private static ProtectionEngine unreadyEngine(LandRegistryStore store,
            PermissionContextProvider provider) {
        return new ProtectionEngine(store::snapshot, provider, () -> false);
    }

    private static ProtectionEngine readyEngine(LandRegistryStore store,
            PermissionContextProvider provider) {
        return new ProtectionEngine(store::snapshot, provider, () -> true);
    }

    @Test
    void unreadyEmptyRegistryDeniesAtBlock() {
        LandRegistryStore store = new LandRegistryStore();
        ProtectionEngine engine = unreadyEngine(store, grantAll());
        UUID actor = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        assertFalse(engine.isRegistryReady(), "gate must report the unready state");
        for (ProtectionActionType action : GATED_ACTIONS) {
            var decision = engine.decideAtBlock(actor, worldId, 145, 64, 145, action);
            assertEquals(PermissionState.DENY, decision.outcome(),
                    "unhydrated index must not read as wilderness for " + action);
        }
    }

    @Test
    void unreadyEmptyRegistryDeniesAtChunkAndByLand() {
        LandRegistryStore store = new LandRegistryStore();
        ProtectionEngine engine = unreadyEngine(store, grantAll());
        UUID actor = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        assertEquals(PermissionState.DENY,
                engine.decideAt(actor, worldId, 9, 9, ProtectionActionType.BLOCK_BREAK).outcome(),
                "chunk decision on an unhydrated index must deny");
        assertEquals(PermissionState.DENY,
                engine.decide(actor, new LandId(UUID.randomUUID()),
                        ProtectionActionType.BLOCK_BREAK).outcome(),
                "land decision on an unhydrated index must deny");
    }

    @Test
    void unreadyPopulatedRegistryStillDenies() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        ProtectionEngine engine = unreadyEngine(store, grantAll());
        var decision = engine.decideAtBlock(UUID.randomUUID(), worldId, 5, 64, 5,
                ProtectionActionType.BLOCK_BREAK);
        assertEquals(PermissionState.DENY, decision.outcome(),
                "a partial snapshot proves nothing about completeness: deny until ready");
    }

    @Test
    void readinessFlipReleasesGateWithoutResidue() {
        LandRegistryStore store = new LandRegistryStore();
        AtomicBoolean ready = new AtomicBoolean(false);
        ProtectionEngine engine =
                new ProtectionEngine(store::snapshot, grantAll(), ready::get);
        UUID actor = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        assertEquals(PermissionState.DENY,
                engine.decideAtBlock(actor, worldId, 145, 64, 145,
                        ProtectionActionType.BLOCK_BREAK).outcome());
        ready.set(true);
        assertTrue(engine.isRegistryReady());
        assertEquals(PermissionState.ALLOW,
                engine.decideAtBlock(actor, worldId, 145, 64, 145,
                        ProtectionActionType.BLOCK_BREAK).outcome(),
                "hydration must release the gate: no denial may stick");
    }

    @Test
    void throwingReadinessFailsClosed() {
        LandRegistryStore store = new LandRegistryStore();
        BooleanSupplier exploding = () -> {
            throw new IllegalStateException("readiness unreadable");
        };
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, grantAll(), exploding);
        assertFalse(engine.isRegistryReady());
        assertEquals(PermissionState.DENY,
                engine.decideAtBlock(UUID.randomUUID(), UUID.randomUUID(), 145, 64, 145,
                        ProtectionActionType.BLOCK_BREAK).outcome(),
                "an unreadable gate must deny, never assume ready");
    }

    @Test
    void snapshotWithheldWhileUnready() {
        LandRegistryStore store = new LandRegistryStore();
        ProtectionEngine unready = unreadyEngine(store, grantAll());
        assertNull(unready.snapshot(),
                "no complete snapshot exists before hydration: withhold it");
        ProtectionEngine ready = readyEngine(store, grantAll());
        assertTrue(ready.snapshot() != null && ready.snapshot().isEmpty(),
                "a hydrated empty world still exposes its (empty) snapshot");
    }

    @Test
    void crossDeniedWhileUnready() {
        LandRegistryStore store = new LandRegistryStore();
        ProtectionEngine engine = unreadyEngine(store, grantAll());
        UUID worldId = UUID.randomUUID();
        assertTrue(CrossBoundaryDecider.crossDenied(engine, worldId,
                        0, 0, 1, 1,
                        ProtectionActionType.BLOCK_MOVE_IN, ProtectionActionType.BLOCK_MOVE_OUT),
                "an unhydrated index cannot prove wilderness on either end: deny");
    }

    @Test
    void readyEmptyRegistryStillAllowsWilderness() {
        LandRegistryStore store = new LandRegistryStore();
        ProtectionEngine engine = readyEngine(store, grantAll());
        UUID actor = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        assertTrue(engine.isRegistryReady());
        for (ProtectionActionType action : GATED_ACTIONS) {
            var decision = engine.decideAtBlock(actor, worldId, 145, 64, 145, action);
            assertEquals(PermissionState.ALLOW, decision.outcome(),
                    "hydrated wilderness must stay vanilla for " + action);
        }
    }

    @Test
    void legacyConstructorStaysAlwaysReady() {
        LandRegistryStore store = new LandRegistryStore();
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, grantAll());
        assertTrue(engine.isRegistryReady(),
                "callers without a hydration concept keep the historical behaviour");
        assertEquals(PermissionState.ALLOW,
                engine.decideAtBlock(UUID.randomUUID(), UUID.randomUUID(), 145, 64, 145,
                        ProtectionActionType.BLOCK_BREAK).outcome());
    }

    @Test
    void readyPopulatedRegistryDecidesNormally() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        UUID actor = UUID.randomUUID();
        ProtectionEngine allowing = readyEngine(store, grantAll());
        assertEquals(PermissionState.ALLOW,
                allowing.decide(actor, landId, ProtectionActionType.BLOCK_BREAK).outcome());
        ProtectionEngine denying =
                readyEngine(store, ProtectionEngine.inheritOnlyProvider());
        assertEquals(PermissionState.DENY,
                denying.decide(actor, landId, ProtectionActionType.BLOCK_BREAK).outcome());
    }

    @Test
    void readyCrossingOverWildernessStillPasses() {
        LandRegistryStore store = new LandRegistryStore();
        ProtectionEngine engine = readyEngine(store, grantAll());
        assertFalse(CrossBoundaryDecider.crossDenied(engine, UUID.randomUUID(),
                        0, 0, 1, 1,
                        ProtectionActionType.BLOCK_MOVE_IN, ProtectionActionType.BLOCK_MOVE_OUT),
                "hydrated wilderness-to-wilderness crossings stay vanilla");
    }
}

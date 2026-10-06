package com.smile.chunkland.runtime.api;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChunkLandReadApiLocationTest {

    private static final UUID WORLD = UUID.randomUUID();

    private static LandRegistryStore storeWith(LandId id, int chunkX, int chunkZ) {
        LandSnapshot land = new LandSnapshot(id, "Located", "located", OwnerRef.player(UUID.randomUUID()),
                WORLD, Set.of(new ChunkKey(WORLD, chunkX, chunkZ)), List.of(), 0, 0, Instant.now(), Instant.now());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land)));
        return store;
    }

    private static ChunkLandReadApi api(LandRegistryStore store, AtomicBoolean ready, BlockDecisionLookup decisions) {
        return new ChunkLandReadApi(store::snapshot, (a, l, act, s) -> null,
                (l, r, s) -> Optional.empty(), (l, s) -> Optional.empty(), ready::get, decisions);
    }

    @Test
    void getLandAtReadsTheChunkIndex() {
        LandId id = new LandId(UUID.randomUUID());
        ChunkLandReadApi api = new ChunkLandReadApi(storeWith(id, 3, -2));
        assertEquals(Optional.of(id), api.getLandAt(WORLD, 3, -2));
        assertTrue(api.getLandAt(WORLD, 4, -2).isEmpty());
        assertTrue(api.getLandAt(UUID.randomUUID(), 3, -2).isEmpty());
        assertThrows(NullPointerException.class, () -> api.getLandAt(null, 0, 0));
    }

    @Test
    void decideAtBlockPassesTheObservedSnapshotAndCoordinates() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistryStore store = storeWith(id, 0, 0);
        LandRegistry published = store.snapshot();
        UUID actor = UUID.randomUUID();
        AtomicReference<LandRegistry> seen = new AtomicReference<>();
        ChunkLandReadApi api = api(store, new AtomicBoolean(true), (a, w, x, y, z, action, snapshot) -> {
            seen.set(snapshot);
            assertEquals(actor, a);
            assertEquals(List.of(5, 70, -9), List.of(x, y, z));
            return new PermissionDecision(PermissionState.ALLOW, action.decisionSource(), "allowed");
        });
        PermissionDecision decision = api.decideAtBlock(actor, WORLD, 5, 70, -9, ProtectionActionType.BLOCK_PLACE);
        assertEquals(PermissionState.ALLOW, decision.outcome());
        assertSame(published, seen.get());
    }

    @Test
    void decideAtBlockFailsClosed() {
        LandRegistryStore store = storeWith(new LandId(UUID.randomUUID()), 0, 0);
        AtomicBoolean ready = new AtomicBoolean(false);
        AtomicBoolean asked = new AtomicBoolean();
        ChunkLandReadApi unready = api(store, ready, (a, w, x, y, z, action, s) -> {
            asked.set(true);
            return new PermissionDecision(PermissionState.ALLOW, action.decisionSource(), "allowed");
        });
        assertFalse(unready.isReady());
        assertEquals(PermissionState.DENY,
                unready.decideAtBlock(UUID.randomUUID(), WORLD, 0, 0, 0, ProtectionActionType.BLOCK_BREAK).outcome());
        assertFalse(asked.get(), "an unconfirmed index must not be decided");

        ready.set(true);
        ChunkLandReadApi nullAnswer = api(store, ready, (a, w, x, y, z, action, s) -> null);
        PermissionDecision denied = nullAnswer.decideAtBlock(UUID.randomUUID(), WORLD, 0, 0, 0,
                ProtectionActionType.PISTON_MOVE);
        assertEquals(PermissionState.DENY, denied.outcome());
        assertEquals(DecisionSource.LAND_RULE, denied.source());

        ChunkLandReadApi throwing = api(store, ready, (a, w, x, y, z, action, s) -> {
            throw new IllegalStateException("index fault");
        });
        assertEquals(PermissionState.DENY,
                throwing.decideAtBlock(UUID.randomUUID(), WORLD, 0, 0, 0, ProtectionActionType.BLOCK_BREAK).outcome());

        ChunkLandReadApi throwingGate = new ChunkLandReadApi(store::snapshot, (a, l, act, s) -> null,
                (l, r, s) -> Optional.empty(), (l, s) -> Optional.empty(),
                () -> { throw new IllegalStateException("gate"); }, (a, w, x, y, z, action, s) -> null);
        assertFalse(throwingGate.isReady());
    }

    @Test
    void legacyConstructorsDenyEveryBlockDecision() {
        ChunkLandReadApi api = new ChunkLandReadApi(storeWith(new LandId(UUID.randomUUID()), 0, 0));
        assertEquals(PermissionState.DENY,
                api.decideAtBlock(UUID.randomUUID(), WORLD, 0, 0, 0, ProtectionActionType.BLOCK_BREAK).outcome());
        assertThrows(NullPointerException.class,
                () -> api.decideAtBlock(null, WORLD, 0, 0, 0, ProtectionActionType.BLOCK_BREAK));
    }

    @Test
    void canIsFalseWhileTheIndexIsUnready() {
        LandId id = new LandId(UUID.randomUUID());
        AtomicBoolean ready = new AtomicBoolean(false);
        ChunkLandReadApi api = new ChunkLandReadApi(storeWith(id, 0, 0)::snapshot,
                (a, l, act, s) -> com.smile.chunkland.api.permission.PermissionContext.builder(act).isOwner(true).build(),
                (l, r, s) -> Optional.empty(), (l, s) -> Optional.empty(), ready::get,
                (a, w, x, y, z, action, s) -> null);
        assertFalse(api.can(UUID.randomUUID(), id, ProtectionActionType.BLOCK_BREAK));
        ready.set(true);
        assertTrue(api.can(UUID.randomUUID(), id, ProtectionActionType.BLOCK_BREAK));
    }
}

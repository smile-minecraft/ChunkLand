package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.DecisionSource;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProtectionEngineTest {

    private static LandSnapshot landAt(UUID worldId, LandId id, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(UUID.randomUUID()),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private static PermissionContextProvider fixedProvider(PermissionContext ctx) {
        return (actor, landId, action, snapshot) -> ctx;
    }

    private static PermissionContext subjectCtx(ProtectionActionType action, UUID actor, PermissionState state) {
        return new PermissionContext(action, false,
                List.of(new PermissionBinding(PermissionSubject.player(actor),
                        new Permission(action, state))),
                PermissionState.INHERIT, PermissionState.INHERIT);
    }

    @Test
    void blockBreakAllowAndDenyEndToEnd() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));

        ProtectionEngine allowEngine = new ProtectionEngine(store::snapshot,
                fixedProvider(subjectCtx(ProtectionActionType.BLOCK_BREAK, actor, PermissionState.ALLOW)));
        var allow = allowEngine.decide(actor, landId, ProtectionActionType.BLOCK_BREAK);
        assertEquals(PermissionState.ALLOW, allow.outcome());
        assertEquals(DecisionSource.SUBJECT_PERMISSION, allow.source());

        ProtectionEngine denyEngine = new ProtectionEngine(store::snapshot,
                fixedProvider(subjectCtx(ProtectionActionType.BLOCK_BREAK, actor, PermissionState.DENY)));
        var deny = denyEngine.decide(actor, landId, ProtectionActionType.BLOCK_BREAK);
        assertEquals(PermissionState.DENY, deny.outcome());
    }

    @Test
    void pvpResolvesFromLandRule() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        PermissionContext ruleAllow = new PermissionContext(
                ProtectionActionType.PLAYER_DAMAGE_PLAYER, false, List.of(),
                PermissionState.INHERIT, PermissionState.ALLOW);
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, fixedProvider(ruleAllow));
        var decision = engine.decide(actor, landId, ProtectionActionType.PLAYER_DAMAGE_PLAYER);
        assertEquals(PermissionState.ALLOW, decision.outcome());
        assertEquals(DecisionSource.LAND_RULE, decision.source());
        assertEquals(DecisionSource.LAND_RULE,
                engine.routeOf(ProtectionActionType.PLAYER_DAMAGE_PLAYER));
    }

    @Test
    void entityDamageResolvesFromSubjectPermission() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        ProtectionEngine engine = new ProtectionEngine(store::snapshot,
                fixedProvider(subjectCtx(ProtectionActionType.ENTITY_DAMAGE, actor, PermissionState.ALLOW)));
        var decision = engine.decide(actor, landId, ProtectionActionType.ENTITY_DAMAGE);
        assertEquals(PermissionState.ALLOW, decision.outcome());
        assertEquals(DecisionSource.SUBJECT_PERMISSION, decision.source());
        assertEquals(DecisionSource.SUBJECT_PERMISSION,
                engine.routeOf(ProtectionActionType.ENTITY_DAMAGE));
    }

    @Test
    void wildernessUnknownLandIsVanillaAllow() {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.empty());
        PermissionContextProvider mustNotBeCalled = (actor, landId, action, snapshot) -> {
            throw new AssertionError("wilderness must not consult the resolver");
        };
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, mustNotBeCalled);
        var decision = engine.decide(UUID.randomUUID(),
                new LandId(UUID.randomUUID()), ProtectionActionType.BLOCK_BREAK);
        assertEquals(PermissionState.ALLOW, decision.outcome(),
                "wilderness (no land) follows vanilla: never DENY");
    }

    @Test
    void wildernessUnknownChunkIsVanillaAllow() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        PermissionContextProvider mustNotBeCalled = (actor, id, action, snapshot) -> {
            throw new AssertionError("wilderness must not consult the resolver");
        };
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, mustNotBeCalled);
        var decision = engine.decideAt(UUID.randomUUID(), worldId, 9, 9, ProtectionActionType.BLOCK_BREAK);
        assertEquals(PermissionState.ALLOW, decision.outcome());
    }

    @Test
    void decideAtFindsLandByChunkCoordinates() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 3, -2))));
        ProtectionEngine engine = new ProtectionEngine(store::snapshot,
                fixedProvider(subjectCtx(ProtectionActionType.BLOCK_BREAK, actor, PermissionState.DENY)));
        assertEquals(PermissionState.DENY,
                engine.decideAt(actor, worldId, 3, -2, ProtectionActionType.BLOCK_BREAK).outcome());
    }

    @Test
    void providerExceptionIsFailClosedDeny() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        PermissionContextProvider exploding = (actor, id, action, snapshot) -> {
            throw new RuntimeException("resolver backend boom");
        };
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, exploding);
        var decision = engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK);
        assertEquals(PermissionState.DENY, decision.outcome(),
                "any decision failure must fail closed");
    }

    @Test
    void nullContextIsFailClosedDeny() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        ProtectionEngine engine = new ProtectionEngine(store::snapshot,
                (actor, id, action, snapshot) -> null);
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK).outcome());
    }

    @Test
    void constructorRejectsIncompleteRegistry() {
        LandRegistryStore store = new LandRegistryStore();
        Map<ProtectionActionType, DecisionSource> routes =
                new EnumMap<>(ProtectionActionRegistry.defaults());
        routes.remove(ProtectionActionType.ENTRY);
        assertThrows(IllegalStateException.class,
                () -> new ProtectionEngine(store::snapshot,
                        ProtectionEngine.inheritOnlyProvider(), routes),
                "engine construction with an incomplete registry must refuse startup");
    }

    @Test
    void inheritOnlyProviderDeniesInsideLand() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        ProtectionEngine engine = new ProtectionEngine(store::snapshot,
                ProtectionEngine.inheritOnlyProvider());
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK).outcome(),
                "skeleton provider has no grants: inside a land it must fail closed");
    }
}

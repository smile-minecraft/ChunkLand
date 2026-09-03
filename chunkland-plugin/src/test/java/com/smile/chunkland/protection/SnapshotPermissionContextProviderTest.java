package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionResolver;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.api.LandRuleLookup;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SnapshotPermissionContextProviderTest {

    private static LandSnapshot landOwnedBy(UUID worldId, LandId id, UUID owner) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private static LandRegistryStore storeWith(UUID worldId, LandId id, UUID owner) {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landOwnedBy(worldId, id, owner))));
        return store;
    }

    private static SubjectPermissionLookup.Grant grantWith(
            ProtectionActionType action, UUID actor, PermissionState state) {
        return new SubjectPermissionLookup.Grant(
                List.of(new PermissionBinding(PermissionSubject.player(actor),
                        new Permission(action, state))),
                PermissionState.INHERIT, PermissionState.INHERIT, PermissionState.INHERIT);
    }

    @Test
    void ownerIsAllowedBySnapshotOwnershipWithoutAnyGrant() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistryStore store = storeWith(worldId, landId, owner);
        var provider = new SnapshotPermissionContextProvider(null, null);
        var engine = new ProtectionEngine(store::snapshot, provider);
        assertEquals(PermissionState.ALLOW,
                engine.decide(owner, landId, ProtectionActionType.BLOCK_PLACE).outcome());
    }

    @Test
    void strangerWithNoGrantIsDenied() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = storeWith(worldId, landId, UUID.randomUUID());
        var engine = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(null, null));
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_PLACE).outcome());
    }

    @Test
    void subjectGrantDecidesForStranger() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID stranger = UUID.randomUUID();
        LandRegistryStore store = storeWith(worldId, landId, UUID.randomUUID());
        var action = ProtectionActionType.CONTAINER_OPEN;

        var allowing = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(null,
                        (actor, id, a, snapshot) -> grantWith(action, stranger, PermissionState.ALLOW)));
        assertEquals(PermissionState.ALLOW, allowing.decide(stranger, landId, action).outcome());

        var denying = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(null,
                        (actor, id, a, snapshot) -> grantWith(action, stranger, PermissionState.DENY)));
        assertEquals(PermissionState.DENY, denying.decide(stranger, landId, action).outcome());
    }

    @Test
    void landDefaultAllowGrantsStranger() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = storeWith(worldId, landId, UUID.randomUUID());
        var engine = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(null,
                        (actor, id, action, snapshot) -> new SubjectPermissionLookup.Grant(
                                List.of(), PermissionState.ALLOW,
                                PermissionState.INHERIT, PermissionState.INHERIT)));
        assertEquals(PermissionState.ALLOW,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.DOOR_USE).outcome());
    }

    @Test
    void subjectLookupFailureIsFailClosedDeny() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistryStore store = storeWith(worldId, landId, owner);
        var engine = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(null,
                        (actor, id, action, snapshot) -> {
                            throw new RuntimeException("grant backend boom");
                        }));
        // Even the owner is denied when grants cannot be read: no grant may be assumed.
        assertEquals(PermissionState.DENY,
                engine.decide(owner, landId, ProtectionActionType.BLOCK_BREAK).outcome());
    }

    @Test
    void absentRuleSourceDeniesRuleActionsForOwnerAndStranger() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistryStore store = storeWith(worldId, landId, owner);
        var engine = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(null, null));
        for (ProtectionActionType action : List.of(
                ProtectionActionType.PISTON_MOVE,
                ProtectionActionType.FLUID_FLOW,
                ProtectionActionType.HOPPER_TRANSFER,
                ProtectionActionType.EXPLOSION_TERRAIN,
                ProtectionActionType.EXPLOSION_ENTITY,
                ProtectionActionType.PLAYER_DAMAGE_PLAYER)) {
            assertEquals(PermissionState.DENY, engine.decide(owner, landId, action).outcome(),
                    "no rule readable: owner must still be DENY for " + action);
            assertEquals(PermissionState.DENY,
                    engine.decide(UUID.randomUUID(), landId, action).outcome(),
                    "no rule readable: stranger must be DENY for " + action);
        }
    }

    @Test
    void presentRuleSourceDecidesRuleActions() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = storeWith(worldId, landId, UUID.randomUUID());
        var action = ProtectionActionType.PISTON_MOVE;

        LandRuleLookup allow = (id, rule, snapshot) -> Optional.of(PermissionState.ALLOW);
        var allowing = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(allow, null));
        assertEquals(PermissionState.ALLOW, allowing.decide(UUID.randomUUID(), landId, action).outcome());

        LandRuleLookup deny = (id, rule, snapshot) -> Optional.of(PermissionState.DENY);
        var denying = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(deny, null));
        assertEquals(PermissionState.DENY, denying.decide(UUID.randomUUID(), landId, action).outcome());
    }

    @Test
    void ruleLookupFailureOrEmptyIsFailClosedDeny() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = storeWith(worldId, landId, UUID.randomUUID());
        var action = ProtectionActionType.FLUID_FLOW;

        var exploding = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(
                        (id, rule, snapshot) -> {
                            throw new RuntimeException("rule backend boom");
                        },
                        null));
        assertEquals(PermissionState.DENY, exploding.decide(UUID.randomUUID(), landId, action).outcome());

        var nulling = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider((id, rule, snapshot) -> null, null));
        assertEquals(PermissionState.DENY, nulling.decide(UUID.randomUUID(), landId, action).outcome());

        var empty = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(
                        (id, rule, snapshot) -> Optional.empty(), null));
        assertEquals(PermissionState.DENY, empty.decide(UUID.randomUUID(), landId, action).outcome());
    }

    @Test
    void subjectPathNeverConsultsRules() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID stranger = UUID.randomUUID();
        LandRegistryStore store = storeWith(worldId, landId, UUID.randomUUID());
        var action = ProtectionActionType.BLOCK_BREAK;
        LandRuleLookup exploding = (id, rule, snapshot) -> {
            throw new AssertionError("subject actions must not read rules");
        };
        var engine = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(exploding,
                        (actor, id, a, snapshot) -> grantWith(action, stranger, PermissionState.ALLOW)));
        assertEquals(PermissionState.ALLOW, engine.decide(stranger, landId, action).outcome());
    }

    @Test
    void unknownLandResolvesToDenyWithoutTouchingLookups() {
        var provider = new SnapshotPermissionContextProvider(
                (id, rule, snapshot) -> {
                    throw new AssertionError("unknown land must not read rules");
                },
                (actor, id, action, snapshot) -> {
                    throw new AssertionError("unknown land must not read grants");
                });
        var ctx = provider.provide(UUID.randomUUID(), new LandId(UUID.randomUUID()),
                ProtectionActionType.BLOCK_BREAK, LandRegistry.empty());
        assertEquals(PermissionState.DENY, PermissionResolver.resolve(ctx).outcome());
    }
}

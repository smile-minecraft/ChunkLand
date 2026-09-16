package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Server ACL namespace isolation at the shared management gate.
 *
 * <p>{@code PLAYER:<uuid>} and {@code SERVER} are disjoint namespaces: a
 * binding, default or explicit grant that authorises an actor on a
 * player-owned land must never authorise the same actor on a Server Land,
 * and the server-land steward grant must never leak onto player-owned land.
 * Server Land management requires the steward grant (owner-equivalent);
 * anything else fails closed before the resolver chain is consulted.
 */
class ServerAclNamespaceIsolationTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID STEWARD = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    static java.util.stream.Stream<ProtectionActionType> managementActions() {
        return ManagementPermissionGate.MANAGEMENT_ACTIONS.stream();
    }

    private static LandSnapshot playerLand(LandId id, UUID owner) {
        LandName name = LandName.of("Home");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(owner), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static LandSnapshot serverLand(LandId id) {
        LandName name = LandName.of("Spawn");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.server(), WORLD, Set.of(new ChunkKey(WORLD, 8, 8)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static PermissionContextProvider emptyProvider() {
        return new SnapshotPermissionContextProvider(null, null);
    }

    private static PermissionContextProvider grantingProvider(UUID actor, ProtectionActionType action) {
        SubjectPermissionLookup.Grant grant = new SubjectPermissionLookup.Grant(
                List.of(new PermissionBinding(
                        PermissionSubject.player(actor), new Permission(action, PermissionState.ALLOW))),
                PermissionState.INHERIT, PermissionState.INHERIT, PermissionState.INHERIT);
        return new SnapshotPermissionContextProvider(null, (a, landId, act, snapshot) -> grant);
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void grantOnServerLandWithoutStewardFlagDenied(ProtectionActionType action) {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(serverLand(id)));
        var decision = ManagementPermissionGate.check(
                ACTOR, id, action, snapshot, false, false, grantingProvider(ACTOR, action));
        assertEquals(PermissionState.DENY, decision.outcome(),
                action + ": an explicit grant must never authorise Server Land without the steward flag");
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void landDefaultAllowOnServerLandWithoutStewardFlagDenied(ProtectionActionType action) {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(serverLand(id)));
        SubjectPermissionLookup.Grant grant = new SubjectPermissionLookup.Grant(
                List.of(), PermissionState.ALLOW, PermissionState.INHERIT, PermissionState.INHERIT);
        PermissionContextProvider provider =
                new SnapshotPermissionContextProvider(null, (a, landId, act, snap) -> grant);
        var decision = ManagementPermissionGate.check(
                ACTOR, id, action, snapshot, false, false, provider);
        assertEquals(PermissionState.DENY, decision.outcome(),
                action + ": a land-default ALLOW must never authorise Server Land without the steward flag");
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void playerOwnerOfAnotherLandDeniedOnServerLand(ProtectionActionType action) {
        LandId own = new LandId(UUID.randomUUID());
        LandId server = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(own, OWNER), serverLand(server)));
        var ownDecision = ManagementPermissionGate.check(
                OWNER, own, action, snapshot, false, false, emptyProvider());
        assertEquals(PermissionState.ALLOW, ownDecision.outcome(),
                action + ": owner must still pass on their own player land");
        var serverDecision = ManagementPermissionGate.check(
                OWNER, server, action, snapshot, false, false, grantingProvider(OWNER, action));
        assertEquals(PermissionState.DENY, serverDecision.outcome(),
                action + ": a player owner must not carry authority onto Server Land");
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void stewardDecisionsStaySeparatedAcrossNamespaces(ProtectionActionType action) {
        LandId own = new LandId(UUID.randomUUID());
        LandId server = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(
                List.of(playerLand(own, STEWARD), serverLand(server)));
        var serverDecision = ManagementPermissionGate.check(
                STEWARD, server, action, snapshot, false, true, emptyProvider());
        assertEquals(PermissionState.ALLOW, serverDecision.outcome(),
                action + ": steward must pass on Server Land");
        var playerDecision = ManagementPermissionGate.check(
                STEWARD, own, action, snapshot, false, true, emptyProvider());
        assertEquals(PermissionState.ALLOW, playerDecision.outcome(),
                action + ": steward who also owns a player land passes there as owner, not via the grant");
        var foreignDecision = ManagementPermissionGate.check(
                ACTOR, own, action, snapshot, false, true, emptyProvider());
        assertEquals(PermissionState.DENY, foreignDecision.outcome(),
                action + ": the steward grant must never authorise another owner's player land");
    }

    @Test
    void strangerDeniedOnServerLandEvenWithGrant() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(serverLand(id)));
        var decision = ManagementPermissionGate.check(
                ACTOR, id, ProtectionActionType.MANAGE_MEMBER, snapshot,
                false, false, grantingProvider(ACTOR, ProtectionActionType.MANAGE_MEMBER));
        assertEquals(PermissionState.DENY, decision.outcome(),
                "stranger with a grant but no steward flag must fail closed on Server Land");
        assertTrue(decision.explanation().toLowerCase().contains("server"),
                "the denial must name the Server Land steward requirement, got: "
                        + decision.explanation());
    }

    @Test
    void throwingProviderFailsClosedForCallers() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        PermissionContextProvider throwing = (actor, landId, action, snap) -> {
            throw new IllegalStateException("lookup failed");
        };
        try {
            ManagementPermissionGate.check(
                    ACTOR, id, ProtectionActionType.DELETE_LAND, snapshot, false, false, throwing);
            assertTrue(false, "a throwing provider must not produce a decision");
        } catch (IllegalStateException expected) {
            // The dispatcher catches any such throw and denies without
            // invoking the handler, so failures stay fail-closed.
        }
    }

    @Test
    void ruleActionsStayOutsideTheManagementGate() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(serverLand(id)));
        try {
            ManagementPermissionGate.check(
                    STEWARD, id, ProtectionActionType.FIRE_SPREAD, snapshot,
                    false, true, emptyProvider());
            assertTrue(false, "a LAND_RULE action must be rejected by the management gate");
        } catch (IllegalArgumentException expected) {
            // Rule actions resolve through the rule chain, never through
            // management mutations: no namespace confusion is possible.
        }
    }
}

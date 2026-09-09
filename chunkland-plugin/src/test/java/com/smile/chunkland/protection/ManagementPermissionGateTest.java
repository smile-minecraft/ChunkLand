package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Behaviour tests for the single management-permission enforcement point.
 *
 * <p>Command handlers and future GUI/Form entry points share
 * {@link ManagementPermissionGate}: the owner passes, an explicitly authorised
 * non-owner passes, a stranger fails closed with bypass off, and the holder of
 * the server-land steward flag passes on Server Land without Admin Bypass.
 */
class ManagementPermissionGateTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STEWARD = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
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
    void ownerPassesWithBypassOff(ProtectionActionType action) {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        var decision = ManagementPermissionGate.check(
                OWNER, id, action, snapshot, false, false, emptyProvider());
        assertEquals(PermissionState.ALLOW, decision.outcome(), action + ": owner must pass");
        assertEquals(DecisionSource.SUBJECT_PERMISSION, decision.source());
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void authorisedNonOwnerPassesWithBypassOff(ProtectionActionType action) {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        var decision = ManagementPermissionGate.check(
                STRANGER, id, action, snapshot, false, false, grantingProvider(STRANGER, action));
        assertEquals(PermissionState.ALLOW, decision.outcome(),
                action + ": authorised non-owner must pass");
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void strangerDeniedFailClosedWithBypassOff(ProtectionActionType action) {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        var decision = ManagementPermissionGate.check(
                STRANGER, id, action, snapshot, false, false, emptyProvider());
        assertEquals(PermissionState.DENY, decision.outcome(),
                action + ": stranger must fail closed with bypass off");
        assertFalse(ManagementPermissionGate.isAllowed(
                STRANGER, id, action, snapshot, false, false, emptyProvider()));
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void adminBypassShortCircuitsToAllow(ProtectionActionType action) {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        var decision = ManagementPermissionGate.check(
                STRANGER, id, action, snapshot, true, false, emptyProvider());
        assertEquals(PermissionState.ALLOW, decision.outcome(),
                action + ": admin bypass must short-circuit to ALLOW");
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void serverLandStewardPassesWithoutAdminBypass(ProtectionActionType action) {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(serverLand(id)));
        var decision = ManagementPermissionGate.check(
                STEWARD, id, action, snapshot, false, true, emptyProvider());
        assertEquals(PermissionState.ALLOW, decision.outcome(),
                action + ": server-land steward must pass without admin bypass");
        assertTrue(decision.explanation().toLowerCase().contains("server"),
                "steward decision must explain the server-land grant, got: " + decision.explanation());
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void serverLandStrangerDeniedWithoutStewardFlag(ProtectionActionType action) {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(serverLand(id)));
        var decision = ManagementPermissionGate.check(
                STRANGER, id, action, snapshot, false, false, emptyProvider());
        assertEquals(PermissionState.DENY, decision.outcome(),
                action + ": stranger on server land without steward flag must be denied");
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void serverLandStewardFlagDoesNotLeakToPlayerLand(ProtectionActionType action) {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        var decision = ManagementPermissionGate.check(
                STRANGER, id, action, snapshot, false, true, emptyProvider());
        assertEquals(PermissionState.DENY, decision.outcome(),
                action + ": steward flag must not grant anything on player land");
    }

    @Test
    void unknownLandDeniedFailClosed() {
        LandId missing = new LandId(UUID.randomUUID());
        var decision = ManagementPermissionGate.check(
                STRANGER, missing, ProtectionActionType.DELETE_LAND,
                LandRegistry.empty(), false, false, emptyProvider());
        assertEquals(PermissionState.DENY, decision.outcome(), "unknown land must fail closed");
    }

    @Test
    void nonManagementActionRejected() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        assertThrows(IllegalArgumentException.class, () -> ManagementPermissionGate.check(
                OWNER, id, ProtectionActionType.BLOCK_BREAK, snapshot, false, false, emptyProvider()));
        assertFalse(ManagementPermissionGate.isManagementAction(ProtectionActionType.BLOCK_BREAK));
        assertFalse(ManagementPermissionGate.isManagementAction(ProtectionActionType.FIRE_SPREAD));
    }

    @Test
    void subcommandMappingCoversCommandEntryPoints() {
        assertEquals(Optional.of(ProtectionActionType.MANAGE_MEMBER),
                ManagementPermissionGate.actionForSubcommand("trust"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_MEMBER),
                ManagementPermissionGate.actionForSubcommand("TRUST"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_MEMBER),
                ManagementPermissionGate.actionForSubcommand("untrust"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_MEMBER),
                ManagementPermissionGate.actionForSubcommand("ban"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_MEMBER),
                ManagementPermissionGate.actionForSubcommand("unban"));
        assertEquals(Optional.of(ProtectionActionType.EXPAND_LAND),
                ManagementPermissionGate.actionForSubcommand("expand"));
        assertEquals(Optional.of(ProtectionActionType.DELETE_LAND),
                ManagementPermissionGate.actionForSubcommand("delete"));
        assertEquals(Optional.empty(), ManagementPermissionGate.actionForSubcommand("wand"));
        assertEquals(Optional.empty(), ManagementPermissionGate.actionForSubcommand("claim"));
        assertEquals(Optional.empty(), ManagementPermissionGate.actionForSubcommand(null));
    }

    @Test
    void nullInputsRejected() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.empty();
        assertThrows(NullPointerException.class, () -> ManagementPermissionGate.check(
                null, id, ProtectionActionType.DELETE_LAND, snapshot, false, false, emptyProvider()));
        assertThrows(NullPointerException.class, () -> ManagementPermissionGate.check(
                STRANGER, null, ProtectionActionType.DELETE_LAND, snapshot, false, false, emptyProvider()));
        assertThrows(NullPointerException.class, () -> ManagementPermissionGate.check(
                STRANGER, id, null, snapshot, false, false, emptyProvider()));
        assertThrows(NullPointerException.class, () -> ManagementPermissionGate.check(
                STRANGER, id, ProtectionActionType.DELETE_LAND, null, false, false, emptyProvider()));
        assertThrows(NullPointerException.class, () -> ManagementPermissionGate.check(
                STRANGER, id, ProtectionActionType.DELETE_LAND, snapshot, false, false, null));
    }

    @Test
    void managementSetHasExactlyFiveActions() {
        assertEquals(5, ManagementPermissionGate.MANAGEMENT_ACTIONS.size());
        assertTrue(ManagementPermissionGate.MANAGEMENT_ACTIONS.contains(ProtectionActionType.MANAGE_MEMBER));
        assertTrue(ManagementPermissionGate.MANAGEMENT_ACTIONS.contains(ProtectionActionType.MANAGE_PERMISSION));
        assertTrue(ManagementPermissionGate.MANAGEMENT_ACTIONS.contains(ProtectionActionType.MANAGE_SUBLAND));
        assertTrue(ManagementPermissionGate.MANAGEMENT_ACTIONS.contains(ProtectionActionType.EXPAND_LAND));
        assertTrue(ManagementPermissionGate.MANAGEMENT_ACTIONS.contains(ProtectionActionType.DELETE_LAND));
    }

    @Test
    void providerContextFlowsThroughToDecision() {
        // A land-default ALLOW grant reaches the decision through the real provider.
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        SubjectPermissionLookup.Grant grant = new SubjectPermissionLookup.Grant(
                List.of(), PermissionState.ALLOW, PermissionState.INHERIT, PermissionState.INHERIT);
        PermissionContextProvider provider =
                new SnapshotPermissionContextProvider(null, (a, landId, act, snap) -> grant);
        var decision = ManagementPermissionGate.check(
                STRANGER, id, ProtectionActionType.MANAGE_SUBLAND, snapshot, false, false, provider);
        assertEquals(PermissionState.ALLOW, decision.outcome());
        // Sanity: the provider really built a subject-permission context.
        PermissionContext ctx = provider.provide(STRANGER, id, ProtectionActionType.MANAGE_SUBLAND, snapshot);
        assertEquals(PermissionState.ALLOW, ctx.landDefault());
    }
}

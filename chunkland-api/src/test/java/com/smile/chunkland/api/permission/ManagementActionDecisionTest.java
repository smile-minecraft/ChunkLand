package com.smile.chunkland.api.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Decision tests for the five management actions (spec §24, §27.2).
 *
 * <p>Each management action resolves from {@code SUBJECT_PERMISSION}, so the
 * Owner Guarantee applies: the owner always passes, an explicitly authorised
 * non-owner passes, a stranger with no grant fails closed, and Admin Bypass
 * short-circuits to ALLOW. Bypass-off ({@code adminBypass=false}) is the
 * default under test; the bypass-on case is pinned separately.
 */
class ManagementActionDecisionTest {

    static Stream<ProtectionActionType> managementActions() {
        return Stream.of(
                ProtectionActionType.MANAGE_MEMBER,
                ProtectionActionType.MANAGE_PERMISSION,
                ProtectionActionType.MANAGE_SUBLAND,
                ProtectionActionType.EXPAND_LAND,
                ProtectionActionType.DELETE_LAND);
    }

    private static final UUID ACTOR = UUID.randomUUID();

    private static PermissionBinding player(ProtectionActionType action, PermissionState state) {
        return new PermissionBinding(PermissionSubject.player(ACTOR), new Permission(action, state));
    }

    private static PermissionBinding group(String name, ProtectionActionType action, PermissionState state) {
        return new PermissionBinding(PermissionSubject.group(name), new Permission(action, state));
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void managementActionsResolveFromSubjectPermission(ProtectionActionType action) {
        assertEquals(DecisionSource.SUBJECT_PERMISSION, action.decisionSource(),
                action + " must resolve from SUBJECT_PERMISSION so Owner Guarantee applies");
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void ownerAlwaysPassesDespiteDenies(ProtectionActionType action) {
        var ctx = PermissionContext.builder(action)
                .adminBypass(false)
                .isOwner(true)
                .landBindings(List.of(
                        player(action, PermissionState.DENY),
                        group("Banned", action, PermissionState.DENY)))
                .landDefault(PermissionState.DENY)
                .globalDefault(PermissionState.DENY)
                .build();
        var decision = PermissionResolver.resolve(ctx);
        assertEquals(PermissionState.ALLOW, decision.outcome(), action + ": owner must pass");
        assertEquals(DecisionSource.SUBJECT_PERMISSION, decision.source());
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void authorisedNonOwnerPassesWithBypassOff(ProtectionActionType action) {
        var ctx = PermissionContext.builder(action)
                .adminBypass(false)
                .isOwner(false)
                .landBindings(List.of(player(action, PermissionState.ALLOW)))
                .build();
        var decision = PermissionResolver.resolve(ctx);
        assertEquals(PermissionState.ALLOW, decision.outcome(),
                action + ": explicitly authorised non-owner must pass");
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void strangerDeniedFailClosedWithBypassOff(ProtectionActionType action) {
        var ctx = PermissionContext.builder(action)
                .adminBypass(false)
                .isOwner(false)
                .build();
        var decision = PermissionResolver.resolve(ctx);
        assertEquals(PermissionState.DENY, decision.outcome(),
                action + ": stranger with no grant must fail closed with bypass off");
        assertEquals(DecisionSource.SUBJECT_PERMISSION, decision.source());
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void strangerDeniedDespiteUnrelatedGrant(ProtectionActionType action) {
        // A grant for a *different* management action must not leak across.
        ProtectionActionType other = action == ProtectionActionType.MANAGE_MEMBER
                ? ProtectionActionType.DELETE_LAND
                : ProtectionActionType.MANAGE_MEMBER;
        var ctx = PermissionContext.builder(action)
                .adminBypass(false)
                .isOwner(false)
                .landBindings(List.of(player(other, PermissionState.ALLOW)))
                .landDefault(PermissionState.INHERIT)
                .build();
        assertEquals(PermissionState.DENY, PermissionResolver.resolve(ctx).outcome(),
                action + ": grant for " + other + " must not authorise " + action);
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void adminBypassShortCircuitsToAllow(ProtectionActionType action) {
        var ctx = PermissionContext.builder(action)
                .adminBypass(true)
                .isOwner(false)
                .landBindings(List.of(player(action, PermissionState.DENY)))
                .landDefault(PermissionState.DENY)
                .build();
        var decision = PermissionResolver.resolve(ctx);
        assertEquals(PermissionState.ALLOW, decision.outcome(),
                action + ": admin bypass must short-circuit to ALLOW");
    }

    @ParameterizedTest
    @MethodSource("managementActions")
    void groupDenyBeatsDirectAllow(ProtectionActionType action) {
        var ctx = PermissionContext.builder(action)
                .adminBypass(false)
                .isOwner(false)
                .landBindings(List.of(
                        player(action, PermissionState.ALLOW),
                        group("Banned", action, PermissionState.DENY)))
                .build();
        assertEquals(PermissionState.DENY, PermissionResolver.resolve(ctx).outcome(),
                action + ": group DENY must beat direct ALLOW");
    }

    @Test
    void managementDecisionsAreExplainable() {
        for (var action : managementActions().toList()) {
            var ctx = PermissionContext.builder(action).adminBypass(false).build();
            var decision = PermissionResolver.resolve(ctx);
            assertTrue(decision.explanation() != null && !decision.explanation().isBlank(),
                    action + " decision must carry an explanation");
        }
    }
}

package com.smile.chunkland.api.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Red-phase characterization tests for the full resolution chain, action-scoped
 * binding aggregation, wildcard/blank subject rejection, and binary-only decisions.
 * These pin the behaviour introduced to close the independent-review blockers.
 */
class PermissionResolverChainTest {

    private static final UUID ACTOR = UUID.randomUUID();

    private static PermissionBinding pb(ProtectionActionType a, PermissionState s) {
        return new PermissionBinding(PermissionSubject.player(ACTOR), new Permission(a, s));
    }

    private static PermissionBinding gb(String name, ProtectionActionType a, PermissionState s) {
        return new PermissionBinding(PermissionSubject.group(name), new Permission(a, s));
    }

    // --- Action-scoped aggregation (cross-action bindings must not leak) -------

    @Test
    void crossActionBindingDoesNotAffectDecision() {
        var action = ProtectionActionType.BLOCK_BREAK;
        // A DENY for a *different* action must not make BLOCK_BREAK DENY.
        var c = PermissionContext.builder(action)
                .landBindings(List.of(pb(ProtectionActionType.BLOCK_PLACE, PermissionState.DENY)))
                .landDefault(PermissionState.ALLOW)
                .build();
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(c).outcome());
    }

    @Test
    void sameActionDenyStillApplies() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = PermissionContext.builder(action)
                .landBindings(List.of(pb(action, PermissionState.DENY)))
                .landDefault(PermissionState.ALLOW)
                .build();
        assertEquals(PermissionState.DENY, PermissionResolver.resolve(c).outcome());
    }

    // --- Subject rejection: no blank / wildcard / EVERYONE group --------------

    @Test
    void groupBlankRejected() {
        assertThrows(IllegalArgumentException.class, () -> PermissionSubject.group(""));
        assertThrows(IllegalArgumentException.class, () -> PermissionSubject.group("   "));
    }

    @Test
    void groupWildcardRejected() {
        assertThrows(IllegalArgumentException.class, () -> PermissionSubject.group("EVERYONE"));
        assertThrows(IllegalArgumentException.class, () -> PermissionSubject.group("everyone"));
        assertThrows(IllegalArgumentException.class, () -> PermissionSubject.group("*"));
    }

    @Test
    void groupNullRejected() {
        assertThrows(NullPointerException.class, () -> PermissionSubject.group(null));
    }

    // --- Decision outcome must be binary (no INHERIT) -------------------------

    @Test
    void decisionRejectsInherit() {
        assertThrows(IllegalArgumentException.class,
                () -> new PermissionDecision(PermissionState.INHERIT, DecisionSource.SUBJECT_PERMISSION, "x"));
    }

    // --- Full subject chain: each layer stops on explicit value --------------

    @Test
    void adminBypassIsHighestAllow() {
        var action = ProtectionActionType.BLOCK_BREAK; // SUBJECT_PERMISSION
        var c = PermissionContext.builder(action)
                .adminBypass(true)
                .isOwner(false)
                .landBindings(List.of(pb(action, PermissionState.DENY)))
                .landDefault(PermissionState.DENY)
                .globalDefault(PermissionState.DENY)
                .build();
        var d = PermissionResolver.resolve(c);
        assertEquals(PermissionState.ALLOW, d.outcome());
        assertTrue(d.explanation().contains("Admin bypass"));
    }

    @Test
    void ownerGuaranteeLayer() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = PermissionContext.builder(action)
                .isOwner(true)
                .subLandBindings(List.of(pb(action, PermissionState.DENY)))
                .landBindings(List.of(gb("Banned", action, PermissionState.DENY)))
                .landDefault(PermissionState.DENY)
                .build();
        var d = PermissionResolver.resolve(c);
        assertEquals(PermissionState.ALLOW, d.outcome());
        assertEquals(DecisionSource.SUBJECT_PERMISSION, d.source());
    }

    @Test
    void subLandBindingStopsBeforeLandDeny() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = PermissionContext.builder(action)
                .subLandBindings(List.of(pb(action, PermissionState.ALLOW)))
                .landBindings(List.of(pb(action, PermissionState.DENY)))
                .landDefault(PermissionState.DENY)
                .build();
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(c).outcome());
    }

    @Test
    void subLandDefaultStopsBeforeLandDeny() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = PermissionContext.builder(action)
                .subLandDefault(PermissionState.ALLOW)
                .landBindings(List.of(pb(action, PermissionState.DENY)))
                .build();
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(c).outcome());
    }

    @Test
    void landBindingLayer() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = PermissionContext.builder(action)
                .landBindings(List.of(pb(action, PermissionState.ALLOW)))
                .landDefault(PermissionState.DENY)
                .worldDefault(PermissionState.DENY)
                .build();
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(c).outcome());
    }

    @Test
    void landDefaultLayer() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = PermissionContext.builder(action)
                .landDefault(PermissionState.ALLOW)
                .worldDefault(PermissionState.DENY)
                .build();
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(c).outcome());
    }

    @Test
    void worldDefaultLayer() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = PermissionContext.builder(action)
                .worldDefault(PermissionState.ALLOW)
                .globalDefault(PermissionState.DENY)
                .build();
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(c).outcome());
    }

    @Test
    void globalDefaultLayer() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = PermissionContext.builder(action)
                .globalDefault(PermissionState.ALLOW)
                .build();
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(c).outcome());
    }

    @Test
    void subLandBindingDenyStopsChain() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = PermissionContext.builder(action)
                .subLandBindings(List.of(pb(action, PermissionState.DENY)))
                .landDefault(PermissionState.ALLOW)
                .build();
        assertEquals(PermissionState.DENY, PermissionResolver.resolve(c).outcome());
    }

    @Test
    void allSubjectLayersInheritImplicitDeny() {
        var action = ProtectionActionType.ENTRY;
        var c = PermissionContext.builder(action).build(); // everything INHERIT
        var d = PermissionResolver.resolve(c);
        assertEquals(PermissionState.DENY, d.outcome());
        assertEquals(DecisionSource.SUBJECT_PERMISSION, d.source());
    }

    // --- Rule chain: owner never exempt, rule layers stop on explicit --------

    @Test
    void ruleOwnerInvalid() {
        var action = ProtectionActionType.PLAYER_DAMAGE_PLAYER; // LAND_RULE
        var c = PermissionContext.builder(action)
                .isOwner(true)
                .landRule(PermissionState.DENY)
                .build();
        var d = PermissionResolver.resolve(c);
        assertEquals(PermissionState.DENY, d.outcome());
        assertEquals(DecisionSource.LAND_RULE, d.source());
    }

    @Test
    void subLandRuleStopsBeforeLandRuleDeny() {
        var action = ProtectionActionType.FIRE_SPREAD; // LAND_RULE
        var c = PermissionContext.builder(action)
                .subLandRule(PermissionState.ALLOW)
                .landRule(PermissionState.DENY)
                .build();
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(c).outcome());
    }

    @Test
    void landRuleLayer() {
        var action = ProtectionActionType.FIRE_SPREAD;
        var c = PermissionContext.builder(action)
                .landRule(PermissionState.ALLOW)
                .worldDefault(PermissionState.DENY)
                .build();
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(c).outcome());
    }

    @Test
    void worldDefaultRuleLayer() {
        var action = ProtectionActionType.FIRE_SPREAD;
        var c = PermissionContext.builder(action)
                .worldDefault(PermissionState.ALLOW)
                .globalDefault(PermissionState.DENY)
                .build();
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(c).outcome());
    }

    @Test
    void globalDefaultRuleLayer() {
        var action = ProtectionActionType.FIRE_SPREAD;
        var c = PermissionContext.builder(action)
                .globalDefault(PermissionState.ALLOW)
                .build();
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(c).outcome());
    }

    @Test
    void allRuleLayersInheritImplicitDeny() {
        var action = ProtectionActionType.MOB_GRIEFING; // LAND_RULE
        var c = PermissionContext.builder(action).build();
        var d = PermissionResolver.resolve(c);
        assertEquals(PermissionState.DENY, d.outcome());
        assertEquals(DecisionSource.LAND_RULE, d.source());
    }

    // --- COMBINED ------------------------------------------------------------

    @Test
    void combinedRequiresBothAllow() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var both = PermissionContext.builder(action)
                .landBindings(List.of(pb(action, PermissionState.ALLOW)))
                .landRule(PermissionState.ALLOW)
                .build();
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolveCombined(both).outcome());
    }

    @Test
    void combinedSubjectDenyWins() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = PermissionContext.builder(action)
                .landBindings(List.of(pb(action, PermissionState.DENY)))
                .landRule(PermissionState.ALLOW)
                .build();
        assertEquals(PermissionState.DENY, PermissionResolver.resolveCombined(c).outcome());
    }

    @Test
    void combinedRuleDenyWins() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = PermissionContext.builder(action)
                .landBindings(List.of(pb(action, PermissionState.ALLOW)))
                .landRule(PermissionState.DENY)
                .build();
        assertEquals(PermissionState.DENY, PermissionResolver.resolveCombined(c).outcome());
    }

    @Test
    void combinedOwnerDoesNotRescueSubjectDeny() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = PermissionContext.builder(action)
                .isOwner(true)
                .landBindings(List.of(pb(action, PermissionState.DENY)))
                .landRule(PermissionState.ALLOW)
                .build();
        assertEquals(PermissionState.DENY, PermissionResolver.resolveCombined(c).outcome());
    }

    @Test
    void adminBypassCoversLandRule() {
        // Admin Bypass is a full bypass: it short-circuits LAND_RULE too, even when
        // the rule itself is DENY.
        var action = ProtectionActionType.FIRE_SPREAD; // LAND_RULE
        var c = PermissionContext.builder(action)
                .adminBypass(true)
                .landRule(PermissionState.DENY)
                .build();
        var d = PermissionResolver.resolve(c);
        assertEquals(PermissionState.ALLOW, d.outcome());
        assertTrue(d.explanation().contains("Admin bypass"));
    }

    // --- Immutability --------------------------------------------------------

    @Test
    void fullContextDefensivelyCopiesBindings() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var mutable = new ArrayList<PermissionBinding>();
        mutable.add(pb(action, PermissionState.ALLOW));
        var c = PermissionContext.builder(action).subLandBindings(mutable).build();
        mutable.clear();
        mutable.add(pb(action, PermissionState.DENY));
        assertEquals(1, c.subLandBindings().size());
        assertEquals(PermissionState.ALLOW, c.subLandBindings().get(0).permission().state());
    }

    @Test
    void fullContextBindingsUnmodifiable() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = PermissionContext.builder(action)
                .subLandBindings(List.of(pb(action, PermissionState.ALLOW)))
                .landBindings(List.of(pb(action, PermissionState.ALLOW)))
                .build();
        assertThrows(UnsupportedOperationException.class, () -> c.subLandBindings().add(pb(action, PermissionState.DENY)));
        assertThrows(UnsupportedOperationException.class, () -> c.landBindings().add(pb(action, PermissionState.DENY)));
    }

    // --- Five-argument compatibility -----------------------------------------

    @Test
    void fiveArgConstructorMapsToLandLevel() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = new PermissionContext(action, false, List.of(pb(action, PermissionState.ALLOW)),
                PermissionState.INHERIT, PermissionState.INHERIT);
        assertEquals(1, c.landBindings().size());
        assertTrue(c.subLandBindings().isEmpty());
        assertEquals(PermissionState.INHERIT, c.worldDefault());
        assertEquals(PermissionState.INHERIT, c.globalDefault());
        assertFalse(c.adminBypass());
        // subjectBindings() alias still works
        assertEquals(1, c.subjectBindings().size());
    }
}

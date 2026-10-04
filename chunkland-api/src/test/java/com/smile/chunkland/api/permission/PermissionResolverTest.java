package com.smile.chunkland.api.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.PermissionSubject.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Red-phase characterization tests for the four-layer Permission Resolver.
 * These pin the deterministic decision table before the
 * implementation exists; they must fail to compile / fail to pass until
 * {@link PermissionResolver}, {@link PermissionContext}, {@link PermissionDecision},
 * {@link PermissionSubject}, {@link PermissionBinding} are introduced.
 */
class PermissionResolverTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private static PermissionBinding player(ProtectionActionType a, PermissionState s) {
        return new PermissionBinding(PermissionSubject.player(ACTOR), new Permission(a, s));
    }

    private static PermissionBinding group(String name, ProtectionActionType a, PermissionState s) {
        return new PermissionBinding(PermissionSubject.group(name), new Permission(a, s));
    }

    private static PermissionContext ctx(
            ProtectionActionType action,
            boolean isOwner,
            List<PermissionBinding> bindings,
            PermissionState landDefault,
            PermissionState landRule) {
        return new PermissionContext(action, isOwner, bindings, landDefault, landRule);
    }

    // --- Layer 1: Owner Guarantee (SUBJECT_PERMISSION only) -------------------

    @Test
    void ownerGuaranteeAllowsSubjectPermissionDespiteAllDenies() {
        var action = ProtectionActionType.BLOCK_BREAK; // SUBJECT_PERMISSION
        var c = ctx(action, true,
                List.of(player(action, PermissionState.DENY), group("Banned", action, PermissionState.DENY)),
                PermissionState.DENY, PermissionState.INHERIT);
        var d = PermissionResolver.resolve(c);
        assertEquals(PermissionState.ALLOW, d.outcome());
        assertEquals(DecisionSource.SUBJECT_PERMISSION, d.source());
        assertTrue(d.explanation().toLowerCase().contains("owner"), d.explanation());
    }

    @Test
    void ownerGuaranteeDoesNotApplyToLandRule() {
        var action = ProtectionActionType.PLAYER_DAMAGE_PLAYER; // LAND_RULE
        var c = ctx(action, true, List.of(), PermissionState.INHERIT, PermissionState.DENY);
        var d = PermissionResolver.resolve(c);
        assertEquals(PermissionState.DENY, d.outcome());
        assertEquals(DecisionSource.LAND_RULE, d.source());
        assertFalse(d.explanation().toLowerCase().contains("owner"), d.explanation());
    }

    // --- Layer 2: Direct + Group same-layer DENY-first aggregation -----------

    @Test
    void directAllowDoesNotOverrideGroupDeny() {
        var action = ProtectionActionType.BLOCK_PLACE; // SUBJECT_PERMISSION
        var c = ctx(action, false,
                List.of(player(action, PermissionState.ALLOW), group("Banned", action, PermissionState.DENY)),
                PermissionState.INHERIT, PermissionState.INHERIT);
        var d = PermissionResolver.resolve(c);
        assertEquals(PermissionState.DENY, d.outcome(), "Direct ALLOW must not override Group DENY");
        assertEquals(DecisionSource.SUBJECT_PERMISSION, d.source());
    }

    @Test
    void groupAggregationDenyFirstAcrossMultipleGroups() {
        var action = ProtectionActionType.CONTAINER_OPEN; // SUBJECT_PERMISSION
        // Two groups both ALLOW -> ALLOW
        var allow = ctx(action, false,
                List.of(group("Friends", action, PermissionState.ALLOW), group("Trusted", action, PermissionState.ALLOW)),
                PermissionState.INHERIT, PermissionState.INHERIT);
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(allow).outcome());
        // One group DENY -> DENY regardless of other ALLOWs
        var deny = ctx(action, false,
                List.of(group("Friends", action, PermissionState.ALLOW), group("Visitors", action, PermissionState.DENY)),
                PermissionState.INHERIT, PermissionState.INHERIT);
        assertEquals(PermissionState.DENY, PermissionResolver.resolve(deny).outcome());
    }

    @Test
    void bindingLayerStopsResolutionOnExplicitValue() {
        var action = ProtectionActionType.DOOR_USE; // SUBJECT_PERMISSION
        // Binding ALLOW short-circuits before the DENY default
        var c = ctx(action, false,
                List.of(player(action, PermissionState.ALLOW)),
                PermissionState.DENY, PermissionState.INHERIT);
        var d = PermissionResolver.resolve(c);
        assertEquals(PermissionState.ALLOW, d.outcome());
        assertEquals(DecisionSource.SUBJECT_PERMISSION, d.source());
    }

    // --- Layer 3: Land Default (fall-through on INHERIT) ---------------------

    @Test
    void inheritFallsThroughToLandDefault() {
        var action = ProtectionActionType.BLOCK_BREAK; // SUBJECT_PERMISSION
        var c = ctx(action, false, List.of(), PermissionState.ALLOW, PermissionState.INHERIT);
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(c).outcome());
        var deny = ctx(action, false, List.of(), PermissionState.DENY, PermissionState.INHERIT);
        assertEquals(PermissionState.DENY, PermissionResolver.resolve(deny).outcome());
    }

    @Test
    void missingDefaultResolvesToImplicitDenyFailClosed() {
        var action = ProtectionActionType.ENTRY; // SUBJECT_PERMISSION
        var c = ctx(action, false, List.of(), PermissionState.INHERIT, PermissionState.INHERIT);
        var d = PermissionResolver.resolve(c);
        assertEquals(PermissionState.DENY, d.outcome(), "All-INHERIT must fail closed to DENY");
        assertEquals(DecisionSource.SUBJECT_PERMISSION, d.source());
        assertTrue(d.explanation().toLowerCase().contains("default") || d.explanation().toLowerCase().contains("implicit"),
                d.explanation());
    }

    // --- Layer 4: Land Rule (LAND_RULE category, owner never exempt) ---------

    @Test
    void landRuleResolutionIgnoresOwnerAndBindings() {
        var action = ProtectionActionType.FIRE_SPREAD; // LAND_RULE
        var allow = ctx(action, false, List.of(), PermissionState.INHERIT, PermissionState.ALLOW);
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(allow).outcome());
        assertEquals(DecisionSource.LAND_RULE, PermissionResolver.resolve(allow).source());
        var deny = ctx(action, false, List.of(), PermissionState.INHERIT, PermissionState.DENY);
        assertEquals(PermissionState.DENY, PermissionResolver.resolve(deny).outcome());
        // Even the owner is bound by the rule
        var ownerDenied = ctx(action, true, List.of(), PermissionState.INHERIT, PermissionState.DENY);
        assertEquals(PermissionState.DENY, PermissionResolver.resolve(ownerDenied).outcome());
    }

    @Test
    void landRuleMissingResolvesToImplicitDeny() {
        var action = ProtectionActionType.MOB_GRIEFING; // LAND_RULE
        var c = ctx(action, false, List.of(), PermissionState.INHERIT, PermissionState.INHERIT);
        var d = PermissionResolver.resolve(c);
        assertEquals(PermissionState.DENY, d.outcome());
        assertEquals(DecisionSource.LAND_RULE, d.source());
    }

    // --- COMBINED (defined semantics; no action currently uses it) -----------

    @Test
    void combinedRequiresBothPermissionAndRuleAllow() {
        var action = ProtectionActionType.BLOCK_BREAK; // stand-in; tested via internal path
        // subject ALLOW + rule ALLOW -> ALLOW
        var both = ctx(action, false,
                List.of(player(action, PermissionState.ALLOW)),
                PermissionState.INHERIT, PermissionState.ALLOW);
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolveCombined(both).outcome());
        // subject DENY + rule ALLOW -> DENY
        var subjDeny = ctx(action, false,
                List.of(player(action, PermissionState.DENY)),
                PermissionState.INHERIT, PermissionState.ALLOW);
        assertEquals(PermissionState.DENY, PermissionResolver.resolveCombined(subjDeny).outcome());
        // subject ALLOW + rule DENY -> DENY (rule not bypassed by subject)
        var ruleDeny = ctx(action, false,
                List.of(player(action, PermissionState.ALLOW)),
                PermissionState.INHERIT, PermissionState.DENY);
        assertEquals(PermissionState.DENY, PermissionResolver.resolveCombined(ruleDeny).outcome());
        // owner guarantee must NOT rescue a COMBINED subject DENY
        var owner = ctx(action, true,
                List.of(player(action, PermissionState.DENY)),
                PermissionState.INHERIT, PermissionState.ALLOW);
        assertEquals(PermissionState.DENY, PermissionResolver.resolveCombined(owner).outcome());
    }

    // --- EVERYONE binding must not exist -------------------------------------

    @Test
    void everyoneBindingDoesNotExist() {
        // The subject kind enum has exactly PLAYER and GROUP; no EVERYONE value.
        assertEquals(2, Kind.values().length);
        assertEquals(Kind.PLAYER, Kind.valueOf("PLAYER"));
        assertEquals(Kind.GROUP, Kind.valueOf("GROUP"));
        assertThrows(IllegalArgumentException.class, () -> Kind.valueOf("EVERYONE"));
        // No factory can construct a wildcard subject.
        assertFalse(hasEveryoneFactory());
    }

    private static boolean hasEveryoneFactory() {
        for (var m : PermissionSubject.class.getDeclaredMethods()) {
            if (m.getName().equalsIgnoreCase("everyone")) {
                return true;
            }
        }
        return false;
    }

    // --- DecisionSource routing per action -----------------------------------

    @Test
    void everyActionDecisionSourceIsRoutedToItsCategory() {
        for (var a : ProtectionActionType.values()) {
            var source = a.decisionSource();
            var c = ctx(a, false, List.of(), PermissionState.INHERIT, PermissionState.INHERIT);
            var d = PermissionResolver.resolve(c);
            assertEquals(source, d.source(), a + " must resolve under its declared DecisionSource");
        }
    }

    @Test
    void subjectPermissionActionNeverConsultsRuleLayer() {
        // A SUBJECT_PERMISSION action with a DENY rule must still be decided by the
        // subject chain, not short-circuited by the rule.
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = ctx(action, false, List.of(player(action, PermissionState.ALLOW)),
                PermissionState.INHERIT, PermissionState.DENY);
        var d = PermissionResolver.resolve(c);
        assertEquals(PermissionState.ALLOW, d.outcome());
        assertEquals(DecisionSource.SUBJECT_PERMISSION, d.source());
    }

    // --- Immutability of input and output ------------------------------------

    @Test
    void inputContextDefensivelyCopiesBindings() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var mutable = new ArrayList<PermissionBinding>();
        mutable.add(player(action, PermissionState.ALLOW));
        var c = ctx(action, false, mutable, PermissionState.INHERIT, PermissionState.INHERIT);
        // Mutating the caller's list must not affect the context.
        mutable.clear();
        mutable.add(player(action, PermissionState.DENY));
        assertEquals(1, c.subjectBindings().size());
        assertEquals(PermissionState.ALLOW, c.subjectBindings().get(0).permission().state());
        // The exposed list must be unmodifiable.
        assertThrows(UnsupportedOperationException.class,
                () -> c.subjectBindings().add(player(action, PermissionState.DENY)));
    }

    @Test
    void outputDecisionIsImmutableAndExplainable() {
        var action = ProtectionActionType.BLOCK_BREAK;
        var c = ctx(action, false, List.of(player(action, PermissionState.ALLOW)),
                PermissionState.INHERIT, PermissionState.INHERIT);
        var d = PermissionResolver.resolve(c);
        assertEquals(PermissionState.ALLOW, d.outcome());
        assertTrue(d.explanation() != null && !d.explanation().isBlank());
        // A second resolve returns an equal but independently constructed decision.
        var d2 = PermissionResolver.resolve(c);
        assertNotSame(d, d2);
        assertEquals(d, d2);
    }

    @Test
    void contextRejectsNullComponents() {
        var action = ProtectionActionType.BLOCK_BREAK;
        assertThrows(NullPointerException.class,
                () -> new PermissionContext(null, false, List.of(), PermissionState.INHERIT, PermissionState.INHERIT));
        assertThrows(NullPointerException.class,
                () -> new PermissionContext(action, false, null, PermissionState.INHERIT, PermissionState.INHERIT));
        assertThrows(NullPointerException.class,
                () -> new PermissionContext(action, false, List.of(), null, PermissionState.INHERIT));
        assertThrows(NullPointerException.class,
                () -> new PermissionContext(action, false, List.of(), PermissionState.INHERIT, null));
    }
}

package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.PermissionResolver;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Red contract for the read-only permission explain classifier.
 *
 * <p>The service derives its layer from the immutable context structure plus
 * the resolver decision source — never by parsing the human-readable
 * explanation text.
 */
class PermissionExplainServiceTest {

    private static PermissionBinding allow(UUID actor, ProtectionActionType action) {
        return new PermissionBinding(PermissionSubject.player(actor),
                new Permission(action, PermissionState.ALLOW));
    }

    private static PermissionBinding deny(UUID actor, ProtectionActionType action) {
        return new PermissionBinding(PermissionSubject.player(actor),
                new Permission(action, PermissionState.DENY));
    }

    private static PermissionExplain explainOf(PermissionContext ctx) {
        PermissionDecision decision = PermissionResolver.resolve(ctx);
        return PermissionExplainService.explain(ctx, decision, null, false, false);
    }

    @Test
    void ownerGuaranteeLayer() {
        PermissionContext ctx = PermissionContext.builder(ProtectionActionType.BLOCK_BREAK)
                .isOwner(true).build();
        PermissionExplain got = explainOf(ctx);
        assertEquals(PermissionState.ALLOW, got.outcome());
        assertEquals(DecisionSource.SUBJECT_PERMISSION, got.source());
        assertEquals(PermissionExplainLayer.GUARANTEE, got.layer());
    }

    @Test
    void adminBypassLayer() {
        PermissionContext ctx = PermissionContext.builder(ProtectionActionType.BLOCK_BREAK)
                .adminBypass(true).build();
        PermissionExplain got = explainOf(ctx);
        assertEquals(PermissionState.ALLOW, got.outcome());
        assertEquals(PermissionExplainLayer.BYPASS, got.layer());
    }

    @Test
    void stewardLayerOnServerLand() {
        PermissionContext ctx = PermissionContext.builder(ProtectionActionType.MANAGE_PERMISSION)
                .build();
        PermissionDecision decision = new PermissionDecision(PermissionState.ALLOW,
                DecisionSource.SUBJECT_PERMISSION, "Server-land steward grant -> ALLOW");
        PermissionExplain got = PermissionExplainService.explain(
                ctx, decision, null, true, true);
        assertEquals(PermissionExplainLayer.STEWARD, got.layer());
        assertEquals(PermissionState.ALLOW, got.outcome());
    }

    @Test
    void sublandBindingBeatsLandBinding() {
        UUID actor = UUID.randomUUID();
        PermissionContext ctx = PermissionContext.builder(ProtectionActionType.BLOCK_BREAK)
                .subLandBindings(List.of(deny(actor, ProtectionActionType.BLOCK_BREAK)))
                .landBindings(List.of(allow(actor, ProtectionActionType.BLOCK_BREAK)))
                .build();
        PermissionExplain got = explainOf(ctx);
        assertEquals(PermissionState.DENY, got.outcome());
        assertEquals(PermissionExplainLayer.SUBLAND_BINDING, got.layer());
    }

    @Test
    void sublandDefaultLayer() {
        PermissionContext ctx = PermissionContext.builder(ProtectionActionType.BLOCK_BREAK)
                .subLandDefault(PermissionState.ALLOW)
                .landDefault(PermissionState.DENY)
                .build();
        PermissionExplain got = explainOf(ctx);
        assertEquals(PermissionState.ALLOW, got.outcome());
        assertEquals(PermissionExplainLayer.SUBLAND_DEFAULT, got.layer());
    }

    @Test
    void groupDenyBeatsDirectAllowAtLandBinding() {
        UUID actor = UUID.randomUUID();
        PermissionBinding directAllow = allow(actor, ProtectionActionType.BLOCK_PLACE);
        PermissionBinding groupDeny = new PermissionBinding(PermissionSubject.group("Crew"),
                new Permission(ProtectionActionType.BLOCK_PLACE, PermissionState.DENY));
        PermissionContext ctx = PermissionContext.builder(ProtectionActionType.BLOCK_PLACE)
                .landBindings(List.of(directAllow, groupDeny)).build();
        PermissionExplain got = explainOf(ctx);
        assertEquals(PermissionState.DENY, got.outcome());
        assertEquals(PermissionExplainLayer.LAND_BINDING, got.layer());
    }

    @Test
    void landDefaultWorldDefaultAndGlobalDefaultLayers() {
        PermissionExplain land = explainOf(PermissionContext.builder(ProtectionActionType.DOOR_USE)
                .landDefault(PermissionState.DENY).build());
        assertEquals(PermissionExplainLayer.LAND_DEFAULT, land.layer());
        assertEquals(PermissionState.DENY, land.outcome());

        PermissionExplain world = explainOf(PermissionContext.builder(ProtectionActionType.DOOR_USE)
                .worldDefault(PermissionState.ALLOW).build());
        assertEquals(PermissionExplainLayer.WORLD_DEFAULT, world.layer());

        PermissionExplain global = explainOf(PermissionContext.builder(ProtectionActionType.DOOR_USE)
                .globalDefault(PermissionState.DENY).build());
        assertEquals(PermissionExplainLayer.GLOBAL_DEFAULT, global.layer());
    }

    @Test
    void ruleLayersAndOwnerNeverRescuesRule() {
        PermissionExplain subRule = explainOf(PermissionContext.builder(
                        ProtectionActionType.PISTON_MOVE)
                .isOwner(true)
                .subLandRule(PermissionState.DENY)
                .landRule(PermissionState.ALLOW)
                .build());
        assertEquals(PermissionState.DENY, subRule.outcome());
        assertEquals(DecisionSource.LAND_RULE, subRule.source());
        assertEquals(PermissionExplainLayer.SUBLAND_RULE, subRule.layer());

        PermissionExplain landRule = explainOf(PermissionContext.builder(
                        ProtectionActionType.FIRE_SPREAD)
                .landRule(PermissionState.ALLOW).build());
        assertEquals(PermissionExplainLayer.LAND_RULE, landRule.layer());
    }

    @Test
    void implicitDenyWhenEveryLayerInherits() {
        PermissionExplain got = explainOf(
                PermissionContext.builder(ProtectionActionType.BUCKET_USE).build());
        assertEquals(PermissionState.DENY, got.outcome());
        assertEquals(PermissionExplainLayer.IMPLICIT_DENY, got.layer());
    }

    @Test
    void matchesResolverOutcomeAndSourceAcrossLayers() {
        List<PermissionContext> contexts = List.of(
                PermissionContext.builder(ProtectionActionType.BLOCK_BREAK)
                        .isOwner(true).build(),
                PermissionContext.builder(ProtectionActionType.BLOCK_BREAK)
                        .adminBypass(true).build(),
                PermissionContext.builder(ProtectionActionType.ENTRY)
                        .landDefault(PermissionState.ALLOW).build(),
                PermissionContext.builder(ProtectionActionType.PISTON_MOVE)
                        .landRule(PermissionState.DENY).build(),
                PermissionContext.builder(ProtectionActionType.CONTAINER_OPEN).build());
        for (PermissionContext ctx : contexts) {
            PermissionDecision decision = PermissionResolver.resolve(ctx);
            PermissionExplain got =
                    PermissionExplainService.explain(ctx, decision, null, false, false);
            assertEquals(decision.outcome(), got.outcome(),
                    "explain outcome must equal resolver outcome for " + ctx.action());
            assertEquals(decision.source(), got.source(),
                    "explain source must equal resolver source for " + ctx.action());
        }
    }

    @Test
    void layerDoesNotParseExplanationText() {
        PermissionContext ctx = PermissionContext.builder(ProtectionActionType.BLOCK_BREAK)
                .landDefault(PermissionState.ALLOW).build();
        PermissionDecision tampered = new PermissionDecision(PermissionState.ALLOW,
                DecisionSource.SUBJECT_PERMISSION,
                "SubLand binding aggregate: DENY -> DENY (tampered copy)");
        PermissionExplain got =
                PermissionExplainService.explain(ctx, tampered, null, false, false);
        assertEquals(PermissionExplainLayer.LAND_DEFAULT, got.layer(),
                "layer must follow context structure, not explanation wording");
    }

    @Test
    void coveringSublandIdTravelsWithoutPlayerDetails() {
        UUID actor = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        SubLandId covering = new SubLandId(UUID.randomUUID());
        PermissionContext ctx = PermissionContext.builder(ProtectionActionType.BLOCK_BREAK)
                .subLandBindings(List.of(deny(actor, ProtectionActionType.BLOCK_BREAK)))
                .build();
        PermissionDecision decision = PermissionResolver.resolve(ctx);
        PermissionExplain got = PermissionExplainService.explain(
                ctx, decision, covering, false, false);
        assertEquals(covering.value().toString(), got.coveringSubLandId());
        String rendered = got.action() + "|" + got.outcome() + "|" + got.source()
                + "|" + got.layer() + "|" + got.reason();
        org.junit.jupiter.api.Assertions.assertFalse(rendered.contains(foreign.toString()),
                "explain must not leak unrelated player ids");
        assertNull(PermissionExplainService.explain(ctx, decision, null, false, false)
                .coveringSubLandId());
    }
}

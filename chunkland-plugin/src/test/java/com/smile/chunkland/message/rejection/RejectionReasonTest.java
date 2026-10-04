package com.smile.chunkland.message.rejection;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * The reason sentence is chosen from the resolver's own decision trace, so
 * these pins run real resolutions instead of hand-written strings: a rename
 * of a layer label in the resolver fails here instead of silently turning
 * every notice into the generic sentence.
 */
class RejectionReasonTest {

    private static String reasonFor(PermissionContext ctx) {
        PermissionDecision decision = PermissionResolver.resolve(ctx);
        assertEquals(PermissionState.DENY, decision.outcome(), decision.explanation());
        return PipelineRejectionRenderer.reasonKey(decision.explanation(), true);
    }

    @Test
    void defaultLayerDenyReadsAsNotAMember() {
        assertEquals(PipelineRejectionRenderer.REASON_NOT_MEMBER_KEY, reasonFor(
                new PermissionContext(ProtectionActionType.BLOCK_BREAK, false, List.of(),
                        PermissionState.DENY, PermissionState.INHERIT)));
        assertEquals(PipelineRejectionRenderer.REASON_NOT_MEMBER_KEY, reasonFor(
                new PermissionContext(ProtectionActionType.BLOCK_BREAK, false, List.of(),
                        PermissionState.INHERIT, PermissionState.INHERIT)),
                "nothing set anywhere still means the player holds no grant");
    }

    @Test
    void failClosedTraceReadsAsDataNotReady() {
        String trace = new PermissionDecision(PermissionState.DENY,
                DecisionSource.SUBJECT_PERMISSION,
                "Fail-closed (snapshot unavailable) -> DENY").explanation();
        assertEquals(PipelineRejectionRenderer.REASON_UNAVAILABLE_KEY,
                PipelineRejectionRenderer.reasonKey(trace, true));
        assertEquals(PipelineRejectionRenderer.REASON_UNAVAILABLE_KEY,
                PipelineRejectionRenderer.reasonKey(trace, false),
                "data-not-ready needs no land name");
    }

    @Test
    void unknownLandKeepsTheNoticeGeneric() {
        assertEquals(PipelineRejectionRenderer.REASON_NO_PERMISSION_KEY,
                PipelineRejectionRenderer.reasonKey("Land default: DENY -> DENY", false));
        assertEquals(PipelineRejectionRenderer.REASON_NO_PERMISSION_KEY,
                PipelineRejectionRenderer.reasonKey(null, false));
    }

    private static PermissionBinding denyBinding() {
        return new PermissionBinding(PermissionSubject.player(UUID.randomUUID()),
                new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.DENY));
    }

    private static PermissionContext layered(List<PermissionBinding> subLandBindings,
            PermissionState subLandDefault, List<PermissionBinding> landBindings,
            PermissionState landDefault, PermissionState worldDefault) {
        return new PermissionContext(ProtectionActionType.BLOCK_BREAK, false, false,
                subLandBindings, subLandDefault, landBindings, landDefault, worldDefault,
                PermissionState.INHERIT, PermissionState.INHERIT, PermissionState.INHERIT);
    }

    @Test
    void grantThatDeniesReadsAsExplicitlyDenied() {
        assertEquals(PipelineRejectionRenderer.REASON_EXPLICIT_KEY, reasonFor(layered(
                List.of(), PermissionState.INHERIT, List.of(denyBinding()),
                PermissionState.ALLOW, PermissionState.INHERIT)));
        assertEquals(PipelineRejectionRenderer.REASON_EXPLICIT_KEY, reasonFor(layered(
                List.of(denyBinding()), PermissionState.INHERIT, List.of(),
                PermissionState.ALLOW, PermissionState.INHERIT)));
    }

    @Test
    void sublandDefaultDenyReadsAsThatAreaBeingClosed() {
        assertEquals(PipelineRejectionRenderer.REASON_SUBLAND_KEY, reasonFor(layered(
                List.of(), PermissionState.DENY, List.of(),
                PermissionState.ALLOW, PermissionState.INHERIT)));
    }

    @Test
    void worldDefaultDenyStillReadsAsNotAMember() {
        assertEquals(PipelineRejectionRenderer.REASON_NOT_MEMBER_KEY, reasonFor(layered(
                List.of(), PermissionState.INHERIT, List.of(),
                PermissionState.INHERIT, PermissionState.DENY)));
    }
}

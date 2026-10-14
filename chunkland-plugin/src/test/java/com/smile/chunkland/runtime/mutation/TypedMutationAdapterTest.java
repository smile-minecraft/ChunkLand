package com.smile.chunkland.runtime.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smile.chunkland.api.event.PermissionChangedEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.mutation.ExpectedVersion;
import com.smile.chunkland.api.mutation.MutationKind;
import com.smile.chunkland.api.mutation.MutationOutcome;
import com.smile.chunkland.api.mutation.MutationRequest;
import com.smile.chunkland.api.mutation.MutationResult;
import com.smile.chunkland.api.mutation.TypedMutationRequest;
import com.smile.chunkland.api.mutation.TypedMutationResult;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Cross-package proof plus adapter gates for the typed mutation contract.
 *
 * <p>This test lives in the plugin module and builds every typed request
 * shape, proving the public contract compiles and is usable outside the API
 * module.
 */
class TypedMutationAdapterTest {

    private final UUID world = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID actor = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final OwnerRef owner = OwnerRef.player(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private final LandId landId = new LandId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
    private final UUID operationId = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private static TypedMutationResult await(CompletionStage<TypedMutationResult> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private TypedMutationRequest deleteRequest() {
        return new TypedMutationRequest.LandDelete(
                actor, owner, landId, ExpectedVersion.structure(7L), operationId);
    }

    private MutationServiceAdapter untouchedDelegateAdapter(AtomicBoolean touched) {
        return new MutationServiceAdapter(
                request -> {
                    touched.set(true);
                    return CompletableFuture.completedFuture(MutationResult.success(landId));
                },
                EnumSet.allOf(MutationKind.class));
    }

    @Test
    void allNineShapesCompileAndSubmitFromPluginModule() throws Exception {
        AtomicBoolean touched = new AtomicBoolean(false);
        MutationServiceAdapter adapter = untouchedDelegateAdapter(touched);
        var cuboid = new Cuboid(16, 0, 32, 31, 255, 47);
        Set<ChunkKey> chunks = cuboid.coveredChunks(world);
        var subLandId = new SubLandId(UUID.randomUUID());
        var subject = UUID.randomUUID();

        TypedMutationRequest[] all = {
            new TypedMutationRequest.LandCreate(
                    actor, owner, null, null, UUID.randomUUID(), world, Set.of(new ChunkKey(world, 0, 0)), "A"),
            new TypedMutationRequest.LandDelete(
                    actor, owner, landId, ExpectedVersion.structure(1L), UUID.randomUUID()),
            new TypedMutationRequest.LandChunkAdd(
                    actor, owner, landId, ExpectedVersion.structure(1L), UUID.randomUUID(),
                    world, Set.of(new ChunkKey(world, 0, 0))),
            new TypedMutationRequest.LandChunkRemove(
                    actor, owner, landId, ExpectedVersion.structure(1L), UUID.randomUUID(),
                    world, Set.of(new ChunkKey(world, 0, 0))),
            new TypedMutationRequest.LandRename(
                    actor, owner, landId, ExpectedVersion.structure(1L), UUID.randomUUID(), "B"),
            new TypedMutationRequest.SublandCreate(
                    actor, owner, landId, ExpectedVersion.structure(1L), UUID.randomUUID(),
                    world, "Cabin", chunks, cuboid),
            new TypedMutationRequest.SublandDelete(
                    actor, owner, landId, ExpectedVersion.structure(1L), UUID.randomUUID(), subLandId),
            new TypedMutationRequest.PermissionChange(
                    actor, owner, landId, ExpectedVersion.policy(1L), UUID.randomUUID(), null,
                    PermissionChangedEvent.ChangeKind.TRUST, PermissionChangedEvent.SubjectKind.PLAYER,
                    subject, null, null, null),
            new TypedMutationRequest.RuleChange(
                    actor, owner, landId, ExpectedVersion.policy(1L), UUID.randomUUID(),
                    LandRuleType.PVP, PermissionState.DENY),
        };
        assertEquals(9, all.length);
        for (TypedMutationRequest request : all) {
            TypedMutationResult result = await(adapter.submitTyped(request));
            assertEquals(MutationOutcome.REJECTED, result.outcome(), "kind " + request.kind());
            assertEquals(MutationServiceAdapter.UNSUPPORTED_DIAGNOSTIC, result.diagnosticKey());
            assertFalse(result.submitted());
        }
        assertFalse(touched.get(), "typed entry must not reach the legacy delegate");
    }

    @Test
    void typedSubmissionDoesNotUseLegacySupportedSet() throws Exception {
        AtomicBoolean touched = new AtomicBoolean(false);
        MutationServiceAdapter adapter = new MutationServiceAdapter(
                request -> {
                    touched.set(true);
                    return CompletableFuture.completedFuture(MutationResult.success(landId));
                },
                EnumSet.of(MutationKind.LAND_CREATE));
        TypedMutationResult result = await(adapter.submitTyped(deleteRequest()));
        assertEquals(MutationOutcome.REJECTED, result.outcome());
        assertEquals(MutationServiceAdapter.UNSUPPORTED_DIAGNOSTIC, result.diagnosticKey());
        assertFalse(result.submitted());
        assertFalse(touched.get(), "unsupported kind must not touch the delegate");
    }

    @Test
    void noLegacySupplementEntryExists() {
        // There must be no entry point that accepts safety supplements alongside
        // a legacy envelope: such an entry could only drop them before delegating.
        assertThrows(NoSuchMethodException.class, () -> MutationServiceAdapter.class.getMethod(
                "submitLegacy", MutationRequest.class, UUID.class, ExpectedVersion.class));
    }

    @Test
    void nullTypedRequestIsRejectedSynchronously() {
        MutationServiceAdapter adapter = untouchedDelegateAdapter(new AtomicBoolean(false));
        assertThrows(NullPointerException.class, () -> adapter.submitTyped(null));
    }
}

package com.smile.chunkland.api.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TypedSubmitBridgeTest {

    private final UUID actor = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final OwnerRef owner = OwnerRef.player(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private final LandId landId = new LandId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
    private final UUID operationId = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private TypedMutationRequest.LandDelete request() {
        return new TypedMutationRequest.LandDelete(
                actor, owner, landId, ExpectedVersion.structure(7L), operationId);
    }

    private static TypedMutationResult await(CompletionStage<TypedMutationResult> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void nullTypedRequestIsRejectedSynchronously() {
        ChunkLandMutations facade = request -> new CompletableFuture<>();
        assertThrows(NullPointerException.class, () -> facade.submitTyped(null));
    }

    @Test
    void versionedTypedRequestNeverReachesDownstream() throws Exception {
        AtomicBoolean touched = new AtomicBoolean(false);
        ChunkLandMutations facade = request -> {
            touched.set(true);
            return CompletableFuture.completedFuture(MutationResult.success(landId));
        };
        TypedMutationResult result = await(facade.submitTyped(request()));
        assertEquals(MutationOutcome.REJECTED, result.outcome());
        assertEquals("mutation.unsupported", result.diagnosticKey());
        assertEquals(operationId, result.operationId());
        assertFalse(result.submitted());
        assertFalse(touched.get(),
                "typed entry must not convert and execute while safety fields cannot reach the pipeline");
    }

    @Test
    void createTypedRequestNeverReachesDownstream() throws Exception {
        AtomicBoolean touched = new AtomicBoolean(false);
        ChunkLandMutations facade = request -> {
            touched.set(true);
            return CompletableFuture.completedFuture(MutationResult.success(landId));
        };
        var world = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var create = new TypedMutationRequest.LandCreate(
                actor, owner, null, null, operationId, world,
                Set.of(new com.smile.chunkland.api.land.ChunkKey(world, 0, 0)), "Home");
        TypedMutationResult result = await(facade.submitTyped(create));
        assertEquals(MutationOutcome.REJECTED, result.outcome());
        assertEquals(operationId, result.operationId());
        assertFalse(touched.get());
    }
}

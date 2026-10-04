package com.smile.chunkland.api.mutation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MutationContractTest {

    private final UUID world = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void mutationRequestDefensiveCopiesChunks() {
        var input = new HashSet<ChunkKey>();
        input.add(new ChunkKey(world, 1, 1));
        var req = new MutationRequest(MutationKind.LAND_CREATE, null, OwnerRef.server(), input, "Home");
        input.add(new ChunkKey(world, 2, 2));
        assertEquals(1, req.chunks().size());
        assertThrows(UnsupportedOperationException.class, () -> req.chunks().add(new ChunkKey(world, 3, 3)));
    }

    @Test
    void mutationRequestNullChunksBecomesEmpty() {
        var req = new MutationRequest(MutationKind.LAND_DELETE, new LandId(UUID.randomUUID()), null, null, null);
        assertNotNull(req.chunks());
        assertTrue(req.chunks().isEmpty());
    }

    @Test
    void mutationRequestRejectsNullKind() {
        assertThrows(NullPointerException.class, () -> new MutationRequest(null, null, null, null, null));
    }

    @Test
    void mutationResultFactoriesAndNullOutcome() {
        var ok = MutationResult.success(new LandId(UUID.randomUUID()));
        assertEquals(MutationOutcome.SUCCESS, ok.outcome());
        var rej = MutationResult.rejected("land.name.conflict");
        assertEquals(MutationOutcome.REJECTED, rej.outcome());
        assertEquals("land.name.conflict", rej.diagnosticKey());
        var fail = MutationResult.failed("economy.unavailable");
        assertEquals(MutationOutcome.FAILED, fail.outcome());
        assertThrows(NullPointerException.class, () -> new MutationResult(null, null, null));
    }

    @Test
    void mutationKindCoversEveryDeclaredEvent() {
        for (var k : new MutationKind[] {
                MutationKind.LAND_CREATE, MutationKind.LAND_DELETE, MutationKind.LAND_CHUNK_ADD,
                MutationKind.LAND_CHUNK_REMOVE, MutationKind.LAND_RENAME, MutationKind.SUBLAND_CREATE,
                MutationKind.SUBLAND_DELETE, MutationKind.PERMISSION_CHANGE, MutationKind.RULE_CHANGE}) {
            assertNotNull(k);
        }
        assertDoesNotThrow(() -> MutationKind.valueOf("LAND_CREATE"));
    }
}

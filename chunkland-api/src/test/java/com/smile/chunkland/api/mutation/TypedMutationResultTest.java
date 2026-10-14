package com.smile.chunkland.api.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.LandId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedMutationResultTest {

    private final UUID operationId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private final LandId landId = new LandId(UUID.fromString("44444444-4444-4444-4444-444444444444"));

    @Test
    void successMeansSubmitted() {
        var result = TypedMutationResult.success(landId, operationId);
        assertEquals(MutationOutcome.SUCCESS, result.outcome());
        assertEquals(landId, result.landId());
        assertEquals(operationId, result.operationId());
        assertTrue(result.submitted(), "success is only reported after durable finalize");
        assertNull(result.diagnosticKey());
    }

    @Test
    void rejectedMeansNeverSubmitted() {
        var result = TypedMutationResult.rejected("reservation.conflict", operationId);
        assertEquals(MutationOutcome.REJECTED, result.outcome());
        assertFalse(result.submitted());
        assertEquals("reservation.conflict", result.diagnosticKey());
        assertNull(result.landId());
    }

    @Test
    void failedBeforeSubmitIsDistinguishable() {
        var result = TypedMutationResult.failedBeforeSubmit("validation.failed", operationId);
        assertEquals(MutationOutcome.FAILED, result.outcome());
        assertFalse(result.submitted());
        assertEquals("validation.failed", result.diagnosticKey());
    }

    @Test
    void failedAfterCommitIsDistinguishable() {
        var result = TypedMutationResult.failedAfterCommit("economy.failed", operationId);
        assertEquals(MutationOutcome.FAILED, result.outcome());
        assertTrue(result.submitted(),
                "post-commit failure must be told apart from never-submitted");
        assertEquals("economy.failed", result.diagnosticKey());
    }

    @Test
    void factoriesRejectBlankKeysAndNullIds() {
        assertThrows(NullPointerException.class, () -> TypedMutationResult.success(landId, null));
        assertThrows(NullPointerException.class, () -> TypedMutationResult.success(null, operationId));
        var missingId = TypedMutationResult.rejected("mutation.unsupported", null);
        assertEquals(MutationOutcome.REJECTED, missingId.outcome());
        assertNull(missingId.operationId(), "a request with no key yields a result with no key");
        assertThrows(IllegalArgumentException.class, () ->
                TypedMutationResult.rejected("  ", operationId));
        assertThrows(NullPointerException.class, () ->
                new TypedMutationResult(null, null, null, operationId, false));
    }

    @Test
    void staleKeyIsReserved() {
        assertEquals("mutation.stale-expected-version", TypedMutationResult.STALE_EXPECTED_VERSION);
    }
}

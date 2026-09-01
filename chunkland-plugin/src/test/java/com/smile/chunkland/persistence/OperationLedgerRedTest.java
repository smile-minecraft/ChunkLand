package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationLedgerRedTest {

    @Test
    void stateAndPayloadAreTypedAndRoundTripDeterministically() {
        assertEquals(LedgerState.PAYMENT_PENDING, LedgerState.parse("PAYMENT_PENDING"));
        assertThrows(IllegalArgumentException.class, () -> LedgerState.parse("unknown"));

        UUID operationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID world = UUID.fromString("00000000-0000-0000-0000-000000000003");
        OperationPayload payload = OperationPayload.claim(
                operationId,
                actor,
                world,
                new LandId(UUID.fromString("00000000-0000-0000-0000-000000000004")),
                List.of(new OperationPayload.Chunk(
                        new ChunkKey(world, 2, -3),
                        12,
                        UUID.fromString("00000000-0000-0000-0000-000000000005"),
                        417L)),
                417L,
                "test-economy",
                Instant.parse("2026-01-01T00:00:00Z"));

        assertEquals(payload, OperationPayload.fromJson(payload.toJson()));
        assertEquals(payload.toJson(), OperationPayload.fromJson(payload.toJson()).toJson());
    }
}

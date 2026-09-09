package com.smile.chunkland.refund;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Input for one domain-first refund.
 *
 * <p>The chunk set selects already-claimed chunks of a single land; the saga
 * reads their durable cost basis from storage and refunds {@code
 * costBasis * numerator / denominator} with integer math. The current pricing
 * table is never consulted. A {@code null} operation id is assigned a fresh
 * UUID by the saga; callers that retry must reuse the same id so the ledger
 * idempotency gate can resume instead of refunding twice.
 */
public record RefundRequest(
        UUID operationId,
        UUID actorUuid,
        UUID worldUuid,
        LandId landId,
        Set<ChunkKey> chunks,
        long refundNumerator,
        long refundDenominator) {

    /** Default refund ratio: one half of the original cost basis. */
    public static final long DEFAULT_NUMERATOR = 1L;

    /** Default refund ratio denominator. */
    public static final long DEFAULT_DENOMINATOR = 2L;

    public RefundRequest {
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(worldUuid, "worldUuid");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(chunks, "chunks");
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        for (ChunkKey chunk : chunks) {
            Objects.requireNonNull(chunk, "chunks must not contain null");
            if (!worldUuid.equals(chunk.worldId())) {
                throw new IllegalArgumentException("chunk world does not match request world");
            }
        }
        chunks = Set.copyOf(chunks);
        if (refundDenominator <= 0) {
            throw new IllegalArgumentException(
                    "refundDenominator must be positive, got " + refundDenominator);
        }
        if (refundNumerator < 0 || refundNumerator > refundDenominator) {
            throw new IllegalArgumentException("refund ratio must satisfy 0 <= numerator <= denominator, got "
                    + refundNumerator + "/" + refundDenominator);
        }
    }

    /** Request with the default one-half refund ratio and a generated operation id. */
    public static RefundRequest of(UUID actorUuid, UUID worldUuid, LandId landId, Set<ChunkKey> chunks) {
        return new RefundRequest(null, actorUuid, worldUuid, landId, chunks,
                DEFAULT_NUMERATOR, DEFAULT_DENOMINATOR);
    }

    /** Copy carrying an explicit operation id for retries. */
    public RefundRequest withOperationId(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return new RefundRequest(operationId, actorUuid, worldUuid, landId, chunks,
                refundNumerator, refundDenominator);
    }
}

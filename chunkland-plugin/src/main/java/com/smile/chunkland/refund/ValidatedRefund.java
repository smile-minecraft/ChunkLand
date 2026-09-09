package com.smile.chunkland.refund;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.persistence.OperationPayload;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A refund plan validated against durable storage.
 *
 * <p>Every chunk carries the durable per-chunk cost basis read from storage at
 * validation time; the atomic domain commit re-checks these values inside its
 * transaction. The refund amount is derived solely from that durable basis and
 * the requested ratio, never from the current pricing table.
 */
public record ValidatedRefund(
        UUID operationId,
        UUID actorUuid,
        UUID worldUuid,
        LandId landId,
        String landDisplayName,
        List<OperationPayload.Chunk> chunks,
        long totalCostBasisMinorUnits,
        long refundAmountMinorUnits,
        Long refundNumerator,
        Long refundDenominator) {

    public ValidatedRefund {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(worldUuid, "worldUuid");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(landDisplayName, "landDisplayName");
        Objects.requireNonNull(chunks, "chunks");
        if (landDisplayName.isBlank()) {
            throw new IllegalArgumentException("landDisplayName must not be blank");
        }
        chunks = List.copyOf(chunks);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        if (totalCostBasisMinorUnits < 0) {
            throw new IllegalArgumentException("totalCostBasisMinorUnits must not be negative");
        }
        if (refundAmountMinorUnits < 0) {
            throw new IllegalArgumentException("refundAmountMinorUnits must not be negative");
        }
        if (refundAmountMinorUnits > totalCostBasisMinorUnits) {
            throw new IllegalArgumentException("refund amount must not exceed the durable cost basis");
        }
        if ((refundNumerator == null) != (refundDenominator == null)) {
            throw new IllegalArgumentException("refund ratio must carry both numerator and denominator");
        }
        if (refundNumerator != null) {
            if (refundDenominator <= 0) {
                throw new IllegalArgumentException("refundDenominator must be positive");
            }
            if (refundNumerator < 0 || refundNumerator > refundDenominator) {
                throw new IllegalArgumentException("refund ratio must satisfy 0 <= numerator <= denominator");
            }
        }
        for (OperationPayload.Chunk chunk : chunks) {
            Objects.requireNonNull(chunk, "chunks must not contain null");
            if (!worldUuid.equals(chunk.chunk().worldId())) {
                throw new IllegalArgumentException("chunk world does not match validated world");
            }
            if (chunk.costBasisMinorUnits() < 0) {
                throw new IllegalArgumentException("chunk cost basis must not be negative");
            }
        }
    }
}

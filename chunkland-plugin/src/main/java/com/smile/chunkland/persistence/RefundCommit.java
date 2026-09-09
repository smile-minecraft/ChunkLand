package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * All durable values needed by the single refund domain-commit transaction.
 *
 * <p>The chunk list carries the durable per-chunk cost basis captured at
 * validation time; the commit re-reads each row inside the same transaction
 * and aborts when a chunk is missing, belongs to another land, or its stored
 * basis no longer matches, so a stale or already-refunded request can never
 * move money. The audit entry must record the refund action; the ledger row
 * advances from {@code CREATED} to {@code DOMAIN_COMMITTED} in the same
 * transaction, before any Economy call runs.
 */
public record RefundCommit(
        UUID operationId,
        LandId landId,
        UUID worldId,
        List<OperationPayload.Chunk> chunks,
        long refundAmountMinorUnits,
        AuditEntry audit) {

    /** Audit action written by the atomic refund domain commit. */
    public static final String REFUND_AUDIT_ACTION = "ECONOMY_REFUND";

    public RefundCommit {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(audit, "audit");
        chunks = List.copyOf(chunks);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        if (refundAmountMinorUnits < 0) {
            throw new IllegalArgumentException("refundAmountMinorUnits must not be negative");
        }
        if (!REFUND_AUDIT_ACTION.equals(audit.action())) {
            throw new IllegalArgumentException("refund audit action must be " + REFUND_AUDIT_ACTION);
        }
        if (!landId.equals(audit.landId())) {
            throw new IllegalArgumentException("audit landId must match the refunded land");
        }
        if (!worldId.equals(audit.worldId())) {
            throw new IllegalArgumentException("audit worldId must match the refunded world");
        }
        for (OperationPayload.Chunk chunk : chunks) {
            Objects.requireNonNull(chunk, "chunks must not contain null");
            ChunkKey key = chunk.chunk();
            if (!worldId.equals(key.worldId())) {
                throw new IllegalArgumentException("refund chunk world does not match the refunded world");
            }
        }
    }
}

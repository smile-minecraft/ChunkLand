package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * All durable values needed by the single shrink domain-commit transaction.
 *
 * <p>No land row is inserted or deleted here: the commit deletes only the
 * delta chunk rows, bumps the existing land {@code structure_revision} by
 * exactly one under a durable compare-and-set, and records a {@code
 * CHUNK_REMOVE} audit row carrying the refund amount. {@code chunks} carries
 * only the delta with the durable per-chunk cost bases captured before the
 * commit — the remaining land chunks keep their stored depth and cost basis
 * untouched. The commit re-reads each delta row inside the same transaction
 * and aborts when a chunk is missing, belongs to another land, or its stored
 * basis no longer matches, so a stale or already-removed request can never
 * move money.
 */
public record ShrinkCommit(
        UUID operationId,
        LandId landId,
        UUID worldId,
        OwnerRef owner,
        long expectedStructureRevision,
        List<OperationPayload.Chunk> chunks,
        long refundAmountMinorUnits,
        AuditEntry audit) {

    public ShrinkCommit {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(audit, "audit");
        chunks = List.copyOf(chunks);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        if (refundAmountMinorUnits < 0) {
            throw new IllegalArgumentException("refundAmountMinorUnits must not be negative");
        }
        if (expectedStructureRevision < 0) {
            throw new IllegalArgumentException("expectedStructureRevision must not be negative");
        }
        for (OperationPayload.Chunk chunk : chunks) {
            Objects.requireNonNull(chunk, "chunks must not contain null");
            if (!worldId.equals(chunk.chunk().worldId())) {
                throw new IllegalArgumentException("chunk worldId does not match commit worldId");
            }
        }
        if (!landId.equals(audit.landId())) {
            throw new IllegalArgumentException("audit landId must match shrunk land");
        }
        if (!worldId.equals(audit.worldId())) {
            throw new IllegalArgumentException("audit worldId must match shrunk land");
        }
        if (!"CHUNK_REMOVE".equals(audit.action())) {
            throw new IllegalArgumentException("shrink audit action must be CHUNK_REMOVE, got " + audit.action());
        }
        if (!chunks.stream()
                .map(OperationPayload.Chunk::chunk)
                .collect(java.util.stream.Collectors.toSet())
                .equals(audit.chunks().stream().collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("shrink chunks must match the audit chunk set");
        }
        for (ChunkKey auditChunk : audit.chunks()) {
            if (!worldId.equals(auditChunk.worldId())) {
                throw new IllegalArgumentException("audit chunk worldId does not match commit worldId");
            }
        }
    }
}

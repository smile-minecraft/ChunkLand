package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * All durable values needed by the single expansion domain-commit transaction.
 *
 * <p>Unlike a claim, no land row is inserted: the commit appends only the
 * delta chunks, bumps the existing land {@code structure_revision} by exactly
 * one under a durable compare-and-set, and records a {@code CHUNK_ADD} audit
 * row. {@code chunks} carries only the delta — the existing land chunks keep
 * their stored depth and cost basis untouched.
 */
public record ExpandCommit(
        UUID operationId,
        LandId landId,
        UUID worldId,
        OwnerRef owner,
        long expectedStructureRevision,
        List<OperationPayload.Chunk> chunks,
        AuditEntry audit) {

    public ExpandCommit {
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
            throw new IllegalArgumentException("audit landId must match expanded land");
        }
        if (!worldId.equals(audit.worldId())) {
            throw new IllegalArgumentException("audit worldId must match expanded land");
        }
        if (!"CHUNK_ADD".equals(audit.action())) {
            throw new IllegalArgumentException("expand audit action must be CHUNK_ADD, got " + audit.action());
        }
        if (!chunks.stream()
                .map(OperationPayload.Chunk::chunk)
                .collect(java.util.stream.Collectors.toSet())
                .equals(audit.chunks().stream().collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("expand chunks must match the audit chunk set");
        }
        for (ChunkKey auditChunk : audit.chunks()) {
            if (!worldId.equals(auditChunk.worldId())) {
                throw new IllegalArgumentException("audit chunk worldId does not match commit worldId");
            }
        }
    }
}

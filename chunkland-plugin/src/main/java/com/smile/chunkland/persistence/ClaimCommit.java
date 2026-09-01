package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.LandSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** All durable values needed by the single domain-commit transaction. */
public record ClaimCommit(
        UUID operationId,
        LandSnapshot land,
        List<OperationPayload.Chunk> chunks,
        AuditEntry audit) {

    public ClaimCommit {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(land, "land");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(audit, "audit");
        chunks = List.copyOf(chunks);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        if (!land.id().equals(audit.landId())) {
            throw new IllegalArgumentException("audit landId must match committed land");
        }
        if (!land.worldId().equals(audit.worldId())) {
            throw new IllegalArgumentException("audit worldId must match committed land");
        }
        if (!land.chunks().equals(chunks.stream().map(OperationPayload.Chunk::chunk).collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("claim chunks must match the land chunk set");
        }
    }
}

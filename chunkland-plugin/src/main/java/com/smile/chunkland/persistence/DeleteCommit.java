package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * All durable values needed by the single land-delete domain-commit
 * transaction.
 *
 * <p>The commit removes every Land-owned row (chunks, SubLands with their
 * bindings/defaults/rules, land bindings/defaults/rules and entry bans) and
 * the land row itself under a durable structure-revision compare, then records
 * a {@code LAND_DELETE} audit row carrying the full refund amount. History in
 * {@code audit_log} (including the deleted land id), {@code subject_groups},
 * {@code permission_profiles} and any other namespace's rows are never
 * touched. {@code chunks} carries the full land set with the durable
 * per-chunk cost bases captured before the commit; the commit re-reads each
 * row inside the same transaction and aborts when a chunk is missing, belongs
 * to another land, or its stored basis no longer matches, so a stale or
 * already-deleted request can never move money.
 */
public record DeleteCommit(
        UUID operationId,
        LandId landId,
        UUID worldId,
        OwnerRef owner,
        long expectedStructureRevision,
        List<OperationPayload.Chunk> chunks,
        long refundAmountMinorUnits,
        AuditEntry audit) {

    public DeleteCommit {
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
            throw new IllegalArgumentException("audit landId must match deleted land");
        }
        if (!worldId.equals(audit.worldId())) {
            throw new IllegalArgumentException("audit worldId must match deleted land");
        }
        if (!"LAND_DELETE".equals(audit.action())) {
            throw new IllegalArgumentException("delete audit action must be LAND_DELETE, got " + audit.action());
        }
        if (!chunks.stream()
                .map(OperationPayload.Chunk::chunk)
                .collect(java.util.stream.Collectors.toSet())
                .equals(audit.chunks().stream().collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("delete chunks must match the audit chunk set");
        }
        for (com.smile.chunkland.api.land.ChunkKey auditChunk : audit.chunks()) {
            if (!worldId.equals(auditChunk.worldId())) {
                throw new IllegalArgumentException("audit chunk worldId does not match commit worldId");
            }
        }
    }
}

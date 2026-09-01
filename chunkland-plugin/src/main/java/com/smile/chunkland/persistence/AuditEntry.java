package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable audit entry. Connected chunks are stored in {@code audit_chunks}.
 * The record is defensively copied; collections are unmodifiable.
 */
public record AuditEntry(
        long id,
        Instant timestamp,
        UUID actor,
        String action,
        LandId landId,
        UUID worldId,
        Long singleChunkPacked,
        int metadataVersion,
        String beforeJson,
        String afterJson,
        String metadataJson,
        List<ChunkKey> chunks) {

    public AuditEntry {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        if (chunks == null) {
            throw new IllegalArgumentException("chunks must not be null");
        }
        chunks = List.copyOf(chunks);
    }

    public Optional<UUID> actorOpt() {
        return Optional.ofNullable(actor);
    }

    public Optional<LandId> landIdOpt() {
        return Optional.ofNullable(landId);
    }

    public Optional<UUID> worldIdOpt() {
        return Optional.ofNullable(worldId);
    }
}

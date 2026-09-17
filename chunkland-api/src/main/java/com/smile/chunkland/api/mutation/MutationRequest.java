package com.smile.chunkland.api.mutation;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable carrier describing a requested mutation (spec §85).
 *
 * <p>This is a skeleton contract: the concrete per-kind request shapes (claim
 * pricing, subland geometry, rename validation, ...) are finalized in later
 * milestones (M2 claim saga, M3 subland, M5 stable mutation API). The carrier
 * intentionally exposes a minimal, extensible set of fields so the immutable
 * envelope and the {@link MutationKind} vocabulary are fixed now, while the
 * field-level validation rules are layered on by the dedicated mutation tasks.
 *
 * <p>The {@code chunks} collection is defensively copied and exposed unmodifiable.
 * {@code landId}, {@code requestedBy} and {@code displayName} may be {@code null}
 * depending on the kind (e.g. a create has no target land id yet).
 *
 * <p>Thread-safe immutable value object.
 *
 * @since 0.1.0
 */
public record MutationRequest(
        MutationKind kind,
        LandId landId,
        OwnerRef requestedBy,
        Set<ChunkKey> chunks,
        String displayName) {

    public MutationRequest(
            MutationKind kind, LandId landId, OwnerRef requestedBy,
            Set<ChunkKey> chunks, String displayName) {
        Objects.requireNonNull(kind, "kind");
        this.kind = kind;
        this.landId = landId;
        this.requestedBy = requestedBy;
        this.chunks = chunks == null ? Set.of() : Set.copyOf(chunks);
        this.displayName = displayName;
    }
}

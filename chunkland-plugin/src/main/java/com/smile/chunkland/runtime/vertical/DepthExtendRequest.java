package com.smile.chunkland.runtime.vertical;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.UUID;

/**
 * One accepted depth-extend proposal travelling from the runtime CAS
 * accumulator to durable storage.
 *
 * @param chunk chunk whose stored depth extends
 * @param landId owning land at decision time; the store re-checks it and
 *               fails closed on a stale (mismatched) value
 * @param actor triggering player, or {@code null} when unknown
 * @param requestedDepth the decided deeper stored value (already clamped to
 *                       the world minimum by the decision gate)
 * @param triggeringOperationY block Y of the legal operation, recorded in
 *                             the {@code DEPTH_EXTEND} audit
 */
public record DepthExtendRequest(
        ChunkKey chunk,
        LandId landId,
        UUID actor,
        int requestedDepth,
        int triggeringOperationY) {

    public DepthExtendRequest {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(landId, "landId");
    }
}

package com.smile.chunkland.runtime.vertical;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.util.Objects;

/**
 * Outcome of one durable depth-extend application.
 *
 * @param chunk chunk the request targeted
 * @param landId owning land the write was checked against
 * @param applied true when the stored row actually moved deeper (and exactly
 *                then a {@code DEPTH_EXTEND} audit was written); false for
 *                shallower, unknown-chunk or stale-land no-ops, which persist
 *                nothing and record no audit
 * @param beforeStored durable value before the write (read-time fallback for
 *                     rows without a durable value)
 * @param afterStored durable value after the write (equals {@code beforeStored}
 *                    when not applied)
 * @param triggeringOperationY block Y recorded in the audit
 */
public record DepthWriteResult(
        ChunkKey chunk,
        LandId landId,
        boolean applied,
        int beforeStored,
        int afterStored,
        int triggeringOperationY) {

    public DepthWriteResult {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(landId, "landId");
    }
}

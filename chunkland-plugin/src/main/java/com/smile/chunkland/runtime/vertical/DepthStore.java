package com.smile.chunkland.runtime.vertical;

import java.util.concurrent.CompletionStage;

/**
 * Durable-apply seam for one depth-extend request.
 *
 * <p>The production implementation applies the atomic minimum to
 * {@code land_chunks.stored_min_protected_y} and writes the
 * {@code DEPTH_EXTEND} audit in the same persistence transaction. The
 * returned stage completes with an applied result only when both landed;
 * a failure completes exceptionally and must never be treated as durable.
 */
@FunctionalInterface
public interface DepthStore {

    /**
     * Apply one request.
     *
     * @return a stage with the write outcome; exceptionally completed on
     *         any durable failure
     */
    CompletionStage<DepthWriteResult> apply(DepthExtendRequest request);
}

package com.smile.chunkland.subland;

import com.smile.chunkland.runtime.vertical.DepthExtendRequest;
import com.smile.chunkland.runtime.vertical.DepthWriteResult;
import java.util.concurrent.CompletionStage;

/**
 * Narrow seam around the existing durable depth store.
 *
 * <p>Production wires this directly to {@code DepthExtendStore::extend}; tests
 * can observe the requests without opening SQLite. The seam adds no alternate
 * persistence path.
 */
@FunctionalInterface
public interface SubLandDepthExtendPort {

    /** Apply one durable depth request. */
    CompletionStage<DepthWriteResult> extend(DepthExtendRequest request);
}

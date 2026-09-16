package com.smile.chunkland.runtime.index;

/**
 * Volatile readiness flag for the runtime land registry.
 *
 * <p>The registry starts empty and is only safe to read for occupancy once the
 * startup rebuild has published a complete snapshot. Until {@link #markReady()}
 * runs, an empty registry means "unknown", not "wilderness": readers must fail
 * closed instead of accepting a claim over durable land they cannot see.
 *
 * <p>One volatile write publishes readiness; readers observe a complete flag.
 */
public final class RegistryReadiness {

    private volatile boolean ready;

    /** Mark the registry as hydrated from the durable repository. */
    public void markReady() {
        this.ready = true;
    }

    /** Whether a complete registry snapshot has been published. */
    public boolean isReady() {
        return ready;
    }
}

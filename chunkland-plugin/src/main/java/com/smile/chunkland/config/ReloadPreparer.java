package com.smile.chunkland.config;

/**
 * Prepare hook consulted inside {@link ConfigService#tryReload} after a
 * candidate loads and passes the gate, but before it publishes.
 *
 * <p>A preparer builds and validates the derived state a reload would
 * publish (for example the permission defaults view) without changing
 * anything observable. Throwing fails the whole reload with nothing
 * published and no listener notified, so a half-prepared generation can
 * never be observed. Preparers run only on the gated reload path; the
 * legacy {@link ConfigService#reload()} notifies listeners exactly as
 * before and never consults them.</p>
 */
@FunctionalInterface
public interface ReloadPreparer {

    /**
     * Build and validate derived state for {@code candidate}.
     *
     * @param candidate the loaded snapshot that is about to publish; never null
     * @throws RuntimeException to fail the whole reload with nothing published
     */
    void prepare(ChunkLandConfig candidate);
}

package com.smile.chunkland.runtime.index;

import java.util.Objects;

/**
 * Volatile holder for {@link LandRegistry}.
 *
 * <p>Readers take one volatile snapshot via {@link #snapshot()} and then
 * query that immutable snapshot without further synchronization. This ensures
 * every region thread observes a complete old or complete new version.
 *
 * <p>Publishers build a complete new registry off-thread and swap with
 * {@link #publish(LandRegistry)} (single volatile write). Partial state is never visible.
 */
public final class LandRegistryStore {

    private volatile LandRegistry snapshot;

    public LandRegistryStore() {
        this.snapshot = LandRegistry.empty();
    }

    public LandRegistry snapshot() {
        return snapshot;
    }

    public void publish(LandRegistry next) {
        this.snapshot = Objects.requireNonNull(next, "next");
    }
}

package com.smile.chunkland.protection;

import java.util.Objects;

/**
 * Volatile holder for the durable land authorisation layers.
 *
 * <p>Readers take one volatile snapshot and then answer from that immutable
 * value without further coordination, so every decision thread observes a
 * complete old or complete new version. Publishers build a complete new
 * snapshot off the decision path and swap it with a single volatile write;
 * partial state is never visible. Starts unloaded so decisions fail closed
 * until the first successful durable load publishes.
 */
public final class LandAuthorisationCache {

    private volatile LandAuthorisationSnapshot current = LandAuthorisationSnapshot.unloaded();

    /** Current immutable snapshot. Lock-free volatile read. */
    public LandAuthorisationSnapshot snapshot() {
        return current;
    }

    /** Publish one complete immutable snapshot with a single volatile write. */
    public void publish(LandAuthorisationSnapshot next) {
        this.current = Objects.requireNonNull(next, "next");
    }
}

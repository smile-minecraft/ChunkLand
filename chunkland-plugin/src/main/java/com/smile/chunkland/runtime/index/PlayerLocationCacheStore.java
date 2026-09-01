package com.smile.chunkland.runtime.index;

import java.util.Objects;

/**
 * Volatile holder for {@link PlayerLocationCache}.
 *
 * <p>Readers call {@link #snapshot()} once (volatile read) and then query the
 * immutable snapshot without further synchronization. Publishers build a complete
 * new cache and swap with {@link #publish(PlayerLocationCache)} (volatile write).
 */
public final class PlayerLocationCacheStore {

    private volatile PlayerLocationCache snapshot;

    public PlayerLocationCacheStore() {
        this.snapshot = PlayerLocationCache.empty();
    }

    public PlayerLocationCache snapshot() {
        return snapshot;
    }

    public void publish(PlayerLocationCache next) {
        this.snapshot = Objects.requireNonNull(next, "next");
    }
}

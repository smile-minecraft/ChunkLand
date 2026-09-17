package com.smile.chunkland.command;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Shared close token for one enable generation of the bypass toggle.
 *
 * <p>Each enable owns exactly one lifecycle: the production handler, the
 * startup recovery and the plugin wiring all share it. Disable closes it
 * before clearing the state, so late async callbacks from the old
 * generation stop before any audit insert, terminal write or state
 * mutation. A closed lifecycle never reopens; the next enable owns a new
 * one. Null means no gate (open), kept only for direct unit callers.
 */
public final class AdminBypassLifecycle {

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Marks the owning enable generation shut down. Idempotent. */
    public void close() {
        closed.set(true);
    }

    /** Whether the owning generation has shut down. */
    public boolean isClosed() {
        return closed.get();
    }

    /** Thrown when an initiation loses the race against {@link #close()}. */
    public static final class Closed extends RuntimeException {
        private Closed() {
            super("bypass generation closed");
        }
    }

    /**
     * Runs one non-blocking initiation atomically against {@link #close}:
     * either the initiation runs fully before the close lands, or the
     * close lands first and nothing runs. The monitor is held only across
     * the initiation call itself — never while waiting on a stage — and
     * the closed flag is re-verified before returning, so a close landing
     * inside the call still discards the result. A discarded durable write
     * stays open for the next recovery.
     *
     * @throws Closed when the generation shut down first
     */
    public <T> T initiate(Supplier<T> initiation) {
        Objects.requireNonNull(initiation, "initiation");
        synchronized (this) {
            if (closed.get()) {
                throw new Closed();
            }
            T result = initiation.get();
            if (closed.get()) {
                throw new Closed();
            }
            return result;
        }
    }
}

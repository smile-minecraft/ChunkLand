package com.smile.chunkland.runtime.vertical;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production owner of the depth-extend trigger adapter and persistence queue.
 *
 * <p>Wiring: block operations enter through {@link #onBlockOperation}, which
 * classifies via the {@link DepthExtendEventAdapter} (snapshot seams only,
 * no world data, no SQL) and offers accepted proposals to the
 * {@link DepthPersistenceQueue} without blocking the caller. The drain
 * {@link Executor} is owner-provided and borrowed — this service never shuts
 * it down and never creates a thread.
 *
 * <p>Lifecycle: {@link #close} first stops accepting new proposals, then
 * flushes every accepted write, and only then closes the persistence handle
 * (stop → flush → close), so no accepted extend is lost on plugin disable
 * and no executor or thread is left behind. Close is idempotent; closing
 * twice or proposing after close is a silent no-op. Close must not run on
 * the drain executor or the persistence thread.
 */
public final class DepthExtendService implements AutoCloseable {

    private final DepthPersistenceQueue queue;
    private final DepthExtendEventAdapter adapter;
    private final AutoCloseable persistence;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private DepthExtendService(DepthPersistenceQueue queue, DepthExtendEventAdapter adapter,
            AutoCloseable persistence) {
        this.queue = queue;
        this.adapter = adapter;
        this.persistence = persistence;
    }

    /**
     * Start the service over an already-open persistence handle.
     *
     * @param store durable-apply seam (for example the SQL conditional-update store)
     * @param drainExecutor owner-provided drain executor; borrowed, never shut down
     * @param capacity maximum in-flight accepted writes; must be positive
     * @param adapter trigger adapter over the immutable snapshot seams
     * @param persistenceToCloseAfterFlush persistence handle closed after the
     *        queue flushes (for example the shared SQLite store); never null
     * @return the running service, owned by the caller
     */
    public static DepthExtendService start(DepthStore store, Executor drainExecutor, int capacity,
            DepthExtendEventAdapter adapter, AutoCloseable persistenceToCloseAfterFlush) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(drainExecutor, "drainExecutor");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(persistenceToCloseAfterFlush, "persistenceToCloseAfterFlush");
        return new DepthExtendService(
                new DepthPersistenceQueue(drainExecutor, store, capacity), adapter,
                persistenceToCloseAfterFlush);
    }

    /**
     * Classify one block operation and, when legal, submit it for persistence.
     *
     * <p>Non-blocking: classification reads only the injected snapshot seams
     * and the offer only enqueues. SQL runs later on the persistence path.
     *
     * @return the write's future when accepted; empty when silent (wrong
     *         trigger, unauthorized, wilderness, shallower-than-stored) or
     *         when the service is closed or the queue is full
     */
    public Optional<CompletableFuture<DepthWriteResult>> onBlockOperation(AutoExtendTrigger trigger,
            UUID actor, boolean actorAuthorized, com.smile.chunkland.api.land.ChunkKey chunk,
            int operationY) {
        if (closed.get()) {
            return Optional.empty();
        }
        return adapter.propose(trigger, actor, actorAuthorized, chunk, operationY)
                .flatMap(queue::offer);
    }

    /** True while new proposals are still accepted. */
    public boolean isAccepting() {
        return !closed.get() && !queue.isDisabled();
    }

    /** Pending accepted writes (draining, not yet durable). */
    public int pendingCount() {
        return queue.pendingCount();
    }

    /**
     * Stop accepting, flush every accepted write, then close persistence.
     * Idempotent: repeats are no-ops.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            queue.disableAndFlush();
        } finally {
            closePersistence();
        }
    }

    private void closePersistence() {
        try {
            persistence.close();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception checked) {
            throw new IllegalStateException("depth persistence close failed", checked);
        }
    }
}

package com.smile.chunkland.runtime.vertical;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded depth persistence queue (spec §20).
 *
 * <p>Ownership and flush order:
 *
 * <ol>
 *   <li>The drain {@link Executor} is owner-provided (Folia scheduler or
 *       persistence-adjacent executor) and is <em>never</em> shut down by
 *       this queue — it is borrowed, not owned, so no new thread or
 *       executor is created here.
 *   <li>{@link #offer} is non-blocking: it takes a capacity permit and
 *       submits one task. When disabled or full the offer is rejected
 *       observably (empty result plus a rejection counter), never silently
 *       dropped into an unbounded buffer.
 *   <li>Each accepted task applies one {@link DepthStore} write and
 *       completes its future with the outcome. A durable failure completes
 *       that future exceptionally and is recorded in {@link #failures} —
 *       a failed write is never reported as durable.
 *   <li>{@link #disableAndFlush} (and {@link #close}) first disables new
 *       accepts, then waits for every accepted write to finish and reports
 *       a {@link FlushSummary}. Normal plugin disable calls this so no
 *       accepted extend is lost; a sudden process crash still has the
 *       documented tiny unpersisted window.
 * </ol>
 *
 * <p>The drain executor must never be the persistence thread itself when
 * the store blocks on it; the production store is fully asynchronous, so
 * any executor works. {@code disableAndFlush} must not be called from a
 * task running on the drain executor.
 *
 * <p>Thread-safe.
 */
public final class DepthPersistenceQueue implements AutoCloseable {

    /** Outcome of {@link #disableAndFlush()}. */
    public record FlushSummary(int completed, int failed) {
    }

    /** One accepted write that failed its durable apply. */
    public record DepthWriteFailure(DepthExtendRequest request, Throwable cause) {
        public DepthWriteFailure {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(cause, "cause");
        }
    }

    private final Executor drainExecutor;
    private final DepthStore store;
    private final Semaphore capacity;
    private final Object lifecycleLock = new Object();
    private final Set<CompletableFuture<DepthWriteResult>> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<DepthWriteFailure> failures = new ConcurrentLinkedQueue<>();
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong completedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong rejectedFullCount = new AtomicLong();
    private final AtomicLong rejectedDisabledCount = new AtomicLong();
    private volatile boolean disabled;

    /**
     * @param drainExecutor owner-provided executor for drain tasks; borrowed, never shut down
     * @param store durable-apply seam
     * @param capacity maximum in-flight accepted writes; must be positive
     */
    public DepthPersistenceQueue(Executor drainExecutor, DepthStore store, int capacity) {
        this.drainExecutor = Objects.requireNonNull(drainExecutor, "drainExecutor");
        this.store = Objects.requireNonNull(store, "store");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = new Semaphore(capacity);
    }

    /**
     * Offer one extend for persistence.
     *
     * @return the write's future when accepted; empty when rejected because
     *         the queue is disabled or full (see the rejection counters)
     */
    public Optional<CompletableFuture<DepthWriteResult>> offer(DepthExtendRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<DepthWriteResult> future = new CompletableFuture<>();
        synchronized (lifecycleLock) {
            if (disabled) {
                rejectedDisabledCount.incrementAndGet();
                return Optional.empty();
            }
            if (!capacity.tryAcquire()) {
                rejectedFullCount.incrementAndGet();
                return Optional.empty();
            }
            pending.add(future);
            acceptedCount.incrementAndGet();
        }
        try {
            drainExecutor.execute(() -> drain(request, future));
        } catch (RejectedExecutionException rejected) {
            finish(request, future, CompletableFuture.failedFuture(rejected));
        }
        return Optional.of(future);
    }

    private void drain(DepthExtendRequest request, CompletableFuture<DepthWriteResult> future) {
        CompletionStage<DepthWriteResult> applied;
        try {
            applied = store.apply(request);
        } catch (RuntimeException | Error failure) {
            finish(request, future, CompletableFuture.failedFuture(failure));
            return;
        }
        if (applied == null) {
            finish(request, future, CompletableFuture.failedFuture(
                    new IllegalStateException("depth store returned null for " + request.chunk())));
            return;
        }
        applied.whenComplete((result, failure) -> {
            if (failure != null) {
                finish(request, future, CompletableFuture.failedFuture(failure));
            } else if (result == null) {
                finish(request, future, CompletableFuture.failedFuture(
                        new IllegalStateException("depth store completed null for " + request.chunk())));
            } else {
                finish(request, future, CompletableFuture.completedFuture(result));
            }
        });
    }

    private void finish(DepthExtendRequest request,
                        CompletableFuture<DepthWriteResult> future,
                        CompletableFuture<DepthWriteResult> outcome) {
        try {
            outcome.whenComplete((result, failure) -> {
                if (failure != null) {
                    failures.add(new DepthWriteFailure(request, failure));
                    failedCount.incrementAndGet();
                    future.completeExceptionally(failure);
                } else {
                    completedCount.incrementAndGet();
                    future.complete(result);
                }
            });
        } finally {
            pending.remove(future);
            capacity.release();
        }
    }

    /**
     * Disable new accepts, then wait for every accepted write to finish.
     *
     * @return cumulative accepted-write outcomes (completed vs failed);
     *         failures remain visible on their own futures and in
     *         {@link #failures}
     */
    public FlushSummary disableAndFlush() {
        final List<CompletableFuture<DepthWriteResult>> inflight;
        synchronized (lifecycleLock) {
            disabled = true;
            inflight = new ArrayList<>(pending);
        }
        if (!inflight.isEmpty()) {
            CompletableFuture.allOf(inflight.toArray(new CompletableFuture[0]))
                    .exceptionally(ignored -> null)
                    .join();
        }
        return new FlushSummary((int) completedCount.get(), (int) failedCount.get());
    }

    /** Equivalent to {@link #disableAndFlush()}; never shuts down the borrowed executor. */
    @Override
    public void close() {
        disableAndFlush();
    }

    public boolean isDisabled() {
        return disabled;
    }

    public int pendingCount() {
        return pending.size();
    }

    public List<DepthWriteFailure> failures() {
        return List.copyOf(failures);
    }

    public long acceptedCount() {
        return acceptedCount.get();
    }

    public long rejectedFullCount() {
        return rejectedFullCount.get();
    }

    public long rejectedDisabledCount() {
        return rejectedDisabledCount.get();
    }
}

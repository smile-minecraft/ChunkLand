package com.smile.chunkland.runtime.mutation;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.mutation.MutationOutcome;
import com.smile.chunkland.api.mutation.MutationRequest;
import com.smile.chunkland.api.mutation.MutationResult;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single serialized mutation entry point.
 *
 * <p>Region or any caller thread may only call {@link #submit(MutationRequest)};
 * shared runtime/domain state is mutated exclusively on the single serial
 * executor. Logical reservation is attempted atomically on the caller thread
 * before queuing so that concurrent claims for the same chunk are rejected
 * without relying on timing or DB UNIQUE alone. All collaborators are
 * injectable and immutable; no mutable map, JDBC Connection or Bukkit object
 * is exposed to callers. Economy is invoked strictly outside any SQL
 * transaction.
 *
 * <p>Ordering is Validate → Reservation → Ledger → Economy → Domain Commit → Publish → Finalize.
 * Reservation is released on every terminal path (success, failure,
 * cancellation, executor rejection after close) BEFORE the caller-visible
 * promise completes, so observers of the returned future always see a
 * consistent reservation state.
 *
 * <p><b>Ledger handoff contract.</b> The {@link LedgerWriter} seam is currently
 * the minimal operation-ledger interface: it carries
 * {@code insert(operationId, request)} for the durable pre-commit record and
 * {@code finalizeState(operationId, outcome)} for the terminal marker. The
 * current coordinator calls {@code finalizeState} on the success path after
 * Publish and awaits the returned stage via {@code awaitStage} before
 * reporting success; any failure — null stage, synchronous throw,
 * {@code toCompletableFuture} failure/null, or exceptional completion — is
 * mapped to {@code finalize.failed} rather than caller-visible SUCCESS.
 * Success is only reported after normal Finalize completion. A future ledger
 * state machine will replace this seam with full recovery for any operation
 * whose terminal outcome has not been recorded; when that arrives it will
 * continue to be driven from {@code finalizeState(operationId, outcome)}
 * with the actual {@link MutationOutcome} and no coordinator public API
 * change is required.
 */
public final class MutationCoordinator implements AutoCloseable {

    private static final String THREAD_NAME = "chunkland-mutation";

    private final ExecutorService serial;
    private final MutationValidator validator;
    private final ReservationKeyExtractor keyExtractor;
    private final LogicalReservationRegistry reservations;
    private final LedgerWriter ledgerWriter;
    private final EconomyOperator economyOperator;
    private final DomainCommitter domainCommitter;
    private final MutationPublisher publisher;
    private final StageRecorder recorder;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<TrackedTask> runningTask = new AtomicReference<>();
    private final Object lifecycleLock = new Object();
    private final java.util.concurrent.ConcurrentHashMap<CompletableFuture<MutationResult>, TrackedTask> acceptedTasks =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Signal for tests waiting for terminal abort to complete.
    volatile CountDownLatch testTerminalLatchForTest;

    // Deterministic hook for the authorization-versus-invocation window.
    // Invoked immediately after a stage is authorized (tryEnterStage succeeds)
    // but before the collaborator supplier is called, without holding
    // lifecycleLock. Production leaves this null; verification uses a
    // latch-based hook to force close/cancel to win deterministically
    // in the gap.
    volatile java.util.function.Consumer<String> testPostAuthorizeHookForTest;

    // Deterministic override for reservation key extraction to exercise
    // the null-return path without subclassing the final extractor.
    volatile java.util.function.Function<MutationRequest, Set<String>> testKeyExtractorHookForTest;

    /**
     * External collaborator side effects cannot be forcibly undone.
     * When {@link #close()} converges the coordinator to a terminal
     * FAILED result, the running collaborator may still finish its
     * external work on its own thread; the coordinator guarantees
     * exactly-once reservation release and exactly-one caller-visible
     * result, and prevents any later pipeline stage from starting.
     * Full operation recovery remains future ledger scope.
     *
     * <p><b>Authorization-versus-invocation contract.</b> Each pipeline stage
     * is gated by {@code tryEnterStage} under {@code lifecycleLock}. The
     * closed/cancelled check, reservation release and stage recording are
     * linearized there, but the external collaborator supplier is invoked
     * AFTER releasing the lock so that a synchronously blocking supplier
     * cannot block {@link #close()}. If close/cancel wins before
     * authorization, the stage is not invoked. If authorization wins first,
     * the collaborator may be invoked and may complete after close/cancel;
     * the coordinator will still converge exactly once, release the
     * reservation, and will not start any later stage. No lock is held
     * while waiting on a collaborator future, so close remains bounded.
     */

    public MutationCoordinator(
            MutationValidator validator,
            ReservationKeyExtractor keyExtractor,
            LogicalReservationRegistry reservations,
            LedgerWriter ledgerWriter,
            EconomyOperator economyOperator,
            DomainCommitter domainCommitter,
            MutationPublisher publisher,
            StageRecorder recorder) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.keyExtractor = Objects.requireNonNull(keyExtractor, "keyExtractor");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.ledgerWriter = Objects.requireNonNull(ledgerWriter, "ledgerWriter");
        this.economyOperator = Objects.requireNonNull(economyOperator, "economyOperator");
        this.domainCommitter = Objects.requireNonNull(domainCommitter, "domainCommitter");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.recorder = recorder;
        this.serial = Executors.newSingleThreadExecutor(new MutationThreadFactory());
    }

    /** Minimal production-friendly constructor with no-op collaborators where appropriate. */
    public static MutationCoordinator withDefaults(StageRecorder recorder) {
        LogicalReservationRegistry reg = new LogicalReservationRegistry();
        ReservationKeyExtractor ext = new ReservationKeyExtractor();
        MutationValidator val = request -> MutationValidator.ValidationResult.ok();
        LedgerWriter ledger = new NoopLedgerWriter();
        EconomyOperator economy = (req, opId) -> CompletableFuture.completedFuture(EconomyOperator.EconomyResult.ok());
        DomainCommitter committer = (req, opId) -> CompletableFuture.completedFuture(
                DomainCommitter.DomainCommitResult.success(new LandId(UUID.randomUUID())));
        MutationPublisher pub = (req, res) -> CompletableFuture.completedFuture(null);
        return new MutationCoordinator(val, ext, reg, ledger, economy, committer, pub, recorder);
    }

    public LogicalReservationRegistry reservations() {
        return reservations;
    }

    /**
     * Submit an immutable mutation request. The returned stage is immutable
     * and never exposes a mutable collection, JDBC object or Bukkit type.
     *
     * <p>If the serial executor has been shut down (e.g. {@link #close()}
     * has been called), this method still returns a complete stage with
     * {@code FAILED / coordinator.rejected} and releases the caller-thread
     * reservation synchronously, so the caller observes a deterministic
     * outcome without a hang or a leaked reservation.
     */
    public CompletionStage<MutationResult> submit(MutationRequest request) {
        Objects.requireNonNull(request, "request");
        // Validate
        MutationValidator.ValidationResult vr;
        try {
            vr = validator.validate(request);
        } catch (Exception e) {
            record("Validate");
            return CompletableFuture.completedFuture(MutationResult.failed("validation.failed"));
        }
        record("Validate");
        if (vr == null) {
            return CompletableFuture.completedFuture(MutationResult.failed("validation.failed"));
        }
        if (!vr.valid()) {
            return CompletableFuture.completedFuture(MutationResult.rejected(vr.diagnosticKey()));
        }
        // Reservation (atomic on caller thread)
        Set<String> keys;
        try {
            var hook = testKeyExtractorHookForTest;
            if (hook != null) {
                keys = hook.apply(request);
            } else {
                keys = keyExtractor.keys(request);
            }
        } catch (Exception e) {
            return CompletableFuture.completedFuture(MutationResult.failed("reservation.failed"));
        }
        if (keys == null) {
            return CompletableFuture.completedFuture(MutationResult.failed("reservation.failed"));
        }
        // Ensure immutable copy
        Set<String> immutableKeys = Set.copyOf(keys);
        UUID operationId = UUID.randomUUID();
        if (!reservations.tryAcquire(immutableKeys, operationId)) {
            record("Reservation");
            return CompletableFuture.completedFuture(MutationResult.rejected("reservation.conflict"));
        }
        record("Reservation");

        // Linearized submit vs close: the closed check, cancellable promise
        // creation and task enqueue are atomic with close's terminalization
        // decision under the lifecycle lock. The returned future's cancel
        // participates in the same lock, so cancellation is linearized with
        // stage authorization. We never hold the lock while waiting on a
        // collaborator, so a synchronously blocking supplier cannot block close.
        CompletableFuture<MutationResult> promise;
        TrackedTask task;
        synchronized (lifecycleLock) {
            if (closed.get()) {
                reservations.release(immutableKeys, operationId);
                return CompletableFuture.completedFuture(MutationResult.failed("coordinator.rejected"));
            }
            final Set<String> keysForCancel = immutableKeys;
            final UUID opIdForCancel = operationId;
            promise = new CompletableFuture<MutationResult>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    synchronized (lifecycleLock) {
                        if (isDone()) return false;
                        boolean r = super.cancel(mayInterruptIfRunning);
                        if (r) {
                            reservations.release(keysForCancel, opIdForCancel);
                            acceptedTasks.remove(this);
                            CountDownLatch latch = testTerminalLatchForTest;
                            if (latch != null) latch.countDown();
                        }
                        return r;
                    }
                }
            };
            task = new TrackedTask(this, request, immutableKeys, operationId, promise);
            acceptedTasks.put(promise, task);
            try {
                serial.execute(task);
            } catch (RejectedExecutionException rejected) {
                acceptedTasks.remove(promise);
                reservations.release(immutableKeys, operationId);
                promise.complete(MutationResult.failed("coordinator.rejected"));
            }
            return promise;
        }
    }

    /**
     * Run the mutation pipeline on the serial executor thread. Each terminal
     * branch releases the reservation BEFORE completing the caller-visible
     * promise so that any observer of the returned future sees the released
     * reservation state. Each side-effecting stage is preceded by a
     * cancellation/close check so a caller-side cancel or coordinator close
     * stops subsequent side effects (no economy charge, no domain commit,
     * no publish, no finalize). Collaborator waits are close-aware: a
     * never-completing collaborator does not block {@link #close()}
     * indefinitely; the coordinator converges to one terminal result via
     * the running-task handle and prevents later stages on late completion.
     */
    private void firePostAuthorizeHook(String stage) {
        var hook = testPostAuthorizeHookForTest;
        if (hook != null) {
            try {
                hook.accept(stage);
            } catch (Throwable ignored) {
                // hook is test-only; never propagate into pipeline
            }
        }
    }

    private void runPipeline(MutationRequest request,
                               Set<String> immutableKeys,
                               UUID operationId,
                               CompletableFuture<MutationResult> promise) {
        if (abortIfTerminal(promise, immutableKeys, operationId)) return;

        // Ledger
        if (!tryEnterStage("Ledger", promise, immutableKeys, operationId)) return;
        firePostAuthorizeHook("Ledger");
        CompletionStage<Void> ledgerStage;
        try {
            ledgerStage = ledgerWriter.insert(operationId, request);
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            MutationResult r = isUniqueViolation(cause)
                    ? MutationResult.rejected("land.chunk.conflict")
                    : MutationResult.failed("ledger.failed");
            completeTerminalOnce(promise, immutableKeys, operationId, r);
            return;
        }
        try {
            awaitStage(ledgerStage, promise);
        } catch (Exception e) {
            if (isTerminalCancellation(e, promise)) {
                completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.failed("coordinator.rejected"));
                return;
            }
            Throwable cause = unwrap(e);
            MutationResult r = isUniqueViolation(cause)
                    ? MutationResult.rejected("land.chunk.conflict")
                    : MutationResult.failed("ledger.failed");
            completeTerminalOnce(promise, immutableKeys, operationId, r);
            return;
        }
        if (abortIfTerminal(promise, immutableKeys, operationId)) return;

        // Economy — must be outside any SQL transaction
        if (!tryEnterStage("Economy", promise, immutableKeys, operationId)) return;
        firePostAuthorizeHook("Economy");
        CompletionStage<EconomyOperator.EconomyResult> economyStage;
        try {
            economyStage = economyOperator.charge(request, operationId);
        } catch (Throwable t) {
            completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.failed("economy.failed"));
            return;
        }
        EconomyOperator.EconomyResult economyResult;
        try {
            economyResult = awaitStage(economyStage, promise);
        } catch (Exception e) {
            if (isTerminalCancellation(e, promise)) {
                completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.failed("coordinator.rejected"));
                return;
            }
            completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.failed("economy.failed"));
            return;
        }
        if (economyResult == null) {
            completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.failed("economy.failed"));
            return;
        }
        if (!economyResult.success()) {
            String key = economyResult.diagnosticKey() != null ? economyResult.diagnosticKey() : "economy.failed";
            completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.failed(key));
            return;
        }
        if (abortIfTerminal(promise, immutableKeys, operationId)) return;

        // Domain Commit
        if (!tryEnterStage("DomainCommit", promise, immutableKeys, operationId)) return;
        firePostAuthorizeHook("DomainCommit");
        CompletionStage<DomainCommitter.DomainCommitResult> domainStage;
        try {
            domainStage = domainCommitter.commit(request, operationId);
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            MutationResult r = isUniqueViolation(cause)
                    ? MutationResult.rejected("land.chunk.conflict")
                    : MutationResult.failed("domain.failed");
            completeTerminalOnce(promise, immutableKeys, operationId, r);
            return;
        }
        DomainCommitter.DomainCommitResult commitResult;
        try {
            commitResult = awaitStage(domainStage, promise);
        } catch (Exception e) {
            if (isTerminalCancellation(e, promise)) {
                completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.failed("coordinator.rejected"));
                return;
            }
            Throwable cause = unwrap(e);
            MutationResult r = isUniqueViolation(cause)
                    ? MutationResult.rejected("land.chunk.conflict")
                    : MutationResult.failed("domain.failed");
            completeTerminalOnce(promise, immutableKeys, operationId, r);
            return;
        }
        if (commitResult == null) {
            completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.failed("domain.failed"));
            return;
        }
        if (!commitResult.success()) {
            MutationResult r = commitResult.conflict()
                    ? MutationResult.rejected(commitResult.diagnosticKey())
                    : MutationResult.failed(commitResult.diagnosticKey());
            completeTerminalOnce(promise, immutableKeys, operationId, r);
            return;
        }
        if (abortIfTerminal(promise, immutableKeys, operationId)) return;

        // Publish
        if (!tryEnterStage("Publish", promise, immutableKeys, operationId)) return;
        firePostAuthorizeHook("Publish");
        CompletionStage<Void> publishStage;
        try {
            publishStage = publisher.publish(request, commitResult);
        } catch (Throwable t) {
            completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.failed("publish.failed"));
            return;
        }
        try {
            awaitStage(publishStage, promise);
        } catch (Exception e) {
            if (isTerminalCancellation(e, promise)) {
                completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.failed("coordinator.rejected"));
                return;
            }
            completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.failed("publish.failed"));
            return;
        }
        if (abortIfTerminal(promise, immutableKeys, operationId)) return;

        // Finalize — collaborator stage: any failure (null stage, sync throw,
        // toCompletableFuture failure/null, exceptional completion) maps to
        // an explicit failed terminal result. Success is only reported after
        // normal completion of finalizeState.
        if (!tryEnterStage("Finalize", promise, immutableKeys, operationId)) return;
        firePostAuthorizeHook("Finalize");
        CompletionStage<Void> finalizeStage;
        try {
            finalizeStage = ledgerWriter.finalizeState(operationId, MutationOutcome.SUCCESS);
        } catch (Throwable t) {
            completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.failed("finalize.failed"));
            return;
        }
        try {
            awaitStage(finalizeStage, promise);
        } catch (Exception e) {
            if (isTerminalCancellation(e, promise)) {
                completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.failed("coordinator.rejected"));
                return;
            }
            completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.failed("finalize.failed"));
            return;
        }
        completeTerminalOnce(promise, immutableKeys, operationId, MutationResult.success(commitResult.landId()));
    }

    private boolean abortIfCancelled(CompletableFuture<?> promise,
                                    Set<String> keys,
                                    UUID operationId) {
        if (promise.isCancelled()) {
            reservations.release(keys, operationId);
            return true;
        }
        return false;
    }

    private boolean abortIfTerminal(CompletableFuture<MutationResult> promise,
                                    Set<String> keys,
                                    UUID operationId) {
        if (closed.get() || promise.isCancelled() || promise.isDone()) {
            // Reservation must be released exactly once; remove(key, owner) is idempotent.
            reservations.release(keys, operationId);
            if (closed.get() && !promise.isDone()) {
                promise.complete(MutationResult.failed("coordinator.rejected"));
            }
            acceptedTasks.remove(promise);
            CountDownLatch latch = testTerminalLatchForTest;
            if (latch != null) latch.countDown();
            return true;
        }
        return false;
    }

    private boolean tryEnterStage(String stage,
                                  CompletableFuture<MutationResult> promise,
                                  Set<String> keys,
                                  UUID operationId) {
        synchronized (lifecycleLock) {
            if (closed.get() || promise.isCancelled() || promise.isDone()) {
                reservations.release(keys, operationId);
                if (closed.get() && !promise.isDone()) {
                    promise.complete(MutationResult.failed("coordinator.rejected"));
                }
                acceptedTasks.remove(promise);
                CountDownLatch latch = testTerminalLatchForTest;
                if (latch != null) latch.countDown();
                return false;
            }
            record(stage);
            return true;
        }
    }

    private boolean isTerminalCancellation(Throwable e, CompletableFuture<MutationResult> promise) {
        Throwable cause = unwrap(e);
        if (cause instanceof CancellationException) {
            return closed.get() || promise.isCancelled() || promise.isDone();
        }
        // Also treat CompletionException wrapping CancellationException with closed flag
        if (e instanceof CancellationException) {
            return closed.get() || promise.isCancelled() || promise.isDone();
        }
        return false;
    }

    private void completeTerminalOnce(CompletableFuture<MutationResult> promise,
                                      Set<String> keys,
                                      UUID operationId,
                                      MutationResult result) {
        reservations.release(keys, operationId);
        acceptedTasks.remove(promise);
        promise.complete(result);
        CountDownLatch latch = testTerminalLatchForTest;
        if (latch != null) latch.countDown();
    }

    private <T> T awaitStage(CompletionStage<T> stage, CompletableFuture<MutationResult> promise) {
        if (stage == null) {
            throw new CompletionException(new NullPointerException("collaborator returned null CompletionStage"));
        }
        CompletableFuture<T> cf;
        try {
            cf = stage.toCompletableFuture();
        } catch (Throwable t) {
            throw new CompletionException(t);
        }
        if (cf == null) {
            throw new CompletionException(new NullPointerException("collaborator toCompletableFuture returned null"));
        }
        if (promise.isDone() || closed.get() || promise.isCancelled()) {
            throw new CompletionException(new CancellationException("coordinator closed or cancelled"));
        }
        CompletableFuture<Object> race = CompletableFuture.anyOf(cf, promise);
        try {
            race.join();
        } catch (CompletionException e) {
            if (promise.isDone()) {
                throw new CompletionException(new CancellationException("coordinator closed or cancelled"));
            }
            if (cf.isCompletedExceptionally()) {
                try {
                    cf.join();
                } catch (CompletionException ce) {
                    throw ce;
                }
            }
            throw e;
        }
        if (promise.isDone()) {
            throw new CompletionException(new CancellationException("coordinator closed or cancelled"));
        }
        try {
            return cf.join();
        } catch (CompletionException e) {
            throw e;
        }
    }

    private void record(String stage) {
        if (recorder != null) recorder.record(stage);
    }

    private static Throwable unwrap(Throwable e) {
        if (e instanceof CompletionException ce && ce.getCause() != null) return ce.getCause();
        return e;
    }

    /**
     * Detect a genuine UNIQUE (chunk uniqueness) violation. The check walks
     * the cause chain for any {@link java.sql.SQLException} whose message
     * carries explicit UNIQUE/DUPLICATE evidence. A bare "constraint" word
     * is NOT sufficient: FOREIGN KEY and CHECK failures also carry
     * "constraint" in their messages and must remain generic ledger/domain
     * failures, never {@code land.chunk.conflict}.
     */
    private static boolean isUniqueViolation(Throwable cause) {
        Throwable t = cause;
        while (t != null) {
            if (t instanceof java.sql.SQLException) {
                String msg = t.getMessage();
                if (msg != null) {
                    String upper = msg.toUpperCase(Locale.ROOT);
                    if (upper.contains("UNIQUE") || upper.contains("DUPLICATE")) {
                        return true;
                    }
                }
            }
            t = t.getCause();
        }
        return false;
    }

    @Override
    public void close() {
        // Idempotent and linearizable with stage-start gates: the closed
        // flag transition is performed under the lifecycle lock so no
        // stage can be authorized after close returns. shutdownNow() is
        // called outside the lock to avoid holding the gate while waiting
        // on executor internals. Every accepted task is tracked from
        // enqueue until terminal, so a task accepted but not yet
        // registered as running (dequeue window) is still converged.
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        List<Runnable> drained = serial.shutdownNow();
        for (Runnable r : drained) {
            if (r instanceof TrackedTask t) {
                completeTerminalOnce(t.promise, t.keys, t.operationId, MutationResult.failed("coordinator.rejected"));
            }
        }
        TrackedTask running;
        synchronized (lifecycleLock) {
            running = runningTask.getAndSet(null);
        }
        if (running != null) {
            completeTerminalOnce(running.promise, running.keys, running.operationId, MutationResult.failed("coordinator.rejected"));
        }
        // Accepted-but-not-yet-running window: task dequeued but
        // TrackedTask.run has not yet set runningTask. Iterate a
        // snapshot of acceptedTasks and terminalize any still pending.
        for (TrackedTask t : new java.util.ArrayList<>(acceptedTasks.values())) {
            if (!t.promise.isDone()) {
                completeTerminalOnce(t.promise, t.keys, t.operationId, MutationResult.failed("coordinator.rejected"));
            }
        }
    }

    private static final class MutationThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, THREAD_NAME);
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Tagged wrapper around the pipeline runnable so {@link #close()} can
     * recognise queued tasks via {@code shutdownNow()}'s returned list and
     * release the matching reservation while completing the caller-visible
     * promise with the deterministic FAILED outcome. It also tracks the
     * currently running mutation so close can converge a never-completing
     * collaborator without blocking indefinitely.
     */
    private static final class TrackedTask implements Runnable {
        private final MutationCoordinator owner;
        private final MutationRequest request;
        private final Set<String> keys;
        private final UUID operationId;
        private final CompletableFuture<MutationResult> promise;

        TrackedTask(MutationCoordinator owner,
                     MutationRequest request,
                     Set<String> keys,
                     UUID operationId,
                     CompletableFuture<MutationResult> promise) {
            this.owner = owner;
            this.request = request;
            this.keys = keys;
            this.operationId = operationId;
            this.promise = promise;
        }

        @Override
        public void run() {
            // Publish running task under the lifecycle lock so close() and
            // stage-start gates are linearizable. Late completion cannot
            // resume later stages because every stage re-checks the gate.
            synchronized (owner.lifecycleLock) {
                owner.runningTask.set(this);
            }
            try {
                owner.runPipeline(request, keys, operationId, promise);
            } finally {
                synchronized (owner.lifecycleLock) {
                    if (owner.runningTask.get() == this) {
                        owner.runningTask.set(null);
                    }
                }
            }
        }
    }

    private static final class NoopLedgerWriter implements LedgerWriter {
        @Override
        public CompletionStage<Void> insert(UUID operationId, MutationRequest request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> finalizeState(UUID operationId, MutationOutcome outcome) {
            return CompletableFuture.completedFuture(null);
        }
    }
}

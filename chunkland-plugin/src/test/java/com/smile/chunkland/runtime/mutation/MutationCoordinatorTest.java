package com.smile.chunkland.runtime.mutation;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.mutation.MutationKind;
import com.smile.chunkland.api.mutation.MutationOutcome;
import com.smile.chunkland.api.mutation.MutationRequest;
import com.smile.chunkland.api.mutation.MutationResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MutationCoordinatorTest {

    private static void awaitBarrier(CyclicBarrier barrier, long timeout, String description) {
        try {
            barrier.await(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(description + " interrupted", e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new CompletionException(description + " failed", e);
        }
    }

    private static void awaitRelease(CountDownLatch latch, String description) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError(description + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(description + " interrupted", e);
        }
    }

    private static MutationResult awaitMutationResult(CompletableFuture<MutationResult> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException("mutation future interrupted", e);
        } catch (ExecutionException e) {
            throw new CompletionException(e.getCause());
        } catch (TimeoutException e) {
            throw new CompletionException("mutation future timed out", e);
        }
    }

    private MutationCoordinator coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.testTerminalLatchForTest = null;
            coordinator.close();
        }
    }

    private MutationCoordinator fresh(StageRecorder recorder,
                                      MutationValidator validator,
                                      LedgerWriter ledger,
                                      EconomyOperator economy,
                                      DomainCommitter committer,
                                      MutationPublisher publisher) {
        LogicalReservationRegistry reg = new LogicalReservationRegistry();
        ReservationKeyExtractor ext = new ReservationKeyExtractor();
        MutationValidator v = validator != null ? validator : req -> MutationValidator.ValidationResult.ok();
        LedgerWriter l = ledger != null ? ledger : new NoopLedger();
        EconomyOperator e = economy != null ? economy : (req, op) -> CompletableFuture.completedFuture(EconomyOperator.EconomyResult.ok());
        DomainCommitter d = committer != null ? committer : (req, op) -> CompletableFuture.completedFuture(DomainCommitter.DomainCommitResult.success(new LandId(UUID.randomUUID())));
        MutationPublisher p = publisher != null ? publisher : (req, res) -> CompletableFuture.completedFuture(null);
        coordinator = new MutationCoordinator(v, ext, reg, l, e, d, p, recorder);
        return coordinator;
    }

    private static MutationRequest claim(UUID world, int x, int z, String name) {
        return new MutationRequest(MutationKind.LAND_CREATE, null, OwnerRef.player(UUID.randomUUID()), Set.of(new ChunkKey(world, x, z)), name);
    }

    /**
     * Two callers race to claim the same chunk. The caller-thread reservation must
     * reject the second one deterministically without relying on sleep. We block
     * the first submit at the ledger stage and explicitly wait for the second
     * submit's future to complete (via the future's own state, not a sleep)
     * before releasing the ledger latch.
     */
    @Test
    void sameChunkTwoParallelClaimsExactlyOneSuccess() throws Exception {
        UUID world = UUID.randomUUID();
        MutationRequest req = claim(world, 5, 5, "Home");
        StageRecorder recorder = new StageRecorder();

        CountDownLatch ledgerStarted = new CountDownLatch(1);
        CountDownLatch releaseLedger = new CountDownLatch(1);
        LedgerWriter blocking = new LedgerWriter() {
            @Override
            public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                return CompletableFuture.runAsync(() -> {
                    ledgerStarted.countDown();
                    awaitRelease(releaseLedger, "blocking ledger release");
                });
            }
            @Override
            public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                return CompletableFuture.completedFuture(null);
            }
        };

        fresh(recorder, null, blocking, null, null, null);

        CyclicBarrier ready = new CyclicBarrier(2);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        // Capture the inner future returned by coordinator.submit on each caller
        // thread. The rejected submit's inner future is completed synchronously
        // (on the caller thread) with the deterministic REJECTED outcome, so its
        // isDone() reflects the resolved reservation state without depending on
        // the outer supplyAsync wrapper finishing. Checking the inner future
        // instead of the outer future avoids a rare race where the supplier
        // thread is preempted between the inner future returning and the outer future
        // being marked as done (which would show both outer futures as not
        // done and fail the XOR assertion).
        AtomicReference<CompletableFuture<MutationResult>> innerF1 = new AtomicReference<>();
        AtomicReference<CompletableFuture<MutationResult>> innerF2 = new AtomicReference<>();
        CountDownLatch bothSubmitted = new CountDownLatch(2);
        CompletableFuture<MutationResult> f1;
        CompletableFuture<MutationResult> f2;
        Throwable primary = null;
        try {
            f1 = CompletableFuture.supplyAsync(() -> {
                awaitBarrier(ready, 2, "caller rendezvous");
                CompletableFuture<MutationResult> inner = coordinator.submit(req).toCompletableFuture();
                innerF1.set(inner);
                bothSubmitted.countDown();
                return awaitMutationResult(inner);
            }, callers);
            f2 = CompletableFuture.supplyAsync(() -> {
                awaitBarrier(ready, 2, "caller rendezvous");
                CompletableFuture<MutationResult> inner = coordinator.submit(req).toCompletableFuture();
                innerF2.set(inner);
                bothSubmitted.countDown();
                return awaitMutationResult(inner);
            }, callers);
            // Wait until both suppliers have called coordinator.submit so the
            // inner futures are captured and the rejected one is done synchronously.
            assertTrue(bothSubmitted.await(3, TimeUnit.SECONDS),
                    "both submits must be captured before checking reservation state");
            // Wait until the first submission reaches the ledger stage (or verify it
            // already has, since bothSubmitted may outrun the serial executor).
            assertTrue(ledgerStarted.getCount() == 0 || ledgerStarted.await(3, TimeUnit.SECONDS),
                    "ledger should start");
            CompletableFuture<MutationResult> if1 = innerF1.get();
            CompletableFuture<MutationResult> if2 = innerF2.get();
            assertNotNull(if1, "first inner future must be captured");
            assertNotNull(if2, "second inner future must be captured");
            assertTrue(if1.isDone() ^ if2.isDone(),
                    "exactly one submit should be done (rejected) before ledger is released");

            releaseLedger.countDown();

            MutationResult r1 = f1.get(3, TimeUnit.SECONDS);
            MutationResult r2 = f2.get(3, TimeUnit.SECONDS);
            long successes = List.of(r1, r2).stream().filter(r -> r.outcome() == MutationOutcome.SUCCESS).count();
            long rejected = List.of(r1, r2).stream().filter(r -> r.outcome() == MutationOutcome.REJECTED).count();
            assertEquals(1, successes, "exactly one success");
            assertEquals(1, rejected, "exactly one rejected");
            assertEquals(0, coordinator.reservations().size(), "reservation released");
            List<MutationResult> both = List.of(r1, r2);
            assertTrue(both.stream().anyMatch(r -> "reservation.conflict".equals(r.diagnosticKey())));
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            // Failure-path cleanup: every assertion/timeout/exception path must release
            // the blocking ledger and shut down the caller pool with bounded termination.
            // Executor cleanup evidence: callers pool must terminate deterministically so
            // a leak never hides behind a missing awaitTermination. Cleanup failures
            // are added as suppressed to the primary failure so the root cause is not lost,
            // and InterruptedException restores the interrupt status.
            releaseLedger.countDown();
            boolean terminated = false;
            Throwable interruptSuppressed = null;
            try {
                callers.shutdown();
                terminated = callers.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interruptSuppressed = e;
                callers.shutdownNow();
            }
            if (!terminated) {
                callers.shutdownNow();
                try {
                    terminated = callers.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (interruptSuppressed == null) interruptSuppressed = e;
                    else interruptSuppressed.addSuppressed(e);
                }
            }
            if (interruptSuppressed != null && primary != null) {
                primary.addSuppressed(interruptSuppressed);
            }
            if (!terminated) {
                AssertionError ae = new AssertionError("caller pool must terminate before test exits");
                if (primary != null) {
                    primary.addSuppressed(ae);
                } else if (interruptSuppressed != null) {
                    interruptSuppressed.addSuppressed(ae);
                    AssertionError wrapper = new AssertionError("caller pool must terminate before test exits", interruptSuppressed);
                    throw wrapper;
                } else {
                    throw ae;
                }
            }
        }
    }

    /**
     * Higher-fanout variant: eight callers race the same chunk. The contract is
     * stricter than the N=2 case — exactly one durable mutation (DomainCommit)
     * must be triggered while the other seven are rejected at the caller-thread
     * reservation. The CyclicBarrier makes the rendezvous deterministic so all
     * eight submits land on the registry at the same instant; the
     * AtomicInteger on DomainCommit provides the durable-mutation witness.
     * A blocking ledger holds the winner's pipeline so the reservation stays
     * acquired while all seven rejections are observed deterministically via
     * inner-future isDone, without using sleep.
     */
    @Test
    void sameChunkEightParallelClaimsExactlyOneSuccess() throws Exception {
        int n = 8;
        UUID world = UUID.randomUUID();
        MutationRequest req = claim(world, 5, 5, "Home");
        StageRecorder recorder = new StageRecorder();
        AtomicInteger commits = new AtomicInteger(0);
        DomainCommitter counting = (request, opId) -> {
            commits.incrementAndGet();
            return CompletableFuture.completedFuture(
                    DomainCommitter.DomainCommitResult.success(new LandId(UUID.randomUUID())));
        };
        CountDownLatch ledgerStarted = new CountDownLatch(1);
        CountDownLatch releaseLedger = new CountDownLatch(1);
        LedgerWriter blocking = new LedgerWriter() {
            @Override
            public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                return CompletableFuture.runAsync(() -> {
                    ledgerStarted.countDown();
                    awaitRelease(releaseLedger, "blocking ledger release");
                });
            }
            @Override
            public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                return CompletableFuture.completedFuture(null);
            }
        };
        fresh(recorder, null, blocking, null, counting, null);

        CyclicBarrier ready = new CyclicBarrier(n);
        ExecutorService callers = Executors.newFixedThreadPool(n);
        List<AtomicReference<CompletableFuture<MutationResult>>> innerRefs = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) innerRefs.add(new AtomicReference<>());
        CountDownLatch bothSubmitted = new CountDownLatch(n);
        List<CompletableFuture<MutationResult>> futures = new java.util.ArrayList<>(n);
        Throwable primary = null;
        try {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    awaitBarrier(ready, 5, "caller rendezvous");
                    CompletableFuture<MutationResult> inner = coordinator.submit(req).toCompletableFuture();
                    innerRefs.get(idx).set(inner);
                    bothSubmitted.countDown();
                    return awaitMutationResult(inner);
                }, callers));
            }
            assertTrue(bothSubmitted.await(5, TimeUnit.SECONDS),
                    "all eight submits must be captured before checking reservation state");
            assertTrue(ledgerStarted.getCount() == 0 || ledgerStarted.await(3, TimeUnit.SECONDS),
                    "ledger should start");
            List<CompletableFuture<MutationResult>> inners = innerRefs.stream().map(AtomicReference::get).toList();
            assertTrue(inners.stream().noneMatch(r -> r == null), "every inner future must be captured");
            long doneBeforeRelease = inners.stream().filter(CompletableFuture::isDone).count();
            assertEquals(n - 1, doneBeforeRelease,
                    "exactly seven submits should be done (rejected) while the winner is blocked at ledger");
            long notDoneBeforeRelease = inners.stream().filter(r -> !r.isDone()).count();
            assertEquals(1, notDoneBeforeRelease,
                    "exactly one submit should be pending (winner) before ledger is released");

            releaseLedger.countDown();

            List<MutationResult> results = futures.stream()
                    .map(MutationCoordinatorTest::awaitMutationResult)
                    .toList();

            long successes = results.stream()
                    .filter(r -> r.outcome() == MutationOutcome.SUCCESS).count();
            long rejected = results.stream()
                    .filter(r -> r.outcome() == MutationOutcome.REJECTED).count();
            assertEquals(1, successes, "exactly one claim wins");
            assertEquals(n - 1, rejected, "remaining claims are rejected at reservation");
            assertTrue(results.stream()
                    .filter(r -> r.outcome() == MutationOutcome.REJECTED)
                    .allMatch(r -> "reservation.conflict".equals(r.diagnosticKey())),
                    "every rejection must surface as reservation.conflict");
            assertEquals(1, commits.get(),
                    "DomainCommit must run exactly once — no duplicate durable mutation");
            assertEquals(0, coordinator.reservations().size(),
                    "reservation released after the winner finalizes");
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            // Bounded cleanup with secondary await and suppressed propagation so the
            // primary assertion timeout or barrier failure is never hidden by a
            // termination assert. InterruptedException restores interrupt status.
            releaseLedger.countDown();
            boolean terminated = false;
            Throwable interruptSuppressed = null;
            try {
                callers.shutdown();
                terminated = callers.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interruptSuppressed = e;
                callers.shutdownNow();
            }
            if (!terminated) {
                callers.shutdownNow();
                try {
                    terminated = callers.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (interruptSuppressed == null) interruptSuppressed = e;
                    else interruptSuppressed.addSuppressed(e);
                }
            }
            if (interruptSuppressed != null && primary != null) {
                primary.addSuppressed(interruptSuppressed);
            }
            if (!terminated) {
                AssertionError ae = new AssertionError("caller pool must terminate before test exits");
                if (primary != null) {
                    primary.addSuppressed(ae);
                } else if (interruptSuppressed != null) {
                    interruptSuppressed.addSuppressed(ae);
                    AssertionError wrapper = new AssertionError("caller pool must terminate before test exits", interruptSuppressed);
                    throw wrapper;
                } else {
                    throw ae;
                }
            }
        }
    }

    @Test
    void differentChunkBothSucceed() throws Exception {
        UUID world = UUID.randomUUID();
        MutationRequest a = claim(world, 1, 1, "A");
        MutationRequest b = claim(world, 2, 2, "B");
        fresh(null, null, null, null, null, null);

        CompletableFuture<MutationResult> fa = coordinator.submit(a).toCompletableFuture();
        CompletableFuture<MutationResult> fb = coordinator.submit(b).toCompletableFuture();
        MutationResult ra = fa.get(2, TimeUnit.SECONDS);
        MutationResult rb = fb.get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.SUCCESS, ra.outcome());
        assertEquals(MutationOutcome.SUCCESS, rb.outcome());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void validationFailureReleasesAndDoesNotReserve() throws Exception {
        StageRecorder rec = new StageRecorder();
        MutationValidator failing = req -> MutationValidator.ValidationResult.rejected("validation.bad");
        fresh(rec, failing, null, null, null, null);
        UUID world = UUID.randomUUID();
        MutationRequest req = claim(world, 10, 10, "Bad");
        MutationResult r = coordinator.submit(req).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.REJECTED, r.outcome());
        assertEquals("validation.bad", r.diagnosticKey());
        assertEquals(List.of("Validate"), rec.snapshot());
        assertEquals(0, coordinator.reservations().size());
        // retry same chunk after validation failure should be allowed (no stuck reservation) but will again be rejected by validation
        MutationResult r2 = coordinator.submit(req).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.REJECTED, r2.outcome());
        assertEquals("validation.bad", r2.diagnosticKey());
    }

    @Test
    void ledgerFailureReleasesReservation() throws Exception {
        LedgerWriter failing = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                CompletableFuture<Void> f = new CompletableFuture<>();
                f.completeExceptionally(new RuntimeException("ledger boom"));
                return f;
            }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) { return CompletableFuture.completedFuture(null); }
        };
        fresh(null, null, failing, null, null, null);
        UUID world = UUID.randomUUID();
        MutationRequest req = claim(world, 3, 3, "L");
        MutationResult r = coordinator.submit(req).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("ledger.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
        // second attempt same chunk should not be blocked by stuck reservation; will again hit ledger failure (not reservation conflict)
        MutationResult r2 = coordinator.submit(req).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r2.outcome());
        assertNotEquals("reservation.conflict", r2.diagnosticKey());
    }

    @Test
    void economyFailureReleasesReservation() throws Exception {
        // First call blocks on a latch so we can deterministically observe the
        // reservation state between the two same-key submissions.
        CountDownLatch firstEconomyEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstEconomy = new CountDownLatch(1);
        AtomicInteger economyCalls = new AtomicInteger(0);
        EconomyOperator counting = (req, op) -> {
            int n = economyCalls.incrementAndGet();
            if (n == 1) {
                CompletableFuture<EconomyOperator.EconomyResult> f = new CompletableFuture<>();
                CompletableFuture.runAsync(() -> {
                    firstEconomyEntered.countDown();
                    awaitRelease(releaseFirstEconomy, "first economy release");
                }).whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        f.complete(EconomyOperator.EconomyResult.failed("economy.insufficient"));
                    } else {
                        f.completeExceptionally(failure);
                    }
                });
                return f;
            }
            return CompletableFuture.completedFuture(EconomyOperator.EconomyResult.ok());
        };
        fresh(null, null, null, counting, null, null);
        UUID world = UUID.randomUUID();
        MutationRequest req = claim(world, 4, 4, "E");

        CompletableFuture<MutationResult> f1 = coordinator.submit(req).toCompletableFuture();
        assertTrue(firstEconomyEntered.await(2, TimeUnit.SECONDS), "first economy should enter");
        releaseFirstEconomy.countDown();
        MutationResult r1 = f1.get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r1.outcome());
        assertEquals("economy.insufficient", r1.diagnosticKey());
        assertEquals(0, coordinator.reservations().size(),
                "reservation must be released after economy failure");

        // Second submission with the same chunk key: must reach the economy
        // stage (not be rejected as reservation.conflict), proving the
        // reservation was actually released by the first failure.
        MutationRequest sameChunk = new MutationRequest(
                req.kind(), null, req.requestedBy(), req.chunks(), "E2");
        MutationResult r2 = coordinator.submit(sameChunk).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.SUCCESS, r2.outcome(),
                "second submission must run pipeline (reservation released)");
        assertEquals(2, economyCalls.get(),
                "second submission must reach economy stage (reservation key was free)");
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void domainFailureReleasesReservation() throws Exception {
        DomainCommitter failing = (req, op) -> {
            CompletableFuture<DomainCommitter.DomainCommitResult> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("domain boom"));
            return f;
        };
        fresh(null, null, null, null, failing, null);
        UUID world = UUID.randomUUID();
        MutationRequest req = claim(world, 6, 6, "D");
        MutationResult r = coordinator.submit(req).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void publishFailureReleasesReservation() throws Exception {
        MutationPublisher failing = (req, res) -> {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("publish boom"));
            return f;
        };
        fresh(null, null, null, null, null, failing);
        UUID world = UUID.randomUUID();
        MutationRequest req = claim(world, 7, 7, "P");
        MutationResult r = coordinator.submit(req).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("publish.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void stageOrderingSuccess() throws Exception {
        StageRecorder rec = new StageRecorder();
        fresh(rec, null, null, null, null, null);
        UUID world = UUID.randomUUID();
        MutationRequest req = claim(world, 8, 8, "O");
        MutationResult r = coordinator.submit(req).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.SUCCESS, r.outcome());
        assertEquals(List.of("Validate", "Reservation", "Ledger", "Economy", "DomainCommit", "Publish", "Finalize"), rec.snapshot());
    }

    @Test
    void stageOrderingValidationFailureOnlyValidate() throws Exception {
        StageRecorder rec = new StageRecorder();
        fresh(rec, req -> MutationValidator.ValidationResult.rejected("bad"), null, null, null, null);
        UUID world = UUID.randomUUID();
        MutationResult r = coordinator.submit(claim(world, 9, 9, "X")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.REJECTED, r.outcome());
        assertEquals(List.of("Validate"), rec.snapshot());
    }

    @Test
    void dbUniqueFallbackMapsToRejected() throws Exception {
        DomainCommitter uniqueFail = (req, op) -> {
            CompletableFuture<DomainCommitter.DomainCommitResult> f = new CompletableFuture<>();
            f.completeExceptionally(new CompletionException(new java.sql.SQLException("UNIQUE constraint failed: land_chunks")));
            return f;
        };
        fresh(null, null, null, null, uniqueFail, null);
        UUID world = UUID.randomUUID();
        MutationRequest req = claim(world, 11, 11, "U");
        MutationResult r = coordinator.submit(req).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.REJECTED, r.outcome());
        assertEquals("land.chunk.conflict", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
        assertNotEquals(MutationOutcome.SUCCESS, r.outcome(), "DB UNIQUE must not be swallowed as success");
    }

    /**
     * Foreign key violations on the domain commit path must NOT be misclassified
     * as {@code land.chunk.conflict}. The constraint violation is real but it is
     * an integrity failure, not a chunk uniqueness conflict. The reservation
     * must still be released.
     */
    @Test
    void foreignKeyFailureOnDomainMapsToFailedNotConflict() throws Exception {
        DomainCommitter fkFail = (req, op) -> {
            CompletableFuture<DomainCommitter.DomainCommitResult> f = new CompletableFuture<>();
            f.completeExceptionally(new CompletionException(
                    new java.sql.SQLException("FOREIGN KEY constraint failed: land_id_ref")));
            return f;
        };
        fresh(null, null, null, null, fkFail, null);
        UUID world = UUID.randomUUID();
        MutationRequest req = claim(world, 41, 41, "FK");
        MutationResult r = coordinator.submit(req).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome(),
                "FK violation must surface as FAILED, not REJECTED");
        assertEquals("domain.failed", r.diagnosticKey(),
                "FK violation must NOT be mapped to land.chunk.conflict");
        assertNotEquals("land.chunk.conflict", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size(),
                "FK failure must still release the reservation");
    }

    /**
     * CHECK constraint violations must NOT be misclassified as
     * {@code land.chunk.conflict}. CHECK failures indicate a domain shape
     * problem (e.g. negative coordinate) and are ledger/domain integrity
     * issues, not chunk-uniqueness conflicts.
     */
    @Test
    void checkConstraintFailureOnDomainMapsToFailedNotConflict() throws Exception {
        DomainCommitter checkFail = (req, op) -> {
            CompletableFuture<DomainCommitter.DomainCommitResult> f = new CompletableFuture<>();
            f.completeExceptionally(new CompletionException(
                    new java.sql.SQLException("CHECK constraint failed: chunks_positive")));
            return f;
        };
        fresh(null, null, null, null, checkFail, null);
        UUID world = UUID.randomUUID();
        MutationRequest req = claim(world, 42, 42, "CK");
        MutationResult r = coordinator.submit(req).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("domain.failed", r.diagnosticKey(),
                "CHECK violation must NOT be mapped to land.chunk.conflict");
        assertNotEquals("land.chunk.conflict", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size(),
                "CHECK failure must still release the reservation");
    }

    /**
     * The same UNIQUE-only classification must hold for the ledger stage,
     * not just the domain commit stage. A FOREIGN KEY failure on the
     * operation-ledger insert must surface as a generic ledger failure,
     * never as a chunk-uniqueness conflict.
     */
    @Test
    void foreignKeyFailureOnLedgerMapsToFailedNotConflict() throws Exception {
        LedgerWriter fkFail = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                CompletableFuture<Void> f = new CompletableFuture<>();
                f.completeExceptionally(new CompletionException(
                        new java.sql.SQLException("FOREIGN KEY constraint failed: op_owner_ref")));
                return f;
            }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                return CompletableFuture.completedFuture(null);
            }
        };
        fresh(null, null, fkFail, null, null, null);
        UUID world = UUID.randomUUID();
        MutationRequest req = claim(world, 43, 43, "FKL");
        MutationResult r = coordinator.submit(req).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("ledger.failed", r.diagnosticKey(),
                "FK violation on ledger must NOT map to land.chunk.conflict");
        assertNotEquals("land.chunk.conflict", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    /**
     * A bare "constraint" word in the SQLException message (with no UNIQUE
     * or DUPLICATE evidence) must NOT be classified as a chunk conflict.
     * This guards against the broad-message fallback that historically
     * mis-mapped FOREIGN KEY/CHECK errors to {@code land.chunk.conflict}.
     */
    @Test
    void bareConstraintMessageIsNotConflict() throws Exception {
        DomainCommitter generic = (req, op) -> {
            CompletableFuture<DomainCommitter.DomainCommitResult> f = new CompletableFuture<>();
            f.completeExceptionally(new CompletionException(
                    new java.sql.SQLException("constraint violation detected")));
            return f;
        };
        fresh(null, null, null, null, generic, null);
        UUID world = UUID.randomUUID();
        MutationResult r = coordinator.submit(claim(world, 44, 44, "G"))
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("domain.failed", r.diagnosticKey());
        assertNotEquals("land.chunk.conflict", r.diagnosticKey());
    }

    @Test
    void economyOutsideTransaction() throws Exception {
        // Hold the (simulated) SQL transaction open with a latch so the
        // Economy spy runs deterministically after the ledger stage closes
        // the transaction. No sleep or busy-poll is used.
        CountDownLatch ledgerStarted = new CountDownLatch(1);
        CountDownLatch releaseLedger = new CountDownLatch(1);
        AtomicBoolean inTx = new AtomicBoolean(false);
        LedgerWriter txLedger = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                return CompletableFuture.runAsync(() -> {
                    inTx.set(true);
                    ledgerStarted.countDown();
                    try {
                        awaitRelease(releaseLedger, "transaction ledger release");
                    } finally {
                        inTx.set(false);
                    }
                });
            }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) { return CompletableFuture.completedFuture(null); }
        };
        AtomicBoolean economySawTx = new AtomicBoolean(true);
        EconomyOperator spy = (req, op) -> {
            economySawTx.set(inTx.get());
            return CompletableFuture.completedFuture(EconomyOperator.EconomyResult.ok());
        };
        fresh(null, null, txLedger, spy, null, null);
        UUID world = UUID.randomUUID();
        CompletableFuture<MutationResult> f = coordinator.submit(claim(world, 12, 12, "T")).toCompletableFuture();
        assertTrue(ledgerStarted.await(2, TimeUnit.SECONDS), "ledger should start");
        assertTrue(inTx.get(), "transaction must be open while ledger holds it");
        // Economy has not been called yet: pipeline is blocked at ledger.
        assertFalse(f.isDone(), "pipeline must still be blocked at ledger");
        releaseLedger.countDown();
        MutationResult r = f.get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.SUCCESS, r.outcome());
        assertFalse(economySawTx.get(), "Economy must not be inside SQL transaction");
    }

    @Test
    void repeatedFinalizeDoesNotBreakState() throws Exception {
        AtomicInteger finalizeCount = new AtomicInteger(0);
        LedgerWriter counting = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) { return CompletableFuture.completedFuture(null); }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                finalizeCount.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        };
        fresh(null, null, counting, null, null, null);
        UUID world = UUID.randomUUID();
        MutationRequest req = claim(world, 13, 13, "R");
        MutationResult r1 = coordinator.submit(req).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.SUCCESS, r1.outcome());
        assertEquals(1, finalizeCount.get());
        // Second submit same chunk – since domain committer generates new LandId each time, it would succeed again (no DB). But we can test that second submit does not throw and increments finalize again
        MutationRequest req2 = new MutationRequest(MutationKind.LAND_CREATE, null, OwnerRef.player(UUID.randomUUID()), Set.of(new ChunkKey(world, 13, 13)), "R2");
        MutationResult r2 = coordinator.submit(req2).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.SUCCESS, r2.outcome());
        assertEquals(2, finalizeCount.get());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void immutableRequestAndResult() {
        UUID world = UUID.randomUUID();
        Set<ChunkKey> input = new java.util.HashSet<>();
        input.add(new ChunkKey(world, 1, 1));
        MutationRequest req = new MutationRequest(MutationKind.LAND_CREATE, null, OwnerRef.server(), input, "Home");
        input.add(new ChunkKey(world, 2, 2));
        assertEquals(1, req.chunks().size(), "request must defensively copy");
        assertThrows(UnsupportedOperationException.class, () -> req.chunks().add(new ChunkKey(world, 3, 3)));

        MutationResult res = MutationResult.success(new LandId(UUID.randomUUID()));
        assertEquals(MutationOutcome.SUCCESS, res.outcome());
        // diagnosticKey stable, not rendered message
        MutationResult rej = MutationResult.rejected("reservation.conflict");
        assertEquals("reservation.conflict", rej.diagnosticKey());
        assertFalse(rej.diagnosticKey().contains(" "), "diagnostic key must be stable key, not sentence");
    }

    @Test
    void submitFromMultipleRegionThreadsOnlyViaCoordinator() throws Exception {
        fresh(null, null, null, null, null, null);
        UUID world = UUID.randomUUID();
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        Throwable primary = null;
        try {
            List<CompletableFuture<MutationResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                int x = 100 + i;
                MutationRequest req = claim(world, x, x, "N" + i);
                futures.add(CompletableFuture.supplyAsync(
                        () -> awaitMutationResult(coordinator.submit(req).toCompletableFuture()), pool));
            }
            for (var f : futures) {
                MutationResult r = f.get(3, TimeUnit.SECONDS);
                assertEquals(MutationOutcome.SUCCESS, r.outcome());
                assertNotNull(r.landId());
            }
            assertEquals(0, coordinator.reservations().size());
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            // Bounded cleanup: normal shutdown with timeout, fallback to shutdownNow.
            // Cleanup failure is suppressed onto primary so the original assertion is not lost.
            boolean terminated = false;
            Throwable interruptSuppressed = null;
            try {
                pool.shutdown();
                terminated = pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interruptSuppressed = e;
                pool.shutdownNow();
            }
            if (!terminated) {
                pool.shutdownNow();
                try {
                    terminated = pool.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (interruptSuppressed == null) interruptSuppressed = e;
                    else interruptSuppressed.addSuppressed(e);
                }
            }
            if (interruptSuppressed != null && primary != null) {
                primary.addSuppressed(interruptSuppressed);
            }
            if (!terminated) {
                AssertionError ae = new AssertionError("pool must terminate before test exits");
                if (primary != null) {
                    primary.addSuppressed(ae);
                } else if (interruptSuppressed != null) {
                    interruptSuppressed.addSuppressed(ae);
                    throw new AssertionError("pool must terminate before test exits", interruptSuppressed);
                } else {
                    throw ae;
                }
            }
        }
    }

    // --- Regression tests for terminal-path release and cancellation ---

    /**
     * Submitting after the coordinator is closed must not leak the reservation
     * acquired on the caller thread, and must produce a clear FAILED result so
     * callers can recover. Without the fix the {@code serial.execute(...)} call
     * throws RejectedExecutionException after shutdown, leaving both the
     * reservation and the promise dangling.
     */
    @Test
    void submitAfterCloseReleasesReservationAndFails() throws Exception {
        fresh(null, null, null, null, null, null);
        UUID world = UUID.randomUUID();
        coordinator.close();
        MutationResult r = coordinator.submit(claim(world, 20, 20, "AfterClose"))
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome(),
                "submit after close must return FAILED, not hang");
        assertEquals("coordinator.rejected", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size(),
                "no reservation may leak after executor rejection");
    }

    /**
     * Cancelling the returned future must stop the pipeline before any
     * downstream side effect runs (no economy charge, no domain commit, no
     * publish, no finalize). Without the fix the pipeline body keeps running
     * after cancel and only the reservation is released.
     */
    @Test
    void cancellationStopsPipelineBeforeEconomy() throws Exception {
        CountDownLatch ledgerEntered = new CountDownLatch(1);
        CountDownLatch releaseLedger = new CountDownLatch(1);
        CountDownLatch finalizeCalled = new CountDownLatch(1);
        AtomicBoolean economyCalled = new AtomicBoolean(false);
        AtomicBoolean domainCalled = new AtomicBoolean(false);
        AtomicBoolean publishCalled = new AtomicBoolean(false);

        LedgerWriter blocking = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                return CompletableFuture.runAsync(() -> {
                    ledgerEntered.countDown();
                    awaitRelease(releaseLedger, "cancellation ledger release");
                });
            }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                finalizeCalled.countDown();
                return CompletableFuture.completedFuture(null);
            }
        };
        EconomyOperator spy = (req, op) -> {
            economyCalled.set(true);
            return CompletableFuture.completedFuture(EconomyOperator.EconomyResult.ok());
        };
        DomainCommitter spyD = (req, op) -> {
            domainCalled.set(true);
            return CompletableFuture.completedFuture(DomainCommitter.DomainCommitResult.success(new LandId(UUID.randomUUID())));
        };
        MutationPublisher spyP = (req, res) -> {
            publishCalled.set(true);
            return CompletableFuture.completedFuture(null);
        };

        fresh(null, null, blocking, spy, spyD, spyP);
        UUID world = UUID.randomUUID();
        CompletableFuture<MutationResult> f = coordinator.submit(claim(world, 30, 30, "C")).toCompletableFuture();

        assertTrue(ledgerEntered.await(2, TimeUnit.SECONDS), "ledger should start");
        assertTrue(f.cancel(true), "future must be cancellable mid-pipeline");
        releaseLedger.countDown();

        // Pipeline must NOT continue to economy / domain / publish / finalize.
        // Use the finalize-state latch as the deterministic "pipeline finished"
        // signal. Without the fix, finalizeState is invoked; with the fix, the
        // pipeline aborts at the cancellation check before finalize.
        assertFalse(finalizeCalled.await(2, TimeUnit.SECONDS),
                "pipeline must not reach finalize after cancel");
        assertFalse(economyCalled.get(), "economy must not run after cancel");
        assertFalse(domainCalled.get(), "domain must not run after cancel");
        assertFalse(publishCalled.get(), "publish must not run after cancel");
        assertEquals(0, coordinator.reservations().size(),
                "reservation must be released after cancellation");
    }

    /**
     * Deterministic regression test: same chunk key after a clean success path
     * must run the full pipeline (proving the reservation was released by the
     * success finalize). Uses latches to wait for the first finalize without
     * any sleep.
     */
    @Test
    void successPathReleasesReservationForResubmission() throws Exception {
        CountDownLatch firstFinalize = new CountDownLatch(1);
        LedgerWriter tracking = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                firstFinalize.countDown();
                return CompletableFuture.completedFuture(null);
            }
        };
        fresh(null, null, tracking, null, null, null);
        UUID world = UUID.randomUUID();
        MutationRequest req = claim(world, 50, 50, "P");

        MutationResult r1 = coordinator.submit(req).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.SUCCESS, r1.outcome());
        assertTrue(firstFinalize.await(2, TimeUnit.SECONDS), "first finalize must run");
        assertEquals(0, coordinator.reservations().size());

        MutationRequest sameKey = new MutationRequest(
                req.kind(), null, req.requestedBy(), req.chunks(), "P2");
        MutationResult r2 = coordinator.submit(sameKey).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.SUCCESS, r2.outcome(),
                "second submit must run pipeline, not be reservation-conflicted");
        assertEquals(0, coordinator.reservations().size());
    }

    // --- Regression tests for close-time queued-work drain ---

    /**
     * Deterministic regression test for the close-drain contract:
     *
     * <ol>
     *   <li>The first submit blocks inside the economy collaborator.</li>
     *   <li>The second submit is queued behind the first (its reservation
     *       is held but the pipeline has not run).</li>
     *   <li>{@link MutationCoordinator#close()} drains the queue: the
     *       queued promise must complete deterministically with
     *       {@code FAILED / coordinator.rejected}, and its reservation
     *       must be released so the registry ends at size 0.</li>
     * </ol>
     *
     * <p>Running work (the first submit) is allowed to finish or observe
     * cancellation; only the queued work is required to have a
     * deterministic failed outcome and no leaked reservation.
     */
    @Test
    void closeDrainsQueuedWorkWithDeterministicFailure() throws Exception {
        CountDownLatch firstEconomyEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger economyCalls = new AtomicInteger(0);
        EconomyOperator blocking = (req, op) -> {
            int n = economyCalls.incrementAndGet();
            if (n == 1) {
                CompletableFuture<EconomyOperator.EconomyResult> f = new CompletableFuture<>();
                CompletableFuture.runAsync(() -> {
                    firstEconomyEntered.countDown();
                    awaitRelease(releaseFirst, "close-drain economy release");
                }).whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        f.complete(EconomyOperator.EconomyResult.ok());
                    } else {
                        f.completeExceptionally(failure);
                    }
                });
                return f;
            }
            return CompletableFuture.completedFuture(EconomyOperator.EconomyResult.ok());
        };
        fresh(null, null, null, blocking, null, null);
        UUID world = UUID.randomUUID();

        // 1) first mutation blocks in economy.
        CompletableFuture<MutationResult> first =
                coordinator.submit(claim(world, 60, 60, "F")).toCompletableFuture();
        assertTrue(firstEconomyEntered.await(2, TimeUnit.SECONDS),
                "first economy call must have started");

        // 2) second mutation is queued behind the first; its caller-thread
        //    reservation is held but the pipeline has not run.
        CompletableFuture<MutationResult> second =
                coordinator.submit(claim(world, 61, 61, "S")).toCompletableFuture();
        assertFalse(second.isDone(),
                "second must still be queued while first blocks in economy");
        assertTrue(coordinator.reservations().size() >= 1,
                "queued second mutation must hold a reservation before close");

        // 3) close: queued work must complete deterministically.
        coordinator.close();

        MutationResult r2 = second.get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r2.outcome(),
                "queued mutation must surface a deterministic FAILED outcome after close");
        assertEquals("coordinator.rejected", r2.diagnosticKey(),
                "queued mutation must use the coordinator.rejected diagnostic key");

        // 4) running first mutation may finish or observe cancellation; in
        //    either case its reservation must be released.
        releaseFirst.countDown();
        try {
            first.get(2, TimeUnit.SECONDS);
        } catch (CancellationException e) {
            // close-time cancellation is expected; reservation cleanup remains asserted below
        } catch (ExecutionException e) {
            throw new AssertionError("first mutation failed unexpectedly after close", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for first mutation after close", e);
        } catch (TimeoutException e) {
            throw new AssertionError("first mutation timed out after close", e);
        }
        assertEquals(0, coordinator.reservations().size(),
                "no reservation may leak after close drains queued work");
    }

    /**
     * Deterministic regression test for idempotent close: calling
     * {@link MutationCoordinator#close()} multiple times must not throw
     * and must remain safe for further submits.
     */
    @Test
    void closeIsIdempotent() throws Exception {
        fresh(null, null, null, null, null, null);
        coordinator.close();
        // second and third close calls must be no-ops, not exceptions.
        coordinator.close();
        coordinator.close();

        UUID world = UUID.randomUUID();
        MutationResult r = coordinator.submit(claim(world, 70, 70, "I"))
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("coordinator.rejected", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    // --- Regression: running never-completing collaborator + close ---

    @Test
    void neverCompletingLedgerCloseReachesBoundedTerminalResultAndNoLaterStages() throws Exception {
        CompletableFuture<Void> never = new CompletableFuture<>();
        CountDownLatch ledgerEntered = new CountDownLatch(1);
        AtomicBoolean economyCalled = new AtomicBoolean(false);
        AtomicBoolean domainCalled = new AtomicBoolean(false);
        AtomicBoolean publishCalled = new AtomicBoolean(false);
        CountDownLatch finalizeLatch = new CountDownLatch(1);
        StageRecorder rec = new StageRecorder();

        LedgerWriter blocking = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                ledgerEntered.countDown();
                return never;
            }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                finalizeLatch.countDown();
                return CompletableFuture.completedFuture(null);
            }
        };
        EconomyOperator ecoSpy = (req, op) -> {
            economyCalled.set(true);
            return CompletableFuture.completedFuture(EconomyOperator.EconomyResult.ok());
        };
        DomainCommitter domSpy = (req, op) -> {
            domainCalled.set(true);
            return CompletableFuture.completedFuture(DomainCommitter.DomainCommitResult.success(new LandId(UUID.randomUUID())));
        };
        MutationPublisher pubSpy = (req, res) -> {
            publishCalled.set(true);
            return CompletableFuture.completedFuture(null);
        };

        fresh(rec, null, blocking, ecoSpy, domSpy, pubSpy);
        UUID world = UUID.randomUUID();
        CompletableFuture<MutationResult> f = coordinator.submit(claim(world, 80, 80, "NC")).toCompletableFuture();

        assertTrue(ledgerEntered.await(2, TimeUnit.SECONDS), "ledger must have started");

        long closeStart = System.nanoTime();
        coordinator.close();
        long closeMs = (System.nanoTime() - closeStart) / 1_000_000;
        assertTrue(closeMs < 800, "close() must return promptly, was " + closeMs + "ms");

        MutationResult r = f.get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome(), "never-completing plus close must be FAILED");
        assertEquals("coordinator.rejected", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size(), "reservation must be released after close");

        // idempotent close must remain safe
        coordinator.close();
        coordinator.close();
        assertEquals(0, coordinator.reservations().size());

        // late collaborator completion must not resume pipeline or change result
        never.complete(null);
        // If pipeline incorrectly resumed, it would reach Economy/Domain/Publish/Finalize.
        // Use bounded awaits to assert they are NOT reached.
        assertFalse(finalizeLatch.await(400, TimeUnit.MILLISECONDS), "finalize must not run after close");
        assertFalse(economyCalled.get(), "economy must not run after close");
        assertFalse(domainCalled.get(), "domain must not run after close");
        assertFalse(publishCalled.get(), "publish must not run after close");

        MutationResult r2 = f.getNow(null);
        assertNotNull(r2);
        assertEquals(MutationOutcome.FAILED, r2.outcome(), "late completion must not change terminal result");
        assertEquals("coordinator.rejected", r2.diagnosticKey());
        // No later stage should have been recorded beyond Ledger
        List<String> snap = rec.snapshot();
        assertTrue(snap.contains("Ledger"), "ledger stage must have been recorded");
        assertFalse(snap.contains("Economy"), "economy must not be recorded after close");
        assertFalse(snap.contains("DomainCommit"), "domain must not be recorded after close");
    }

    @Test
    void lateCompletionAfterCloseCannotProduceSuccess() throws Exception {
        CompletableFuture<Void> ledgerFuture = new CompletableFuture<>();
        CountDownLatch ledgerEntered = new CountDownLatch(1);
        AtomicBoolean economyCalled = new AtomicBoolean(false);
        CountDownLatch finalizeLatch = new CountDownLatch(1);

        LedgerWriter ledger = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                ledgerEntered.countDown();
                return ledgerFuture;
            }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                finalizeLatch.countDown();
                return CompletableFuture.completedFuture(null);
            }
        };
        EconomyOperator eco = (req, op) -> {
            economyCalled.set(true);
            return CompletableFuture.completedFuture(EconomyOperator.EconomyResult.ok());
        };

        fresh(null, null, ledger, eco, null, null);
        UUID world = UUID.randomUUID();
        CompletableFuture<MutationResult> f = coordinator.submit(claim(world, 81, 81, "LC")).toCompletableFuture();
        assertTrue(ledgerEntered.await(2, TimeUnit.SECONDS));

        coordinator.close();
        MutationResult closedResult = f.get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, closedResult.outcome());
        assertEquals("coordinator.rejected", closedResult.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());

        // Collaborator later completes successfully - must NOT turn into SUCCESS
        ledgerFuture.complete(null);
        assertFalse(finalizeLatch.await(400, TimeUnit.MILLISECONDS), "finalize must not run after close");
        assertFalse(economyCalled.get(), "economy must not run after close even if ledger later succeeds");

        MutationResult later = f.getNow(null);
        assertEquals(MutationOutcome.FAILED, later.outcome());
        assertEquals("coordinator.rejected", later.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void synchronouslyBlockingEconomyDoesNotBlockClose() throws Exception {
        CountDownLatch supplierEntered = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        EconomyOperator blockingEco = (req, op) -> {
            supplierEntered.countDown();
            awaitRelease(releaseSupplier, "synchronous economy release");
            return CompletableFuture.completedFuture(EconomyOperator.EconomyResult.ok());
        };
        fresh(null, null, null, blockingEco, null, null);
        UUID world = UUID.randomUUID();
        CompletableFuture<MutationResult> f = coordinator.submit(claim(world, 84, 84, "SB")).toCompletableFuture();
        assertTrue(supplierEntered.await(2, TimeUnit.SECONDS), "economy supplier must be entered");

        long start = System.nanoTime();
        coordinator.close();
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 500, "close must not block on synchronously blocking supplier, was " + ms + "ms");

        releaseSupplier.countDown();
        MutationResult r = f.get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome(), "blocked supplier plus close must be FAILED");
        assertEquals("coordinator.rejected", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size(), "reservation must be cleaned after close");
    }

    @Test
    void queuedMutationCancelledBeforeRunningDoesNotInvokeLedger() throws Exception {
        CountDownLatch firstLedgerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstLedger = new CountDownLatch(1);
        AtomicInteger ledgerInvocations = new AtomicInteger(0);
        CountDownLatch secondLedgerEntered = new CountDownLatch(1);

        LedgerWriter blockingFirst = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                int n = ledgerInvocations.incrementAndGet();
                if (n == 1) {
                    firstLedgerEntered.countDown();
                    return CompletableFuture.runAsync(() -> {
                        awaitRelease(releaseFirstLedger, "queued ledger release");
                    });
                } else {
                    secondLedgerEntered.countDown();
                    return CompletableFuture.completedFuture(null);
                }
            }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                return CompletableFuture.completedFuture(null);
            }
        };

        fresh(null, null, blockingFirst, null, null, null);
        UUID world = UUID.randomUUID();
        // First occupies the serial executor.
        CompletableFuture<MutationResult> first = coordinator.submit(claim(world, 85, 85, "Q1")).toCompletableFuture();
        assertTrue(firstLedgerEntered.await(2, TimeUnit.SECONDS), "first ledger must start");

        // Second is queued (reservation acquired, accepted, but not yet running).
        CompletableFuture<MutationResult> second = coordinator.submit(claim(world, 86, 86, "Q2")).toCompletableFuture();
        assertFalse(second.isDone(), "second must be queued");
        // Cancel second while queued - must be linearized with stage start.
        assertTrue(second.cancel(true), "queued future must be cancellable");
        assertEquals(1, coordinator.reservations().size(), "second's reservation must be released on cancel, only first remains");

        releaseFirstLedger.countDown();
        MutationResult r1 = first.get(2, TimeUnit.SECONDS);
        // First may succeed or be terminal, but second must never have invoked ledger
        assertFalse(secondLedgerEntered.await(400, TimeUnit.MILLISECONDS), "second ledger must never be invoked after cancel");
        assertTrue(second.isCancelled() || second.isDone(), "second must be terminal");
        assertEquals(0, coordinator.reservations().size(), "all reservations must be cleaned after first completes");
    }

    @Test
    void synchronousExceptionFromLedgerIsTerminal() throws Exception {
        LedgerWriter syncThrow = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                throw new RuntimeException("sync ledger boom");
            }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                return CompletableFuture.completedFuture(null);
            }
        };
        fresh(null, null, syncThrow, null, null, null);
        UUID world = UUID.randomUUID();
        MutationResult r = coordinator.submit(claim(world, 90, 90, "SL")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("ledger.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void synchronousExceptionFromEconomyIsTerminal() throws Exception {
        EconomyOperator syncThrow = (req, op) -> { throw new RuntimeException("sync economy boom"); };
        fresh(null, null, null, syncThrow, null, null);
        UUID world = UUID.randomUUID();
        MutationResult r = coordinator.submit(claim(world, 91, 91, "SE")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("economy.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void synchronousExceptionFromDomainIsTerminal() throws Exception {
        DomainCommitter syncThrow = (req, op) -> { throw new RuntimeException("sync domain boom"); };
        fresh(null, null, null, null, syncThrow, null);
        UUID world = UUID.randomUUID();
        MutationResult r = coordinator.submit(claim(world, 92, 92, "SD")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("domain.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void synchronousExceptionFromPublishIsTerminal() throws Exception {
        MutationPublisher syncThrow = (req, res) -> { throw new RuntimeException("sync publish boom"); };
        fresh(null, null, null, null, null, syncThrow);
        UUID world = UUID.randomUUID();
        MutationResult r = coordinator.submit(claim(world, 93, 93, "SP")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("publish.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void acceptedButNotYetRunningIsConvergedOnClose() throws Exception {
        // Single mutation, close immediately without waiting for it to start.
        // The task is accepted and may be queued, dequeued-but-not-running,
        // or running; close must converge it via acceptedTasks tracking.
        LedgerWriter never = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                return new CompletableFuture<>();
            }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                return CompletableFuture.completedFuture(null);
            }
        };
        fresh(null, null, never, null, null, null);
        UUID world = UUID.randomUUID();
        CompletableFuture<MutationResult> f = coordinator.submit(claim(world, 94, 94, "AC")).toCompletableFuture();
        // Race close immediately
        coordinator.close();
        MutationResult r = f.get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("coordinator.rejected", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size(), "reservation must be cleaned even if task not yet running");
        // Idempotent close
        coordinator.close();
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void nullLedgerStageIsTerminal() throws Exception {
        LedgerWriter nullStage = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) { return null; }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) { return CompletableFuture.completedFuture(null); }
        };
        fresh(null, null, nullStage, null, null, null);
        MutationResult r = coordinator.submit(claim(UUID.randomUUID(), 95, 95, "NL")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("ledger.failed", r.diagnosticKey());
        assertNotNull(r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
        assertTrue(r.diagnosticKey().contains("ledger") || r.diagnosticKey().equals("ledger.failed"));
        // Ensure future is completed, not leaked
        CompletableFuture<MutationResult> f = coordinator.submit(claim(UUID.randomUUID(), 95, 96, "NL2")).toCompletableFuture();
        // Next submit should not be blocked by leaked reservation (different chunk, should succeed)
        MutationResult r2 = f.get(2, TimeUnit.SECONDS);
        // r2 may be ledger.failed again, but not reservation.conflict
        assertNotEquals("reservation.conflict", r2.diagnosticKey());
    }

    @Test
    void nullEconomyStageIsTerminal() throws Exception {
        EconomyOperator nullStage = (req, op) -> null;
        fresh(null, null, null, nullStage, null, null);
        MutationResult r = coordinator.submit(claim(UUID.randomUUID(), 96, 96, "NE")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("economy.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void nullEconomyResultIsTerminal() throws Exception {
        EconomyOperator nullResult = (req, op) -> CompletableFuture.completedFuture(null);
        fresh(null, null, null, nullResult, null, null);
        MutationResult r = coordinator.submit(claim(UUID.randomUUID(), 97, 97, "NER")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("economy.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void nullDomainStageIsTerminal() throws Exception {
        DomainCommitter nullStage = (req, op) -> null;
        fresh(null, null, null, null, nullStage, null);
        MutationResult r = coordinator.submit(claim(UUID.randomUUID(), 98, 98, "ND")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("domain.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void nullDomainResultIsTerminal() throws Exception {
        DomainCommitter nullResult = (req, op) -> CompletableFuture.completedFuture(null);
        fresh(null, null, null, null, nullResult, null);
        MutationResult r = coordinator.submit(claim(UUID.randomUUID(), 99, 99, "NDR")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("domain.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void nullPublishStageIsTerminal() throws Exception {
        MutationPublisher nullStage = (req, res) -> null;
        fresh(null, null, null, null, null, nullStage);
        MutationResult r = coordinator.submit(claim(UUID.randomUUID(), 100, 100, "NP")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("publish.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    // helper
    private static class NoopLedger implements LedgerWriter {
        @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) { return CompletableFuture.completedFuture(null); }
    }
}

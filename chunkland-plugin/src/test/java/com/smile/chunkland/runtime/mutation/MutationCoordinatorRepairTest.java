package com.smile.chunkland.runtime.mutation;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.mutation.MutationKind;
import com.smile.chunkland.api.mutation.MutationOutcome;
import com.smile.chunkland.api.mutation.MutationRequest;
import com.smile.chunkland.api.mutation.MutationResult;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Repair tests for finalize failure mapping, validator/keyExtractor null
 * handling and the authorization-versus-invocation race contract.
 */
class MutationCoordinatorRepairTest {

    private MutationCoordinator coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.testTerminalLatchForTest = null;
            coordinator.testPostAuthorizeHookForTest = null;
            coordinator.testKeyExtractorHookForTest = null;
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
        LedgerWriter l = ledger != null ? ledger : new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) { return CompletableFuture.completedFuture(null); }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) { return CompletableFuture.completedFuture(null); }
        };
        EconomyOperator e = economy != null ? economy : (req, op) -> CompletableFuture.completedFuture(EconomyOperator.EconomyResult.ok());
        DomainCommitter d = committer != null ? committer : (req, op) -> CompletableFuture.completedFuture(DomainCommitter.DomainCommitResult.success(new LandId(UUID.randomUUID())));
        MutationPublisher p = publisher != null ? publisher : (req, res) -> CompletableFuture.completedFuture(null);
        coordinator = new MutationCoordinator(v, ext, reg, l, e, d, p, recorder);
        return coordinator;
    }

    private static MutationRequest claim(UUID world, int x, int z, String name) {
        return new MutationRequest(MutationKind.LAND_CREATE, null, OwnerRef.player(UUID.randomUUID()), Set.of(new ChunkKey(world, x, z)), name);
    }

    // --- Finalize failure mappings ---

    @Test
    void finalizeNullStageMapsToExplicitFailedResult() throws Exception {
        LedgerWriter ledger = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) { return CompletableFuture.completedFuture(null); }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) { return null; }
        };
        fresh(null, null, ledger, null, null, null);
        MutationResult r = coordinator.submit(claim(UUID.randomUUID(), 1, 1, "F-null")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome(), "finalize null stage must be FAILED not SUCCESS");
        assertEquals("finalize.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size(), "reservation must be released on finalize null");
    }

    @Test
    void finalizeSynchronousThrowMapsToExplicitFailedResult() throws Exception {
        LedgerWriter ledger = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) { return CompletableFuture.completedFuture(null); }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) { throw new RuntimeException("sync finalize boom"); }
        };
        fresh(null, null, ledger, null, null, null);
        MutationResult r = coordinator.submit(claim(UUID.randomUUID(), 2, 2, "F-sync")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome(), "finalize sync throw must be FAILED not SUCCESS");
        assertEquals("finalize.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
        assertNotEquals(MutationOutcome.SUCCESS, r.outcome());
    }

    @Test
    void finalizeExceptionalCompletionMapsToExplicitFailedResult() throws Exception {
        LedgerWriter ledger = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) { return CompletableFuture.completedFuture(null); }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                CompletableFuture<Void> f = new CompletableFuture<>();
                f.completeExceptionally(new RuntimeException("async finalize boom"));
                return f;
            }
        };
        fresh(null, null, ledger, null, null, null);
        MutationResult r = coordinator.submit(claim(UUID.randomUUID(), 3, 3, "F-ex")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome(), "finalize exceptional stage must be FAILED not SUCCESS");
        assertEquals("finalize.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void finalizeToCompletableFutureReturnsNullMapsToFailed() throws Exception {
        LedgerWriter ledger = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) { return CompletableFuture.completedFuture(null); }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                return new CompletableFuture<Void>() {
                    @Override
                    public CompletableFuture<Void> toCompletableFuture() {
                        return null;
                    }
                };
            }
        };
        fresh(null, null, ledger, null, null, null);
        MutationResult r = coordinator.submit(claim(UUID.randomUUID(), 4, 4, "F-tocf-null")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("finalize.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void finalizeToCompletableFutureThrowsSynchronouslyMapsToFailed() throws Exception {
        LedgerWriter ledger = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) { return CompletableFuture.completedFuture(null); }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                return new CompletableFuture<Void>() {
                    @Override
                    public CompletableFuture<Void> toCompletableFuture() {
                        throw new RuntimeException("sync toCompletableFuture boom");
                    }
                };
            }
        };
        fresh(null, null, ledger, null, null, null);
        MutationResult r = coordinator.submit(claim(UUID.randomUUID(), 4, 5, "F-tocf-throw")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome(), "finalize toCompletableFuture sync throw must be FAILED not SUCCESS");
        assertEquals("finalize.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size(), "reservation must be released on finalize toCompletableFuture throw");
        assertNotEquals(MutationOutcome.SUCCESS, r.outcome());
    }

    @Test
    void finalizeSuccessStillReportsSuccess() throws Exception {
        LedgerWriter ledger = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) { return CompletableFuture.completedFuture(null); }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                assertEquals(MutationOutcome.SUCCESS, o);
                return CompletableFuture.completedFuture(null);
            }
        };
        fresh(null, null, ledger, null, null, null);
        MutationResult r = coordinator.submit(claim(UUID.randomUUID(), 5, 5, "F-ok")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.SUCCESS, r.outcome());
        assertEquals(0, coordinator.reservations().size());
    }

    // --- Validator / key extractor null handling ---

    @Test
    void validatorReturnsNullMapsToValidationFailedWithoutNpe() throws Exception {
        MutationValidator nullValidator = req -> null;
        fresh(null, nullValidator, null, null, null, null);
        MutationResult r = coordinator.submit(claim(UUID.randomUUID(), 6, 6, "V-null")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome(), "validator null must map to FAILED");
        assertEquals("validation.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size(), "no reservation must be held after validator null");
        // Second submit same chunk must not be conflict-rejected (no leaked reservation)
        MutationResult r2 = coordinator.submit(claim(UUID.randomUUID(), 6, 7, "V-null2")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertNotEquals("reservation.conflict", r2.diagnosticKey());
    }

    @Test
    void keyExtractorReturnsNullMapsToReservationFailedWithoutNpe() throws Exception {
        fresh(null, null, null, null, null, null);
        coordinator.testKeyExtractorHookForTest = req -> null;
        MutationResult r = coordinator.submit(claim(UUID.randomUUID(), 7, 7, "K-null")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome(), "key extractor null must map to FAILED");
        assertEquals("reservation.failed", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size(), "no reservation must leak on extractor null");
        // Retry different chunk that would reuse same logical key shape must not be blocked
        coordinator.testKeyExtractorHookForTest = null;
        MutationResult r2 = coordinator.submit(claim(UUID.randomUUID(), 7, 8, "K-null2")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.SUCCESS, r2.outcome());
        assertEquals(0, coordinator.reservations().size());
    }

    // --- Authorization-versus-invocation race ---

    /**
     * If authorization wins before close, the collaborator may still be invoked
     * after close, but no later stage may run and the terminal result is
     * exactly-once FAILED with reservation released. This uses a deterministic
     * latch barrier between authorization and invocation, not sleep.
     */
    @Test
    void authorizedCollaboratorMayBeInvokedAfterCloseButLaterStagesSuppressed() throws Exception {
        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch blockInvoke = new CountDownLatch(1);
        AtomicBoolean ledgerInvoked = new AtomicBoolean(false);
        AtomicBoolean economyInvoked = new AtomicBoolean(false);
        AtomicBoolean domainInvoked = new AtomicBoolean(false);
        AtomicBoolean publishInvoked = new AtomicBoolean(false);
        AtomicBoolean finalizeInvoked = new AtomicBoolean(false);
        StageRecorder rec = new StageRecorder();

        LedgerWriter ledger = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                ledgerInvoked.set(true);
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) {
                finalizeInvoked.set(true);
                return CompletableFuture.completedFuture(null);
            }
        };
        EconomyOperator economy = (req, op) -> {
            economyInvoked.set(true);
            return CompletableFuture.completedFuture(EconomyOperator.EconomyResult.ok());
        };
        DomainCommitter committer = (req, op) -> {
            domainInvoked.set(true);
            return CompletableFuture.completedFuture(DomainCommitter.DomainCommitResult.success(new LandId(UUID.randomUUID())));
        };
        MutationPublisher publisher = (req, res) -> {
            publishInvoked.set(true);
            return CompletableFuture.completedFuture(null);
        };

        fresh(rec, null, ledger, economy, committer, publisher);
        coordinator.testPostAuthorizeHookForTest = stage -> {
            if ("Ledger".equals(stage)) {
                authorized.countDown();
                try {
                    blockInvoke.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        CompletableFuture<MutationResult> f = coordinator.submit(claim(UUID.randomUUID(), 20, 20, "Race-A")).toCompletableFuture();
        assertTrue(authorized.await(2, TimeUnit.SECONDS), "Ledger authorization must be reached");

        long closeStart = System.nanoTime();
        coordinator.close();
        long closeMs = (System.nanoTime() - closeStart) / 1_000_000;
        assertTrue(closeMs < 800, "close must remain bounded while collaborator is blocked in auth->invoke gap, was " + closeMs + "ms");

        // Release the gap – collaborator will now be invoked even though close has already won
        blockInvoke.countDown();

        MutationResult r = f.get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertEquals("coordinator.rejected", r.diagnosticKey());
        assertEquals(0, coordinator.reservations().size(), "reservation must be released exactly once");
        assertTrue(ledgerInvoked.get(), "already-authorized collaborator may be invoked after close");
        assertFalse(economyInvoked.get(), "no later stage must run after close, even if authorized collaborator completed");
        assertFalse(domainInvoked.get(), "domain must not run after close");
        assertFalse(publishInvoked.get(), "publish must not run after close");
        assertFalse(finalizeInvoked.get(), "finalize must not run after close when Ledger was terminalized");
        // Stage recorder must not contain later stages
        assertFalse(rec.snapshot().contains("Economy"), "Economy must not be recorded after close");
        assertFalse(rec.snapshot().contains("DomainCommit"), "DomainCommit must not be recorded after close");

        // Late completion must not change terminal result
        MutationResult later = f.getNow(null);
        assertEquals(MutationOutcome.FAILED, later.outcome());
    }

    @Test
    void closeBeforeAuthorizationSuppressesCollaboratorInvocation() throws Exception {
        CountDownLatch firstLedgerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondLedgerInvoked = new AtomicBoolean(false);

        AtomicInteger ledgerCalls = new AtomicInteger(0);
        LedgerWriter ledger = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                int n = ledgerCalls.incrementAndGet();
                if (n == 1) {
                    firstLedgerEntered.countDown();
                    return CompletableFuture.runAsync(() -> {
                        try { releaseFirst.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    });
                } else {
                    secondLedgerInvoked.set(true);
                    return CompletableFuture.completedFuture(null);
                }
            }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) { return CompletableFuture.completedFuture(null); }
        };

        fresh(null, null, ledger, null, null, null);
        UUID world = UUID.randomUUID();
        CompletableFuture<MutationResult> first = coordinator.submit(claim(world, 30, 30, "Q1")).toCompletableFuture();
        assertTrue(firstLedgerEntered.await(2, TimeUnit.SECONDS), "first ledger must start");

        CompletableFuture<MutationResult> second = coordinator.submit(claim(world, 31, 31, "Q2")).toCompletableFuture();
        assertFalse(second.isDone(), "second must be queued while first blocks");

        // Close wins before second's Ledger authorization
        coordinator.close();

        MutationResult r2 = second.get(2, TimeUnit.SECONDS);
        assertEquals(MutationOutcome.FAILED, r2.outcome());
        assertEquals("coordinator.rejected", r2.diagnosticKey());
        assertFalse(secondLedgerInvoked.get(), "collaborator must NOT be invoked when close wins before authorization");

        releaseFirst.countDown();
        try { first.get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void cancelBeforeAuthorizationSuppressesStageAndReleasesReservation() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger ledgerCalls = new AtomicInteger(0);
        CountDownLatch secondLedgerEntered = new CountDownLatch(1);

        LedgerWriter ledger = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                int n = ledgerCalls.incrementAndGet();
                if (n == 1) {
                    firstEntered.countDown();
                    return CompletableFuture.runAsync(() -> {
                        try { releaseFirst.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    });
                } else {
                    secondLedgerEntered.countDown();
                    return CompletableFuture.completedFuture(null);
                }
            }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) { return CompletableFuture.completedFuture(null); }
        };

        fresh(null, null, ledger, null, null, null);
        UUID world = UUID.randomUUID();
        CompletableFuture<MutationResult> first = coordinator.submit(claim(world, 40, 40, "C1")).toCompletableFuture();
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

        CompletableFuture<MutationResult> second = coordinator.submit(claim(world, 41, 41, "C2")).toCompletableFuture();
        assertFalse(second.isDone(), "second must be queued");
        assertTrue(second.cancel(true), "queued future must be cancellable");

        // Second's reservation must be released immediately, only first remains
        assertEquals(1, coordinator.reservations().size());

        releaseFirst.countDown();
        MutationResult r1 = first.get(2, TimeUnit.SECONDS);
        assertNotNull(r1);
        assertFalse(secondLedgerEntered.await(400, TimeUnit.MILLISECONDS), "cancelled queued task must not invoke ledger");
        assertTrue(second.isCancelled() || second.isDone());
        assertEquals(0, coordinator.reservations().size());
    }

    @Test
    void cancelInAuthorizeToInvokeGapMayStillInvokeAuthorizedCollaboratorButNoLaterStage() throws Exception {
        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch blockInvoke = new CountDownLatch(1);
        CountDownLatch ledgerDone = new CountDownLatch(1);
        AtomicBoolean ledgerInvoked = new AtomicBoolean(false);
        AtomicBoolean economyInvoked = new AtomicBoolean(false);

        LedgerWriter ledger = new LedgerWriter() {
            @Override public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                ledgerInvoked.set(true);
                ledgerDone.countDown();
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletionStage<Void> finalizeState(UUID opId, MutationOutcome o) { return CompletableFuture.completedFuture(null); }
        };
        EconomyOperator economy = (req, op) -> {
            economyInvoked.set(true);
            return CompletableFuture.completedFuture(EconomyOperator.EconomyResult.ok());
        };

        fresh(null, null, ledger, economy, null, null);
        coordinator.testPostAuthorizeHookForTest = stage -> {
            if ("Ledger".equals(stage)) {
                authorized.countDown();
                try { blockInvoke.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };

        CompletableFuture<MutationResult> f = coordinator.submit(claim(UUID.randomUUID(), 50, 50, "CancelGap")).toCompletableFuture();
        assertTrue(authorized.await(2, TimeUnit.SECONDS), "authorization must be reached");

        assertTrue(f.cancel(true), "must be cancellable in auth->invoke gap");

        blockInvoke.countDown();

        assertTrue(ledgerDone.await(2, TimeUnit.SECONDS), "authorized ledger should be invoked after cancel releases the gap");
        assertTrue(f.isCancelled() || f.isDone());
        assertTrue(ledgerInvoked.get(), "authorized ledger may still be invoked after cancel");
        assertFalse(economyInvoked.get(), "no later stage must run after cancel");
        assertEquals(0, coordinator.reservations().size(), "reservation must be released after cancel in gap");
    }
}

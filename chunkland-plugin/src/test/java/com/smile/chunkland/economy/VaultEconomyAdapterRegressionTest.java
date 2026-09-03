package com.smile.chunkland.economy;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.mutation.MutationKind;
import com.smile.chunkland.api.mutation.MutationRequest;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.runtime.mutation.EconomyOperator;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the three blocking issues raised against the
 * Economy Adapter.
 *
 * <p>Each test exercises a single contract that the production adapter must
 * honour:
 * <ul>
 *   <li>Concurrent charge/refund calls sharing an operationId must observe
 *       a single bridge invocation; subsequent callers must receive the same
 *       outcome without re-invoking the bridge.</li>
 *   <li>Refund must compare the ledger entry's recorded
 *       {@code economyProviderId} against the bridge's current
 *       {@code providerId()}; a mismatch must surface as
 *       {@link RefundOutcome#UNKNOWN} and must NOT trigger a deposit.</li>
 *   <li>The idempotency key handed to the bridge must scope by operation kind
 *       ({@code charge:<uuid>} / {@code refund:<uuid>}) so that a charge and a
 *       refund sharing the same underlying UUID cannot collide in any global
 *       provider-side dedup.</li>
 * </ul>
 *
 * <p>Each test runs {@value #REPS} repetitions to flush out flakes. Synchronisation
 * uses only {@link CountDownLatch} / {@link CyclicBarrier} / a controllable bridge
 * — no {@code Thread.sleep}.
 */
class VaultEconomyAdapterRegressionTest {

    private static final Currency CUR = Currency.of("TEST", 2);
    private static final int REPS = 100;

    private ExecutorService pool;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
            pool = null;
        }
    }

    /**
     * Bridge that signals when it has been entered for the first time and
     * then blocks until released. Lets a test deterministically observe the
     * "two callers arrived during the same in-flight window" condition.
     */
    static final class ControllableBridge implements VaultBridge {
        final AtomicInteger withdrawCalls = new AtomicInteger();
        final AtomicInteger depositCalls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Response withdrawResponse = Response.ok();
        volatile Response depositResponse = Response.ok();
        volatile String providerId = "vault:default";
        volatile boolean available = true;

        @Override public boolean isAvailable() { return available; }
        @Override public String providerId() { return providerId; }
        @Override public Response withdraw(UUID playerId, double amount, UUID operationId) {
            int n = withdrawCalls.incrementAndGet();
            if (n == 1) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("bridge release timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            return withdrawResponse;
        }
        @Override public Response deposit(UUID playerId, double amount, UUID operationId) {
            int n = depositCalls.incrementAndGet();
            if (n == 1) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("bridge release timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            return depositResponse;
        }
    }

    /**
     * Lightweight bridge stub for tests that do not need blocking
     * synchronisation (e.g. provider-identity or scoped-key assertions).
     */
    static final class SimpleBridge implements VaultBridge {
        final AtomicInteger withdrawCalls = new AtomicInteger();
        final AtomicInteger depositCalls = new AtomicInteger();
        volatile String providerId = "vault:default";
        volatile Response withdrawResponse = Response.ok();
        volatile Response depositResponse = Response.ok();
        volatile boolean available = true;

        @Override public boolean isAvailable() { return available; }
        @Override public String providerId() { return providerId; }
        @Override public Response withdraw(UUID playerId, double amount, UUID operationId) {
            withdrawCalls.incrementAndGet();
            return withdrawResponse;
        }
        @Override public Response deposit(UUID playerId, double amount, UUID operationId) {
            depositCalls.incrementAndGet();
            return depositResponse;
        }
    }

    private static MutationRequest claimRequest(UUID actor) {
        return new MutationRequest(
                MutationKind.LAND_CREATE,
                null,
                OwnerRef.player(actor),
                Set.of(new com.smile.chunkland.api.land.ChunkKey(UUID.randomUUID(), 0, 0)),
                "Home");
    }

    private static LedgerEntry refundEntry(UUID opId, String providerId, UUID actor, long priceMinor) {
        return new LedgerEntry(opId, "CLAIM", "PAYMENT_PENDING", actor,
                UUID.randomUUID(), null, priceMinor, providerId, "tx-ref",
                "{}", 1, Instant.now(), Instant.now());
    }

    private ExecutorService newPool(int n) {
        pool = Executors.newFixedThreadPool(n);
        return pool;
    }

    private void assertConcurrentRefundSharesOutcome(
            VaultBridge.Response depositResponse, RefundOutcome expected, String description) throws Exception {
        for (int rep = 0; rep < REPS; rep++) {
            ControllableBridge bridge = new ControllableBridge();
            bridge.depositResponse = depositResponse;
            VaultEconomyAdapter adapter = new VaultEconomyAdapter(
                    bridge, CUR, req -> new Money(100, CUR));
            UUID op = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            LedgerEntry entry = refundEntry(op, bridge.providerId, actor, 10_000L);

            CyclicBarrier start = new CyclicBarrier(2);
            CountDownLatch callerReturnedFromRefund = new CountDownLatch(1);
            ExecutorService callers = newPool(2);
            CompletableFuture<RefundOutcome> first = CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    CompletableFuture<RefundOutcome> result = adapter.refund(entry).toCompletableFuture();
                    callerReturnedFromRefund.countDown();
                    return result.get(5, TimeUnit.SECONDS);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }, callers);
            CompletableFuture<RefundOutcome> second = CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    CompletableFuture<RefundOutcome> result = adapter.refund(entry).toCompletableFuture();
                    callerReturnedFromRefund.countDown();
                    return result.get(5, TimeUnit.SECONDS);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }, callers);

            try {
                assertTrue(bridge.entered.await(5, TimeUnit.SECONDS),
                        description + " rep " + rep + ": first deposit must block");
                assertTrue(callerReturnedFromRefund.await(5, TimeUnit.SECONDS),
                        description + " rep " + rep
                                + ": a concurrent caller must receive the published in-flight future");
                bridge.release.countDown();

                assertEquals(expected, first.get(5, TimeUnit.SECONDS),
                        description + " rep " + rep + ": first caller outcome");
                assertEquals(expected, second.get(5, TimeUnit.SECONDS),
                        description + " rep " + rep + ": second caller outcome");
                assertEquals(1, bridge.depositCalls.get(),
                        description + " rep " + rep + ": concurrent callers must invoke deposit once");
            } finally {
                bridge.release.countDown();
                callers.shutdownNow();
                callers.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    private static <T> T awaitStage(CompletionStage<T> stage) throws Exception {
        return stage.thenApply(value -> value).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void refundExposedViewCannotCancelOrForgeInternalOutcome() throws Exception {
        for (int rep = 0; rep < REPS; rep++) {
            ControllableBridge bridge = new ControllableBridge();
            VaultEconomyAdapter adapter = new VaultEconomyAdapter(
                    bridge, CUR, req -> new Money(100, CUR));
            UUID op = UUID.randomUUID();
            LedgerEntry entry = refundEntry(op, bridge.providerId, UUID.randomUUID(), 10_000L);
            CyclicBarrier start = new CyclicBarrier(2);
            CountDownLatch stageReturned = new CountDownLatch(1);
            AtomicReference<CompletionStage<RefundOutcome>> exposed = new AtomicReference<>();
            ExecutorService callers = newPool(2);

            CompletableFuture<RefundOutcome> first = CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    CompletionStage<RefundOutcome> result = adapter.refund(entry);
                    exposed.compareAndSet(null, result);
                    stageReturned.countDown();
                    return awaitStage(result);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }, callers);
            CompletableFuture<RefundOutcome> second = CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    CompletionStage<RefundOutcome> result = adapter.refund(entry);
                    exposed.compareAndSet(null, result);
                    stageReturned.countDown();
                    return awaitStage(result);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }, callers);

            try {
                assertTrue(bridge.entered.await(5, TimeUnit.SECONDS),
                        "rep " + rep + ": refund must enter the bridge");
                assertTrue(stageReturned.await(5, TimeUnit.SECONDS),
                        "rep " + rep + ": concurrent caller must receive a view");
                CompletionStage<RefundOutcome> view = exposed.get();

                assertTrue(view.toCompletableFuture().cancel(false));
                assertTrue(view.toCompletableFuture().complete(RefundOutcome.REFUNDED));
                assertTrue(view.toCompletableFuture().completeExceptionally(
                        new IllegalStateException("forged refund outcome")));

                bridge.release.countDown();
                assertEquals(RefundOutcome.REFUNDED, first.get(5, TimeUnit.SECONDS));
                assertEquals(RefundOutcome.REFUNDED, second.get(5, TimeUnit.SECONDS));
                assertEquals(1, bridge.depositCalls.get(),
                        "rep " + rep + ": external view controls must not duplicate deposit");
            } finally {
                bridge.release.countDown();
                callers.shutdownNow();
                callers.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void thirdRefundCallerBeforeBridgeReleaseSharesOriginalInFlightFuture() throws Exception {
        for (int rep = 0; rep < REPS; rep++) {
            ControllableBridge bridge = new ControllableBridge();
            bridge.depositResponse = VaultBridge.Response.failed("economy.transient");
            VaultEconomyAdapter adapter = new VaultEconomyAdapter(
                    bridge, CUR, req -> new Money(100, CUR));
            LedgerEntry entry = refundEntry(
                    UUID.randomUUID(), bridge.providerId, UUID.randomUUID(), 10_000L);
            CyclicBarrier start = new CyclicBarrier(2);
            CountDownLatch callerReturned = new CountDownLatch(1);
            AtomicReference<CompletionStage<RefundOutcome>> publishedView = new AtomicReference<>();
            ExecutorService callers = newPool(2);

            CompletableFuture<RefundOutcome> first = CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    CompletionStage<RefundOutcome> result = adapter.refund(entry);
                    publishedView.compareAndSet(null, result);
                    callerReturned.countDown();
                    return awaitStage(result);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }, callers);
            CompletableFuture<RefundOutcome> second = CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    CompletionStage<RefundOutcome> result = adapter.refund(entry);
                    publishedView.compareAndSet(null, result);
                    callerReturned.countDown();
                    return awaitStage(result);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }, callers);

            try {
                assertTrue(bridge.entered.await(5, TimeUnit.SECONDS),
                        "rep " + rep + ": first refund must block in bridge");
                assertTrue(callerReturned.await(5, TimeUnit.SECONDS),
                        "rep " + rep + ": second caller must receive the published view");
                CompletionStage<RefundOutcome> firstView = publishedView.get();

                assertTrue(firstView.toCompletableFuture().cancel(false));
                assertTrue(firstView.toCompletableFuture().complete(RefundOutcome.REFUNDED));
                assertTrue(firstView.toCompletableFuture().completeExceptionally(
                        new IllegalStateException("forged refund outcome")));

                CompletionStage<RefundOutcome> third = adapter.refund(entry);
                CompletableFuture<RefundOutcome> thirdCopy = third.toCompletableFuture();
                assertFalse(thirdCopy.isDone(),
                        "rep " + rep + ": third caller must share the still-running operation");
                assertEquals(1, bridge.depositCalls.get(),
                        "rep " + rep + ": retry before release must not start another deposit");

                bridge.release.countDown();
                assertEquals(RefundOutcome.FAILED, first.get(5, TimeUnit.SECONDS));
                assertEquals(RefundOutcome.FAILED, second.get(5, TimeUnit.SECONDS));
                assertEquals(RefundOutcome.FAILED, thirdCopy.get(5, TimeUnit.SECONDS));
                assertEquals(1, bridge.depositCalls.get(),
                        "rep " + rep + ": the in-flight window must deposit exactly once");
            } finally {
                bridge.release.countDown();
                callers.shutdownNow();
                callers.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void chargeExposedViewCannotCancelOrForgeInternalOutcome() throws Exception {
        for (int rep = 0; rep < REPS; rep++) {
            ControllableBridge bridge = new ControllableBridge();
            VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(
                    bridge, CUR, new Money(100, CUR));
            UUID op = UUID.randomUUID();
            MutationRequest request = claimRequest(UUID.randomUUID());
            CyclicBarrier start = new CyclicBarrier(2);
            CountDownLatch stageReturned = new CountDownLatch(1);
            AtomicReference<CompletionStage<EconomyOperator.EconomyResult>> exposed = new AtomicReference<>();
            ExecutorService callers = newPool(2);

            CompletableFuture<EconomyOperator.EconomyResult> first = CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    CompletionStage<EconomyOperator.EconomyResult> result = adapter.charge(request, op);
                    exposed.compareAndSet(null, result);
                    stageReturned.countDown();
                    return awaitStage(result);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }, callers);
            CompletableFuture<EconomyOperator.EconomyResult> second = CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    CompletionStage<EconomyOperator.EconomyResult> result = adapter.charge(request, op);
                    exposed.compareAndSet(null, result);
                    stageReturned.countDown();
                    return awaitStage(result);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }, callers);

            try {
                assertTrue(bridge.entered.await(5, TimeUnit.SECONDS),
                        "rep " + rep + ": charge must enter the bridge");
                assertTrue(stageReturned.await(5, TimeUnit.SECONDS),
                        "rep " + rep + ": concurrent caller must receive a view");
                CompletionStage<EconomyOperator.EconomyResult> view = exposed.get();

                assertTrue(view.toCompletableFuture().cancel(false));
                assertTrue(view.toCompletableFuture().complete(EconomyOperator.EconomyResult.failed("forged")));
                assertTrue(view.toCompletableFuture().completeExceptionally(
                        new IllegalStateException("forged charge outcome")));

                bridge.release.countDown();
                assertTrue(first.get(5, TimeUnit.SECONDS).success());
                assertTrue(second.get(5, TimeUnit.SECONDS).success());
                assertEquals(1, bridge.withdrawCalls.get(),
                        "rep " + rep + ": external view controls must not duplicate withdraw");
            } finally {
                bridge.release.countDown();
                callers.shutdownNow();
                callers.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void concurrentBlockedFailedRefundInvokesBridgeOnceAndSharesOutcome() throws Exception {
        assertConcurrentRefundSharesOutcome(
                VaultBridge.Response.failed("economy.transient"), RefundOutcome.FAILED, "FAILED");
    }

    @Test
    void concurrentBlockedUnknownRefundInvokesBridgeOnceAndSharesOutcome() throws Exception {
        assertConcurrentRefundSharesOutcome(null, RefundOutcome.UNKNOWN, "UNKNOWN");
    }

    /**
     * Two concurrent callers using the same operationId must observe exactly
     * one bridge withdraw. Repeated {@value #REPS} times to flush out races in
     * the cache lookup.
     */
    @Test
    void concurrentChargeSameOperationIdInvokesBridgeOnce() throws Exception {
        for (int rep = 0; rep < REPS; rep++) {
            ControllableBridge bridge = new ControllableBridge();
            VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(
                    bridge, CUR, new Money(100, CUR));
            UUID op = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            MutationRequest req = claimRequest(actor);

            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService callers = newPool(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CompletableFuture<EconomyOperator.EconomyResult> f1;
            CompletableFuture<EconomyOperator.EconomyResult> f2;

            try {
                f1 = CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                                return awaitStage(adapter.charge(req, op));
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        throw new RuntimeException(t);
                    }
                }, callers);
                f2 = CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        return awaitStage(adapter.charge(req, op));
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        throw new RuntimeException(t);
                    }
                }, callers);

                assertTrue(bridge.entered.await(5, TimeUnit.SECONDS),
                        "first bridge call must enter");
                bridge.release.countDown();

                EconomyOperator.EconomyResult r1 = f1.get(5, TimeUnit.SECONDS);
                EconomyOperator.EconomyResult r2 = f2.get(5, TimeUnit.SECONDS);
                assertNull(failure.get(), "no worker error expected: " + failure.get());

                assertEquals(1, bridge.withdrawCalls.get(),
                        "rep " + rep + ": concurrent same-op charge must hit bridge exactly once");
                assertEquals(r1.success(), r2.success(),
                        "rep " + rep + ": both callers must observe same outcome");
                assertEquals(r1.diagnosticKey(), r2.diagnosticKey(),
                        "rep " + rep + ": both callers must observe same diagnostic key");
                assertTrue(r1.success(), "rep " + rep + ": charge should succeed");
            } finally {
                bridge.release.countDown();
                callers.shutdownNow();
                callers.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Two concurrent callers issuing the same refund must observe exactly one
     * bridge deposit. Repeated {@value #REPS} times to flush out races.
     */
    @Test
    void concurrentRefundSameOperationInvokesBridgeOnce() throws Exception {
        for (int rep = 0; rep < REPS; rep++) {
            ControllableBridge bridge = new ControllableBridge();
            VaultEconomyAdapter adapter = new VaultEconomyAdapter(
                    bridge, CUR, req -> new Money(100, CUR));
            UUID op = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            LedgerEntry entry = refundEntry(op, bridge.providerId, actor, 10_000L);

            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService callers = newPool(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CompletableFuture<RefundOutcome> f1;
            CompletableFuture<RefundOutcome> f2;

            try {
                f1 = CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        return awaitStage(adapter.refund(entry));
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        throw new RuntimeException(t);
                    }
                }, callers);
                f2 = CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        return awaitStage(adapter.refund(entry));
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        throw new RuntimeException(t);
                    }
                }, callers);

                assertTrue(bridge.entered.await(5, TimeUnit.SECONDS),
                        "first bridge deposit must enter");
                bridge.release.countDown();

                RefundOutcome o1 = f1.get(5, TimeUnit.SECONDS);
                RefundOutcome o2 = f2.get(5, TimeUnit.SECONDS);
                assertNull(failure.get(), "no worker error expected: " + failure.get());

                assertEquals(1, bridge.depositCalls.get(),
                        "rep " + rep + ": concurrent same-op refund must hit bridge exactly once");
                assertEquals(o1, o2,
                        "rep " + rep + ": both callers must observe same outcome");
                assertEquals(RefundOutcome.REFUNDED, o1,
                        "rep " + rep + ": refund should succeed");
            } finally {
                bridge.release.countDown();
                callers.shutdownNow();
                callers.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Higher-fanout charge race: N callers share the same operationId.
     * Exactly one bridge withdraw must occur and all N callers must see the
     * same outcome.
     */
    @Test
    void concurrentChargeFanoutInvokesBridgeOnce() throws Exception {
        int n = 8;
        for (int rep = 0; rep < REPS; rep++) {
            ControllableBridge bridge = new ControllableBridge();
            VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(
                    bridge, CUR, new Money(100, CUR));
            UUID op = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            MutationRequest req = claimRequest(actor);

            CyclicBarrier barrier = new CyclicBarrier(n);
            ExecutorService callers = newPool(n);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicInteger okCount = new AtomicInteger();

            try {
                CompletableFuture<?>[] futures = new CompletableFuture[n];
                for (int i = 0; i < n; i++) {
                    futures[i] = CompletableFuture.runAsync(() -> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            EconomyOperator.EconomyResult r =
                                    awaitStage(adapter.charge(req, op));
                            if (r.success()) okCount.incrementAndGet();
                        } catch (Throwable t) {
                            failure.compareAndSet(null, t);
                            throw new RuntimeException(t);
                        }
                    }, callers);
                }

                assertTrue(bridge.entered.await(5, TimeUnit.SECONDS),
                        "first bridge call must enter");
                bridge.release.countDown();

                CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);
                assertNull(failure.get(), "no worker error expected: " + failure.get());

                assertEquals(1, bridge.withdrawCalls.get(),
                        "rep " + rep + ": fanout charge must hit bridge exactly once");
                assertEquals(n, okCount.get(),
                        "rep " + rep + ": all callers must see success");
                assertEquals(1, adapter.chargeCacheSizeForTest(),
                        "rep " + rep + ": cache must hold one entry for the op");
            } finally {
                bridge.release.countDown();
                callers.shutdownNow();
                callers.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Refund must not call the bridge when the recorded economy provider
     * differs from the current bridge providerId. Mismatch must surface as
     * {@link RefundOutcome#UNKNOWN} so callers can drive reconciliation
     * instead of a blind deposit into the wrong provider.
     *
     * <p>The non-success outcome must be evicted from the in-memory cache so
     * a transient mismatch does not permanently block retries. A subsequent
     * call re-evaluates the provider identity, and once the bridge recovers
     * the next call may succeed (see {@link
     * #refundProviderRecoversAfterUnknownAndRetries()}).
     */
    @Test
    void refundProviderMismatchReturnsUnknownWithoutDeposit() throws Exception {
        SimpleBridge bridge = new SimpleBridge();
        // Ledger records the original provider; bridge now reports a different one.
        bridge.providerId = "vault:current";
        VaultEconomyAdapter adapter = new VaultEconomyAdapter(
                bridge, CUR, req -> new Money(100, CUR));
        UUID op = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LedgerEntry entry = refundEntry(op, "vault:previous-plugin", actor, 10_000L);

        RefundOutcome outcome = awaitStage(adapter.refund(entry));

        assertEquals(RefundOutcome.UNKNOWN, outcome,
                "provider mismatch must surface as UNKNOWN, not FAILED or REFUNDED");
        assertEquals(0, bridge.depositCalls.get(),
                "provider mismatch must NOT trigger a deposit");
        assertEquals(0, bridge.withdrawCalls.get(),
                "refund must NOT touch withdraw");
        // Non-success outcomes are evicted atomically so a transient mismatch
        // does not permanently block retries when the bridge/provider recovers.
        assertEquals(0, adapter.refundCacheSizeForTest(),
                "non-success outcome must be evicted so retries can re-attempt");

        // While the mismatch persists, retries re-run performRefund (the
        // provider gate still rejects before deposit, so the bridge is not
        // asked to deposit) and return UNKNOWN again.
        RefundOutcome retry = awaitStage(adapter.refund(entry));
        assertEquals(RefundOutcome.UNKNOWN, retry);
        assertEquals(0, bridge.depositCalls.get(),
                "persistent mismatch must NOT trigger a deposit");
    }

    /**
     * After a provider-mismatch {@link RefundOutcome#UNKNOWN} is evicted from
     * the in-memory cache, a subsequent retry with the same raw operationId
     * must re-run the provider check. Once the bridge reports a matching
     * providerId, the retry must succeed and the bridge deposit must be
     * invoked exactly once for the recovery window.
     *
     * <p>With the previous permanently-cached behaviour, the second call
     * would short-circuit to the cached UNKNOWN, never reaching the bridge
     * and never depositing — leaving the recovery loop stuck even after the
     * provider restored. This test is the regression barrier for that
     * defect.
     */
    @Test
    void refundProviderRecoversAfterUnknownAndRetries() throws Exception {
        SimpleBridge bridge = new SimpleBridge();
        bridge.providerId = "vault:current";
        VaultEconomyAdapter adapter = new VaultEconomyAdapter(
                bridge, CUR, req -> new Money(100, CUR));
        UUID op = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LedgerEntry entry = refundEntry(op, "vault:previous-plugin", actor, 10_000L);

        // First attempt: bridge provider differs from the recorded provider,
        // so performRefund returns UNKNOWN without calling bridge.deposit.
        RefundOutcome first = awaitStage(adapter.refund(entry));
        assertEquals(RefundOutcome.UNKNOWN, first,
                "first attempt with mismatch must surface as UNKNOWN");
        assertEquals(0, bridge.depositCalls.get(),
                "mismatch must NOT trigger a deposit on first attempt");

        // Bridge recovers: provider identity now matches the recorded one.
        bridge.providerId = "vault:previous-plugin";

        // Second attempt with the same raw operationId must re-run the
        // provider check, see them aligned, and proceed to deposit.
        RefundOutcome second = awaitStage(adapter.refund(entry));
        assertEquals(RefundOutcome.REFUNDED, second,
                "retry after provider recovery must succeed");
        assertEquals(1, bridge.depositCalls.get(),
                "retry after recovery must invoke the bridge deposit exactly once");
    }

    /**
     * After a transient bridge {@link RefundOutcome#FAILED} is evicted, a
     * subsequent retry with the same raw operationId must re-run the bridge
     * call and may succeed once the bridge recovers. The first (failed)
     * deposit is preserved on the bridge counter because the bridge was
     * actually consulted, but the in-memory cache must not pin the failure.
     *
     * <p>With the previous permanently-cached behaviour, the second call
     * would short-circuit to the cached FAILED and never re-consult the
     * bridge, leaving operators without a path to recover even after the
     * transient condition cleared.
     */
    @Test
    void refundTransientFailedRecoversAndRetries() throws Exception {
        SimpleBridge bridge = new SimpleBridge();
        bridge.providerId = "vault:default";
        bridge.depositResponse = VaultBridge.Response.failed("economy.transient");
        VaultEconomyAdapter adapter = new VaultEconomyAdapter(
                bridge, CUR, req -> new Money(100, CUR));
        UUID op = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LedgerEntry entry = refundEntry(op, "vault:default", actor, 10_000L);

        // First attempt: bridge returns a transient failure.
            RefundOutcome first = awaitStage(adapter.refund(entry));
        assertEquals(RefundOutcome.FAILED, first,
                "transient bridge failure must surface as FAILED");
        assertEquals(1, bridge.depositCalls.get(),
                "first attempt must actually consult the bridge");
        assertEquals(0, adapter.refundCacheSizeForTest(),
                "FAILED outcome must be evicted so retries can re-attempt");

        // Bridge recovers.
        bridge.depositResponse = VaultBridge.Response.ok();

        // Second attempt with the same raw operationId must re-run the
        // bridge call and succeed. Total deposits: 2 (the failed attempt
        // and the successful retry) — the bridge kept its own record of
        // the first attempt, but our cache did not pin the failure.
            RefundOutcome second = awaitStage(adapter.refund(entry));
        assertEquals(RefundOutcome.REFUNDED, second,
                "retry after bridge recovery must succeed");
        assertEquals(2, bridge.depositCalls.get(),
                "retry must re-invoke the bridge deposit exactly once after recovery");
    }

    /**
     * After a non-success outcome has been evicted, the refundFutures cache
     * holds no entry for that operationId. A subsequent attempt — which
     * arrives in a fresh in-flight window — must atomically claim a single
     * in-flight slot so concurrent callers cannot both reach the bridge.
     *
     * <p>This test uses two adapters to exercise the two invariants
     * independently while keeping them in the same scenario:
     * <ol>
     *   <li>{@code adapter1} drives the eviction: the first call returns
     *       {@link RefundOutcome#FAILED} and the cache entry is dropped.</li>
     *   <li>{@code adapter2} is constructed with an empty cache (the same
     *       state {@code adapter1} ends up in after eviction); two
     *       concurrent callers must share a single bridge deposit thanks
     *       to the existing atomic in-flight dedup invariant.</li>
     * </ol>
     */
    @Test
    void refundEvictionAllowsConcurrentRetryToInvokeBridgeOnce() throws Exception {
        for (int rep = 0; rep < REPS; rep++) {
            // Phase 1: drive an eviction on adapter1. Bridge is non-blocking
            // here so the sequential first call returns promptly.
            SimpleBridge bridge1 = new SimpleBridge();
            bridge1.providerId = "vault:default";
            bridge1.depositResponse = VaultBridge.Response.failed("economy.transient");
            VaultEconomyAdapter adapter1 = new VaultEconomyAdapter(
                    bridge1, CUR, req -> new Money(100, CUR));
            UUID op = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            LedgerEntry entry = refundEntry(op, "vault:default", actor, 10_000L);

            RefundOutcome first = awaitStage(adapter1.refund(entry));
            assertEquals(RefundOutcome.FAILED, first,
                    "rep " + rep + ": first attempt must surface FAILED");
            assertEquals(1, bridge1.depositCalls.get(),
                    "rep " + rep + ": first attempt must consult the bridge");
            assertEquals(0, adapter1.refundCacheSizeForTest(),
                    "rep " + rep + ": failed outcome must be evicted from the cache");

            // Phase 2: a fresh adapter with an empty cache (matching the
            // post-eviction state) receives two concurrent callers. The
            // atomic in-flight dedup invariant must apply: exactly one
            // bridge deposit for both callers.
            ControllableBridge bridge2 = new ControllableBridge();
            VaultEconomyAdapter adapter2 = new VaultEconomyAdapter(
                    bridge2, CUR, req -> new Money(100, CUR));

            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService callers = newPool(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CompletableFuture<RefundOutcome> f1;
            CompletableFuture<RefundOutcome> f2;
            try {
                f1 = CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        return awaitStage(adapter2.refund(entry));
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        throw new RuntimeException(t);
                    }
                }, callers);
                f2 = CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        return awaitStage(adapter2.refund(entry));
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        throw new RuntimeException(t);
                    }
                }, callers);

                assertTrue(bridge2.entered.await(5, TimeUnit.SECONDS),
                        "rep " + rep + ": bridge must be entered by the first concurrent caller");
                bridge2.release.countDown();

                RefundOutcome o1 = f1.get(5, TimeUnit.SECONDS);
                RefundOutcome o2 = f2.get(5, TimeUnit.SECONDS);
                assertNull(failure.get(),
                        "rep " + rep + ": no worker error expected: " + failure.get());
                assertEquals(RefundOutcome.REFUNDED, o1,
                        "rep " + rep + ": first caller must see REFUNDED");
                assertEquals(RefundOutcome.REFUNDED, o2,
                        "rep " + rep + ": second caller must see REFUNDED");

                assertEquals(1, bridge2.depositCalls.get(),
                        "rep " + rep + ": concurrent callers in new window must hit bridge exactly once");
                assertEquals(1, adapter2.refundCacheSizeForTest(),
                        "rep " + rep + ": REFUNDED outcome must persist after the recovery window");
            } finally {
                bridge2.release.countDown();
                callers.shutdownNow();
                callers.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Successful REFUNDED outcomes must remain permanently cached for the
     * raw operationId so repeated compensation retries cannot trigger a
     * second deposit. Repeated {@value #REPS} times to catch any leakage
     * in the eviction path that would otherwise clobber the success cache.
     */
    @Test
    void successfulRefundRetainsIdempotencyOverManyRetries() throws Exception {
        for (int rep = 0; rep < REPS; rep++) {
            SimpleBridge bridge = new SimpleBridge();
            bridge.providerId = "vault:default";
            VaultEconomyAdapter adapter = new VaultEconomyAdapter(
                    bridge, CUR, req -> new Money(100, CUR));
            UUID op = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            LedgerEntry entry = refundEntry(op, "vault:default", actor, 10_000L);

            for (int i = 0; i < 25; i++) {
                RefundOutcome outcome = awaitStage(adapter.refund(entry));
                assertEquals(RefundOutcome.REFUNDED, outcome,
                        "rep " + rep + " retry " + i + ": must remain REFUNDED");
            }
            assertEquals(1, bridge.depositCalls.get(),
                    "rep " + rep + ": successful refund must not double-deposit across many retries");
            assertEquals(1, adapter.refundCacheSizeForTest(),
                    "rep " + rep + ": REFUNDED outcome must remain cached for the operationId");
        }
    }

    /**
     * Same provider identity between ledger and bridge must allow the refund
     * to proceed (sanity check that the provider-mismatch gate is not
     * over-eager).
     */
    @Test
    void refundProviderMatchProceeds() throws Exception {
        SimpleBridge bridge = new SimpleBridge();
        bridge.providerId = "vault:EssentialsX";
        VaultEconomyAdapter adapter = new VaultEconomyAdapter(
                bridge, CUR, req -> new Money(100, CUR));
        UUID op = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LedgerEntry entry = refundEntry(op, "vault:EssentialsX", actor, 10_000L);

        RefundOutcome outcome = awaitStage(adapter.refund(entry));

        assertEquals(RefundOutcome.REFUNDED, outcome,
                "matching provider must allow refund");
        assertEquals(1, bridge.depositCalls.get());
    }

    /**
     * The idempotency key handed to {@link VaultBridge#withdraw} must be
     * scoped under a charge namespace ({@code charge:<uuid>}) so that the
     * bridge (or any provider-side dedup layer) cannot confuse it with a
     * refund whose underlying UUID happens to match. The adapter derives the
     * scoped key as a UUID v3 over the prefix + raw UUID bytes.
     */
    @Test
    void chargePassesChargeScopedKeyToBridge() throws Exception {
        final AtomicReference<UUID> seen = new AtomicReference<>();
        VaultBridge spy = new VaultBridge() {
            @Override public boolean isAvailable() { return true; }
            @Override public String providerId() { return "vault:default"; }
            @Override public Response withdraw(UUID playerId, double amount, UUID operationId) {
                seen.set(operationId);
                return Response.ok();
            }
            @Override public Response deposit(UUID playerId, double amount, UUID operationId) {
                return Response.ok();
            }
        };
        VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(
                spy, CUR, new Money(100, CUR));
        UUID op = UUID.randomUUID();
        awaitStage(adapter.charge(claimRequest(UUID.randomUUID()), op));
        UUID expected = VaultEconomyAdapter.scopedId(VaultEconomyAdapter.CHARGE_KEY_PREFIX, op);
        assertEquals(expected, seen.get(),
                "withdraw must receive charge-scoped idempotency key");
        assertNotEquals(op, seen.get(),
                "scoped key must not equal raw operationId");
    }

    /**
     * The idempotency key handed to {@link VaultBridge#deposit} must be
     * scoped under a refund namespace ({@code refund:<uuid>}). Combined with
     * the charge scoping, this guarantees that a charge and a refund sharing
     * the same underlying UUID cannot collide in any global provider-side
     * dedup.
     */
    @Test
    void refundPassesRefundScopedKeyToBridge() throws Exception {
        final AtomicReference<UUID> seenDeposit = new AtomicReference<>();
        VaultBridge spy = new VaultBridge() {
            @Override public boolean isAvailable() { return true; }
            @Override public String providerId() { return "vault:default"; }
            @Override public Response withdraw(UUID playerId, double amount, UUID operationId) {
                return Response.ok();
            }
            @Override public Response deposit(UUID playerId, double amount, UUID operationId) {
                seenDeposit.set(operationId);
                return Response.ok();
            }
        };
        VaultEconomyAdapter adapter = new VaultEconomyAdapter(
                spy, CUR, req -> new Money(100, CUR));
        UUID op = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LedgerEntry entry = refundEntry(op, "vault:default", actor, 10_000L);
        awaitStage(adapter.refund(entry));
        // Bridge contract: scoped UUID built from "refund:" + the operation UUID.
        UUID expected = UUID.nameUUIDFromBytes(("refund:" + op).getBytes());
        assertEquals(expected, seenDeposit.get(),
                "deposit must receive refund-scoped idempotency key");
        // Sanity: scoped key must differ from raw operationId so they cannot
        // collide in any provider-side dedup that hashes the UUID bytes.
        assertNotEquals(op, seenDeposit.get(),
                "refund scoped key must not equal the raw operationId");
    }

    /**
     * Combined regression: charge and refund of the same underlying UUID
     * must produce two distinct scoped keys at the bridge boundary, and both
     * must invoke the bridge (charge first, refund after) without
     * cross-collapsing.
     */
    @Test
    void chargeAndRefundForSameRawUuidDoNotCollapseInBridge() throws Exception {
        UUID[] withdrawOps = new UUID[1];
        UUID[] depositOps = new UUID[1];
        VaultBridge spy = new VaultBridge() {
            @Override public boolean isAvailable() { return true; }
            @Override public String providerId() { return "vault:default"; }
            @Override public Response withdraw(UUID playerId, double amount, UUID operationId) {
                withdrawOps[0] = operationId;
                return Response.ok();
            }
            @Override public Response deposit(UUID playerId, double amount, UUID operationId) {
                depositOps[0] = operationId;
                return Response.ok();
            }
        };
        VaultEconomyAdapter adapter = new VaultEconomyAdapter(
                spy, CUR, req -> new Money(100, CUR));
        UUID op = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        awaitStage(adapter.charge(claimRequest(actor), op));
        LedgerEntry entry = refundEntry(op, "vault:default", actor, 10_000L);
        awaitStage(adapter.refund(entry));

        assertNotNull(withdrawOps[0], "charge must hit withdraw");
        assertNotNull(depositOps[0], "refund must hit deposit");
        assertNotEquals(withdrawOps[0], depositOps[0],
                "charge and refund must produce distinct scoped keys");
        assertNotEquals(op, withdrawOps[0], "withdraw key must be scoped, not raw");
        assertNotEquals(op, depositOps[0], "deposit key must be scoped, not raw");
    }

    /**
     * Higher-fanout cache state assertion: after a fanout race for a single
     * operationId, the adapter must hold exactly one cache entry.
     */
    @Test
    void fanoutFailureModeCacheState() throws Exception {
        int n = 4;
        final AtomicInteger calls = new AtomicInteger();
        VaultBridge failing = new VaultBridge() {
            @Override public boolean isAvailable() { return true; }
            @Override public String providerId() { return "vault:default"; }
            @Override public Response withdraw(UUID playerId, double amount, UUID operationId) {
                calls.incrementAndGet();
                return Response.failed("economy.insufficient");
            }
            @Override public Response deposit(UUID playerId, double amount, UUID operationId) {
                return Response.ok();
            }
        };
        VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(
                failing, CUR, new Money(100, CUR));
        UUID op = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        MutationRequest req = claimRequest(actor);

        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService callers = newPool(n);
        AtomicInteger failCount = new AtomicInteger();
        try {
            CompletableFuture<?>[] futures = new CompletableFuture[n];
            for (int i = 0; i < n; i++) {
                futures[i] = CompletableFuture.runAsync(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        EconomyOperator.EconomyResult r =
                                awaitStage(adapter.charge(req, op));
                        if (!r.success()) failCount.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, callers);
            }
            CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get(),
                    "fanout must invoke bridge exactly once even on failure");
            assertEquals(n, failCount.get(),
                    "all callers must observe the same failure");
            assertEquals(1, adapter.chargeCacheSizeForTest(),
                    "cache must hold exactly one entry (the failure)");
        } finally {
            callers.shutdownNow();
            callers.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}

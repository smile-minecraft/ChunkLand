package com.smile.chunkland.runtime.mutation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Deterministic regression tests for the logical reservation registry.
 *
 * <p>Each test exercises the multi-key atomicity / linearization requirement
 * that {@link MutationCoordinator} relies on. The registry must not expose
 * partial reservations: competing observers either see all keys held by one
 * owner or none of them, never a subset.
 */
class LogicalReservationRegistryTest {

    private static final String WORLD_PREFIX = "w:";

    private static Set<String> keys(UUID world, int x, int z) {
        return Set.of(WORLD_PREFIX + world + ":" + x, WORLD_PREFIX + world + ":" + z);
    }

    /**
     * Single-acquire path: a successful multi-key acquire must insert all keys
     * under the same owner before any other thread can observe the registry.
     */
    @Test
    void multiKeyAcquireStoresAllKeysUnderOwner() {
        LogicalReservationRegistry reg = new LogicalReservationRegistry();
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Set<String> ks = keys(world, 1, 2);

        assertTrue(reg.tryAcquire(ks, owner));
        assertEquals(2, reg.size());
        for (String k : ks) {
            assertTrue(reg.isReserved(k));
        }
    }

    /**
     * When one of the requested keys is already held by a different owner the
     * acquire must fail without mutating any other key. The competing owner
     * must still own its key after the failed attempt.
     */
    @Test
    void failedMultiKeyAcquireDoesNotMutateUnrelatedKeys() {
        LogicalReservationRegistry reg = new LogicalReservationRegistry();
        UUID world = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        Set<String> aliceKey = Set.of(WORLD_PREFIX + world + ":1");
        Set<String> bobKeys = keys(world, 1, 2);

        assertTrue(reg.tryAcquire(aliceKey, alice));
        assertFalse(reg.tryAcquire(bobKeys, bob),
                "bob's acquire must fail because alice owns k1");

        assertEquals(1, reg.size(), "failed multi-key acquire must not change registry size");
        assertTrue(reg.isReserved(WORLD_PREFIX + world + ":1"));
        assertFalse(reg.isReserved(WORLD_PREFIX + world + ":2"));

        // After rollback the registry must be exactly the same as before
        // bob's attempt: only alice's key, nothing else.
        reg.release(aliceKey, alice);
        assertEquals(0, reg.size(), "release returns registry to empty");
    }

    /**
     * Linearization invariant: many concurrent callers racing on the same
     * multi-key set must observe exactly one winner and a consistent
     * registry snapshot. No partial set may ever be observable: if any
     * thread sees a key as reserved, the registry size must reflect a
     * coherent multi-key acquisition by exactly one owner.
     */
    @Test
    void concurrentMultiKeyAcquireIsLinearisable() throws Exception {
        LogicalReservationRegistry reg = new LogicalReservationRegistry();
        int iterations = 80;
        int parallelism = 6;

        for (int iter = 0; iter < iterations; iter++) {
            reg.clearForTests();
            UUID world = UUID.randomUUID();
            Set<String> ks = keys(world, iter, iter + 1);

            CyclicBarrier barrier = new CyclicBarrier(parallelism);
            AtomicInteger wins = new AtomicInteger(0);
            AtomicInteger losses = new AtomicInteger(0);
            AtomicReference<UUID> winnerUuid = new AtomicReference<>();
            AtomicReference<Throwable> taskError = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(parallelism);
            Throwable primary = null;
            try {
                List<CompletableFuture<Void>> tasks = new ArrayList<>();
                for (int i = 0; i < parallelism; i++) {
                    UUID owner = UUID.randomUUID();
                    tasks.add(CompletableFuture.runAsync(() -> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            if (reg.tryAcquire(ks, owner)) {
                                wins.incrementAndGet();
                                winnerUuid.set(owner);
                            } else {
                                losses.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            taskError.compareAndSet(null, e);
                        } catch (BrokenBarrierException | TimeoutException e) {
                            taskError.compareAndSet(null, e);
                        } catch (Throwable e) {
                            taskError.compareAndSet(null, e);
                        }
                    }, pool));
                }
                CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                        .get(5, TimeUnit.SECONDS);
                if (taskError.get() != null) {
                    throw new AssertionError("iteration " + iter + ": barrier await failed", taskError.get());
                }
            } catch (Throwable t) {
                primary = t;
                throw t;
            } finally {
                // Deterministic pool cleanup: shutdown drains queued work, awaitTermination
                // proves every thread joined. Bounded fallback handles barrier/future failure
                // that could otherwise leak threads across iterations. Cleanup failure is
                // added as suppressed so the primary barrier or assertion failure is not lost,
                // and InterruptedException restores the interrupt status.
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
                    AssertionError ae = new AssertionError("iteration " + iter + ": pool must terminate before next iteration");
                    if (primary != null) {
                        primary.addSuppressed(ae);
                    } else if (interruptSuppressed != null) {
                        interruptSuppressed.addSuppressed(ae);
                        throw new AssertionError("iteration " + iter + ": pool must terminate before next iteration", interruptSuppressed);
                    } else {
                        throw ae;
                    }
                }
            }

            assertEquals(1, wins.get(),
                    "iteration " + iter + ": exactly one acquirer must win");
            assertEquals(parallelism - 1, losses.get(),
                    "iteration " + iter + ": all other acquirers must lose");
            assertEquals(ks.size(), reg.size(),
                    "iteration " + iter + ": registry must contain all keys (no partial)");
            for (String k : ks) {
                assertTrue(reg.isReserved(k),
                        "iteration " + iter + ": key " + k + " must be reserved");
            }

            // Release using the actual winner so the registry returns to 0.
            UUID winner = winnerUuid.get();
            assertNotNull(winner, "iteration " + iter + ": winner UUID must be captured");
            reg.release(ks, winner);
            assertEquals(0, reg.size(),
                    "iteration " + iter + ": registry must be empty after release");
        }
    }

    /**
     * Observation during acquire/release must be consistent. An observer
     * thread continuously polling {@code size()} and {@code isReserved()}
     * must never see a partially-acquired multi-key set: whenever any key
     * from the target set is reserved, all keys from that set must be
     * reserved by the same linearisation point. We check the invariant via
     * the atomic {@code size()} read, which under the lock returns the
     * number of currently-reserved keys; for a 2-key target set a partial
     * state would surface as size=1, which never appears under the
     * single-lock linearisation.
     */
    @Test
    void concurrentObserverSeesConsistentMultiKeyState() throws Exception {
        LogicalReservationRegistry reg = new LogicalReservationRegistry();
        UUID world = UUID.randomUUID();
        Set<String> ks = keys(world, 99, 100);
        int n = ks.size();

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean invariantViolated = new AtomicBoolean(false);
        AtomicReference<Throwable> barrierError = new AtomicReference<>();

        Thread observer = new Thread(() -> {
            while (!stop.get()) {
                int observed = reg.size();
                // The registry's only valid sizes are 0 (no one holds any
                // of the target keys) or n (one owner holds all of them).
                // A partial observation (1 < observed < n) would mean a
                // competing observer saw a partial reservation set, which
                // the linearisation lock must prevent.
                if (observed != 0 && observed != n) {
                    invariantViolated.set(true);
                    return;
                }
            }
        }, "reservation-observer");
        // Daemon: never block JVM shutdown even if the test fails between
        // observer.start() and the explicit join below. Bounded join below is
        // the real guarantee; daemon is only a safety net.
        observer.setDaemon(true);
        observer.start();

        Throwable primary = null;
        try {
            // Deterministic rendezvous: each acquire/release cycle
            // synchronizes with the observer via two barriers so the
            // observer is guaranteed to sample while the holder holds,
            // without using Thread.sleep or busy-poll. Barrier failures are
            // recorded in barrierError so they become test failures instead
            // of silent interrupts.
            for (int i = 0; i < 200; i++) {
                java.util.concurrent.CyclicBarrier b1 = new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.CyclicBarrier b2 = new java.util.concurrent.CyclicBarrier(2);
                AtomicBoolean holderAcquired = new AtomicBoolean(false);
                UUID owner = UUID.randomUUID();
                Thread holder = new Thread(() -> {
                    boolean acquired = reg.tryAcquire(ks, owner);
                    holderAcquired.set(acquired);
                    try {
                        b1.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        barrierError.compareAndSet(null, e);
                        if (acquired) reg.release(ks, owner);
                        return;
                    } catch (BrokenBarrierException | TimeoutException e) {
                        barrierError.compareAndSet(null, e);
                        if (acquired) reg.release(ks, owner);
                        return;
                    }
                    try {
                        b2.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        barrierError.compareAndSet(null, e);
                    } catch (BrokenBarrierException | TimeoutException e) {
                        barrierError.compareAndSet(null, e);
                    } finally {
                        if (acquired) reg.release(ks, owner);
                    }
                }, "holder-" + i);
                Thread check = new Thread(() -> {
                    try {
                        b1.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        barrierError.compareAndSet(null, e);
                        return;
                    } catch (BrokenBarrierException | TimeoutException e) {
                        barrierError.compareAndSet(null, e);
                        return;
                    }
                    int observed = reg.size();
                    if (observed != 0 && observed != n) invariantViolated.set(true);
                    try {
                        b2.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        barrierError.compareAndSet(null, e);
                    } catch (BrokenBarrierException | TimeoutException e) {
                        barrierError.compareAndSet(null, e);
                    }
                }, "check-" + i);
                // Daemon is JVM safety net only, not a substitute for bounded join assertion.
                holder.setDaemon(true);
                check.setDaemon(true);
                try {
                    holder.start();
                    check.start();
                    holder.join(5_000);
                    check.join(5_000);
                } finally {
                    boolean holderAlive = holder.isAlive();
                    boolean checkAlive = check.isAlive();
                    if (holderAlive || checkAlive) {
                        b1.reset();
                        b2.reset();
                        if (holderAlive) holder.interrupt();
                        if (checkAlive) check.interrupt();
                        try { holder.join(1_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); if (primary != null) primary.addSuppressed(e); }
                        try { check.join(1_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); if (primary != null) primary.addSuppressed(e); }
                    }
                }
                assertTrue(holderAcquired.get(), "iteration " + i + ": holder must acquire");
                assertFalse(holder.isAlive(), "iteration " + i + ": holder must terminate before next iteration");
                assertFalse(check.isAlive(), "iteration " + i + ": check must terminate before next iteration");
                if (barrierError.get() != null) {
                    throw new AssertionError("iteration " + i + ": barrier failed", barrierError.get());
                }
                if (invariantViolated.get()) break;
            }
            if (barrierError.get() != null) {
                throw new AssertionError("barrier failed during stress run", barrierError.get());
            }
            // Holder/check loop finished; invariant will be checked after observer shutdown.
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            // Bounded observer cleanup: always stop even if holder assertions failed.
            // Observer termination is bounded and failures are added as suppressed to primary
            // so the original iteration failure is not lost. InterruptedException restores interrupt.
            stop.set(true);
            boolean observerTerminated = false;
            Throwable interruptSuppressed = null;
            try {
                observer.join(2_000);
                observerTerminated = !observer.isAlive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interruptSuppressed = e;
            }
            if (interruptSuppressed != null && primary != null) {
                primary.addSuppressed(interruptSuppressed);
            }
            if (!observerTerminated) {
                observer.interrupt();
                try {
                    observer.join(1_000);
                    observerTerminated = !observer.isAlive();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (interruptSuppressed == null) interruptSuppressed = e;
                    else interruptSuppressed.addSuppressed(e);
                    if (primary != null) primary.addSuppressed(e);
                }
            }
            if (!observerTerminated) {
                AssertionError ae = new AssertionError("observer must terminate cleanly after stop signal");
                if (primary != null) {
                    primary.addSuppressed(ae);
                } else if (interruptSuppressed != null) {
                    interruptSuppressed.addSuppressed(ae);
                    throw new AssertionError("observer must terminate cleanly after stop signal", interruptSuppressed);
                } else {
                    throw ae;
                }
            } else if (interruptSuppressed != null && primary == null) {
                // Interrupted during join but observer did terminate; preserve interrupt without failing
                // (primary == null means no other failure, so just keep interrupt status)
            }
        }

        if (barrierError.get() != null) {
            throw new AssertionError("barrier failed during stress run", barrierError.get());
        }
        assertFalse(invariantViolated.get(),
                "observer must never see a partial multi-key reservation size");
        assertEquals(0, reg.size(),
                "all reservations must have been released after the stress run");
        assertFalse(observer.isAlive(), "observer must be terminated after cleanup");
    }
}

package com.smile.chunkland.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.index.WorldChunkIndex;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Concurrency proof: readers never observe partial publish.
 */
class RuntimeIndexConcurrencyTest {

    private static LandSnapshot land(UUID world, LandId id, int x, int z) {
        return new LandSnapshot(id, "N", "n", OwnerRef.player(UUID.randomUUID()), world, Set.of(new ChunkKey(world, x, z)), List.of(), 0, 0, Instant.now(), Instant.now());
    }

    @Test
    void readersOnlySeeOldOrNewNotPartial() throws Exception {
        LandRegistryStore store = new LandRegistryStore();
        UUID w = UUID.randomUUID();
        LandId oldId = new LandId(UUID.randomUUID());
        LandId newId = new LandId(UUID.randomUUID());
        LandRegistry oldReg = LandRegistry.from(List.of(land(w, oldId, 0, 0), land(w, new LandId(UUID.randomUUID()), 1, 1)));
        LandRegistry newReg = LandRegistry.from(List.of(land(w, newId, 10, 10), land(w, new LandId(UUID.randomUUID()), 11, 11)));
        store.publish(oldReg);

        int readers = 12;
        int iters = 5000;
        // Two-phase rendezvous (no sleep): all readers pass the first barrier
        // together, then each reader takes one snapshot of `old` and signals
        // through `eachObservedOld`. The publisher waits on that latch before
        // flipping the store, so every reader is guaranteed to have observed
        // `old` at least once before the new publication can land.
        CyclicBarrier rendezvous = new CyclicBarrier(readers + 1);
        CountDownLatch eachObservedOld = new CountDownLatch(readers);
        AtomicReference<Throwable> err = new AtomicReference<>();

        ExecutorService readerPool = Executors.newFixedThreadPool(readers);
        List<CompletableFuture<Void>> futures = new ArrayList<>(readers);
        Throwable primary = null;
        try {
            for (int i = 0; i < readers; i++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        rendezvous.await(5, TimeUnit.SECONDS);
                        // Single deterministic pre-publish observation:
                        // every reader must see the complete old snapshot
                        // before publish is allowed to proceed.
                        LandRegistry pre = store.snapshot();
                        boolean preOld = pre.findLandId(w, 0, 0) != null;
                        boolean preNew = pre.findLandId(w, 10, 10) != null;
                        if (!preOld || preNew || pre.lands().size() != 2) {
                            err.compareAndSet(null, new AssertionError("pre-publish read must see complete old"));
                        }
                        eachObservedOld.countDown();
                        for (int k = 0; k < iters; k++) {
                            LandRegistry snap = store.snapshot(); // single volatile read
                            boolean hasOld = snap.findLandId(w, 0, 0) != null;
                            boolean hasNew = snap.findLandId(w, 10, 10) != null;
                            // must be exclusive
                            if (hasOld && hasNew) err.compareAndSet(null, new AssertionError("saw both old and new -> partial"));
                            if (!hasOld && !hasNew) err.compareAndSet(null, new AssertionError("saw neither old nor new -> partial"));
                            // also check size consistency: old has 2, new has 2, but within snap size must be 2
                            if (snap.lands().size() != 2) err.compareAndSet(null, new AssertionError("inconsistent size " + snap.lands().size()));
                            // packed correctness under concurrency
                            assertNull(snap.findLandId(UUID.randomUUID(), 0, 0));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        err.compareAndSet(null, e);
                    } catch (BrokenBarrierException | TimeoutException e) {
                        err.compareAndSet(null, e);
                    } catch (Throwable e) {
                        err.compareAndSet(null, e);
                    }
                }, readerPool));
            }
            // Synchronise with readers, then publish only after every reader
            // has demonstrated a clean pre-publish read of `old`.
            // Barrier and latch awaits are bounded; failures become AssertionError
            // with the original cause preserved so they are not swallowed.
            try {
                rendezvous.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("rendezvous interrupted", e);
            } catch (BrokenBarrierException | TimeoutException e) {
                throw new AssertionError("rendezvous failed", e);
            }
            try {
                if (!eachObservedOld.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("eachObservedOld latch timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("eachObservedOld interrupted", e);
            }
            store.publish(newReg);
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
            if (err.get() != null) throw new AssertionError(err.get());
            assertNotNull(store.snapshot().findLandId(w, 10, 10));
            assertNull(store.snapshot().findLandId(w, 0, 0));
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            // Bounded cleanup: shutdown with timeout, fallback to shutdownNow, and
            // add termination or interruption failures as suppressed to primary so
            // the original linearizability failure is not hidden.
            boolean terminated = false;
            Throwable interruptSuppressed = null;
            try {
                readerPool.shutdown();
                terminated = readerPool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interruptSuppressed = e;
                readerPool.shutdownNow();
            }
            if (!terminated) {
                readerPool.shutdownNow();
                try {
                    terminated = readerPool.awaitTermination(2, TimeUnit.SECONDS);
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
                AssertionError ae = new AssertionError("reader pool must terminate before test exits");
                if (primary != null) {
                    primary.addSuppressed(ae);
                } else if (interruptSuppressed != null) {
                    interruptSuppressed.addSuppressed(ae);
                    throw new AssertionError("reader pool must terminate before test exits", interruptSuppressed);
                } else {
                    throw ae;
                }
            }
        }
    }

    @Test
    void emptyPublishIsVisibleAtomically() throws Exception {
        LandRegistryStore store = new LandRegistryStore();
        UUID w = UUID.randomUUID();
        LandRegistry with = LandRegistry.from(List.of(land(w, new LandId(UUID.randomUUID()), 5, 5)));
        LandRegistry empty = LandRegistry.empty();
        store.publish(with);
        // Two-phase rendezvous (no sleep): reader must observe the complete
        // `with` snapshot at least once before the publisher is allowed to
        // flip to `empty`.
        CyclicBarrier rendezvous = new CyclicBarrier(2);
        CountDownLatch readerObservedWith = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        ExecutorService readerPool = Executors.newSingleThreadExecutor();
        Throwable primary = null;
        try {
            CompletableFuture<Void> readerFuture = CompletableFuture.runAsync(() -> {
                try {
                    rendezvous.await(5, TimeUnit.SECONDS);
                    // Pre-publish observation: must be complete `with`.
                    LandRegistry pre = store.snapshot();
                    boolean preHas = pre.findLandId(w, 5, 5) != null;
                    if (!preHas || pre.isEmpty() || pre.lands().size() != 1) {
                        err.compareAndSet(null, new AssertionError("pre-publish read must see complete with"));
                    }
                    readerObservedWith.countDown();
                    for (int i = 0; i < 2000; i++) {
                        LandRegistry snap = store.snapshot();
                        boolean has = snap.findLandId(w, 5, 5) != null;
                        // snap is either with or empty, never half; EITHER inconsistency
                        // is a violation (logical OR: each witness alone is enough to fail).
                        if (snap.isEmpty() != !has || snap.lands().size() != (has?1:0)) {
                            err.compareAndSet(null, new AssertionError("inconsistent empty state"));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    err.compareAndSet(null, e);
                } catch (BrokenBarrierException | TimeoutException e) {
                    err.compareAndSet(null, e);
                } catch (Throwable e) {
                    err.compareAndSet(null, e);
                }
            }, readerPool);
            try {
                rendezvous.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("rendezvous interrupted", e);
            } catch (BrokenBarrierException | TimeoutException e) {
                throw new AssertionError("rendezvous failed", e);
            }
            try {
                if (!readerObservedWith.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("readerObservedWith latch timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("readerObservedWith interrupted", e);
            }
            store.publish(empty);
            readerFuture.get(30, TimeUnit.SECONDS);
            if (err.get() != null) throw new AssertionError(err.get());
            assertTrue(store.snapshot().isEmpty());
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            boolean terminated = false;
            Throwable interruptSuppressed = null;
            try {
                readerPool.shutdown();
                terminated = readerPool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interruptSuppressed = e;
                readerPool.shutdownNow();
            }
            if (!terminated) {
                readerPool.shutdownNow();
                try {
                    terminated = readerPool.awaitTermination(2, TimeUnit.SECONDS);
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
                AssertionError ae = new AssertionError("reader pool must terminate before test exits");
                if (primary != null) {
                    primary.addSuppressed(ae);
                } else if (interruptSuppressed != null) {
                    interruptSuppressed.addSuppressed(ae);
                    throw new AssertionError("reader pool must terminate before test exits", interruptSuppressed);
                } else {
                    throw ae;
                }
            }
        }
    }
}

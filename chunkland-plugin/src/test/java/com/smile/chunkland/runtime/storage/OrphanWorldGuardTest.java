package com.smile.chunkland.runtime.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Red contract for the orphan world-catalog guard.
 *
 * <p>The guard is the atomic safety boundary between world load/unload and
 * the irreversible purge transaction: catalog updates take the write lock and
 * bump the generation, while a purge holds the read lock and aborts unless
 * the generation it confirmed with is still current. Generation zero means
 * no verified catalog exists yet and must fail every orphan verb closed.
 */
class OrphanWorldGuardTest {

    @Test
    void freshGuardHasAnUnverifiedEmptySnapshot() {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        WorldCatalogSnapshot snapshot = guard.snapshot();
        assertEquals(0, snapshot.generation());
        assertTrue(snapshot.loadedWorlds().isEmpty());
        assertFalse(snapshot.isVerified());
    }

    @Test
    void publishAssignsIncreasingGenerations() {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertEquals(1, guard.publish(Set.of(first)));
        WorldCatalogSnapshot one = guard.snapshot();
        assertEquals(1, one.generation());
        assertEquals(Set.of(first), one.loadedWorlds());
        assertTrue(one.isVerified());
        assertTrue(one.isLoaded(first));
        assertFalse(one.isLoaded(second));

        assertEquals(2, guard.publish(Set.of(first, second)));
        WorldCatalogSnapshot two = guard.snapshot();
        assertEquals(2, two.generation());
        assertEquals(Set.of(first, second), two.loadedWorlds());
    }

    @Test
    void publishRejectsNull() {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        assertThrows(NullPointerException.class, () -> guard.publish(null));
        assertFalse(guard.snapshot().isVerified());
    }

    @Test
    void invalidateReturnsToUnverified() {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        guard.publish(Set.of(UUID.randomUUID()));
        assertTrue(guard.snapshot().isVerified());
        guard.invalidate();
        WorldCatalogSnapshot snapshot = guard.snapshot();
        assertEquals(0, snapshot.generation());
        assertFalse(snapshot.isVerified());
    }

    @Test
    void snapshotsAreImmutableAndDetached() {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        UUID first = UUID.randomUUID();
        guard.publish(Set.of(first));
        WorldCatalogSnapshot taken = guard.snapshot();
        guard.publish(Set.of(UUID.randomUUID()));
        assertEquals(Set.of(first), taken.loadedWorlds());
        assertEquals(1, taken.generation());
        assertThrows(UnsupportedOperationException.class,
                () -> taken.loadedWorlds().add(UUID.randomUUID()));
    }

    @Test
    void heldReadLockExcludesOtherThreadsWriteLock() throws Exception {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        guard.readLock().lock();
        java.util.concurrent.atomic.AtomicBoolean otherAcquired = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.CountDownLatch attempted = new java.util.concurrent.CountDownLatch(1);
        Thread other = new Thread(() -> {
            otherAcquired.set(guard.writeLock().tryLock());
            if (otherAcquired.get()) {
                guard.writeLock().unlock();
            }
            attempted.countDown();
        });
        try {
            other.start();
            assertTrue(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(otherAcquired.get(),
                    "a purge holding the read lock must block catalog updates");
            other.join(5000);
        } finally {
            guard.readLock().unlock();
        }
        assertTrue(guard.writeLock().tryLock());
        guard.writeLock().unlock();
    }

    @Test
    void heldWriteLockExcludesOtherThreadsReadLock() throws Exception {
        // A thread already holding the write lock may re-enter the read
        // lock itself (downgrade), so exclusion is proven cross-thread:
        // the latch guarantees the other thread attempts while this
        // thread still holds the write lock.
        OrphanWorldGuard guard = new OrphanWorldGuard();
        guard.writeLock().lock();
        java.util.concurrent.atomic.AtomicBoolean otherAcquired = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.CountDownLatch attempted = new java.util.concurrent.CountDownLatch(1);
        Thread other = new Thread(() -> {
            otherAcquired.set(guard.readLock().tryLock());
            if (otherAcquired.get()) {
                guard.readLock().unlock();
            }
            attempted.countDown();
        });
        try {
            other.start();
            assertTrue(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(otherAcquired.get(),
                    "a catalog update must block purge transactions");
            other.join(5000);
        } finally {
            guard.writeLock().unlock();
        }
        assertTrue(guard.readLock().tryLock());
        guard.readLock().unlock();
    }

    @Test
    void currentUnderReadLockRequiresTheReadHold() {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        guard.publish(Set.of(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, guard::currentUnderReadLock);
        guard.readLock().lock();
        try {
            WorldCatalogSnapshot current = guard.currentUnderReadLock();
            assertEquals(1, current.generation());
        } finally {
            guard.readLock().unlock();
        }
    }

    @Test
    void snapshotNeverReturnsNull() {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        assertTrue(guard.snapshot() != null);
        guard.publish(Set.of());
        assertTrue(guard.snapshot() != null);
    }
}

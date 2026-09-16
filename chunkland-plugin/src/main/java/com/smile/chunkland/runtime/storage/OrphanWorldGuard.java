package com.smile.chunkland.runtime.storage;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Atomic safety boundary between world load/unload and the irreversible
 * orphan purge transaction.
 *
 * <p>Catalog updates ({@link #publish}, {@link #invalidate}, driven by the
 * world load/unload listener on the server thread) take the write lock and
 * move the generation. A purge transaction takes the read lock for its whole
 * SQL boundary and aborts unless the generation it confirmed with is still
 * current and the target world is still absent — so a world that reloads
 * between the confirmation and the SQL can never be deleted, and a catalog
 * update can never interleave with the delete. Snapshots are immutable and
 * detached: readers take one under a brief read hold and then work without
 * further synchronization.
 *
 * <p>This class never touches Bukkit or SQL; it only guards memory. The lock
 * is a leaf — no other lock is ever acquired while holding it — so taking
 * the read lock on the persistence thread and the write lock on the server
 * thread cannot deadlock.
 */
public final class OrphanWorldGuard {

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private long generation;
    private Set<UUID> loadedWorlds = Set.of();

    /**
     * Takes a detached snapshot under a brief read hold.
     * Generation zero means no verified catalog exists.
     */
    public WorldCatalogSnapshot snapshot() {
        lock.readLock().lock();
        try {
            return new WorldCatalogSnapshot(generation, loadedWorlds);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Publishes a freshly copied world list and moves to the next
     * generation. Returns the assigned generation.
     */
    public long publish(Set<UUID> worlds) {
        Objects.requireNonNull(worlds, "worlds");
        Set<UUID> copy = Set.copyOf(worlds);
        lock.writeLock().lock();
        try {
            loadedWorlds = copy;
            return ++generation;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Drops back to the unverified state after a failed catalog read. Every
     * orphan verb fails closed until the next successful publish.
     */
    public void invalidate() {
        lock.writeLock().lock();
        try {
            generation = 0;
            loadedWorlds = Set.of();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Held by a purge transaction for its whole SQL boundary. */
    public Lock readLock() {
        return lock.readLock();
    }

    /** Held by catalog updates. */
    public Lock writeLock() {
        return lock.writeLock();
    }

    /**
     * Reads the current state; the caller must already hold the read lock
     * (as the purge transaction does), otherwise fail-closed.
     */
    public WorldCatalogSnapshot currentUnderReadLock() {
        if (((ReentrantReadWriteLock) lock).getReadHoldCount() == 0) {
            throw new IllegalStateException("orphan guard read lock is not held");
        }
        return new WorldCatalogSnapshot(generation, loadedWorlds);
    }
}

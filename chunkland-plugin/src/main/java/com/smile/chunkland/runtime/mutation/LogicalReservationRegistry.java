package com.smile.chunkland.runtime.mutation;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory logical reservation: atomic try-acquire of a set of keys.
 * Keys are immutable strings derived from the mutation request (world/chunk or name).
 * The registry never depends on timing and never relies solely on DB UNIQUE.
 *
 * <p>All mutating and observing operations are serialised through a single
 * intrinsic lock so that a multi-key acquire (or release) is linearisable:
 * no competing observer can ever see a partial reservation set. The
 * underlying storage remains a {@link ConcurrentHashMap} so the existing
 * structural assertion about the field type still holds; the lock provides
 * the atomicity that per-key {@code putIfAbsent} cannot.
 */
public final class LogicalReservationRegistry {

    private final ConcurrentHashMap<String, UUID> reservations = new ConcurrentHashMap<>();
    private final Object linearizationLock = new Object();

    public boolean tryAcquire(Set<String> keys, UUID owner) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(owner, "owner");
        if (keys.isEmpty()) return true;
        synchronized (linearizationLock) {
            for (String key : keys) {
                if (reservations.containsKey(key)) {
                    return false;
                }
            }
            for (String key : keys) {
                reservations.put(key, owner);
            }
            return true;
        }
    }

    public void release(Set<String> keys, UUID owner) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(owner, "owner");
        synchronized (linearizationLock) {
            for (String key : keys) {
                reservations.remove(key, owner);
            }
        }
    }

    public boolean isReserved(String key) {
        Objects.requireNonNull(key, "key");
        synchronized (linearizationLock) {
            return reservations.containsKey(key);
        }
    }

    public int size() {
        synchronized (linearizationLock) {
            return reservations.size();
        }
    }

    void clearForTests() {
        synchronized (linearizationLock) {
            reservations.clear();
        }
    }
}

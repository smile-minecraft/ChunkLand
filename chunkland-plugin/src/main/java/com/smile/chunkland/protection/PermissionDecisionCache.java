package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded, memory-only reuse for protection decisions.
 *
 * <p>Each {@link Key} names one decision completely: the actor, the land,
 * the covering subland (or {@code null} on the land-only paths), the action,
 * and the four epochs that move whenever the underlying content moves
 * (global policy, world policy, land policy revision, owner ACL epoch),
 * plus the structure revision, the world, and the owner context the
 * resolver observed. A hit requires full key equality, so an entry from
 * one actor, land, subland, action, route or generation can never answer
 * for another.
 *
 * <p>Eviction is first-in-first-out over a lock-free queue: only the
 * insertion of a previously absent key enqueues, so the queue never holds
 * duplicates and never outgrows the map plus in-flight inserts. While the
 * map exceeds its budget the oldest queued keys are dropped until it fits
 * again; over-eviction under contention only costs a later recompute, never
 * a stale answer. No executor, no thread, no blocking call exists here:
 * every method is a bounded sequence of map and queue operations.
 *
 * <p>Lifecycle is linearizable by generation: the map, the queue and the
 * budget always move together inside one immutable generation object, and
 * {@code get}, {@code put}, {@code clear} and {@code setMaxEntries} each
 * act on a single generation they captured. Clearing or changing the
 * budget publishes a fresh empty generation, so a {@code put} that started
 * before the switch still lands in the retired generation where no later
 * {@code get}, {@code size} or re-enable can ever observe it; the retired
 * maps and queues are left for garbage collection, never scanned or moved.
 *
 * <p>A budget of zero disables the cache: reads miss and writes are
 * dropped. Changing the budget starts a new empty generation instead of
 * trimming the old one, so entries from the previous budget can never
 * outlive the configuration that produced them.
 */
public final class PermissionDecisionCache {

    /** Default budget when the config carries no explicit value. */
    public static final int DEFAULT_MAX_ENTRIES = 4096;

    /** Hard ceiling so a misconfigured file cannot ask for an unbounded map. */
    public static final int MAX_MAX_ENTRIES = 1_000_000;

    /**
     * Complete identity of one cached decision. The subland is {@code null}
     * on land-only paths; every other component is required. Epochs come
     * from immutable views read on the decision path, never from storage.
     */
    public record Key(
            UUID actor,
            LandId landId,
            SubLandId sublandId,
            UUID worldId,
            ProtectionActionType action,
            long globalPolicyEpoch,
            long worldPolicyEpoch,
            long landPolicyRevision,
            long structureRevision,
            long ownerAclEpoch,
            boolean landAuthLoaded,
            boolean owner,
            boolean adminBypass) {
        public Key {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(landId, "landId");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(action, "action");
        }
    }

    private static final class State {
        final ConcurrentHashMap<Key, PermissionDecision> map = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Key> queue = new ConcurrentLinkedQueue<>();
        final int budget;

        State(int budget) {
            this.budget = budget;
        }
    }

    private final AtomicReference<State> state;

    public PermissionDecisionCache(int maxEntries) {
        this.state = new AtomicReference<>(new State(clampMaxEntries(maxEntries)));
    }

    /**
     * Fail-closed budget mapping: zero or negative disables the cache,
     * oversized values stop at {@link #MAX_MAX_ENTRIES}.
     */
    public static int clampMaxEntries(int raw) {
        if (raw <= 0) {
            return 0;
        }
        return Math.min(raw, MAX_MAX_ENTRIES);
    }

    /** Current budget; zero means disabled. Lock-free atomic read. */
    public int maxEntries() {
        return state.get().budget;
    }

    /**
     * Apply a new budget by publishing a fresh empty generation. A repeat
     * of the current budget only trims the live generation to fit; any
     * actual change retires the old map and queue untouched, so in-flight
     * writes to the retired generation stay invisible to the new one. The
     * swap is a compare-and-set loop, so a budget change that lands first
     * is never overwritten by a racing clear or budget change that read an
     * older generation: the loser re-reads and retries on the newer one.
     * All queue operations stay lock-free and memory-only.
     */
    public void setMaxEntries(int raw) {
        int next = clampMaxEntries(raw);
        while (true) {
            State current = state.get();
            if (next == current.budget) {
                trim(current);
                return;
            }
            if (state.compareAndSet(current, new State(next))) {
                return;
            }
        }
    }

    /** Hit or miss; a disabled cache always misses. Never throws. */
    public PermissionDecision get(Key key) {
        if (key == null) {
            return null;
        }
        State snapshot = state.get();
        if (snapshot.budget <= 0) {
            return null;
        }
        return snapshot.map.get(key);
    }

    /**
     * Store one decision under its full key in the generation this call
     * captured. Only the first insert of an absent key enqueues for
     * eviction; a repeated key already maps to the same deterministic
     * decision, so re-inserting would only duplicate queue nodes. A write
     * that observes a disabled generation is dropped, even if a later
     * generation re-enables the cache.
     */
    public void put(Key key, PermissionDecision decision) {
        if (key == null || decision == null) {
            return;
        }
        State snapshot = state.get();
        int budget = snapshot.budget;
        if (budget <= 0) {
            return;
        }
        if (snapshot.map.putIfAbsent(key, decision) == null) {
            snapshot.queue.offer(key);
            trim(snapshot);
        }
    }

    /**
     * Retire the live generation and publish a fresh empty one under the
     * same budget. No later read, size check or re-enable can observe the
     * retired entries or queue nodes. The swap is a compare-and-set loop,
     * so a racing budget change wins instead of being overwritten: the
     * loser re-reads the newer budget and retries, which keeps a disabled
     * cache disabled and an enabled one enabled. Memory-only and lock-free.
     */
    public void clear() {
        while (true) {
            State current = state.get();
            if (state.compareAndSet(current, new State(current.budget))) {
                return;
            }
        }
    }

    /** Current entry count of the live generation. */
    public int size() {
        return state.get().map.size();
    }

    private void trim(State snapshot) {
        while (snapshot.map.size() > snapshot.budget) {
            Key oldest = snapshot.queue.poll();
            if (oldest == null) {
                break;
            }
            snapshot.map.remove(oldest);
        }
    }
}

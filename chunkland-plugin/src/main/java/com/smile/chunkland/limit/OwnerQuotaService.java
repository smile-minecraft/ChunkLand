package com.smile.chunkland.limit;

import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.limit.LimitResult;
import com.smile.chunkland.api.limit.LimitType;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production owner-quota reservation seam.
 *
 * <p>Each successful {@link #tryReserveLand(OwnerRef)} atomically increments a
 * per-owner in-memory reservation counter. The check {@code committed + reserved < limit}
 * is performed under a per-owner lock so that N concurrent contenders for the same owner
 * are linearised. The external {@link LimitResolver} is consulted OUTSIDE the lock
 * so no lock is held while invoking the provider.</p>
 *
 * <p>Server-owned requests bypass quota entirely: {@link OwnerRef.ServerOwnerRef}
 * always succeeds with a no-op reservation (spec §12 Server Land not counted).
 * Player quotas are isolated by owner key, so different owners do not interfere.</p>
 *
 * <p>Reservation lifecycle: success returns a {@link QuotaReservation} that must
 * be either {@link QuotaReservation#complete()} (commit to durable count) or
 * {@link QuotaReservation#release()} (abort). Releasing a failed reservation is a no-op.
 * Counts never go negative and never exceed the limit. Failed tryReserve has no side effect.</p>
 */
public final class OwnerQuotaService {

    private final LimitResolver resolver;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    private static final class State {
        int landCommitted;
        int landReserved;
        int chunkCommitted;
        int chunkReserved;
        final Object lock = new Object();
    }

    public OwnerQuotaService(LimitResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * Simulation seam: set the committed (durable) land count for an owner.
     * Used to simulate existing lands before the concurrent burst.
     */
    public void setCommitted(OwnerRef owner, int committed) {
        setLandCommitted(owner, committed);
    }

    public void setLandCommitted(OwnerRef owner, int committed) {
        Objects.requireNonNull(owner, "owner");
        if (committed < 0) {
            throw new IllegalArgumentException("committed must be >= 0: " + committed);
        }
        if (owner instanceof OwnerRef.ServerOwnerRef) {
            return;
        }
        String key = owner.key();
        State s = states.computeIfAbsent(key, k -> new State());
        synchronized (s.lock) {
            s.landCommitted = committed;
            if (s.landReserved < 0) s.landReserved = 0;
        }
    }

    public void setChunkCommitted(OwnerRef owner, int committed) {
        Objects.requireNonNull(owner, "owner");
        if (committed < 0) {
            throw new IllegalArgumentException("committed must be >= 0: " + committed);
        }
        if (owner instanceof OwnerRef.ServerOwnerRef) {
            return;
        }
        String key = owner.key();
        State s = states.computeIfAbsent(key, k -> new State());
        synchronized (s.lock) {
            s.chunkCommitted = committed;
            if (s.chunkReserved < 0) s.chunkReserved = 0;
        }
    }

    public int committed(OwnerRef owner) {
        return landCommitted(owner);
    }

    public int landCommitted(OwnerRef owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner instanceof OwnerRef.ServerOwnerRef) return 0;
        State s = states.get(owner.key());
        if (s == null) return 0;
        synchronized (s.lock) {
            return s.landCommitted;
        }
    }

    public int chunkCommitted(OwnerRef owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner instanceof OwnerRef.ServerOwnerRef) return 0;
        State s = states.get(owner.key());
        if (s == null) return 0;
        synchronized (s.lock) {
            return s.chunkCommitted;
        }
    }

    public int reserved(OwnerRef owner) {
        return landReserved(owner);
    }

    public int landReserved(OwnerRef owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner instanceof OwnerRef.ServerOwnerRef) return 0;
        State s = states.get(owner.key());
        if (s == null) return 0;
        synchronized (s.lock) {
            return s.landReserved;
        }
    }

    public int chunkReserved(OwnerRef owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner instanceof OwnerRef.ServerOwnerRef) return 0;
        State s = states.get(owner.key());
        if (s == null) return 0;
        synchronized (s.lock) {
            return s.chunkReserved;
        }
    }

    public int total(OwnerRef owner) {
        return landTotal(owner);
    }

    public int landTotal(OwnerRef owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner instanceof OwnerRef.ServerOwnerRef) return 0;
        State s = states.get(owner.key());
        if (s == null) return 0;
        synchronized (s.lock) {
            return Math.addExact(s.landCommitted, s.landReserved);
        }
    }

    public int chunkTotal(OwnerRef owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner instanceof OwnerRef.ServerOwnerRef) return 0;
        State s = states.get(owner.key());
        if (s == null) return 0;
        synchronized (s.lock) {
            return Math.addExact(s.chunkCommitted, s.chunkReserved);
        }
    }

    /**
     * Try to reserve one land slot for the given owner.
     * Server owner always succeeds with a no-op reservation.
     * Player owner succeeds only if {@code committed + reserved < limit}.
     */
    public Optional<QuotaReservation> tryReserveLand(OwnerRef owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner instanceof OwnerRef.ServerOwnerRef) {
            return Optional.of(QuotaReservation.noop());
        }
        // Resolve limit OUTSIDE the lock — do not hold the per-owner lock while calling provider.
        LimitResult result = resolver.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER);
        long limit = result.limit();
        if (limit > Integer.MAX_VALUE) {
            throw new IllegalStateException("limit too large: " + limit);
        }
        int intLimit = (int) limit;
        String key = owner.key();
        State state = states.computeIfAbsent(key, k -> new State());
        synchronized (state.lock) {
            long committedAndReserved = (long) state.landCommitted + (long) state.landReserved;
            if (committedAndReserved >= intLimit) {
                return Optional.empty();
            }
            state.landReserved = Math.addExact(state.landReserved, 1);
            return Optional.of(new QuotaReservation(this, key, state, false));
        }
    }

    /**
     * Try to reserve {@code chunks} total-chunk capacity for the owner (uses MAX_TOTAL_CHUNKS_PER_PLAYER).
     * Server owner bypasses.
     */
    public Optional<QuotaReservation> tryReserveChunks(OwnerRef owner, int chunks) {
        Objects.requireNonNull(owner, "owner");
        if (chunks <= 0) {
            throw new IllegalArgumentException("chunks must be > 0: " + chunks);
        }
        if (owner instanceof OwnerRef.ServerOwnerRef) {
            return Optional.of(QuotaReservation.noop());
        }
        LimitResult result = resolver.resolve(owner, LimitType.MAX_TOTAL_CHUNKS_PER_PLAYER);
        long limit = result.limit();
        if (limit > Integer.MAX_VALUE) {
            throw new IllegalStateException("limit too large: " + limit);
        }
        int intLimit = (int) limit;
        String key = owner.key();
        State state = states.computeIfAbsent(key, k -> new State());
        synchronized (state.lock) {
            // For chunk quota we treat committed as already-used chunk total.
            // This seam does not track per-land chunk distribution; callers provide current committed.
            long totalWithRequest = (long) state.chunkCommitted + (long) state.chunkReserved + (long) chunks;
            if (totalWithRequest > intLimit) {
                return Optional.empty();
            }
            state.chunkReserved = Math.addExact(state.chunkReserved, chunks);
            return Optional.of(new QuotaReservation(this, key, state, true, chunks));
        }
    }

    // Called by QuotaReservation to atomically transition.
    void completeReservation(String key, State state, boolean isChunk, int chunkDelta) {
        synchronized (state.lock) {
            if (isChunk) {
                if (state.chunkReserved < chunkDelta) {
                    throw new IllegalStateException("reserved chunk mismatch for " + key);
                }
                if (state.chunkReserved <= 0) {
                    throw new IllegalStateException("no reservation to complete for " + key);
                }
                // Compute both results before mutating so a failed transition
                // (e.g. counter overflow) leaves the counters untouched and the
                // handle releasable instead of wedging the quota.
                int committed = Math.addExact(state.chunkCommitted, chunkDelta);
                int reserved = Math.subtractExact(state.chunkReserved, chunkDelta);
                state.chunkReserved = reserved;
                state.chunkCommitted = committed;
            } else {
                if (state.landReserved <= 0) {
                    throw new IllegalStateException("no reservation to complete for " + key);
                }
                int committed = Math.addExact(state.landCommitted, 1);
                int reserved = Math.subtractExact(state.landReserved, 1);
                state.landReserved = reserved;
                state.landCommitted = committed;
            }
        }
    }

    /**
     * Repair a reservation whose normal {@code complete()} transition failed
     * after the durable domain commit already succeeded: consume whatever is
     * still reserved and move the full delta to committed so the in-memory
     * counters match the durable truth instead of leaking a reservation.
     *
     * <p>The committed total is computed before mutating, so a counter
     * overflow still throws without touching anything and the handle stays
     * releasable.
     */
    void repairCommit(String key, State state, boolean isChunk, int chunkDelta) {
        synchronized (state.lock) {
            if (isChunk) {
                int committed = Math.addExact(state.chunkCommitted, chunkDelta);
                int consume = Math.min(Math.max(state.chunkReserved, 0), chunkDelta);
                state.chunkReserved = Math.subtractExact(state.chunkReserved, consume);
                state.chunkCommitted = committed;
            } else {
                int committed = Math.addExact(state.landCommitted, 1);
                int consume = Math.min(Math.max(state.landReserved, 0), 1);
                state.landReserved = Math.subtractExact(state.landReserved, consume);
                state.landCommitted = committed;
            }
        }
    }

    void releaseReservation(String key, State state, boolean isChunk, int chunkDelta) {
        synchronized (state.lock) {
            if (isChunk) {
                if (state.chunkReserved <= 0) {
                    return;
                }
                int toRelease = Math.min(chunkDelta, state.chunkReserved);
                state.chunkReserved = Math.subtractExact(state.chunkReserved, toRelease);
                if (state.chunkReserved < 0) state.chunkReserved = 0;
            } else {
                if (state.landReserved <= 0) {
                    return;
                }
                state.landReserved = Math.subtractExact(state.landReserved, 1);
                if (state.landReserved < 0) state.landReserved = 0;
            }
        }
    }

    /**
     * Handle returned from a successful {@link #tryReserveLand(OwnerRef)}.
     * Callers must call either {@link #complete()} (durable commit) or {@link #release()} (abort).
     * Operations are idempotent; double release/complete is safe.
     */
    public static final class QuotaReservation implements AutoCloseable {
        private final OwnerQuotaService service;
        private final String key;
        private final State state;
        private final boolean isChunk;
        private final int chunkDelta;
        private final boolean noop;
        private boolean done;

        private QuotaReservation(OwnerQuotaService service, String key, State state, boolean isChunk) {
            this(service, key, state, isChunk, 1);
        }

        private QuotaReservation(OwnerQuotaService service, String key, State state, boolean isChunk, int chunkDelta) {
            this.service = service;
            this.key = key;
            this.state = state;
            this.isChunk = isChunk;
            this.chunkDelta = chunkDelta;
            this.noop = false;
        }

        private QuotaReservation(boolean noop) {
            this.service = null;
            this.key = null;
            this.state = null;
            this.isChunk = false;
            this.chunkDelta = 0;
            this.noop = noop;
        }

        static QuotaReservation noop() {
            QuotaReservation r = new QuotaReservation(true);
            r.done = false;
            return r;
        }

        /**
         * Mark the reservation as committed: reserved -> committed.
         *
         * <p>The terminal flag is set only after the counter transition
         * succeeds, so a failed transition leaves this handle releasable and
         * the counters untouched instead of wedging the quota. Still
         * idempotent: a completed handle ignores further calls.
         */
        public synchronized void complete() {
            if (done) return;
            if (noop) {
                done = true;
                return;
            }
            service.completeReservation(key, state, isChunk, chunkDelta);
            done = true;
        }

        /** Release the reservation without committing. */
        public synchronized void release() {
            if (done) return;
            done = true;
            if (noop) return;
            service.releaseReservation(key, state, isChunk, chunkDelta);
        }

        /**
         * Force the reservation to committed after the durable domain commit
         * already succeeded but the normal {@code complete()} transition
         * failed: whatever is still reserved is consumed and the full delta
         * lands on committed, so no reservation leaks and the counters match
         * the durable truth. Only a counter overflow still throws, leaving
         * the handle releasable. Idempotent like {@code complete()}.
         */
        public synchronized void forceComplete() {
            if (done) return;
            if (noop) {
                done = true;
                return;
            }
            service.repairCommit(key, state, isChunk, chunkDelta);
            done = true;
        }

        @Override
        public void close() {
            release();
        }

        public boolean isNoop() {
            return noop;
        }
    }
}

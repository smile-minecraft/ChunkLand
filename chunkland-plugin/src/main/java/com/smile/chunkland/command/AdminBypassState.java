package com.smile.chunkland.command;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-enable player-scoped admin-bypass memory.
 *
 * <p>Every entry defaults to off: only an explicit audited toggle flips one
 * actor on, and the flag never leaks across players, restarts or enables.
 * The set is bounded so one enable generation cannot grow without limit,
 * and {@link #clear()} drops every entry (the disable path calls it). All
 * management gate seams read through {@link #isOn}, never through the
 * bypass permission node, so holding the node alone authorises nothing.
 *
 * <p>The bound holds on two layers. Admission: {@link #admit} counts
 * committed entries plus pending reservations under one monitor, so an
 * {@code on} toggle whose audit has not settled yet still occupies the
 * slot it may commit — a second actor can never write a success audit for
 * a slot that is already spoken for. Commit: {@link #commit} and
 * {@link #setEnabled} re-check the committed size under the same monitor,
 * so the set can never grow past the cap even if reservation accounting
 * ever drifted. Reservations are not bypass: {@link #isOn} only reports
 * audit-committed entries.
 *
 * <p>Every {@link #clear()} advances the generation: a toggle that started
 * before a disable commits only when its reservation still matches the
 * current generation and membership, so an in-flight audit settling after
 * disable can never re-enable bypass. Admission, epoch capture and actor
 * membership form one atomic token; callers must never capture the epoch
 * and reserve in two separate steps.
 */
public final class AdminBypassState {

    /** Hard cap on enabled actors per enable generation. */
    public static final int MAX_ACTORS = 1024;

    /** Admission outcome for one {@code on} attempt. */
    public enum ReserveOutcome {
        /** Already committed on: no reservation needed, audit may proceed. */
        ALREADY_ON,
        /** A pending slot is now held for this actor until commit/release. */
        RESERVED,
        /** No slot left: fail closed without writing any audit. */
        REFUSED
    }

    /**
     * Atomic reservation token binding one actor to the exact generation
     * observed at admission. Only the token held in the pending map for the
     * current generation can commit or release; a stale token touches
     * nothing, so it can never disturb a newer generation's slot for the
     * same actor.
     */
    public record Reservation(UUID actor, long epoch) {
        public Reservation {
            Objects.requireNonNull(actor, "actor");
        }
    }

    /**
     * Atomic admission result: the outcome, the held token when
     * {@code RESERVED}, and the generation observed while holding the
     * monitor. The token epoch always equals this epoch when reserved.
     */
    public record Admission(ReserveOutcome outcome, Reservation reservation, long epoch) {
        public Admission {
            Objects.requireNonNull(outcome, "outcome");
            if (outcome == ReserveOutcome.RESERVED) {
                Objects.requireNonNull(reservation, "reservation");
                if (reservation.epoch() != epoch) {
                    throw new IllegalArgumentException("token epoch must match admission epoch");
                }
            } else if (reservation != null) {
                throw new IllegalArgumentException("only RESERVED carries a token");
            }
        }
    }

    private final ConcurrentHashMap<UUID, Boolean> enabled = new ConcurrentHashMap<>();
    /** Pending admission slots by actor, guarded by the {@link #enabled} monitor. */
    private final Map<UUID, Reservation> pending = new HashMap<>();
    /** Enable generation, bumped by {@link #clear()}, guarded by the monitor. */
    private long epoch;

    /** Whether the actor currently holds an explicit bypass. Never null. */
    public boolean isOn(UUID actor) {
        Objects.requireNonNull(actor, "actor");
        return Boolean.TRUE.equals(enabled.get(actor));
    }

    /**
     * Flips one actor. Enabling a new actor while full returns {@code false}
     * and changes nothing; disabling always succeeds and also cancels that
     * actor's pending reservation, so a later stale commit for the same
     * token must fail instead of re-enabling. An actor that already holds
     * a pending slot consumes it instead of requiring a second slot. The
     * capacity check-and-insert runs atomically under the memory monitor
     * and counts pending reservations, so distinct actors racing at the cap
     * can never push the set past the bound. Reads stay lock-free on the
     * concurrent map; this is an administrative toggle path, never the
     * enforcement hot path. Never throws for a non-null actor.
     *
     * @return {@code true} when the requested state is now in effect
     */
    public boolean setEnabled(UUID actor, boolean on) {
        Objects.requireNonNull(actor, "actor");
        synchronized (enabled) {
            if (!on) {
                enabled.remove(actor);
                pending.remove(actor);
                return true;
            }
            if (enabled.containsKey(actor)) {
                pending.remove(actor);
                return true;
            }
            Reservation held = pending.get(actor);
            if (held != null && held.epoch() == epoch) {
                enabled.put(actor, Boolean.TRUE);
                pending.remove(actor);
                return true;
            }
            if (enabled.size() + pending.size() >= MAX_ACTORS) {
                return false;
            }
            enabled.put(actor, Boolean.TRUE);
            return true;
        }
    }

    /**
     * Admits one {@code on} attempt atomically against the bound: the
     * generation check, the already-on check, the capacity check and the
     * token creation all happen under one monitor. {@code ALREADY_ON}
     * needs no slot; {@code RESERVED} holds one per-actor slot until
     * {@link #commit} or {@link #release(Reservation)}; {@code REFUSED}
     * must fail closed without writing any audit. A second admission for
     * the same actor while its slot is held returns the same token without
     * consuming another slot. This is the only admission path the toggle
     * handler may use; capturing the epoch separately would reopen the
     * clear race.
     */
    public Admission admit(UUID actor) {
        Objects.requireNonNull(actor, "actor");
        synchronized (enabled) {
            if (enabled.containsKey(actor)) {
                return new Admission(ReserveOutcome.ALREADY_ON, null, epoch);
            }
            Reservation existing = pending.get(actor);
            if (existing != null && existing.epoch() == epoch) {
                return new Admission(ReserveOutcome.RESERVED, existing, epoch);
            }
            if (existing != null) {
                pending.remove(actor);
            }
            if (enabled.size() + pending.size() >= MAX_ACTORS) {
                return new Admission(ReserveOutcome.REFUSED, null, epoch);
            }
            Reservation token = new Reservation(actor, epoch);
            pending.put(actor, token);
            return new Admission(ReserveOutcome.RESERVED, token, epoch);
        }
    }

    /**
     * Admits one {@code on} attempt against the bound, counting committed
     * entries plus pending reservations atomically. Kept for direct state
     * callers; the toggle handler must use {@link #admit} so the token and
     * its generation stay bound. The created token uses the actual
     * generation observed under the monitor.
     */
    public ReserveOutcome tryReserve(UUID actor) {
        return admit(actor).outcome();
    }

    /**
     * Commits a reserved {@code on} after its audit succeeded. Succeeds
     * only when the token still matches the pending map for the current
     * generation — the same actor, the same generation, still held — and
     * only then moves the actor to committed. A stale generation, a
     * cancelled or missing reservation, or a mismatched token touches
     * nothing and returns {@code false}, so an in-flight audit settling
     * after disable, after a legacy cancel, or a forged token for an
     * already-committed actor can never report success or re-enable
     * bypass. The committed size is re-checked under the monitor, so the
     * set can never exceed the cap. The already-on path must use
     * {@link #confirmStillOn}, never this method. Never throws for a
     * non-null token.
     *
     * @return {@code true} only when this token moved its actor to committed
     */
    public boolean commit(Reservation token) {
        Objects.requireNonNull(token, "token");
        synchronized (enabled) {
            if (this.epoch != token.epoch()) {
                return false;
            }
            Reservation held = pending.get(token.actor());
            if (held == null || !held.equals(token)) {
                return false;
            }
            if (enabled.containsKey(token.actor())) {
                pending.remove(token.actor());
                return true;
            }
            if (enabled.size() >= MAX_ACTORS) {
                pending.remove(token.actor());
                return false;
            }
            enabled.put(token.actor(), Boolean.TRUE);
            pending.remove(token.actor());
            return true;
        }
    }

    /**
     * Token commit under the legacy name. Delegates to {@link #commit}.
     */
    public boolean commitReserved(Reservation token) {
        return commit(token);
    }

    /**
     * Commits a reserved {@code on} after its audit succeeded. Succeeds
     * only when the captured enable generation still matches and the
     * actor still holds its reservation — a stale generation, a missing
     * or cancelled reservation, or a mismatched epoch touches nothing and
     * returns {@code false}, so an in-flight audit settling after disable,
     * after a legacy cancel, or a forged commit for an already-committed
     * actor can never report success or re-enable bypass. The committed
     * size is re-checked under the monitor, so the set can never exceed
     * the cap. Kept for direct state callers; the toggle handler commits
     * the token it admitted and confirms already-on via
     * {@link #confirmStillOn}.
     *
     * @return {@code true} only when this reservation moved its actor to committed
     */
    public boolean commitReserved(UUID actor, long epoch) {
        Objects.requireNonNull(actor, "actor");
        synchronized (enabled) {
            if (this.epoch != epoch) {
                return false;
            }
            Reservation held = pending.get(actor);
            if (held == null || held.epoch() != epoch || !held.actor().equals(actor)) {
                return false;
            }
            if (enabled.size() >= MAX_ACTORS) {
                pending.remove(actor);
                return false;
            }
            enabled.put(actor, Boolean.TRUE);
            pending.remove(actor);
            return true;
        }
    }

    /**
     * Confirms an already-on actor is still on in the admitted generation.
     * Used for the {@code ALREADY_ON} admission path, which holds no slot:
     * succeeds only when the generation still matches and the actor is
     * still committed, so a disable or legacy cancel racing the audit
     * fails closed instead of reporting a phantom success.
     */
    public boolean confirmStillOn(UUID actor, long epoch) {
        Objects.requireNonNull(actor, "actor");
        synchronized (enabled) {
            if (this.epoch != epoch) {
                return false;
            }
            return enabled.containsKey(actor);
        }
    }

    /**
     * Drops the pending reservation only when the token still matches the
     * pending map for the current generation. A stale token skips the
     * removal: {@link #clear()} already wiped every reservation, and the
     * map may since hold a new generation's slot for the same actor, which
     * must be preserved. Never throws for a non-null token.
     */
    public void release(Reservation token) {
        Objects.requireNonNull(token, "token");
        synchronized (enabled) {
            if (this.epoch != token.epoch()) {
                return;
            }
            Reservation held = pending.get(token.actor());
            if (held != null && held.equals(token)) {
                pending.remove(token.actor());
            }
        }
    }

    /**
     * Drops one actor's pending reservation for the matching generation.
     * A stale generation skips the removal: {@link #clear()} already wiped
     * every reservation, and the set may since hold a new generation's
     * slot for the same actor. Kept for direct state callers; the toggle
     * handler releases the token it admitted.
     */
    public void release(UUID actor, long epoch) {
        Objects.requireNonNull(actor, "actor");
        synchronized (enabled) {
            if (this.epoch != epoch) {
                return;
            }
            Reservation held = pending.get(actor);
            if (held != null && held.epoch() == epoch && held.actor().equals(actor)) {
                pending.remove(actor);
            }
        }
    }

    /** Current enable generation. Never throws. */
    public long epoch() {
        synchronized (enabled) {
            return epoch;
        }
    }

    /** Drops every entry and reservation and advances the generation, e.g.
     * on disable. Never throws. */
    public void clear() {
        synchronized (enabled) {
            enabled.clear();
            pending.clear();
            epoch++;
        }
    }

    /** Number of currently enabled actors. */
    public int size() {
        return enabled.size();
    }

    /** Number of currently held reservations. */
    public int reservationCount() {
        synchronized (enabled) {
            return pending.size();
        }
    }
}

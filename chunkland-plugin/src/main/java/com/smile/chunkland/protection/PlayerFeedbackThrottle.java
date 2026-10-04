package com.smile.chunkland.protection;

import com.smile.chunkland.selection.SelectionClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded per-player cooldown for best-effort deny feedback.
 *
 * <p>The first request for a player passes and records the time; further
 * requests inside the window are refused. Stale timestamps are swept once
 * per retention period and the tracked set is capped, so a server that sees
 * many distinct players never grows this without limit. All state lives in
 * concurrent maps and each check-and-update runs atomically per player; the
 * event thread never waits.
 */
final class PlayerFeedbackThrottle {

    private final SelectionClock clock;
    private final java.util.function.Supplier<Duration> cooldown;
    private final Duration retention;
    private final int maxEntries;
    private final ConcurrentMap<UUID, Instant> lastSent = new ConcurrentHashMap<>();
    private final AtomicInteger trackedCount = new AtomicInteger();
    private final AtomicReference<Instant> nextSweep = new AtomicReference<>();

    PlayerFeedbackThrottle(SelectionClock clock, Duration cooldown, Duration retention,
                           int maxEntries) {
        this(clock, fixed(cooldown), retention, maxEntries);
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown must not be negative: " + cooldown);
        }
        if (retention.compareTo(cooldown) <= 0) {
            throw new IllegalArgumentException("retention must be longer than cooldown: "
                    + retention);
        }
    }

    private static java.util.function.Supplier<Duration> fixed(Duration cooldown) {
        Objects.requireNonNull(cooldown, "cooldown");
        return () -> cooldown;
    }

    /**
     * @param cooldown live window source, read on every request so a config
     *                 reload applies at once; a failing, {@code null} or
     *                 negative answer refuses the request (fail quiet)
     */
    PlayerFeedbackThrottle(SelectionClock clock, java.util.function.Supplier<Duration> cooldown,
                           Duration retention, int maxEntries) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
        }
        this.maxEntries = maxEntries;
    }

    /**
     * Tries to spend the player's slot.
     *
     * @return {@code true} when feedback may be shown now; {@code false}
     *         inside the window, or when the tracked set is full
     */
    boolean tryAcquire(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Instant now = Objects.requireNonNull(clock.now(), "clock must not return null");
        Duration window = cooldown.get();
        if (window == null || window.isNegative()) {
            return false;
        }
        sweepExpired(now);
        AtomicBoolean acquired = new AtomicBoolean();
        lastSent.compute(playerId, (ignored, previous) -> {
            if (previous != null
                    && Duration.between(previous, now).compareTo(window) < 0) {
                return previous;
            }
            if (previous == null && !reserveSlot()) {
                return null;
            }
            acquired.set(true);
            return now;
        });
        return acquired.get();
    }

    /** Removes the stored timestamp for a player, if one exists. */
    void forget(UUID playerId) {
        if (playerId != null && lastSent.remove(playerId) != null) {
            trackedCount.decrementAndGet();
        }
    }

    /** Number of player timestamps currently retained. */
    int trackedPlayerCount() {
        return lastSent.size();
    }

    private boolean reserveSlot() {
        while (true) {
            int current = trackedCount.get();
            if (current >= maxEntries) {
                return false;
            }
            if (trackedCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void sweepExpired(Instant now) {
        Instant scheduled = nextSweep.get();
        if (scheduled != null && now.isBefore(scheduled)) {
            return;
        }
        if (nextSweep.compareAndSet(scheduled, now.plus(retention))) {
            for (var entry : lastSent.entrySet()) {
                if (Duration.between(entry.getValue(), now).compareTo(retention) >= 0
                        && lastSent.remove(entry.getKey(), entry.getValue())) {
                    trackedCount.decrementAndGet();
                }
            }
        }
    }
}

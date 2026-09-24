package com.smile.chunkland.message.rejection;

import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.selection.SelectionClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-player, per-action throttle for protection rejection notices.
 *
 * <p>The first deny for a {@code (player, action)} pair passes and records
 * the send time; further denies inside the window are suppressed. Each pair
 * has its own window, so one noisy action never mutes another, and one noisy
 * player never mutes anyone else.
 *
 * <p>Time comes from an injected {@link SelectionClock} so tests advance a
 * fake clock instead of sleeping the event thread. All state lives in a
 * {@link ConcurrentHashMap}; each key's check-and-update runs atomically in
 * {@link ConcurrentMap#compute}, so concurrent denies cannot both acquire the
 * same send slot. The critical section only reads and writes one in-memory
 * timestamp; it performs no blocking I/O.
 */
public final class RejectionCooldown {

    /** Default quiet window between two notices for one {@code (player, action)}. */
    public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(2);

    /** Cooldown key: one window per denied player and action. */
    private record Key(UUID playerId, ProtectionActionType action) {
    }

    private final SelectionClock clock;
    private final Duration cooldown;
    private final ConcurrentMap<Key, Instant> lastSent = new ConcurrentHashMap<>();

    public RejectionCooldown(SelectionClock clock, Duration cooldown) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown must not be negative: " + cooldown);
        }
    }

    /**
     * Tries to spend the send slot for this pair.
     *
     * @return {@code true} when a notice may be sent now (first deny, or the
     *         window has expired); the send time is recorded. {@code false}
     *         when the pair is still inside its window and the deny must stay
     *         silent.
     */
    public boolean tryAcquire(UUID playerId, ProtectionActionType action) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(action, "action");
        Instant now = clock.now();
        Objects.requireNonNull(now, "clock must not return null");
        Key key = new Key(playerId, action);
        AtomicBoolean acquired = new AtomicBoolean();
        lastSent.compute(key, (ignored, previous) -> {
            if (previous != null
                    && Duration.between(previous, now).compareTo(cooldown) < 0) {
                return previous;
            }
            acquired.set(true);
            return now;
        });
        return acquired.get();
    }
}

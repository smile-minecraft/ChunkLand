package com.smile.chunkland.protection;

import com.smile.chunkland.command.PlayerScheduler;
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
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Best-effort, player-only feedback for denied ENTRY transit. */
public final class EntryDenialParticleFeedback {

    public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(2);
    public static final Duration DEFAULT_RETENTION = Duration.ofMinutes(10);
    public static final int MAX_ENTRIES = 4096;
    public static final int PARTICLE_COUNT = 4;
    public static final org.bukkit.Particle PARTICLE = org.bukkit.Particle.DUST;
    public static final org.bukkit.Color DUST_COLOR = org.bukkit.Color.fromRGB(220, 40, 40);
    public static final float DUST_SIZE = 0.8F;
    public static final double PARTICLE_SPREAD = 0.05D;

    @FunctionalInterface
    public interface ParticleSender {
        void send(Player player, Location location);
    }

    private final SelectionClock clock;
    private final Duration cooldown;
    private final Duration retention;
    private final int maxEntries;
    private final PlayerScheduler scheduler;
    private final ParticleSender sender;
    private final ConcurrentMap<UUID, Instant> lastSent = new ConcurrentHashMap<>();
    private final AtomicInteger trackedCount = new AtomicInteger();
    private final AtomicReference<Instant> nextSweep = new AtomicReference<>();

    public EntryDenialParticleFeedback(SelectionClock clock, Duration cooldown,
                                        PlayerScheduler scheduler, ParticleSender sender) {
        this(clock, cooldown, DEFAULT_RETENTION, MAX_ENTRIES, scheduler, sender);
    }

    EntryDenialParticleFeedback(SelectionClock clock, Duration cooldown,
                                Duration retention, int maxEntries,
                                PlayerScheduler scheduler, ParticleSender sender) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cooldown = cooldown == null ? DEFAULT_COOLDOWN : cooldown;
        this.retention = retention == null ? DEFAULT_RETENTION : retention;
        if (this.cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown must not be negative: " + this.cooldown);
        }
        if (this.retention.compareTo(this.cooldown) <= 0) {
            throw new IllegalArgumentException("retention must be longer than cooldown: "
                    + this.retention);
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
        }
        this.maxEntries = maxEntries;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    public void show(Player player, Location location) {
        if (player == null || location == null) {
            return;
        }
        try {
            var destinationWorld = location.getWorld();
            var playerWorld = player.getWorld();
            if (destinationWorld == null || playerWorld == null
                    || !destinationWorld.getUID().equals(playerWorld.getUID())) {
                return;
            }
            UUID playerId = player.getUniqueId();
            Instant now = Objects.requireNonNull(clock.now(), "clock must not return null");
            if (!tryAcquire(playerId, now)) {
                return;
            }
            Location destination = location.clone();
            scheduler.runForPlayer(player, () -> {
                try {
                    sender.send(player, destination);
                } catch (RuntimeException ignored) {
                    // Particle feedback is best effort and cannot affect enforcement.
                }
            });
        } catch (RuntimeException ignored) {
            // Clock, player, and scheduling failures stay silent on the deny path.
        }
    }

    /** Removes the stored timestamp for a player, if one exists. */
    public void forget(UUID playerId) {
        if (playerId != null && lastSent.remove(playerId) != null) {
            trackedCount.decrementAndGet();
        }
    }

    /** Number of player timestamps currently retained. */
    int trackedPlayerCount() {
        return lastSent.size();
    }

    private boolean tryAcquire(UUID playerId, Instant now) {
        sweepExpired(now);
        AtomicBoolean acquired = new AtomicBoolean();
        lastSent.compute(playerId, (ignored, previous) -> {
            if (previous != null
                    && Duration.between(previous, now).compareTo(cooldown) < 0) {
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

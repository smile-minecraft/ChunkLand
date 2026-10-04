package com.smile.chunkland.protection;

import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.selection.SelectionClock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Best-effort, player-only highlight for a denied block or entity action.
 *
 * <p>The denied target is marked for the denied player alone, so they can see
 * exactly which block or entity the land refused. The mark is throttled per
 * player and sent from the player's own thread; nothing here can change the
 * cancel decision, and every failure stays silent.
 */
public final class ActionDenialParticleFeedback {

    /** Short window: repeated clicks stay visible without flooding the client. */
    public static final Duration DEFAULT_COOLDOWN = Duration.ofMillis(400);
    public static final Duration DEFAULT_RETENTION = Duration.ofMinutes(10);
    public static final int MAX_ENTRIES = 4096;

    /**
     * One denied target.
     *
     * @param worldId world the target sits in
     * @param x block X for a block mark, exact X for a spot mark
     * @param y block Y for a block mark, exact Y for a spot mark
     * @param z block Z for a block mark, exact Z for a spot mark
     * @param block {@code true} for a whole block cell, {@code false} for a
     *              free spot such as an entity position
     */
    public record Mark(UUID worldId, double x, double y, double z, boolean block) {
        public Mark {
            Objects.requireNonNull(worldId, "worldId");
        }
    }

    /** Sends the highlight; runs on the denied player's thread. */
    @FunctionalInterface
    public interface MarkSender {
        void send(Player player, Mark mark);
    }

    private final PlayerFeedbackThrottle throttle;
    private final PlayerScheduler scheduler;
    private final MarkSender sender;

    public ActionDenialParticleFeedback(SelectionClock clock, Duration cooldown,
                                         PlayerScheduler scheduler, MarkSender sender) {
        this.throttle = new PlayerFeedbackThrottle(clock,
                cooldown == null ? DEFAULT_COOLDOWN : cooldown, DEFAULT_RETENTION, MAX_ENTRIES);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    /**
     * @param cooldown live window source, read on every deny so a config
     *                 reload applies at once
     */
    public ActionDenialParticleFeedback(SelectionClock clock,
                                         java.util.function.Supplier<Duration> cooldown,
                                         PlayerScheduler scheduler, MarkSender sender) {
        this.throttle = new PlayerFeedbackThrottle(clock, cooldown, DEFAULT_RETENTION,
                MAX_ENTRIES);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    /** Highlights the denied block cell. */
    public void showBlock(Player player, UUID worldId, int blockX, int blockY, int blockZ) {
        if (worldId == null) {
            return;
        }
        show(player, new Mark(worldId, blockX, blockY, blockZ, true));
    }

    /** Highlights a denied free spot, such as an entity position. */
    public void showSpot(Player player, UUID worldId, double x, double y, double z) {
        if (worldId == null) {
            return;
        }
        show(player, new Mark(worldId, x, y, z, false));
    }

    /** Removes the stored timestamp for a player, if one exists. */
    public void forget(UUID playerId) {
        throttle.forget(playerId);
    }

    /** Number of player timestamps currently retained. */
    int trackedPlayerCount() {
        return throttle.trackedPlayerCount();
    }

    private void show(Player player, Mark mark) {
        if (player == null) {
            return;
        }
        try {
            UUID playerId = player.getUniqueId();
            if (playerId == null || !throttle.tryAcquire(playerId)) {
                return;
            }
            scheduler.runForPlayer(player, () -> {
                try {
                    sender.send(player, mark);
                } catch (RuntimeException ignored) {
                    // Particle feedback is best effort and cannot affect enforcement.
                }
            });
        } catch (RuntimeException ignored) {
            // Clock, player, and scheduling failures stay silent on the deny path.
        }
    }
}

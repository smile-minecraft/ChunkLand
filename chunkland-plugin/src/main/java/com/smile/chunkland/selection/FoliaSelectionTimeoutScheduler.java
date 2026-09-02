package com.smile.chunkland.selection;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Folia-safe production timeout adapter. Every timeout is submitted to the
 * target player's EntityScheduler; no global or blocking scheduler is used.
 */
public final class FoliaSelectionTimeoutScheduler implements SelectionTimeoutScheduler {
    private final Plugin plugin;
    private final Function<UUID, Player> playerLookup;

    public FoliaSelectionTimeoutScheduler(Plugin plugin, Function<UUID, Player> playerLookup) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
    }

    @Override
    public Cancellable schedule(UUID playerId, Duration delay, Runnable task) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(task, "task");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        Player player = playerLookup.apply(playerId);
        if (player == null) {
            throw new IllegalStateException("cannot schedule selection timeout for offline player");
        }
        long delayTicks = Math.max(1L, Math.ceilDiv(delay.toMillis(), 50L));
        ScheduledTask scheduled = player.getScheduler().runDelayed(
                plugin,
                ignored -> task.run(),
                null,
                delayTicks);
        if (scheduled == null) {
            throw new IllegalStateException("player scheduler retired the selection timeout");
        }
        return new ScheduledHandle(scheduled);
    }

    private static final class ScheduledHandle implements Cancellable {
        private final ScheduledTask task;

        ScheduledHandle(ScheduledTask task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            task.cancel();
        }

        @Override
        public boolean isCancelled() {
            return task.isCancelled();
        }
    }
}

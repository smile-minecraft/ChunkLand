package com.smile.chunkland.selection;

import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/**
 * Production {@link VisualizationTickScheduler} bound to the operating player.
 *
 * <p>Every tick is submitted through the AceLib public scheduler facade
 * ({@link SafeScheduler#runForPlayerLater}), so it runs in the operating
 * player's Folia context no matter which region currently owns the player —
 * the task follows the player across regions instead of pinning a region.
 * Only the public facade is used (obtained via the ServicesManager-backed
 * bridge with an {@code isReady} check upstream); no internal AceLib
 * implementation is referenced, and no global scheduler, executor, or
 * background thread is created or used here.
 *
 * <p>Terrain rule: this class never queries a Highest Block, block data, an
 * entity scan, or any Bukkit world/chunk/block object. The only player reads
 * are the scheduler-bound dispatch itself and, downstream in the sink, the
 * operating player's own location and player-scoped particle send.
 */
public final class FoliaVisualizationTickScheduler implements VisualizationTickScheduler {
    private final Function<UUID, Player> playerLookup;
    private final Supplier<Optional<SafeScheduler>> schedulers;

    public FoliaVisualizationTickScheduler(
            Function<UUID, Player> playerLookup,
            Supplier<Optional<SafeScheduler>> schedulers) {
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.schedulers = Objects.requireNonNull(schedulers, "schedulers");
    }

    @Override
    public SelectionTimeoutScheduler.Cancellable schedule(UUID playerId, long delayTicks, Runnable task) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(task, "task");
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must not be negative: " + delayTicks);
        }
        Player player = playerLookup.apply(playerId);
        if (player == null) {
            throw new IllegalStateException("cannot schedule visualization tick for offline player");
        }
        SafeScheduler scheduler = schedulers.get().orElse(null);
        if (scheduler == null) {
            throw new IllegalStateException("visualization scheduler is not ready");
        }
        ScheduledTask scheduled;
        try {
            scheduled = scheduler.runForPlayerLater(player, task, delayTicks);
        } catch (RuntimeException dispatchFailure) {
            throw new IllegalStateException("player scheduler retired the visualization tick", dispatchFailure);
        }
        if (scheduled == null) {
            throw new IllegalStateException("player scheduler retired the visualization tick");
        }
        return new ScheduledHandle(scheduled);
    }

    private static final class ScheduledHandle implements SelectionTimeoutScheduler.Cancellable {
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

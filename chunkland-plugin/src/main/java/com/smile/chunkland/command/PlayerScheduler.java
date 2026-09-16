package com.smile.chunkland.command;

import java.util.Objects;
import org.bukkit.entity.Player;

/**
 * Caller-owned hop back to a player's Folia thread.
 *
 * <p>Async completions (offline-player resolution, durable lookups) finish
 * on arbitrary executor threads where touching a {@link Player}, a
 * {@link ReplySink} or any other Bukkit sender API is unsafe. Handlers
 * schedule their reply/mutation continuation through this seam instead, so
 * production can route it to the Folia entity scheduler while tests drive a
 * recording or direct seam deterministically. Scheduling is fire-and-forget:
 * the region-facing caller never waits for the hop.
 *
 * <p>A throwing scheduler (retired entity scheduler, departed player) means
 * the continuation is dropped fail-closed by the caller — never retried,
 * never leaked.
 */
@FunctionalInterface
public interface PlayerScheduler {

    /**
     * Runs {@code task} on {@code player}'s thread, or throws when the hop
     * itself cannot be scheduled.
     */
    void runForPlayer(Player player, Runnable task);

    /** Inline seam for tests and for callers that are already player-thread-safe. */
    static PlayerScheduler direct() {
        return (player, task) -> {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(task, "task");
            task.run();
        };
    }
}

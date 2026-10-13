package com.smile.chunkland.command;

import org.bukkit.entity.Player;

/**
 * A {@link PlayerScheduler} that also reports a scheduled task the framework
 * discards before running it.
 *
 * <p>The Folia entity scheduler invokes exactly one of the run callback and
 * the retired callback: when the entity leaves between scheduling and
 * execution, only the retired callback fires. Callers that hold per-player
 * state across the hop (for example a change-in-flight flag) pass its
 * release as {@code onDiscarded}, so a discarded task still terminates the
 * operation instead of leaking the flag until restart.
 */
public interface DiscardingPlayerScheduler extends PlayerScheduler {

    /**
     * Runs {@code task} on {@code player}'s thread, or throws when the hop
     * itself cannot be scheduled. When the framework accepts the task but
     * discards it before running (the player left in between),
     * {@code onDiscarded} runs instead of {@code task}; the two never both
     * run. A {@code null} {@code onDiscarded} keeps the plain
     * {@link #runForPlayer(Player, Runnable)} behaviour.
     */
    void runForPlayer(Player player, Runnable task, Runnable onDiscarded);
}

package com.smile.chunkland.selection;

import java.util.UUID;

/** Injectable player-scoped visualization tick seam. */
@FunctionalInterface
public interface VisualizationTickScheduler {
    /**
     * Schedule one visualization tick for the player after the given delay.
     *
     * <p>The production implementation dispatches through the player-scoped
     * scheduler facade, so the tick runs in the operating player's context
     * even across region boundaries. It never uses a global scheduler.
     *
     * @param playerId the operating player the tick belongs to
     * @param delayTicks delay before the tick runs, in ticks
     * @param task the tick body
     * @return a handle that cancels the pending tick
     */
    SelectionTimeoutScheduler.Cancellable schedule(UUID playerId, long delayTicks, Runnable task);
}

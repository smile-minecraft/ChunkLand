package com.smile.chunkland.selection;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Injectable player-scoped timeout scheduling seam. */
@FunctionalInterface
public interface SelectionTimeoutScheduler {
    Cancellable schedule(UUID playerId, Duration delay, Runnable task);

    interface Cancellable {
        void cancel();

        boolean isCancelled();

        static Cancellable noop() {
            return new Cancellable() {
                @Override
                public void cancel() {
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }
            };
        }
    }

    static SelectionTimeoutScheduler rejecting() {
        return (playerId, delay, task) -> {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(delay, "delay");
            Objects.requireNonNull(task, "task");
            throw new IllegalStateException("selection timeout scheduler is not configured");
        };
    }
}

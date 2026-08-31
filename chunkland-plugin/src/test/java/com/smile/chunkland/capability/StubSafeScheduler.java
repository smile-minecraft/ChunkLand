package com.smile.chunkland.capability;

import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import com.smile.acelib.scheduler.TaskErrorRecord;
import com.smile.acelib.scheduler.TaskType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only {@link SafeScheduler} stand-in. Every dispatched task becomes a tracked entry in
 * the same append-only list; {@link #cancelAll()} flips all tracked entries to {@code cancelled}
 * and clears the list. The dispatcher methods are synchronous (no region/async execution) so
 * the smoke path is unit-testable without a live Folia server.
 */
final class StubSafeScheduler implements SafeScheduler {

    private final AtomicLong counter = new AtomicLong();
    private final AtomicLong cancelAllCount = new AtomicLong();
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final CopyOnWriteArrayList<TrackedTask> tracked = new CopyOnWriteArrayList<>();
    private final JavaPlugin plugin;

    StubSafeScheduler() {
        this(null);
    }

    StubSafeScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Number of {@link #cancelAll()} calls observed by this scheduler. */
    long cancelAllCount() {
        return cancelAllCount.get();
    }

    /** Number of live (uncancelled) tracked tasks. */
    int liveTaskCount() {
        int c = 0;
        for (TrackedTask t : tracked) {
            if (!t.cancelled.get()) {
                c++;
            }
        }
        return c;
    }

    int totalTracked() {
        return tracked.size();
    }

    /** @return a snapshot of the currently dispatched task records (for assertions). */
    List<TrackedTask> snapshot() {
        return List.copyOf(tracked);
    }

    @Override
    public ScheduledTask runGlobal(Runnable runnable) {
        return record(TaskType.GLOBAL, runnable);
    }

    @Override
    public ScheduledTask runAsync(Runnable runnable) {
        return record(TaskType.ASYNC, runnable);
    }

    @Override
    public ScheduledTask runLater(Runnable runnable, long delay) {
        return record(TaskType.LATER, runnable);
    }

    @Override
    public ScheduledTask runTimer(Runnable runnable, long delay, long period) {
        return record(TaskType.TIMER, runnable);
    }

    @Override
    public ScheduledTask runForPlayer(Player player, Runnable runnable) {
        return record(TaskType.PLAYER, runnable, player == null ? null : player.getUniqueId());
    }

    @Override
    public ScheduledTask runForPlayerLater(Player player, Runnable runnable, long delay) {
        return record(TaskType.PLAYER_LATER, runnable, player == null ? null : player.getUniqueId());
    }

    @Override
    public ScheduledTask runForEntity(org.bukkit.entity.Entity entity, Runnable runnable) {
        return record(TaskType.ENTITY, runnable);
    }

    @Override
    public ScheduledTask runAtLocation(Location location, Runnable runnable) {
        return record(TaskType.LOCATION, runnable, location);
    }

    @Override
    public List<TaskErrorRecord> getRecorderErrors(int limit) {
        return List.of();
    }

    @Override
    public void cancelAll() {
        cancelAllCount.incrementAndGet();
        for (TrackedTask t : tracked) {
            t.cancelled.set(true);
        }
        tracked.clear();
    }

    private ScheduledTask record(TaskType type, Runnable runnable, Object... context) {
        long creationTick = counter.incrementAndGet();
        TrackedTask task = new TrackedTask(type, runnable, creationTick, context);
        tracked.add(task);
        return task;
    }

    /** Visible for tests. */
    static final class TrackedTask implements ScheduledTask {
        private final TaskType type;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final long creationTick;
        @SuppressWarnings("unused")
        private final Object[] context;

        TrackedTask(TaskType type, Runnable runnable, long creationTick, Object[] context) {
            this.type = type;
            this.creationTick = creationTick;
            this.context = context;
        }

        public TaskType type() {
            return type;
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public JavaPlugin getPlugin() {
            return null;
        }

        @Override
        public TaskType getType() {
            return type;
        }

        @Override
        public long getCreationTick() {
            return creationTick;
        }
    }

    /** Convenience helpers for tests that need a JavaPlugin reference (not used here). */
    @SuppressWarnings("unused")
    private static JavaPlugin unusedPlugin() {
        return null;
    }
}

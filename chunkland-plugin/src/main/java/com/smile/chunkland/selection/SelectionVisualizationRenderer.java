package com.smile.chunkland.selection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Owns one render loop per operating player.
 *
 * <p>Server-independent state machine: each tick replans a frame from the
 * stored session snapshot with the live budget and emits one budget-capped
 * window through the player-scoped sink. The loop self-reschedules through
 * the player-scoped tick seam, so no global scheduler, executor, or background
 * thread exists here. Geometry never touches a Highest Block, block data, an
 * entity scan, or any Bukkit world/chunk/block object — see
 * {@link SelectionVisualizationGeometry}.
 *
 * <p>Lifecycle: {@code start}/{@code refresh} upsert the player's entry and
 * replace any previous task (no orphans); {@code stop} cancels and forgets.
 * Every tick carries the entry generation it was scheduled with, so a stale
 * tick can never emit or reschedule after a replacement or a stop. Backend
 * failures end that player's loop fail-closed; selection data is unaffected
 * (the session manager additionally stops the render defensively and keeps
 * the session).
 */
public final class SelectionVisualizationRenderer implements SelectionVisualizationTaskController {
    private final Object monitor = new Object();
    private final Map<UUID, Entry> loops = new LinkedHashMap<>();
    private final VisualizationTickScheduler ticks;
    private final SelectionParticleSink sink;
    private final Supplier<SelectionVisualizationBudget> budgets;

    public SelectionVisualizationRenderer(
            VisualizationTickScheduler ticks,
            SelectionParticleSink sink,
            Supplier<SelectionVisualizationBudget> budgets) {
        this.ticks = Objects.requireNonNull(ticks, "ticks");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.budgets = Objects.requireNonNull(budgets, "budgets");
    }

    @Override
    public void start(SelectionSession session) {
        Objects.requireNonNull(session, "session");
        render(session);
    }

    @Override
    public void refresh(SelectionSession session) {
        Objects.requireNonNull(session, "session");
        render(session);
    }

    @Override
    public void stop(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Entry removed;
        synchronized (monitor) {
            removed = loops.remove(playerId);
        }
        if (removed != null) {
            try {
                removed.handle.cancel();
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public boolean isActive(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (monitor) {
            return loops.containsKey(playerId);
        }
    }

    private void render(SelectionSession session) {
        UUID playerId = session.playerId();
        Entry previous;
        Entry next;
        synchronized (monitor) {
            previous = loops.remove(playerId);
            next = new Entry(session, previous == null ? 0L : previous.generation + 1L);
            loops.put(playerId, next);
        }
        if (previous != null) {
            try {
                previous.handle.cancel();
            } catch (RuntimeException ignored) {
            }
        }
        SelectionVisualizationBudget budget = requireBudget();
        SelectionTimeoutScheduler.Cancellable handle;
        try {
            handle = ticks.schedule(playerId, budget.refreshIntervalTicks(), () -> tick(playerId, next.generation));
        } catch (RuntimeException scheduleFailure) {
            synchronized (monitor) {
                loops.remove(playerId, next);
            }
            throw scheduleFailure;
        }
        synchronized (monitor) {
            if (loops.get(playerId) == next) {
                next.handle = handle;
            } else {
                try {
                    handle.cancel();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void tick(UUID playerId, long generation) {
        Entry entry;
        synchronized (monitor) {
            entry = loops.get(playerId);
            if (entry == null || entry.generation != generation) {
                return;
            }
        }
        SelectionParticleSink.ViewerPose viewer;
        try {
            viewer = sink.viewerOf(playerId).orElse(null);
        } catch (RuntimeException viewerFailure) {
            drop(playerId, generation);
            return;
        }
        if (viewer == null) {
            drop(playerId, generation);
            return;
        }
        SelectionVisualizationBudget budget;
        try {
            budget = requireBudget();
        } catch (RuntimeException budgetFailure) {
            drop(playerId, generation);
            return;
        }
        SelectionVisualizationGeometry.Frame frame;
        try {
            frame = SelectionVisualizationGeometry.plan(
                    entry.session, budget, viewer.x(), viewer.y(), viewer.z());
        } catch (RuntimeException planFailure) {
            drop(playerId, generation);
            return;
        }
        List<SelectionVisualizationGeometry.Point> window;
        try {
            window = frame.window(entry.tickIndex, budget.maxParticlesPerTick());
        } catch (RuntimeException windowFailure) {
            drop(playerId, generation);
            return;
        }
        try {
            for (SelectionVisualizationGeometry.Point point : window) {
                sink.emit(playerId, point.x(), point.y(), point.z());
            }
        } catch (RuntimeException emitFailure) {
            drop(playerId, generation);
            return;
        }
        long nextTick;
        try {
            nextTick = Math.addExact(entry.tickIndex, 1L);
        } catch (ArithmeticException overflow) {
            nextTick = 0L;
        }
        SelectionTimeoutScheduler.Cancellable handle;
        try {
            long scheduledTick = nextTick;
            handle = ticks.schedule(playerId, budget.refreshIntervalTicks(), () -> tick(playerId, generation));
            synchronized (monitor) {
                Entry current = loops.get(playerId);
                if (current == entry && current.generation == generation) {
                    entry.tickIndex = scheduledTick;
                    try {
                        entry.handle.cancel();
                    } catch (RuntimeException ignored) {
                    }
                    entry.handle = handle;
                    return;
                }
            }
            try {
                handle.cancel();
            } catch (RuntimeException ignored) {
            }
        } catch (RuntimeException rescheduleFailure) {
            drop(playerId, generation);
        }
    }

    private void drop(UUID playerId, long generation) {
        Entry removed;
        synchronized (monitor) {
            Entry current = loops.get(playerId);
            if (current == null || current.generation != generation) {
                return;
            }
            removed = loops.remove(playerId);
        }
        if (removed != null) {
            try {
                removed.handle.cancel();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private SelectionVisualizationBudget requireBudget() {
        SelectionVisualizationBudget budget = budgets.get();
        return Objects.requireNonNull(budget, "visualization budget supplier returned null");
    }

    private static final class Entry {
        final SelectionSession session;
        final long generation;
        long tickIndex;
        SelectionTimeoutScheduler.Cancellable handle = SelectionTimeoutScheduler.Cancellable.noop();

        Entry(SelectionSession session, long generation) {
            this.session = session;
            this.generation = generation;
        }
    }
}

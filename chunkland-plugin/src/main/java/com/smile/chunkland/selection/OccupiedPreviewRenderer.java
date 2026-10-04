package com.smile.chunkland.selection;

import com.smile.chunkland.api.land.ChunkKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Renders an occupied land's boundary as its own player-scoped loop.
 *
 * <p>Structurally mirrors {@link SelectionVisualizationRenderer} but renders an
 * explicit immutable chunk set instead of a {@link SelectionSession}, so it can
 * run alongside the active selection without replacing its loop and without
 * writing any selection state. Planning reuses
 * {@link SelectionVisualizationGeometry} (chunk math plus
 * {@code BoundaryExtractor}) and therefore honours the same live budget:
 * {@code maxSegments}, {@code maxParticlesPerTick}, {@code renderDistanceBlocks}
 * and {@code refreshIntervalTicks}. The emission goes through a distinct sink
 * so the preview colour never mixes with the selection colour.
 *
 * <p>No Bukkit world/chunk/block object is read here; only the injected
 * player-scoped tick seam and sink are called.
 */
public final class OccupiedPreviewRenderer implements OccupiedPreviewController {
    private final Object monitor = new Object();
    private final Map<UUID, Entry> loops = new LinkedHashMap<>();
    private final VisualizationTickScheduler ticks;
    private final SelectionParticleSink sink;
    private final Supplier<SelectionVisualizationBudget> budgets;

    public OccupiedPreviewRenderer(
            VisualizationTickScheduler ticks,
            SelectionParticleSink sink,
            Supplier<SelectionVisualizationBudget> budgets) {
        this.ticks = Objects.requireNonNull(ticks, "ticks");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.budgets = Objects.requireNonNull(budgets, "budgets");
    }

    @Override
    public void show(UUID playerId, Set<ChunkKey> chunks, double planeY) {
        show(playerId, chunks, planeY, SelectionPreviewColor.BLOCKED);
    }

    @Override
    public void show(UUID playerId, Set<ChunkKey> chunks, double planeY, SelectionPreviewColor color) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(color, "color");
        if (chunks.isEmpty()) {
            stop(playerId);
            return;
        }
        Entry previous;
        Entry next;
        synchronized (monitor) {
            previous = loops.remove(playerId);
            next = new Entry(Set.copyOf(chunks), planeY, color,
                    previous == null ? 0L : previous.generation + 1L);
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
    public void stopAll() {
        List<Entry> removed;
        synchronized (monitor) {
            removed = new ArrayList<>(loops.values());
            loops.clear();
        }
        for (Entry entry : removed) {
            try {
                entry.handle.cancel();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Whether a preview loop is currently tracked for the player. */
    public boolean isActive(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (monitor) {
            return loops.containsKey(playerId);
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
                    entry.chunks, entry.planeY, budget, viewer.x(), viewer.z());
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
                sink.emit(playerId, point.x(), point.y(), point.z(), entry.color);
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
        final Set<ChunkKey> chunks;
        final double planeY;
        final SelectionPreviewColor color;
        final long generation;
        long tickIndex;
        SelectionTimeoutScheduler.Cancellable handle = SelectionTimeoutScheduler.Cancellable.noop();

        Entry(Set<ChunkKey> chunks, double planeY, SelectionPreviewColor color, long generation) {
            this.chunks = chunks;
            this.planeY = planeY;
            this.color = color;
            this.generation = generation;
        }
    }
}

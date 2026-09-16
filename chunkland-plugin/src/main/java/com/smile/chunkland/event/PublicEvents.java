package com.smile.chunkland.event;

import com.smile.chunkland.api.event.ChunkLandCancellable;
import com.smile.chunkland.api.event.ChunkLandEventBus;
import com.smile.chunkland.api.event.NoopChunkLandEventBus;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

/**
 * Single seam that fires one public event on both the API bus and the Bukkit
 * bridge, merging cancellation.
 *
 * <p>Pre ({@link #firePre}): publishes the API event, then calls the Bukkit
 * view, both synchronously on the caller's thread and before any Economy or
 * durable side effect. The result is the OR of both vetoes. Any failure —
 * a throwing API listener (already fail-closed by the bus), a throwing view
 * factory, a throwing Bukkit caller — fails closed to cancelled with zero
 * mutation side effects.
 *
 * <p>Post ({@link #firePost}): publishes the API event, then calls the
 * Bukkit view, both after the durable commit plus the runtime publish. Every
 * failure is caught, logged and isolated; neither method ever throws, and a
 * Post failure never rolls back the committed mutation.
 *
 * <p>The Bukkit view is a {@code Supplier<?>} (rather than a typed {@code
 * Supplier<Event>}) so mutation packages can build views without importing
 * Bukkit types themselves: only this facade and the {@code event.bukkit}
 * package touch {@code org.bukkit}.
 */
public final class PublicEvents {

    private static final Logger LOG = Logger.getLogger(PublicEvents.class.getName());

    private final ChunkLandEventBus bus;
    private final BukkitEventCaller caller;

    private PublicEvents(ChunkLandEventBus bus, BukkitEventCaller caller) {
        this.bus = bus;
        this.caller = caller;
    }

    /** Facade that never vetoes and never calls Bukkit (views are not built). */
    public static PublicEvents noop() {
        return new PublicEvents(NoopChunkLandEventBus.instance(), null);
    }

    /**
     * @param bus API bus; {@code null} means the no-op bus
     * @param caller Bukkit dispatch; {@code null} disables the Bukkit bridge
     */
    public static PublicEvents create(ChunkLandEventBus bus, BukkitEventCaller caller) {
        return new PublicEvents(bus == null ? NoopChunkLandEventBus.instance() : bus, caller);
    }

    /** The API bus behind this facade. */
    public ChunkLandEventBus bus() {
        return bus;
    }

    /**
     * Fire one Pre event on both channels and merge the vetoes.
     *
     * @param apiEvent cancellable API event, published first
     * @param bukkitView factory for the Bukkit view; {@code null} skips Bukkit
     * @return {@code true} when any listener vetoed or any dispatch step failed
     */
    public boolean firePre(ChunkLandCancellable apiEvent, Supplier<?> bukkitView) {
        Objects.requireNonNull(apiEvent, "apiEvent");
        try {
            bus.publish(apiEvent);
        } catch (Throwable failure) {
            cancelQuietly(apiEvent);
            LOG.log(Level.WARNING, "ChunkLand Pre bus dispatch failed; failing closed", failure);
            return true;
        }
        if (apiEvent.isCancelled()) {
            callBukkitBestEffort(bukkitView);
            return true;
        }
        if (bukkitView == null || caller == null) {
            return false;
        }
        Object view;
        try {
            view = bukkitView.get();
        } catch (Throwable failure) {
            cancelQuietly(apiEvent);
            LOG.log(Level.WARNING, "ChunkLand Pre Bukkit view failed; failing closed", failure);
            return true;
        }
        if (!(view instanceof Event bukkit)) {
            return apiEvent.isCancelled();
        }
        try {
            caller.call(bukkit);
        } catch (Throwable failure) {
            cancelQuietly(apiEvent);
            LOG.log(Level.WARNING, "ChunkLand Pre Bukkit dispatch failed; failing closed", failure);
            return true;
        }
        if (bukkit instanceof Cancellable cancellable) {
            boolean veto;
            try {
                veto = cancellable.isCancelled();
            } catch (Throwable failure) {
                cancelQuietly(apiEvent);
                LOG.log(Level.WARNING, "ChunkLand Pre veto read failed; failing closed", failure);
                return true;
            }
            if (veto) {
                cancelQuietly(apiEvent);
                return true;
            }
        }
        return apiEvent.isCancelled();
    }

    /**
     * Fire one Post event on both channels. Never throws: every failure is
     * caught, logged and isolated.
     *
     * @param apiEvent API event, published first
     * @param bukkitView factory for the Bukkit view; {@code null} skips Bukkit
     */
    public void firePost(Object apiEvent, Supplier<?> bukkitView) {
        if (apiEvent == null) {
            return;
        }
        try {
            bus.publish(apiEvent);
        } catch (Throwable failure) {
            LOG.log(Level.WARNING, "ChunkLand Post bus dispatch failed; isolated", failure);
        }
        callBukkitBestEffort(bukkitView);
    }

    private void callBukkitBestEffort(Supplier<?> bukkitView) {
        if (bukkitView == null || caller == null) {
            return;
        }
        Object view;
        try {
            view = bukkitView.get();
        } catch (Throwable failure) {
            LOG.log(Level.WARNING, "ChunkLand Post Bukkit view failed; isolated", failure);
            return;
        }
        if (!(view instanceof Event bukkit)) {
            return;
        }
        try {
            caller.call(bukkit);
        } catch (Throwable failure) {
            LOG.log(Level.WARNING, "ChunkLand Post Bukkit dispatch failed; isolated", failure);
        }
    }

    private static void cancelQuietly(ChunkLandCancellable event) {
        try {
            event.setCancelled(true);
        } catch (Throwable ignored) {
            // The fail-closed mark itself must never break the mutation path.
        }
    }
}

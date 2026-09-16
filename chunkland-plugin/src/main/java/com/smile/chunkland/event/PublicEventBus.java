package com.smile.chunkland.event;

import com.smile.chunkland.api.event.ChunkLandCancellable;
import com.smile.chunkland.api.event.ChunkLandEventBus;
import com.smile.chunkland.api.event.ChunkLandEventListener;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Synchronous in-process {@link ChunkLandEventBus}.
 *
 * <p>Dispatch runs entirely on the publishing thread: no executor, no
 * blocking wait, no I/O. A throwing Pre listener fails the mutation closed
 * by marking the Pre cancelled; a throwing Post listener is logged and
 * skipped while the remaining listeners still run, so one broken listener
 * can never roll back a committed mutation or starve the rest. Listener
 * lists are copy-on-write, so register/unregister racing a publish never
 * throws and never delivers partially.
 */
public final class PublicEventBus implements ChunkLandEventBus {

    private static final Logger LOG = Logger.getLogger(PublicEventBus.class.getName());

    private final Map<Class<?>, CopyOnWriteArrayList<ChunkLandEventListener<?>>> listeners =
            new ConcurrentHashMap<>();

    @Override
    public <E> void register(Class<E> eventType, ChunkLandEventListener<? super E> listener) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
        listeners.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>())
                .add(listener);
    }

    @Override
    public <E> void unregister(Class<E> eventType, ChunkLandEventListener<? super E> listener) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
        List<ChunkLandEventListener<?>> registered = listeners.get(eventType);
        if (registered != null) {
            registered.remove(listener);
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <E> void publish(E event) {
        Objects.requireNonNull(event, "event");
        for (Map.Entry<Class<?>, CopyOnWriteArrayList<ChunkLandEventListener<?>>> entry
                : listeners.entrySet()) {
            if (!entry.getKey().isInstance(event)) {
                continue;
            }
            for (ChunkLandEventListener<?> listener : entry.getValue()) {
                try {
                    ((ChunkLandEventListener) listener).onEvent(event);
                } catch (Throwable failure) {
                    if (event instanceof ChunkLandCancellable cancellable) {
                        try {
                            cancellable.setCancelled(true);
                        } catch (Throwable ignored) {
                            // The fail-closed mark itself must never break dispatch.
                        }
                    }
                    LOG.log(Level.WARNING,
                            "ChunkLand public event listener failed; "
                                    + "Pre is fail-closed cancelled, Post is isolated",
                            failure);
                }
            }
        }
    }
}

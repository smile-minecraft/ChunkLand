package com.smile.chunkland.api.event;

import java.util.Objects;

/**
 * Dependency-free {@link ChunkLandEventBus} that delivers to nobody.
 *
 * <p>Used as the default wiring wherever no listener is attached: publishing
 * never throws, never blocks and never marks a Pre event cancelled.
 */
public enum NoopChunkLandEventBus implements ChunkLandEventBus {

    /** Single shared instance. */
    INSTANCE;

    /** The shared no-op bus. */
    public static ChunkLandEventBus instance() {
        return INSTANCE;
    }

    @Override
    public <E> void register(Class<E> eventType, ChunkLandEventListener<? super E> listener) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
    }

    @Override
    public <E> void unregister(Class<E> eventType, ChunkLandEventListener<? super E> listener) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
    }

    @Override
    public <E> void publish(E event) {
        Objects.requireNonNull(event, "event");
    }
}

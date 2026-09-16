package com.smile.chunkland.api.event;

/**
 * Synchronous listener for one public event type.
 *
 * <p>Thread contract: invoked synchronously on the publisher's thread. Pre
 * listeners run before any Economy or durable side effect and must return
 * immediately without blocking, I/O, waiting, or region-thread-unsafe world
 * access. Post listeners run after the durable commit plus the runtime
 * snapshot publish; an exception thrown here is caught, logged and isolated
 * by the dispatcher and never rolls back the committed mutation.
 *
 * @param <E> event type observed
 */
@FunctionalInterface
public interface ChunkLandEventListener<E> {

    /** Observe one published event. */
    void onEvent(E event);
}

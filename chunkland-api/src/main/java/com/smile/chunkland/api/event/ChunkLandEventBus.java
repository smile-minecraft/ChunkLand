package com.smile.chunkland.api.event;

/**
 * Synchronous in-process dispatch for ChunkLand public events.
 *
 * <p>Thread contract: {@link #publish} delivers to every matching listener
 * synchronously on the calling thread and returns only after all listeners
 * ran. Callers publish Pre events on the mutation thread before any Economy
 * or durable side effect, and Post events after the durable commit plus the
 * runtime snapshot publish. Listener implementations must obey the contract
 * on {@link ChunkLandEventListener}: no blocking, no I/O, no waiting, and no
 * Bukkit/Paper world access outside the matching Folia thread.
 *
 * <p>Failure contract: a throwing Pre listener fails the mutation closed —
 * the dispatcher marks the Pre cancelled and the mutation produces zero side
 * effects. A throwing Post listener is caught, logged and isolated; the
 * committed mutation still stands.
 */
public interface ChunkLandEventBus {

    /**
     * Observe every future event that is an instance of {@code eventType}.
     */
    <E> void register(Class<E> eventType, ChunkLandEventListener<? super E> listener);

    /** Stop observing {@code eventType}. */
    <E> void unregister(Class<E> eventType, ChunkLandEventListener<? super E> listener);

    /**
     * Deliver {@code event} to every matching listener synchronously on the
     * calling thread.
     */
    <E> void publish(E event);
}

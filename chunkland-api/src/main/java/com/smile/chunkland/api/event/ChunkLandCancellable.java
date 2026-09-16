package com.smile.chunkland.api.event;

/**
 * Marker for synchronous, cancellable Pre events.
 *
 * <p>Thread contract: a Pre event is published and observed synchronously on
 * the mutation caller's thread, strictly before any Economy charge/refund or
 * durable write. Listeners must not block, must not perform I/O, must not
 * wait on other threads, and must not touch Bukkit/Paper APIs unless they are
 * already on the matching Folia region/entity thread. A listener that throws
 * fails the mutation closed: the dispatcher treats the Pre as cancelled and
 * the mutation produces zero side effects.
 */
public interface ChunkLandCancellable {

    /** Whether a listener vetoed the pending mutation. */
    boolean isCancelled();

    /** Veto (or un-veto) the pending mutation. */
    void setCancelled(boolean cancelled);
}

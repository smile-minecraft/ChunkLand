package com.smile.chunkland.event.bukkit;

import org.bukkit.event.Event;

/**
 * Base type for the Bukkit views of ChunkLand public events.
 *
 * <p>Each concrete subclass carries its own {@code HandlerList} so external
 * plugins listen by type. The {@code async} flag mirrors the dispatch
 * thread: Pre events fired on the mutation caller thread and movement-thread
 * transitions are synchronous; Post events fired on the async continuation
 * after the durable commit plus the runtime publish are async.
 *
 * <p>Listener rules (mirroring the API events): never block, never perform
 * I/O, never wait, and never touch world state outside the matching Folia
 * thread. Cancellable Pre views veto the pending mutation with zero side
 * effects; a throwing listener fails the Pre closed and is isolated on Post.
 */
public abstract class ChunkLandBukkitEvent extends Event {

    protected ChunkLandBukkitEvent(boolean async) {
        super(async);
    }
}

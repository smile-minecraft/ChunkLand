package com.smile.chunkland.event.bukkit;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Bukkit view of {@code LandChunkRemovePostEvent}: the shrink committed, a
 * confirmed successful refund settled, and the runtime published. Failed or
 * unconfirmed refunds park for compensation and never fire this view —
 * unlike the delete/create/expand post views, which fire before the refund
 * or final ledger marking is attempted. Fired async; listeners must not
 * block, must not perform I/O, and must hop to the matching Folia thread
 * before touching world state. Throwing listeners are isolated and never
 * roll back the shrink.
 */
public final class LandChunkRemovePostBukkitEvent extends ChunkLandBukkitEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LandId landId;
    private final UUID actorUuid;
    private final Set<ChunkKey> removedChunks;
    private final long refundMinorUnits;

    /** @param async mirrors the dispatch thread (see {@link ChunkLandBukkitEvent}) */
    public LandChunkRemovePostBukkitEvent(LandId landId, UUID actorUuid,
            Set<ChunkKey> removedChunks, long refundMinorUnits, boolean async) {
        super(async);
        this.landId = Objects.requireNonNull(landId, "landId");
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(removedChunks, "removedChunks");
        this.removedChunks = Set.copyOf(removedChunks);
        this.refundMinorUnits = refundMinorUnits;
    }

    /** Shrunk land. */
    public LandId landId() {
        return landId;
    }

    /** Shrinking player (or console actor). */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** Immutable snapshot of the committed delta. */
    public Set<ChunkKey> removedChunks() {
        return removedChunks;
    }

    /** Refund derived from the durable cost basis. */
    public long refundMinorUnits() {
        return refundMinorUnits;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** Bukkit handler list for this event type. */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

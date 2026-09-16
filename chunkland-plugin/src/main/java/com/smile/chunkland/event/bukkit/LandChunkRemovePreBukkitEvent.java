package com.smile.chunkland.event.bukkit;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * Bukkit view of {@code LandChunkRemovePreEvent}: vetoes the pending shrink
 * with zero side effects. Fired synchronously before reservations, the ledger
 * row, the domain commit, and any Economy refund.
 */
public final class LandChunkRemovePreBukkitEvent extends ChunkLandBukkitEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID actorUuid;
    private final UUID worldId;
    private final LandId targetLandId;
    private final Set<ChunkKey> chunks;
    private boolean cancelled;

    /** @param async mirrors the dispatch thread (see {@link ChunkLandBukkitEvent}) */
    public LandChunkRemovePreBukkitEvent(UUID actorUuid, UUID worldId, LandId targetLandId,
            Set<ChunkKey> chunks, boolean async) {
        super(async);
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.targetLandId = Objects.requireNonNull(targetLandId, "targetLandId");
        Objects.requireNonNull(chunks, "chunks");
        this.chunks = Set.copyOf(chunks);
    }

    /** Shrinking player (or console actor). */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** World the land lives in. */
    public UUID worldId() {
        return worldId;
    }

    /** Land that would shrink. */
    public LandId targetLandId() {
        return targetLandId;
    }

    /** Immutable snapshot of the validated delta. */
    public Set<ChunkKey> chunks() {
        return chunks;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
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

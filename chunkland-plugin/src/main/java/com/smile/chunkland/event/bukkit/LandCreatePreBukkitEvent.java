package com.smile.chunkland.event.bukkit;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * Bukkit view of {@code LandCreatePreEvent}: vetoes the pending claim with
 * zero side effects. Fired synchronously before quota reservations, the
 * ledger row, and any Economy charge.
 */
public final class LandCreatePreBukkitEvent extends ChunkLandBukkitEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID actorUuid;
    private final UUID worldId;
    private final OwnerRef owner;
    private final Set<ChunkKey> chunks;
    private final String displayName;
    private boolean cancelled;

    /** @param async mirrors the dispatch thread (see {@link ChunkLandBukkitEvent}) */
    public LandCreatePreBukkitEvent(UUID actorUuid, UUID worldId, OwnerRef owner,
            Set<ChunkKey> chunks, String displayName, boolean async) {
        super(async);
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.owner = Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(displayName, "displayName");
        this.chunks = Set.copyOf(chunks);
        this.displayName = displayName;
    }

    /** Claiming player (or console actor). */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** World the claim would land in. */
    public UUID worldId() {
        return worldId;
    }

    /** Durable owner the land would belong to. */
    public OwnerRef owner() {
        return owner;
    }

    /** Immutable snapshot of the validated chunks. */
    public Set<ChunkKey> chunks() {
        return chunks;
    }

    /** Requested land name. */
    public String displayName() {
        return displayName;
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

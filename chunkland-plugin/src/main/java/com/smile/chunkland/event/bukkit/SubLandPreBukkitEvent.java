package com.smile.chunkland.event.bukkit;

import com.smile.chunkland.api.event.SubLandPreEvent.Operation;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * Bukkit view of {@code SubLandPreEvent}: vetoes the pending SubLand
 * create, update or delete with zero side effects. Fired synchronously after
 * the confirmation is accepted but before the durable atomic commit.
 */
public final class SubLandPreBukkitEvent extends ChunkLandBukkitEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID actorUuid;
    private final LandId parentLandId;
    private final SubLandId subLandId;
    private final Operation operation;
    private boolean cancelled;

    /** @param async mirrors the dispatch thread (see {@link ChunkLandBukkitEvent}) */
    public SubLandPreBukkitEvent(UUID actorUuid, LandId parentLandId, SubLandId subLandId,
            Operation operation, boolean async) {
        super(async);
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        this.parentLandId = Objects.requireNonNull(parentLandId, "parentLandId");
        this.subLandId = Objects.requireNonNull(subLandId, "subLandId");
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    /** Confirming player. */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** Parent land of the mutation. */
    public LandId parentLandId() {
        return parentLandId;
    }

    /** Affected SubLand (the candidate id for creates). */
    public SubLandId subLandId() {
        return subLandId;
    }

    /** Which mutation is pending. */
    public Operation operation() {
        return operation;
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

package com.smile.chunkland.event.bukkit;

import com.smile.chunkland.api.event.SubLandPreEvent.Operation;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Bukkit view of {@code SubLandPostEvent}: the SubLand mutation committed
 * and the runtime published. Fired async; listeners must not block, must not
 * perform I/O, and must hop to the matching Folia thread before touching
 * world state. Throwing listeners are isolated and never roll back the
 * mutation.
 */
public final class SubLandPostBukkitEvent extends ChunkLandBukkitEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID actorUuid;
    private final LandId parentLandId;
    private final SubLandId subLandId;
    private final Operation operation;

    /** @param async mirrors the dispatch thread (see {@link ChunkLandBukkitEvent}) */
    public SubLandPostBukkitEvent(UUID actorUuid, LandId parentLandId, SubLandId subLandId,
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

    /** Affected SubLand. */
    public SubLandId subLandId() {
        return subLandId;
    }

    /** Which mutation committed. */
    public Operation operation() {
        return operation;
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

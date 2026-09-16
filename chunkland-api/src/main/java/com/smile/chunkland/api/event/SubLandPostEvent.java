package com.smile.chunkland.api.event;

import com.smile.chunkland.api.event.SubLandPreEvent.Operation;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import java.util.Objects;
import java.util.UUID;

/**
 * Post event for a committed SubLand create, update or delete.
 *
 * <p>Fired exactly once, after the durable atomic commit plus the runtime
 * publish both succeeded. Fires on the async continuation thread, never on a
 * Folia region thread: listeners must not block, must not perform I/O, must
 * not wait, and must hop through the player scheduler before touching any
 * Bukkit/Paper player or world state. A throwing listener is caught, logged
 * and isolated — the committed SubLand mutation still stands and is never
 * rolled back. A publish failure degrades without firing: the retry runs
 * through {@code rebuildRuntime}, which never re-fires this event.
 */
public final class SubLandPostEvent {

    private final UUID actorUuid;
    private final LandId parentLandId;
    private final SubLandId subLandId;
    private final Operation operation;

    /**
     * @param actorUuid confirming player
     * @param parentLandId parent land of the mutation
     * @param subLandId affected SubLand
     * @param operation which mutation committed
     */
    public SubLandPostEvent(UUID actorUuid, LandId parentLandId, SubLandId subLandId,
            Operation operation) {
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
}

package com.smile.chunkland.api.event;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import java.util.Objects;
import java.util.UUID;

/**
 * Cancellable Pre event for a SubLand create, update or delete.
 *
 * <p>Fired synchronously on the mutation caller's thread after the
 * confirmation triple is accepted but before the durable atomic commit and
 * the runtime publish. Cancelling (or a throwing listener, which fails
 * closed to cancelled) aborts the mutation with zero side effects: no
 * SubLand row write, no parent revision bump, no audit row, and the
 * confirmation mark is released so the operator can retry.
 *
 * <p>Thread contract: same-thread, synchronous dispatch. Listeners must not
 * block, must not perform I/O, must not wait, and must not touch Bukkit/Paper
 * world state unless already on the matching Folia thread.
 */
public final class SubLandPreEvent implements ChunkLandCancellable {

    /** Which SubLand mutation is pending. */
    public enum Operation {
        CREATE,
        UPDATE,
        DELETE
    }

    private final UUID actorUuid;
    private final LandId parentLandId;
    private final SubLandId subLandId;
    private final Operation operation;
    private volatile boolean cancelled;

    /**
     * @param actorUuid confirming player
     * @param parentLandId parent land of the mutation
     * @param subLandId affected SubLand (the candidate id for creates)
     * @param operation which mutation is pending
     */
    public SubLandPreEvent(UUID actorUuid, LandId parentLandId, SubLandId subLandId,
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
}

package com.smile.chunkland.event.bukkit;

import com.smile.chunkland.api.event.PermissionChangedEvent.ChangeKind;
import com.smile.chunkland.api.event.PermissionChangedEvent.SubjectKind;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Bukkit view of {@code PermissionChangedEvent}: a durable permission
 * mutation committed and published. Fired async; listeners must not block,
 * must not perform I/O, and must hop to the matching Folia thread before
 * touching world state. Throwing listeners are isolated and never roll back
 * the committed permission.
 */
public final class PermissionChangedBukkitEvent extends ChunkLandBukkitEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID actorUuid;
    private final LandId landId;
    private final SubLandId subLandId;
    private final ChangeKind kind;
    private final SubjectKind subjectKind;
    private final UUID subjectId;
    private final ProtectionActionType action;
    private final PermissionState state;
    private final UUID profileId;

    /** @param async mirrors the dispatch thread (see {@link ChunkLandBukkitEvent}) */
    public PermissionChangedBukkitEvent(UUID actorUuid, LandId landId, SubLandId subLandId,
            ChangeKind kind, SubjectKind subjectKind, UUID subjectId,
            ProtectionActionType action, PermissionState state, UUID profileId, boolean async) {
        super(async);
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        this.kind = Objects.requireNonNull(kind, "kind");
        if ((landId == null) == (subLandId == null)) {
            throw new IllegalArgumentException("exactly one of landId/subLandId must be set");
        }
        this.landId = landId;
        this.subLandId = subLandId;
        this.subjectKind = subjectKind;
        this.subjectId = subjectId;
        this.action = action;
        this.state = state;
        this.profileId = profileId;
    }

    /** Mutating player (or console actor). */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** Affected land, or {@code null} when sub-land scoped. */
    public LandId landId() {
        return landId;
    }

    /** Affected SubLand, or {@code null} when land scoped. */
    public SubLandId subLandId() {
        return subLandId;
    }

    /** Which mutation committed. */
    public ChangeKind kind() {
        return kind;
    }

    /** Canonical subject dimension, or {@code null} for subject-free changes. */
    public SubjectKind subjectKind() {
        return subjectKind;
    }

    /** Canonical subject identity, or {@code null} for subject-free changes. */
    public UUID subjectId() {
        return subjectId;
    }

    /** Affected action, or {@code null} when not action-scoped. */
    public ProtectionActionType action() {
        return action;
    }

    /** Resulting state, or {@code null} when not state-scoped. */
    public PermissionState state() {
        return state;
    }

    /** Bound profile, or {@code null} when no profile is involved. */
    public UUID profileId() {
        return profileId;
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

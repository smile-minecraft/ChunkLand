package com.smile.chunkland.api.event;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.Objects;
import java.util.UUID;

/**
 * A durable permission mutation committed and published.
 *
 * <p>Fired exactly once per mutation, only after the durable commit plus the
 * runtime snapshot publish both succeeded — never on command parse, never on
 * validation failure, and never on a failed commit. Exactly one of {@code
 * landId} / {@code subLandId} is non-null. The subject identity is the
 * canonical UUID only (player or group); no names, no wildcards, no
 * credentials. Fires on the async continuation thread, never on a Folia
 * region thread: listeners must not block, must not perform I/O, must not
 * wait, and must hop through the player scheduler before touching any
 * Bukkit/Paper player or world state. A throwing listener is caught, logged
 * and isolated — the committed permission still stands and is never rolled
 * back.
 */
public final class PermissionChangedEvent {

    /** Which durable permission mutation committed. */
    public enum ChangeKind {
        TRUST,
        UNTRUST,
        DEFAULT_SET,
        BAN,
        UNBAN,
        BIND,
        UNBIND
    }

    /** Canonical subject dimension; identity only, never credentials. */
    public enum SubjectKind {
        PLAYER,
        GROUP
    }

    private final UUID actorUuid;
    private final LandId landId;
    private final SubLandId subLandId;
    private final ChangeKind kind;
    private final SubjectKind subjectKind;
    private final UUID subjectId;
    private final ProtectionActionType action;
    private final PermissionState state;
    private final UUID profileId;

    /**
     * @param actorUuid mutating player (or console actor)
     * @param landId affected land, or {@code null} when sub-land scoped
     * @param subLandId affected SubLand, or {@code null} when land scoped
     * @param kind which mutation committed
     * @param subjectKind canonical subject dimension, or {@code null} for
     *        subject-free changes (land defaults)
     * @param subjectId canonical subject identity, or {@code null} for
     *        subject-free changes
     * @param action affected action, or {@code null} when not action-scoped
     * @param state resulting state, or {@code null} when not state-scoped
     * @param profileId bound profile, or {@code null} when no profile is involved
     */
    public PermissionChangedEvent(UUID actorUuid, LandId landId, SubLandId subLandId,
            ChangeKind kind, SubjectKind subjectKind, UUID subjectId,
            ProtectionActionType action, PermissionState state, UUID profileId) {
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
}

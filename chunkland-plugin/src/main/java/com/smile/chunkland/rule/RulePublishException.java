package com.smile.chunkland.rule;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import java.util.Objects;
import java.util.UUID;

/**
 * A per-land rule write committed durably but the snapshot publish after it
 * failed, so the committed value is not live yet.
 *
 * <p>Callers must tell this apart from a write that never happened: the
 * durable row stands (with the {@link #before} to {@link #after} transition
 * below) and only the runtime publish is missing, so a retry must refresh
 * rather than resubmit the write. No public event fired for the mutation.
 */
public final class RulePublishException extends IllegalStateException {

    private final UUID actorUuid;
    private final LandId landId;
    private final LandRuleType rule;
    private final PermissionState before;
    private final PermissionState after;

    public RulePublishException(UUID actorUuid, LandId landId, LandRuleType rule,
            PermissionState before, PermissionState after, Throwable cause) {
        super("rule for " + rule + " on land " + landId
                + " committed (" + before + " -> " + after + ")"
                + " but the snapshot publish failed;"
                + " the durable value stands and is not live yet", cause);
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        this.landId = Objects.requireNonNull(landId, "landId");
        this.rule = Objects.requireNonNull(rule, "rule");
        this.before = Objects.requireNonNull(before, "before");
        this.after = Objects.requireNonNull(after, "after");
    }

    /** Mutating player (or console actor). */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** Affected land. */
    public LandId landId() {
        return landId;
    }

    /** Environment rule that was written. */
    public LandRuleType rule() {
        return rule;
    }

    /** Durable rule before the committed write ({@code INHERIT} when none existed). */
    public PermissionState before() {
        return before;
    }

    /** Requested state ({@code INHERIT} when the override was removed). */
    public PermissionState after() {
        return after;
    }
}

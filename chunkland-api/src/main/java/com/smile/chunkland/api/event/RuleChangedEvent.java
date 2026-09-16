package com.smile.chunkland.api.event;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import java.util.Objects;
import java.util.UUID;

/**
 * A durable per-land environment rule mutation committed and published.
 *
 * <p>Fired exactly once per mutation, only after the durable commit plus the
 * runtime snapshot publish both succeeded — never on command parse, never on
 * validation failure, and never on a failed commit. Fires on the async
 * continuation thread, never on a Folia region thread: listeners must not
 * block, must not perform I/O, must not wait, and must hop through the player
 * scheduler before touching any Bukkit/Paper player or world state. A
 * throwing listener is caught, logged and isolated — the committed rule
 * still stands and is never rolled back.
 */
public final class RuleChangedEvent {

    private final UUID actorUuid;
    private final LandId landId;
    private final LandRuleType rule;
    private final PermissionState previous;
    private final PermissionState current;

    /**
     * @param actorUuid mutating player (or console actor)
     * @param landId affected land
     * @param rule environment rule that changed
     * @param previous previously effective state, or {@code null} when unset
     * @param current newly effective state
     */
    public RuleChangedEvent(UUID actorUuid, LandId landId, LandRuleType rule,
            PermissionState previous, PermissionState current) {
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        this.landId = Objects.requireNonNull(landId, "landId");
        this.rule = Objects.requireNonNull(rule, "rule");
        this.current = Objects.requireNonNull(current, "current");
        this.previous = previous;
    }

    /** Mutating player (or console actor). */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** Affected land. */
    public LandId landId() {
        return landId;
    }

    /** Environment rule that changed. */
    public LandRuleType rule() {
        return rule;
    }

    /** Previously effective state, or {@code null} when unset. */
    public PermissionState previous() {
        return previous;
    }

    /** Newly effective state. */
    public PermissionState current() {
        return current;
    }
}

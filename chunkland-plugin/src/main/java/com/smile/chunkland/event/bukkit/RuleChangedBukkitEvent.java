package com.smile.chunkland.event.bukkit;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Bukkit view of {@code RuleChangedEvent}: a durable per-land environment
 * rule committed and published. Fired async; listeners must not block, must
 * not perform I/O, and must hop to the matching Folia thread before touching
 * world state. Throwing listeners are isolated and never roll back the
 * committed rule.
 */
public final class RuleChangedBukkitEvent extends ChunkLandBukkitEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID actorUuid;
    private final LandId landId;
    private final LandRuleType rule;
    private final PermissionState previous;
    private final PermissionState current;

    /** @param async mirrors the dispatch thread (see {@link ChunkLandBukkitEvent}) */
    public RuleChangedBukkitEvent(UUID actorUuid, LandId landId, LandRuleType rule,
            PermissionState previous, PermissionState current, boolean async) {
        super(async);
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

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** Bukkit handler list for this event type. */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

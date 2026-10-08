package com.smile.chunkland.api;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Read API entry point.
 *
 * <p>Contract:
 * <ul>
 *   <li>Callable from any thread.</li>
 *   <li>Performs no I/O and never loads a chunk.</li>
 *   <li>Never returns a mutable domain collection; all returned types are
 *       immutable snapshots / value objects.</li>
 * </ul>
 *
 * <p>The method signatures below are a minimal skeleton. The exact shape of
 * {@code can} / {@code getRule} / {@code getProtectionDepth} (e.g. subland scope,
 * world context, depth basis) is finalized together with the Permission Resolver
 * and the read-API implementation. This interface defines the stable surface;
 * implementations live in {@code chunkland-plugin}.
 */
public interface ChunkLandApi {

    /** Snapshot of a Land by id, or empty if it does not exist. */
    Optional<LandSnapshot> getLandSnapshot(LandId landId);

    /** Snapshot of a SubLand by id, or empty if it does not exist. */
    Optional<SubLandSnapshot> getSubLandSnapshot(SubLandId subLandId);

    /** Owner of a Land, or empty if the Land does not exist. */
    Optional<OwnerRef> getOwner(LandId landId);

    /**
     * Whether {@code actor} may perform {@code action} on {@code landId}.
     * Signature is provisional (see class Javadoc).
     */
    boolean can(UUID actor, LandId landId, ProtectionActionType action);

    /**
     * Effective rule state for {@code rule} on {@code landId}, or empty if the
     * Land does not exist. Signature is provisional (see class Javadoc).
     */
    Optional<PermissionState> getRule(LandId landId, LandRuleType rule);

    /**
     * Effective minimum protected block Y for {@code landId}, or empty if the Land
     * does not exist. Signature is provisional (see class Javadoc).
     */
    Optional<Integer> getProtectionDepth(LandId landId);

    /**
     * Whether the land index behind this API has been confirmed complete.
     *
     * <p>Until startup hydration publishes a complete index, a position with no
     * land is unknown rather than wilderness, so callers must not treat an
     * empty {@link #getLandAt} answer as unclaimed while this is {@code false}.
     * The default answers {@code false}, which keeps implementations without a
     * readiness source fail-closed.
     *
     * @return {@code true} once the land index is confirmed complete
     */
    default boolean isReady() {
        return false;
    }

    /**
     * Land owning a chunk, read from the in-memory index.
     *
     * <p>Callable from any thread; never loads the chunk. An empty answer means
     * wilderness only while {@link #isReady()} is {@code true}.
     *
     * @param worldId world UID
     * @param chunkX  chunk X coordinate (block X shifted right by 4)
     * @param chunkZ  chunk Z coordinate (block Z shifted right by 4)
     * @return the owning land id, or empty when no land owns the chunk
     * @throws NullPointerException if {@code worldId} is {@code null}
     */
    default Optional<LandId> getLandAt(UUID worldId, int chunkX, int chunkZ) {
        Objects.requireNonNull(worldId, "worldId");
        return Optional.empty();
    }

    /**
     * Full protection decision for {@code actor} performing {@code action} at one
     * block, exactly as ChunkLand's own listeners enforce it: admin bypass, owner
     * guarantee, the covering subland, member and group bindings, defaults and
     * land rules all apply.
     *
     * <p>Callable from any thread; memory-only. Wilderness answers {@code ALLOW}
     * (vanilla applies). An unconfirmed index, a missing decision source or any
     * lookup failure answers {@code DENY}. The default implementation always
     * denies.
     *
     * @param actor  acting player UUID; for actions decided by
     *               {@link DecisionSource#LAND_RULE} it only matters for admin bypass
     * @param worldId world UID
     * @param blockX block X coordinate
     * @param blockY block Y coordinate
     * @param blockZ block Z coordinate
     * @param action the protection action
     * @return an {@code ALLOW} or {@code DENY} decision with its source and explanation
     * @throws NullPointerException if {@code actor}, {@code worldId} or {@code action} is {@code null}
     */
    default PermissionDecision decideAtBlock(UUID actor, UUID worldId,
                                             int blockX, int blockY, int blockZ,
                                             ProtectionActionType action) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(action, "action");
        return new PermissionDecision(PermissionState.DENY, action.decisionSource(),
                "Fail-closed (no block decision source) -> DENY");
    }
}

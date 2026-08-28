package com.smile.chunkland.api;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.Optional;
import java.util.UUID;

/**
 * Read API entry point (spec §84).
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
}

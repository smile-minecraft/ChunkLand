package com.smile.chunkland.runtime.api;

import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.UUID;

/**
 * Memory-only block decision over one immutable {@link LandRegistry} snapshot.
 * The implementation must resolve the owning land and covering subland from the
 * given snapshot — the same instance the read API observed — and must never
 * independently re-read a newer publication, perform I/O, or block.
 */
@FunctionalInterface
public interface BlockDecisionLookup {
    /**
     * @param actor    acting player UUID
     * @param worldId  world UID
     * @param blockX   block X coordinate
     * @param blockY   block Y coordinate
     * @param blockZ   block Z coordinate
     * @param action   the protection action
     * @param snapshot the immutable registry snapshot the caller observed
     * @return the decision, or {@code null} when no decision could be made (read as DENY)
     */
    PermissionDecision decideAtBlock(UUID actor, UUID worldId, int blockX, int blockY, int blockZ,
                                     ProtectionActionType action, LandRegistry snapshot);
}

package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.Objects;
import java.util.UUID;

/**
 * Two-ended cross-boundary determinations over the memory-only land index.
 *
 * <p>Every crossing is classified into one of four combinations, and the
 * wilderness side is always vanilla-allow: {@link ProtectionEngine#decideAt}
 * short-circuits to {@code ALLOW} where no land owns the chunk, so checking
 * the deciding ends with an either-end-deny rule yields exactly the matrix
 * below. Same-land pairs decide once at the destination, which reads the
 * same rule either end would have read and therefore never over-blocks
 * while never consulting the index twice for one crossing.
 *
 * <pre>
 * wilderness -&gt; wilderness : pass (no land consults the engine)
 * wilderness -&gt; land       : destination decides (directional IN action)
 * land -&gt; wilderness       : source decides (directional OUT action)
 * land A -&gt; land B         : either end DENY blocks
 * same land                 : single destination decision (same rule both ends)
 * </pre>
 *
 * <p>Hot-path rules: only {@code findLandId} index reads and
 * {@code decideAt} memory lookups, no blocking, no cross-region calls, no
 * storage access. Any failure denies (fail-closed).
 */
public final class CrossBoundaryDecider {

    /** Source/destination ownership combination of one crossing. */
    public enum Relation {
        WILDERNESS,
        SAME_LAND,
        WILD_TO_LAND,
        LAND_TO_WILD,
        CROSS_LAND
    }

    private CrossBoundaryDecider() {
    }

    /**
     * Classifies a crossing from two chunk positions using only the index.
     */
    public static Relation relation(LandRegistry snapshot, UUID worldId,
                                    int srcChunkX, int srcChunkZ,
                                    int dstChunkX, int dstChunkZ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(worldId, "worldId");
        LandId src = snapshot.findLandId(worldId, srcChunkX, srcChunkZ);
        LandId dst = snapshot.findLandId(worldId, dstChunkX, dstChunkZ);
        if (src == null && dst == null) {
            return Relation.WILDERNESS;
        }
        if (src == null) {
            return Relation.WILD_TO_LAND;
        }
        if (dst == null) {
            return Relation.LAND_TO_WILD;
        }
        if (src.equals(dst)) {
            return Relation.SAME_LAND;
        }
        return Relation.CROSS_LAND;
    }

    /**
     * Pure combination matrix: the wilderness side is always allow, the land
     * side decides, and cross-land needs both ends to allow. A missing or
     * {@code INHERIT} outcome is not an allow, so it denies (fail-closed).
     *
     * @return {@code true} when the crossing must be denied
     */
    public static boolean denied(Relation relation,
                                 PermissionState srcOutcome,
                                 PermissionState dstOutcome) {
        Objects.requireNonNull(relation, "relation");
        boolean srcAllow = srcOutcome == PermissionState.ALLOW;
        boolean dstAllow = dstOutcome == PermissionState.ALLOW;
        return switch (relation) {
            case WILDERNESS -> false;
            case SAME_LAND -> !dstAllow;
            case WILD_TO_LAND -> !dstAllow;
            case LAND_TO_WILD -> !srcAllow;
            case CROSS_LAND -> !srcAllow || !dstAllow;
        };
    }

    /**
     * Two-ended check through the engine at block coordinates. The crossing
     * is classified once from a single index snapshot, then only the deciding
     * ends are consulted against that same snapshot: wilderness ends resolve
     * to {@code ALLOW} inside the snapshot-bound decision and therefore never
     * block, so they are not consulted at all.
     *
     * <p>Consistency note: classification and every decision share the one
     * snapshot taken on entry, so a concurrent publish landing mid-flight
     * cannot mix versions. The crossing is judged entirely under the
     * classification-time index; a concurrent publish only affects later
     * crossings. Any failure denies (fail-closed).
     *
     * @param inAction  directional action read at the destination
     * @param outAction directional action read at the source
     * @return {@code true} when the crossing must be denied
     */
    public static boolean crossDenied(ProtectionEngine engine, UUID worldId,
                                      int srcBlockX, int srcBlockZ,
                                      int dstBlockX, int dstBlockZ,
                                      ProtectionActionType inAction,
                                      ProtectionActionType outAction) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(inAction, "inAction");
        Objects.requireNonNull(outAction, "outAction");
        if (!engine.isRegistryReady()) {
            // An unconfirmed index reads every position as wilderness, so no
            // crossing can prove both ends are unowned. Deny until hydration
            // confirms the index is complete.
            return true;
        }
        try {
            LandRegistry snapshot;
            try {
                snapshot = engine.snapshot();
            } catch (RuntimeException ex) {
                return true;
            }
            if (snapshot == null) {
                return true;
            }
            Relation relation = relation(snapshot, worldId,
                    srcBlockX >> 4, srcBlockZ >> 4, dstBlockX >> 4, dstBlockZ >> 4);
            return switch (relation) {
                case WILDERNESS -> false;
                case SAME_LAND, WILD_TO_LAND ->
                        deniedAt(snapshot, engine, worldId, dstBlockX, dstBlockZ, inAction);
                case LAND_TO_WILD ->
                        deniedAt(snapshot, engine, worldId, srcBlockX, srcBlockZ, outAction);
                case CROSS_LAND ->
                        deniedAt(snapshot, engine, worldId, srcBlockX, srcBlockZ, outAction)
                                || deniedAt(snapshot, engine, worldId, dstBlockX, dstBlockZ, inAction);
            };
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /**
     * Two-ended check for a piston, fluid or hopper step between two blocks.
     * A step that stays inside one land reads the in-land action at both
     * blocks, so the covering subland decides first at each end. A step
     * that touches a land boundary reads the directional actions instead:
     * the in-land rule never decides what crosses in or out.
     *
     * <p>Classification and every decision share the one snapshot taken on
     * entry. Any failure denies (fail-closed).
     *
     * @param insideAction action read at both ends when they share one land
     * @param inAction     directional action read at the destination land
     * @param outAction    directional action read at the source land
     * @return {@code true} when the step must be denied
     */
    public static boolean mechanicDenied(ProtectionEngine engine, UUID worldId,
                                         int srcBlockX, int srcBlockY, int srcBlockZ,
                                         int dstBlockX, int dstBlockY, int dstBlockZ,
                                         ProtectionActionType insideAction,
                                         ProtectionActionType inAction,
                                         ProtectionActionType outAction) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(insideAction, "insideAction");
        Objects.requireNonNull(inAction, "inAction");
        Objects.requireNonNull(outAction, "outAction");
        if (!engine.isRegistryReady()) {
            return true;
        }
        try {
            LandRegistry snapshot = engine.snapshot();
            if (snapshot == null) {
                return true;
            }
            Relation relation = relation(snapshot, worldId,
                    srcBlockX >> 4, srcBlockZ >> 4, dstBlockX >> 4, dstBlockZ >> 4);
            return switch (relation) {
                case WILDERNESS -> false;
                case SAME_LAND ->
                        deniedAtBlock(snapshot, engine, worldId,
                                srcBlockX, srcBlockY, srcBlockZ, insideAction)
                                || deniedAtBlock(snapshot, engine, worldId,
                                        dstBlockX, dstBlockY, dstBlockZ, insideAction);
                case WILD_TO_LAND ->
                        deniedAtBlock(snapshot, engine, worldId,
                                dstBlockX, dstBlockY, dstBlockZ, inAction);
                case LAND_TO_WILD ->
                        deniedAtBlock(snapshot, engine, worldId,
                                srcBlockX, srcBlockY, srcBlockZ, outAction);
                case CROSS_LAND ->
                        deniedAtBlock(snapshot, engine, worldId,
                                srcBlockX, srcBlockY, srcBlockZ, outAction)
                                || deniedAtBlock(snapshot, engine, worldId,
                                        dstBlockX, dstBlockY, dstBlockZ, inAction);
            };
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private static boolean deniedAtBlock(LandRegistry snapshot, ProtectionEngine engine, UUID worldId,
                                         int blockX, int blockY, int blockZ,
                                         ProtectionActionType action) {
        return engine.decideAtBlockOnSnapshot(ProtectionListener.ENVIRONMENT_ACTOR,
                worldId, blockX, blockY, blockZ, action, snapshot).outcome()
                == PermissionState.DENY;
    }

    private static boolean deniedAt(LandRegistry snapshot, ProtectionEngine engine, UUID worldId,
                                    int blockX, int blockZ, ProtectionActionType action) {
        return engine.decideAtOnSnapshot(ProtectionListener.ENVIRONMENT_ACTOR,
                worldId, blockX >> 4, blockZ >> 4, action, snapshot).outcome()
                == PermissionState.DENY;
    }
}

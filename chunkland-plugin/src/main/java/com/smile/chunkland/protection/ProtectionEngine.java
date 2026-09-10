package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.PermissionResolver;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.SubLandIndex;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Single enforcement entry for protection decisions.
 *
 * <p>Given an actor, the land owning a position, and an action, the engine
 * takes exactly one volatile {@link LandRegistry} snapshot and resolves
 * through {@link PermissionResolver}. The context provider receives that same
 * snapshot, so existence and lookup can never mix versions.
 *
 * <p>Hot-path rules: memory-only index reads, no blocking, no cross-region
 * calls, no storage access. Fail-closed: any lookup failure yields
 * {@code DENY}; wilderness (no land) follows vanilla and yields
 * {@code ALLOW}, never {@code DENY}.
 *
 * <p>An optional {@link PermissionDecisionCache} reuses decisions across
 * identical calls: the key names the actor, land, covering subland,
 * action and all four epochs plus the structure revision and owner
 * context, so any content move naturally misses. Wilderness, unloaded,
 * unknown and failed sources bypass the cache; failed computations are
 * never stored.
 */
public final class ProtectionEngine {

    private final Supplier<LandRegistry> registrySupplier;
    private final PermissionContextProvider contextProvider;
    private final Map<ProtectionActionType, DecisionSource> routes;
    private final PermissionDecisionCache cache;
    private final PermissionDecisionEpochSource epochs;

    public ProtectionEngine(Supplier<LandRegistry> registrySupplier,
                            PermissionContextProvider contextProvider) {
        this(registrySupplier, contextProvider, ProtectionActionRegistry.defaults());
    }

    /**
     * Cached enforcement: {@code cache} and {@code epochs} are optional and
     * independent of the routing table. A {@code null} cache or a
     * {@code null} source disables reuse and keeps the exact behaviour of
     * the uncached constructors.
     */
    public ProtectionEngine(Supplier<LandRegistry> registrySupplier,
                            PermissionContextProvider contextProvider,
                            PermissionDecisionCache cache,
                            PermissionDecisionEpochSource epochs) {
        this(registrySupplier, contextProvider, ProtectionActionRegistry.defaults(), cache, epochs);
    }

    /**
     * @throws IllegalStateException when the routing table misses an action or
     *         maps one to {@code null} (startup must refuse to continue)
     */
    public ProtectionEngine(Supplier<LandRegistry> registrySupplier,
                            PermissionContextProvider contextProvider,
                            Map<ProtectionActionType, DecisionSource> routes) {
        this(registrySupplier, contextProvider, routes, null, null);
    }

    /**
     * @throws IllegalStateException when the routing table misses an action or
     *         maps one to {@code null} (startup must refuse to continue)
     */
    public ProtectionEngine(Supplier<LandRegistry> registrySupplier,
                            PermissionContextProvider contextProvider,
                            Map<ProtectionActionType, DecisionSource> routes,
                            PermissionDecisionCache cache,
                            PermissionDecisionEpochSource epochs) {
        this.registrySupplier = Objects.requireNonNull(registrySupplier, "registrySupplier");
        this.contextProvider = Objects.requireNonNull(contextProvider, "contextProvider");
        this.routes = ProtectionActionRegistry.validated(routes);
        this.cache = cache;
        this.epochs = epochs;
    }

    /**
     * Returns the current memory-only land index snapshot backing decisions.
     * Each call observes one volatile publish; the registry itself is
     * immutable, so a returned snapshot is always self-consistent.
     */
    public LandRegistry snapshot() {
        return registrySupplier.get();
    }

    /**
     * Skeleton context provider for startup wiring: it grants nothing, so the
     * resolver falls through every layer to its implicit {@code DENY} inside
     * a land. Later milestones replace it with a provider fed by real
     * bindings, defaults, and rules.
     */
    public static PermissionContextProvider inheritOnlyProvider() {
        return (actor, landId, action, snapshot) -> PermissionContext.builder(action).build();
    }

    /**
     * @return the validated decision source for the action.
     */
    public DecisionSource routeOf(ProtectionActionType action) {
        Objects.requireNonNull(action, "action");
        return routes.get(action);
    }

    /**
     * Decides for an already-resolved land. Never throws for decision-path
     * failures; those become {@code DENY}.
     */
    public PermissionDecision decide(UUID actor, LandId landId, ProtectionActionType action) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(action, "action");
        LandRegistry snapshot = takeSnapshot(action);
        if (snapshot == null) {
            return failClosed(action, "snapshot unavailable");
        }
        try {
            if (snapshot.land(landId) == null) {
                return wilderness(action);
            }
            return cachedOrCompute(actor, landId, null, action, snapshot, false, 0, 0, 0);
        } catch (RuntimeException ex) {
            return failClosed(action, "lookup failure: " + ex.getMessage());
        }
    }

    /**
     * Decides for a chunk position, resolving the owning land from the same
     * snapshot used for the decision. Wilderness yields vanilla
     * {@code ALLOW}.
     */
    public PermissionDecision decideAt(UUID actor, UUID worldId, int chunkX, int chunkZ,
                                       ProtectionActionType action) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(action, "action");
        LandRegistry snapshot = takeSnapshot(action);
        if (snapshot == null) {
            return failClosed(action, "snapshot unavailable");
        }
        return decideAtOnSnapshot(actor, worldId, chunkX, chunkZ, action, snapshot);
    }

    /**
     * Decides for a chunk position against a caller-supplied snapshot, never
     * touching the registry supplier. Callers that already hold the snapshot
     * they classified with (for example cross-boundary checks) use this so
     * classification and decision can never mix index versions. The snapshot
     * is memory-only; a {@code null} snapshot still fails closed.
     */
    public PermissionDecision decideAtOnSnapshot(UUID actor, UUID worldId, int chunkX, int chunkZ,
                                                 ProtectionActionType action, LandRegistry snapshot) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(action, "action");
        if (snapshot == null) {
            return failClosed(action, "snapshot unavailable");
        }
        try {
            LandId landId = snapshot.findLandId(worldId, chunkX, chunkZ);
            if (landId == null) {
                return wilderness(action);
            }
            return cachedOrCompute(actor, landId, null, action, snapshot, false, 0, 0, 0);
        } catch (RuntimeException ex) {
            return failClosed(action, "lookup failure: " + ex.getMessage());
        }
    }

    /**
     * Decides for a block position, resolving the owning land from the same
     * snapshot used for the decision and letting the covering subland decide
     * first. Wilderness yields vanilla {@code ALLOW}. Chunk-only callers
     * without a block Y keep using {@link #decideAt}; every listener path
     * with a block or location uses this so subland precedence applies.
     */
    public PermissionDecision decideAtBlock(UUID actor, UUID worldId,
                                            int blockX, int blockY, int blockZ,
                                            ProtectionActionType action) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(action, "action");
        LandRegistry snapshot = takeSnapshot(action);
        if (snapshot == null) {
            return failClosed(action, "snapshot unavailable");
        }
        return decideAtBlockOnSnapshot(actor, worldId, blockX, blockY, blockZ, action, snapshot);
    }

    /**
     * Decides for a block position against a caller-supplied snapshot, never
     * touching the registry supplier. Memory-only like
     * {@link #decideAtOnSnapshot}; a {@code null} snapshot still fails closed.
     */
    public PermissionDecision decideAtBlockOnSnapshot(UUID actor, UUID worldId,
                                                      int blockX, int blockY, int blockZ,
                                                      ProtectionActionType action, LandRegistry snapshot) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(action, "action");
        if (snapshot == null) {
            return failClosed(action, "snapshot unavailable");
        }
        try {
            LandId landId = snapshot.findLandId(worldId, blockX >> 4, blockZ >> 4);
            if (landId == null) {
                return wilderness(action);
            }
            SubLandId covering = coveringSubland(snapshot, landId, blockX, blockY, blockZ);
            return cachedOrCompute(actor, landId, covering, action, snapshot,
                    true, blockX, blockY, blockZ);
        } catch (RuntimeException ex) {
            return failClosed(action, "lookup failure: " + ex.getMessage());
        }
    }

    private LandRegistry takeSnapshot(ProtectionActionType action) {
        try {
            return registrySupplier.get();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * One computation with its cleanliness: only a clean computation (real
     * context, no exception) may populate the cache. Fail-closed answers
     * carry {@code clean=false} and are always recomputed.
     */
    private record Attempt(PermissionContext context, PermissionDecision decision, boolean clean) {
    }

    private Attempt attempt(Supplier<PermissionContext> contexts, ProtectionActionType action) {
        PermissionContext ctx;
        try {
            ctx = contexts.get();
        } catch (RuntimeException ex) {
            return new Attempt(null, failClosed(action, "resolver failure: " + ex.getMessage()), false);
        }
        if (ctx == null) {
            return new Attempt(null, failClosed(action, "no decision context"), false);
        }
        try {
            return new Attempt(ctx, PermissionResolver.resolve(ctx), true);
        } catch (RuntimeException ex) {
            return new Attempt(null, failClosed(action, "resolver failure: " + ex.getMessage()), false);
        }
    }

    /**
     * Cached decision for one land or block position. Without a cache (or
     * without an epoch source) this is exactly the historical direct path.
     * With both wired, a full-key hit returns immediately; a miss computes,
     * then stores only when the computation was clean and the context still
     * agrees with the key's owner flags. Uncacheable sources, failed
     * computations and flag disagreements all answer without storing.
     */
    private PermissionDecision cachedOrCompute(UUID actor, LandId landId, SubLandId subland,
            ProtectionActionType action, LandRegistry snapshot,
            boolean atBlock, int blockX, int blockY, int blockZ) {
        Supplier<PermissionContext> contexts = atBlock
                ? () -> blockContext(actor, landId, blockX, blockY, blockZ, action, snapshot)
                : () -> contextProvider.provide(actor, landId, action, snapshot);
        if (cache == null || epochs == null) {
            return attempt(contexts, action).decision();
        }
        PermissionDecisionCache.Key key;
        try {
            key = epochs.keyFor(actor, landId, subland, action, snapshot);
        } catch (RuntimeException ex) {
            key = null;
        }
        if (key == null) {
            return attempt(contexts, action).decision();
        }
        PermissionDecision hit;
        try {
            hit = cache.get(key);
        } catch (RuntimeException ex) {
            hit = null;
        }
        if (hit != null) {
            return hit;
        }
        Attempt computed = attempt(contexts, action);
        if (!computed.clean() || computed.context() == null
                || computed.context().isOwner() != key.owner()
                || computed.context().adminBypass() != key.adminBypass()) {
            return computed.decision();
        }
        try {
            cache.put(key, computed.decision());
        } catch (RuntimeException ignored) {
            // A cache store must never break enforcement; the decision stands.
        }
        return computed.decision();
    }

    private PermissionContext blockContext(UUID actor, LandId landId,
            int blockX, int blockY, int blockZ,
            ProtectionActionType action, LandRegistry snapshot) {
        if (contextProvider instanceof SnapshotPermissionContextProvider snapshotProvider) {
            return snapshotProvider.provideAtBlock(actor, landId, blockX, blockY, blockZ, action, snapshot);
        }
        return contextProvider.provide(actor, landId, action, snapshot);
    }

    /**
     * Covering subland for one block position, mirroring the provider's own
     * resolution over the same immutable snapshot so the cache key and the
     * decision can never disagree about which subland applies. Memory-only;
     * failures read as no covering subland.
     */
    private static SubLandId coveringSubland(LandRegistry snapshot, LandId landId,
            int blockX, int blockY, int blockZ) {
        try {
            SubLandIndex index = snapshot.subLandIndex(landId);
            if (index == null) {
                return null;
            }
            SubLandSnapshot covering = index.findAtBlock(blockX, blockY, blockZ);
            return covering == null ? null : covering.id();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private PermissionDecision resolveWithSnapshot(UUID actor, LandId landId,
                                                   ProtectionActionType action,
                                                   LandRegistry snapshot) {
        return attempt(() -> contextProvider.provide(actor, landId, action, snapshot), action)
                .decision();
    }

    private PermissionDecision resolveWithBlockSnapshot(UUID actor, LandId landId,
                                                        int blockX, int blockY, int blockZ,
                                                        ProtectionActionType action,
                                                        LandRegistry snapshot) {
        return attempt(() -> blockContext(actor, landId, blockX, blockY, blockZ, action, snapshot),
                action).decision();
    }

    private PermissionDecision wilderness(ProtectionActionType action) {
        return new PermissionDecision(PermissionState.ALLOW, routeOf(action),
                "Wilderness (no owning land): vanilla applies, no protection -> ALLOW");
    }

    private PermissionDecision failClosed(ProtectionActionType action, String reason) {
        return new PermissionDecision(PermissionState.DENY, routeOf(action),
                "Fail-closed (" + reason + ") -> DENY");
    }
}

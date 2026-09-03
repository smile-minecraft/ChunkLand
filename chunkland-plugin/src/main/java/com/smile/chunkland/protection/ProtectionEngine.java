package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.PermissionResolver;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
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
 */
public final class ProtectionEngine {

    private final Supplier<LandRegistry> registrySupplier;
    private final PermissionContextProvider contextProvider;
    private final Map<ProtectionActionType, DecisionSource> routes;

    public ProtectionEngine(Supplier<LandRegistry> registrySupplier,
                            PermissionContextProvider contextProvider) {
        this(registrySupplier, contextProvider, ProtectionActionRegistry.defaults());
    }

    /**
     * @throws IllegalStateException when the routing table misses an action or
     *         maps one to {@code null} (startup must refuse to continue)
     */
    public ProtectionEngine(Supplier<LandRegistry> registrySupplier,
                            PermissionContextProvider contextProvider,
                            Map<ProtectionActionType, DecisionSource> routes) {
        this.registrySupplier = Objects.requireNonNull(registrySupplier, "registrySupplier");
        this.contextProvider = Objects.requireNonNull(contextProvider, "contextProvider");
        this.routes = ProtectionActionRegistry.validated(routes);
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
            return resolveWithSnapshot(actor, landId, action, snapshot);
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
        try {
            LandId landId = snapshot.findLandId(worldId, chunkX, chunkZ);
            if (landId == null) {
                return wilderness(action);
            }
            return resolveWithSnapshot(actor, landId, action, snapshot);
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

    private PermissionDecision resolveWithSnapshot(UUID actor, LandId landId,
                                                   ProtectionActionType action,
                                                   LandRegistry snapshot) {
        try {
            PermissionContext ctx = contextProvider.provide(actor, landId, action, snapshot);
            if (ctx == null) {
                return failClosed(action, "no decision context");
            }
            return PermissionResolver.resolve(ctx);
        } catch (RuntimeException ex) {
            return failClosed(action, "resolver failure: " + ex.getMessage());
        }
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

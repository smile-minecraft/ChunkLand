package com.smile.chunkland.runtime.api;

import com.smile.chunkland.api.ChunkLandApi;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.PermissionResolver;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Memory-only {@link ChunkLandApi} adapter.
 *
 * <p>All reads take exactly one volatile snapshot via {@link Supplier#get()} and
 * then query the immutable structure. No I/O, chunk load, Bukkit, SQL, network
 * or blocking wait is performed. Returned snapshots and collections are
 * immutable; the resolver context is immutable.
 */
public final class ChunkLandReadApi implements ChunkLandApi {

    private final Supplier<LandRegistry> registrySupplier;
    private final PermissionContextProvider contextProvider;
    private final LandRuleLookup ruleLookup;
    private final ProtectionDepthLookup depthLookup;

    public ChunkLandReadApi(Supplier<LandRegistry> registrySupplier,
                            PermissionContextProvider contextProvider,
                            LandRuleLookup ruleLookup,
                            ProtectionDepthLookup depthLookup) {
        this.registrySupplier = Objects.requireNonNull(registrySupplier, "registrySupplier");
        this.contextProvider = Objects.requireNonNull(contextProvider, "contextProvider");
        this.ruleLookup = Objects.requireNonNull(ruleLookup, "ruleLookup");
        this.depthLookup = Objects.requireNonNull(depthLookup, "depthLookup");
    }

    public ChunkLandReadApi(LandRegistryStore store) {
        this(Objects.requireNonNull(store, "store")::snapshot,
                (actor, landId, action, snapshot) -> null,
                (landId, rule, snapshot) -> Optional.empty(),
                (landId, snapshot) -> Optional.empty());
    }

    public ChunkLandReadApi(Supplier<LandRegistry> registrySupplier) {
        this(registrySupplier,
                (actor, landId, action, snapshot) -> null,
                (landId, rule, snapshot) -> Optional.empty(),
                (landId, snapshot) -> Optional.empty());
    }

    @Override
    public Optional<LandSnapshot> getLandSnapshot(LandId landId) {
        Objects.requireNonNull(landId, "landId");
        LandRegistry snapshot = registrySupplier.get();
        Objects.requireNonNull(snapshot, "registry snapshot must not be null");
        LandSnapshot found = snapshot.land(landId);
        return Optional.ofNullable(found);
    }

    @Override
    public Optional<SubLandSnapshot> getSubLandSnapshot(SubLandId subLandId) {
        Objects.requireNonNull(subLandId, "subLandId");
        LandRegistry snapshot = registrySupplier.get();
        Objects.requireNonNull(snapshot, "registry snapshot must not be null");
        var indexes = snapshot.subLandIndexes();
        for (var idx : indexes.values()) {
            var found = idx.findById(subLandId);
            if (found != null) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<OwnerRef> getOwner(LandId landId) {
        Objects.requireNonNull(landId, "landId");
        return getLandSnapshot(landId).map(LandSnapshot::ownerRef);
    }

    @Override
    public boolean can(UUID actor, LandId landId, ProtectionActionType action) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(action, "action");
        LandRegistry snapshot = registrySupplier.get();
        Objects.requireNonNull(snapshot, "registry snapshot must not be null");
        if (snapshot.land(landId) == null) {
            return false;
        }
        var ctx = contextProvider.provide(actor, landId, action, snapshot);
        if (ctx == null) {
            return false;
        }
        var decision = PermissionResolver.resolve(ctx);
        return decision.outcome() == PermissionState.ALLOW;
    }

    @Override
    public Optional<PermissionState> getRule(LandId landId, LandRuleType rule) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(rule, "rule");
        LandRegistry snapshot = registrySupplier.get();
        Objects.requireNonNull(snapshot, "registry snapshot must not be null");
        if (snapshot.land(landId) == null) {
            return Optional.empty();
        }
        Optional<PermissionState> result = ruleLookup.getRule(landId, rule, snapshot);
        return result != null ? result : Optional.empty();
    }

    @Override
    public Optional<Integer> getProtectionDepth(LandId landId) {
        Objects.requireNonNull(landId, "landId");
        LandRegistry snapshot = registrySupplier.get();
        Objects.requireNonNull(snapshot, "registry snapshot must not be null");
        if (snapshot.land(landId) == null) {
            return Optional.empty();
        }
        Optional<Integer> result = depthLookup.getProtectionDepth(landId, snapshot);
        return result != null ? result : Optional.empty();
    }
}

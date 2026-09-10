package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionResolver;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.PermissionDefaultsCache.ConfigView;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.rule.LandRuleService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Production-path proof for subland precedence and single-generation capture.
 *
 * <p>Every decision path with a block position goes through the
 * block-aware engine entry, so the covering subland decides ahead of the
 * land chain; one decision captures one config view and one durable auth
 * for both halves.
 */
class SubLandProductionPathTest {

    private record Fixture(UUID worldId, LandId landId, SubLandId subId,
            LandRegistryStore stores, LandAuthorisationSnapshot auth, UUID actor) {
    }

    private Fixture fixture(PermissionState landState, PermissionState subState) {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        SubLandId subId = new SubLandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SubLandSnapshot sub = new SubLandSnapshot(subId, landId, "Den",
                new Cuboid(0, 0, 0, 15, 255, 15), worldId);
        LandSnapshot land = new LandSnapshot(landId, "Home", "home", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, 0, 0), new ChunkKey(worldId, 6, 6)),
                List.of(sub), 0, 0, Instant.now(), Instant.now());
        LandRegistryStore stores = new LandRegistryStore();
        stores.publish(LandRegistry.from(List.of(land)));

        PermissionBinding landBinding = new PermissionBinding(
                PermissionSubject.player(actor),
                new Permission(ProtectionActionType.BLOCK_BREAK, landState));
        PermissionBinding subBinding = new PermissionBinding(
                PermissionSubject.player(actor),
                new Permission(ProtectionActionType.BLOCK_BREAK, subState));
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.copyOf(
                Map.of(), Map.of(), Map.of(),
                Map.of(landId, List.of(landBinding)),
                Map.of(subId, List.of(subBinding)),
                Map.of(), Map.of());
        return new Fixture(worldId, landId, subId, stores, auth, actor);
    }

    private Supplier<ConfigView> fixedViews() {
        PermissionDefaultsSnapshot empty = PermissionDefaultsSnapshot.empty();
        ConfigView view = new ConfigView(empty, LandRuleService.fromRuleSnapshot(empty), 0L, Map.of());
        return () -> view;
    }

    private ProtectionEngine engineOf(Fixture f) {
        LandAuthorisationSnapshot fixedAuth = f.auth();
        var provider = SnapshotPermissionContextProvider.atomic(fixedViews(), () -> fixedAuth);
        return new ProtectionEngine(f.stores()::snapshot, provider);
    }

    @Test
    void engineBlockPathSeesSublandDenyOverLandAllow() {
        Fixture f = fixture(PermissionState.ALLOW, PermissionState.DENY);
        ProtectionEngine engine = engineOf(f);
        // Inside the subland the explicit DENY wins over the land ALLOW.
        assertEquals(PermissionState.DENY,
                engine.decideAtBlock(f.actor(), f.worldId(), 1, 64, 1,
                        ProtectionActionType.BLOCK_BREAK).outcome());
        // Same land but outside every subland falls back to the land ALLOW.
        assertEquals(PermissionState.ALLOW,
                engine.decideAtBlock(f.actor(), f.worldId(), 100, 64, 100,
                        ProtectionActionType.BLOCK_BREAK).outcome());
    }

    @Test
    void engineBlockPathSeesSublandAllowOverLandDeny() {
        Fixture f = fixture(PermissionState.DENY, PermissionState.ALLOW);
        ProtectionEngine engine = engineOf(f);
        assertEquals(PermissionState.ALLOW,
                engine.decideAtBlock(f.actor(), f.worldId(), 1, 64, 1,
                        ProtectionActionType.BLOCK_BREAK).outcome());
        assertEquals(PermissionState.DENY,
                engine.decideAtBlock(f.actor(), f.worldId(), 100, 64, 100,
                        ProtectionActionType.BLOCK_BREAK).outcome());
    }

    @Test
    void engineBlockPathDiscriminatesByBlockY() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        SubLandId subId = new SubLandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SubLandSnapshot sub = new SubLandSnapshot(subId, landId, "Den",
                new Cuboid(0, 60, 0, 15, 70, 15), worldId);
        LandSnapshot land = new LandSnapshot(landId, "Home", "home", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                List.of(sub), 0, 0, Instant.now(), Instant.now());
        LandRegistryStore stores = new LandRegistryStore();
        stores.publish(LandRegistry.from(List.of(land)));
        PermissionBinding landBinding = new PermissionBinding(
                PermissionSubject.player(actor),
                new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW));
        PermissionBinding subBinding = new PermissionBinding(
                PermissionSubject.player(actor),
                new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.DENY));
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.copyOf(
                Map.of(), Map.of(), Map.of(),
                Map.of(landId, List.of(landBinding)),
                Map.of(subId, List.of(subBinding)),
                Map.of(), Map.of());
        ProtectionEngine engine = new ProtectionEngine(stores::snapshot,
                SnapshotPermissionContextProvider.atomic(fixedViews(), () -> auth));
        assertEquals(PermissionState.DENY,
                engine.decideAtBlock(actor, worldId, 1, 64, 1,
                        ProtectionActionType.BLOCK_BREAK).outcome());
        assertEquals(PermissionState.ALLOW,
                engine.decideAtBlock(actor, worldId, 1, 100, 1,
                        ProtectionActionType.BLOCK_BREAK).outcome(),
                "same XZ above the subland cuboid must fall back to the land chain");
    }

    @Test
    void atomicBlockDecisionCapturesOneViewGeneration() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        SubLandSnapshot sub = new SubLandSnapshot(new SubLandId(UUID.randomUUID()), landId, "Den",
                new Cuboid(0, 0, 0, 15, 255, 15), worldId);
        LandSnapshot land = new LandSnapshot(landId, "Home", "home", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                List.of(sub), 0, 0, Instant.now(), Instant.now());
        LandRegistry registry = LandRegistry.from(List.of(land));

        PermissionDefaultsSnapshot empty = PermissionDefaultsSnapshot.empty();
        ConfigView oldView = new ConfigView(empty, LandRuleService.fromRuleSnapshot(empty), 0L, Map.of());
        ConfigView newView = new ConfigView(empty, LandRuleService.fromRuleSnapshot(empty), 1L, Map.of());
        AtomicInteger calls = new AtomicInteger();
        Supplier<ConfigView> views = () -> {
            int seen = calls.incrementAndGet();
            return seen == 1 ? oldView : newView;
        };
        AtomicInteger authCalls = new AtomicInteger();
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.empty();
        Supplier<LandAuthorisationSnapshot> auths = () -> {
            authCalls.incrementAndGet();
            return auth;
        };
        var provider = SnapshotPermissionContextProvider.atomic(views, auths);
        provider.provideAtBlock(UUID.randomUUID(), landId, 1, 64, 1,
                ProtectionActionType.BLOCK_BREAK, registry);
        assertEquals(1, calls.get(), "one block decision must capture one config view");
        assertEquals(1, authCalls.get(), "one block decision must capture one land auth");
    }

    @Test
    void atomicBlockDecisionNeverMixesAuthGenerations() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        SubLandId subId = new SubLandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SubLandSnapshot sub = new SubLandSnapshot(subId, landId, "Den",
                new Cuboid(0, 0, 0, 15, 255, 15), worldId);
        LandSnapshot land = new LandSnapshot(landId, "Home", "home", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                List.of(sub), 0, 0, Instant.now(), Instant.now());
        LandRegistry registry = LandRegistry.from(List.of(land));

        PermissionBinding landAllow = new PermissionBinding(
                PermissionSubject.player(actor),
                new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW));
        PermissionBinding subDeny = new PermissionBinding(
                PermissionSubject.player(actor),
                new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.DENY));
        LandAuthorisationSnapshot first = LandAuthorisationSnapshot.copyOf(
                Map.of(), Map.of(), Map.of(),
                Map.of(landId, List.of(landAllow)),
                Map.of(subId, List.of(subDeny)),
                Map.of(), Map.of());
        PermissionBinding landDeny = new PermissionBinding(
                PermissionSubject.player(actor),
                new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.DENY));
        PermissionBinding subAllow = new PermissionBinding(
                PermissionSubject.player(actor),
                new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW));
        LandAuthorisationSnapshot second = LandAuthorisationSnapshot.copyOf(
                Map.of(), Map.of(), Map.of(),
                Map.of(landId, List.of(landDeny)),
                Map.of(subId, List.of(subAllow)),
                Map.of(), Map.of());
        AtomicInteger authCalls = new AtomicInteger();
        Supplier<LandAuthorisationSnapshot> auths = () -> {
            int seen = authCalls.incrementAndGet();
            return seen == 1 ? first : second;
        };
        var provider = SnapshotPermissionContextProvider.atomic(fixedViews(), auths);
        var ctx = provider.provideAtBlock(actor, landId, 1, 64, 1,
                ProtectionActionType.BLOCK_BREAK, registry);
        // First generation denies inside the subland; mixing the second
        // generation for either half would flip this to ALLOW.
        assertEquals(PermissionState.DENY, PermissionResolver.resolve(ctx).outcome());
        assertEquals(1, authCalls.get(), "land and subland halves must share one auth capture");
    }
}

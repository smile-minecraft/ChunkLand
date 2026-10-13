package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.rule.LandRuleService;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Durable per-land rules through the atomic provider: the land layer comes
 * from the same immutable authorisation snapshot as the subject layers, while
 * the world, global and built-in layers still come from the one captured
 * config view. One decision captures one of each, so a rule and its
 * authorisation data can never mix generations.
 */
class DurableLandRuleProviderTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final UUID worldId = UUID.randomUUID();
    private final LandId landId = new LandId(UUID.randomUUID());
    private final UUID owner = UUID.randomUUID();
    private final LandRegistry registry = LandRegistry.from(List.of(
            new LandSnapshot(landId, "TestLand", "testland", OwnerRef.player(owner),
                    worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                    List.of(), 0, 0, NOW, NOW)));

    private static ProtectionActionType actionFor(LandRuleType rule) {
        return switch (rule) {
            case PVP -> ProtectionActionType.PLAYER_DAMAGE_PLAYER;
            case EXPLOSION_TERRAIN -> ProtectionActionType.EXPLOSION_TERRAIN;
            case EXPLOSION_ENTITY -> ProtectionActionType.EXPLOSION_ENTITY;
            case FIRE_SPREAD -> ProtectionActionType.FIRE_SPREAD;
            case FIRE_BURN -> ProtectionActionType.FIRE_BURN;
            case MOB_GRIEFING -> ProtectionActionType.MOB_GRIEFING;
            case FLUID_FLOW -> ProtectionActionType.FLUID_FLOW;
            case PISTON -> ProtectionActionType.PISTON_MOVE;
            case HOPPER_TRANSFER -> ProtectionActionType.HOPPER_TRANSFER;
            case HOSTILE_MOB_SPAWN -> ProtectionActionType.HOSTILE_MOB_SPAWN;
            case PASSIVE_MOB_SPAWN -> ProtectionActionType.PASSIVE_MOB_SPAWN;
        };
    }

    private static PermissionDefaultsCache.ConfigView configView(
            Map<LandRuleType, PermissionState> ruleGlobal,
            Map<UUID, Map<LandRuleType, PermissionState>> ruleWorlds) {
        PermissionDefaultsSnapshot snapshot = new PermissionDefaultsSnapshot(
                Map.of(), Map.of(), ruleGlobal, ruleWorlds);
        return new PermissionDefaultsCache.ConfigView(snapshot,
                LandRuleService.fromRuleSnapshot(snapshot), 0L, Map.of());
    }

    private static LandAuthorisationSnapshot authWithRules(
            Map<LandId, Map<LandRuleType, PermissionState>> rules) {
        return LandAuthorisationSnapshot.empty()
                .withDirect(null, null, null, rules, Map.of());
    }

    private static Map<LandId, Map<LandRuleType, PermissionState>> rulesFor(
            LandId land, LandRuleType rule, PermissionState state) {
        EnumMap<LandRuleType, PermissionState> states = new EnumMap<>(LandRuleType.class);
        states.put(rule, state);
        return Map.of(land, Map.copyOf(states));
    }

    private ProtectionEngine engine(PermissionDefaultsCache.ConfigView view,
            LandAuthorisationSnapshot auth) {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(registry);
        return new ProtectionEngine(store::snapshot,
                SnapshotPermissionContextProvider.atomic(() -> view, () -> auth));
    }

    @ParameterizedTest
    @EnumSource(LandRuleType.class)
    void everyDurableRuleDecidesThroughAtomicProvider(LandRuleType rule) {
        ProtectionActionType action = actionFor(rule);
        var allowing = engine(configView(Map.of(), Map.of()),
                authWithRules(rulesFor(landId, rule, PermissionState.ALLOW)));
        assertEquals(PermissionState.ALLOW,
                allowing.decide(UUID.randomUUID(), landId, action).outcome(),
                "durable ALLOW must decide for " + rule);

        var denying = engine(configView(Map.of(), Map.of()),
                authWithRules(rulesFor(landId, rule, PermissionState.DENY)));
        assertEquals(PermissionState.DENY,
                denying.decide(UUID.randomUUID(), landId, action).outcome(),
                "durable DENY must decide for " + rule);
    }

    @Test
    void durableDenyBindsOwnerLikeAnyoneElse() {
        var engine = engine(configView(Map.of(LandRuleType.PVP, PermissionState.ALLOW), Map.of()),
                authWithRules(rulesFor(landId, LandRuleType.PVP, PermissionState.DENY)));
        assertEquals(PermissionState.DENY,
                engine.decide(owner, landId, ProtectionActionType.PLAYER_DAMAGE_PLAYER).outcome(),
                "the owner stays bound by the durable rule: no owner exemption");
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId,
                        ProtectionActionType.PLAYER_DAMAGE_PLAYER).outcome());
    }

    @Test
    void unsetRuleFallsBackToWorldGlobalBuiltin() {
        // World wins when the durable layer is unset.
        var worldWins = engine(
                configView(Map.of(LandRuleType.PISTON, PermissionState.DENY),
                        Map.of(worldId, Map.of(LandRuleType.PISTON, PermissionState.ALLOW))),
                authWithRules(Map.of()));
        assertEquals(PermissionState.ALLOW,
                worldWins.decide(UUID.randomUUID(), landId, ProtectionActionType.PISTON_MOVE)
                        .outcome());

        // Global wins when world is unset too.
        var globalWins = engine(
                configView(Map.of(LandRuleType.PISTON, PermissionState.DENY), Map.of()),
                authWithRules(Map.of()));
        assertEquals(PermissionState.DENY,
                globalWins.decide(UUID.randomUUID(), landId, ProtectionActionType.PISTON_MOVE)
                        .outcome());

        // Built-in closes PVP and leaves passive ambience alone.
        var builtin = engine(configView(Map.of(), Map.of()), authWithRules(Map.of()));
        assertEquals(PermissionState.DENY,
                builtin.decide(UUID.randomUUID(), landId,
                        ProtectionActionType.PLAYER_DAMAGE_PLAYER).outcome());
        assertEquals(PermissionState.ALLOW,
                builtin.decide(UUID.randomUUID(), landId,
                        ProtectionActionType.PASSIVE_MOB_SPAWN).outcome());
    }

    @Test
    void durableInheritFallsBackToWorld() {
        EnumMap<LandRuleType, PermissionState> states = new EnumMap<>(LandRuleType.class);
        states.put(LandRuleType.PISTON, PermissionState.INHERIT);
        var engine = engine(
                configView(Map.of(),
                        Map.of(worldId, Map.of(LandRuleType.PISTON, PermissionState.ALLOW))),
                authWithRules(Map.of(landId, Map.copyOf(states))));
        assertEquals(PermissionState.ALLOW,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.PISTON_MOVE)
                        .outcome(),
                "a durable INHERIT must fall through to the world default");
    }

    @Test
    void crossBoundaryHardLimitsIgnoreDurableAllow() {
        var engine = engine(configView(Map.of(), Map.of()),
                authWithRules(rulesFor(landId, LandRuleType.PISTON, PermissionState.ALLOW)));
        // The in-land rule still applies to the in-land action...
        assertEquals(PermissionState.ALLOW,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.PISTON_MOVE)
                        .outcome());
        // ...but nothing crossing the boundary opens with it.
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_MOVE_IN)
                        .outcome());
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_MOVE_OUT)
                        .outcome());

        var fluids = engine(configView(Map.of(), Map.of()), authWithRules(Map.of(landId,
                Map.of(LandRuleType.FLUID_FLOW, PermissionState.ALLOW,
                        LandRuleType.HOPPER_TRANSFER, PermissionState.ALLOW))));
        assertEquals(PermissionState.DENY,
                fluids.decide(UUID.randomUUID(), landId, ProtectionActionType.FLUID_ENTER)
                        .outcome());
        assertEquals(PermissionState.DENY,
                fluids.decide(UUID.randomUUID(), landId, ProtectionActionType.FLUID_EXIT)
                        .outcome());
        assertEquals(PermissionState.DENY,
                fluids.decide(UUID.randomUUID(), landId, ProtectionActionType.ITEM_TRANSFER_IN)
                        .outcome());
        assertEquals(PermissionState.DENY,
                fluids.decide(UUID.randomUUID(), landId, ProtectionActionType.ITEM_TRANSFER_OUT)
                        .outcome());
    }

    @Test
    void nullAuthorisationReadsAsUnloadedOnBothAtomicPaths() {
        // A null durable source must mean "unloaded" — like the subject
        // query treats it — never "loaded but empty". Both atomic paths
        // used to substitute the empty (loaded) snapshot here, so the rule
        // fell through to the world ALLOW below.
        LandRegistryStore store = new LandRegistryStore();
        store.publish(registry);
        var provider = SnapshotPermissionContextProvider.atomic(
                () -> configView(Map.of(),
                        Map.of(worldId, Map.of(LandRuleType.PVP, PermissionState.ALLOW))),
                () -> null);
        var engine = new ProtectionEngine(store::snapshot, provider);
        ProtectionActionType ruleAction = ProtectionActionType.PLAYER_DAMAGE_PLAYER;
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ruleAction).outcome(),
                "null durable source must fail the rule layer closed on the land path");
        assertEquals(PermissionState.DENY,
                engine.decideAtBlock(UUID.randomUUID(), worldId, 5, 64, 5, ruleAction).outcome(),
                "null durable source must fail the rule layer closed on the block path");
    }

    @Test
    void loadedEmptyStillFallsThroughOnBothAtomicPaths() {
        // Companion: a loaded snapshot without overrides keeps falling
        // through to the world default on both atomic paths.
        LandRegistryStore store = new LandRegistryStore();
        store.publish(registry);
        var provider = SnapshotPermissionContextProvider.atomic(
                () -> configView(Map.of(),
                        Map.of(worldId, Map.of(LandRuleType.PVP, PermissionState.ALLOW))),
                () -> authWithRules(Map.of()));
        var engine = new ProtectionEngine(store::snapshot, provider);
        ProtectionActionType ruleAction = ProtectionActionType.PLAYER_DAMAGE_PLAYER;
        assertEquals(PermissionState.ALLOW,
                engine.decide(UUID.randomUUID(), landId, ruleAction).outcome());
        assertEquals(PermissionState.ALLOW,
                engine.decideAtBlock(UUID.randomUUID(), worldId, 5, 64, 5, ruleAction).outcome());
    }

    @Test
    void unloadedSnapshotDeniesRuleActions() {
        // Changed from "keeps the config chain" (2026-10-14 review fix):
        // that expectation predates the durable rule source, when no
        // per-land rule could be unverifiable. Now an unloaded snapshot
        // means the land's own DENY may be sitting unread in the database,
        // so falling through to a world/global ALLOW would silently widen
        // protection. Unverifiable durable rules fail closed instead.
        var engine = engine(
                configView(Map.of(LandRuleType.PVP, PermissionState.ALLOW),
                        Map.of(worldId, Map.of(LandRuleType.PVP, PermissionState.ALLOW))),
                LandAuthorisationSnapshot.unloaded());
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId,
                        ProtectionActionType.PLAYER_DAMAGE_PLAYER).outcome(),
                "unverifiable durable rules must deny even when the world "
                        + "and global defaults allow");
        assertEquals(PermissionState.DENY,
                engine.decide(owner, landId,
                        ProtectionActionType.PLAYER_DAMAGE_PLAYER).outcome(),
                "the owner stays bound by the fail-closed rule too");
    }

    @Test
    void wildernessStaysVanillaWhileUnloaded() {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(registry);
        var engine = new ProtectionEngine(store::snapshot,
                SnapshotPermissionContextProvider.atomic(
                        () -> configView(Map.of(), Map.of()),
                        LandAuthorisationSnapshot::unloaded));
        assertEquals(PermissionState.ALLOW,
                engine.decideAt(UUID.randomUUID(), worldId, 9, 9,
                        ProtectionActionType.FIRE_SPREAD).outcome(),
                "fail-closed applies inside lands; wilderness keeps vanilla");
        assertEquals(PermissionState.ALLOW,
                engine.decideAt(UUID.randomUUID(), worldId, 9, 9,
                        ProtectionActionType.ENTRY).outcome(),
                "fail-closed applies inside lands; wilderness keeps vanilla");
    }

    @Test
    void alternatingGenerationsStayInLockstep() {
        PermissionDefaultsCache.ConfigView view = configView(Map.of(), Map.of());
        LandAuthorisationSnapshot genA =
                authWithRules(rulesFor(landId, LandRuleType.PVP, PermissionState.ALLOW));
        LandAuthorisationSnapshot genB =
                authWithRules(rulesFor(landId, LandRuleType.PVP, PermissionState.DENY));
        AtomicInteger viewCalls = new AtomicInteger();
        AtomicInteger authCalls = new AtomicInteger();
        var provider = SnapshotPermissionContextProvider.atomic(
                () -> {
                    viewCalls.incrementAndGet();
                    return view;
                },
                () -> {
                    // Odd calls serve A, even calls serve B: both suppliers must
                    // advance in lockstep, which only holds when each decision
                    // reads each supplier exactly once.
                    int seen = authCalls.incrementAndGet();
                    return seen % 2 == 1 ? genA : genB;
                });

        for (int i = 0; i < 4; i++) {
            var ctx = provider.provide(UUID.randomUUID(), landId,
                    ProtectionActionType.PLAYER_DAMAGE_PLAYER, registry);
            assertEquals(i % 2 == 0 ? PermissionState.ALLOW : PermissionState.DENY,
                    ctx.landRule(), "decision " + i + " must read a single generation");
        }
        assertEquals(4, viewCalls.get(), "one decision captures one config view");
        assertEquals(4, authCalls.get(), "one decision captures one durable snapshot");
    }

    @Test
    void concurrentFlipsKeepSingleCapturePerDecision() throws Exception {
        PermissionDefaultsCache.ConfigView viewA = configView(Map.of(), Map.of());
        PermissionDefaultsCache.ConfigView viewB = configView(
                Map.of(LandRuleType.PVP, PermissionState.ALLOW), Map.of());
        LandAuthorisationSnapshot authA =
                authWithRules(rulesFor(landId, LandRuleType.PVP, PermissionState.ALLOW));
        LandAuthorisationSnapshot authB =
                authWithRules(rulesFor(landId, LandRuleType.PVP, PermissionState.DENY));
        AtomicReference<PermissionDefaultsCache.ConfigView> views = new AtomicReference<>(viewA);
        AtomicReference<LandAuthorisationSnapshot> auths = new AtomicReference<>(authA);
        AtomicInteger viewCalls = new AtomicInteger();
        AtomicInteger authCalls = new AtomicInteger();
        var provider = SnapshotPermissionContextProvider.atomic(
                () -> {
                    viewCalls.incrementAndGet();
                    return views.get();
                },
                () -> {
                    authCalls.incrementAndGet();
                    return auths.get();
                });

        AtomicBoolean stop = new AtomicBoolean();
        Thread flipper = new Thread(() -> {
            boolean toggle = false;
            while (!stop.get()) {
                toggle = !toggle;
                views.set(toggle ? viewB : viewA);
                auths.set(toggle ? authB : authA);
            }
        });
        int readers = 4;
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        try {
            flipper.start();
            List<Future<?>> done = new java.util.ArrayList<>();
            for (int r = 0; r < readers; r++) {
                done.add(pool.submit(() -> {
                    for (int i = 0; i < iterations; i++) {
                        provider.provide(UUID.randomUUID(), landId,
                                ProtectionActionType.PLAYER_DAMAGE_PLAYER, registry);
                    }
                    return null;
                }));
            }
            for (Future<?> task : done) {
                task.get();
            }
        } finally {
            stop.set(true);
            flipper.join();
            pool.shutdownNow();
        }
        int total = readers * iterations;
        assertEquals(total, viewCalls.get(),
                "every decision captures exactly one config view, even mid-flip");
        assertEquals(total, authCalls.get(),
                "every decision captures exactly one durable snapshot, even mid-flip");
    }

    @Test
    void hydrationGateDeniesDespiteDurableAllow() {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(registry);
        var provider = SnapshotPermissionContextProvider.atomic(
                () -> configView(Map.of(), Map.of()),
                () -> authWithRules(rulesFor(landId, LandRuleType.PVP, PermissionState.ALLOW)));
        AtomicBoolean ready = new AtomicBoolean(false);
        var gated = new ProtectionEngine(store::snapshot, provider, ready::get);

        assertEquals(PermissionState.DENY,
                gated.decide(UUID.randomUUID(), landId,
                        ProtectionActionType.PLAYER_DAMAGE_PLAYER).outcome(),
                "an unconfirmed index must deny instead of deciding from durable rules");

        ready.set(true);
        assertEquals(PermissionState.ALLOW,
                gated.decide(UUID.randomUUID(), landId,
                        ProtectionActionType.PLAYER_DAMAGE_PLAYER).outcome(),
                "the same durable rule decides once hydration confirms the index");
    }

    @Test
    void ruleAndSubjectLayersPublishTogether() {
        // One refresh publishes rules and authorisation data in a single
        // snapshot: after the publish both halves answer from it at once.
        EnumMap<LandRuleType, PermissionState> states = new EnumMap<>(LandRuleType.class);
        states.put(LandRuleType.PVP, PermissionState.ALLOW);
        EnumMap<com.smile.chunkland.api.permission.ProtectionActionType, PermissionState> defaults =
                new EnumMap<>(com.smile.chunkland.api.permission.ProtectionActionType.class);
        defaults.put(ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW);
        LandAuthorisationSnapshot published = LandAuthorisationSnapshot.empty()
                .withDirect(Map.of(), Map.of(landId, Map.copyOf(defaults)), Map.of(),
                        Map.of(landId, Map.copyOf(states)), Map.of(landId, 7L));

        var provider = SnapshotPermissionContextProvider.atomic(
                () -> configView(Map.of(), Map.of()), () -> published);
        var ruleCtx = provider.provide(UUID.randomUUID(), landId,
                ProtectionActionType.PLAYER_DAMAGE_PLAYER, registry);
        var subjectCtx = provider.provide(UUID.randomUUID(), landId,
                ProtectionActionType.BLOCK_BREAK, registry);
        assertEquals(PermissionState.ALLOW, ruleCtx.landRule());
        assertEquals(PermissionState.ALLOW, subjectCtx.landDefault());
        assertEquals(7L, published.policyRevision(landId).orElseThrow());
        assertTrue(published.loaded());
    }
}

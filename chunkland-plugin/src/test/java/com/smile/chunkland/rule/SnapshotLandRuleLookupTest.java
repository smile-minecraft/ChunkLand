package com.smile.chunkland.rule;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import com.smile.chunkland.protection.PermissionDefaultsCache;
import com.smile.chunkland.protection.PermissionDefaultsSnapshot;
import com.smile.chunkland.protection.ProtectionEngine;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.rule.LandRuleService;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The snapshot rule lookup shares the enforcement chain.
 */
class SnapshotLandRuleLookupTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private record Env(UUID world, LandId land, LandRegistry registry) {
    }

    private static Env env() {
        UUID world = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        LandRegistry registry = LandRegistry.from(List.of(
                new LandSnapshot(land, "Home", "home", OwnerRef.player(UUID.randomUUID()),
                        world, Set.of(new ChunkKey(world, 0, 0)),
                        List.of(), 0, 0, NOW, NOW)));
        return new Env(world, land, registry);
    }

    private static LandAuthorisationSnapshot authWith(LandId land,
            Map<LandRuleType, PermissionState> rules) {
        EnumMap<LandRuleType, PermissionState> states = new EnumMap<>(LandRuleType.class);
        states.putAll(rules);
        if (states.isEmpty()) {
            return LandAuthorisationSnapshot.empty();
        }
        return LandAuthorisationSnapshot.empty()
                .withDirect(Map.of(), Map.of(), Map.of(),
                        Map.of(land, Map.copyOf(states)),
                        Map.of(land, 0L));
    }

    private static PermissionDefaultsCache.ConfigView viewWith(Env env,
            Map<LandRuleType, PermissionState> worldRules,
            Map<LandRuleType, PermissionState> globalRules) {
        Map<UUID, Map<LandRuleType, PermissionState>> worlds = worldRules.isEmpty()
                ? Map.of()
                : Map.of(env.world(), Map.copyOf(worldRules));
        PermissionDefaultsSnapshot snapshot = new PermissionDefaultsSnapshot(
                Map.of(), Map.of(),
                Map.copyOf(globalRules), worlds);
        return new PermissionDefaultsCache.ConfigView(snapshot,
                LandRuleService.fromRuleSnapshot(snapshot), 0L, Map.of());
    }

    private static PermissionDefaultsCache.ConfigView emptyView() {
        PermissionDefaultsSnapshot snapshot = PermissionDefaultsSnapshot.empty();
        return new PermissionDefaultsCache.ConfigView(snapshot,
                LandRuleService.fromRuleSnapshot(snapshot), 0L, Map.of());
    }

    @ParameterizedTest
    @EnumSource(LandRuleType.class)
    void explicitValuesWin(LandRuleType rule) {
        Env env = env();
        for (PermissionState explicit : List.of(PermissionState.ALLOW, PermissionState.DENY)) {
            SnapshotLandRuleLookup lookup = new SnapshotLandRuleLookup(
                    () -> emptyView(), () -> authWith(env.land(), Map.of(rule, explicit)));
            assertEquals(Optional.of(explicit), lookup.getRule(env.land(), rule, env.registry()));
        }
    }

    @Test
    void worldFallbackWinsWhenLandInherits() {
        Env env = env();
        LandRuleType rule = LandRuleType.PVP;
        PermissionDefaultsCache.ConfigView view = viewWith(env, Map.of(rule, PermissionState.ALLOW), Map.of());
        SnapshotLandRuleLookup lookup = new SnapshotLandRuleLookup(
                () -> view, () -> authWith(env.land(), Map.of()));
        assertEquals(Optional.of(PermissionState.ALLOW), lookup.getRule(env.land(), rule, env.registry()));
    }

    @Test
    void globalFallbackWinsWhenWorldInherits() {
        Env env = env();
        LandRuleType rule = LandRuleType.FIRE_SPREAD;
        PermissionDefaultsCache.ConfigView view = viewWith(env, Map.of(), Map.of(rule, PermissionState.DENY));
        SnapshotLandRuleLookup lookup = new SnapshotLandRuleLookup(
                () -> view, () -> authWith(env.land(), Map.of()));
        assertEquals(Optional.of(PermissionState.DENY), lookup.getRule(env.land(), rule, env.registry()));
    }

    @Test
    void builtInFallbackWinsWhenAllInherit() {
        Env env = env();
        SnapshotLandRuleLookup lookup = new SnapshotLandRuleLookup(
                () -> emptyView(), () -> authWith(env.land(), Map.of()));
        for (LandRuleType rule : LandRuleType.values()) {
            assertEquals(Optional.of(LandRuleService.builtInDefaults().get(rule)),
                    lookup.getRule(env.land(), rule, env.registry()));
        }
    }

    @Test
    void unknownLandAnswersEmptyWithoutTouchingSources() {
        Env env = env();
        AtomicInteger viewsTaken = new AtomicInteger();
        AtomicInteger authsTaken = new AtomicInteger();
        SnapshotLandRuleLookup lookup = new SnapshotLandRuleLookup(
                () -> {
                    viewsTaken.incrementAndGet();
                    return emptyView();
                },
                () -> {
                    authsTaken.incrementAndGet();
                    return authWith(env.land(), Map.of());
                });
        // The existence check runs before either source is read, so a miss
        // must answer empty without capturing a view or a durable snapshot.
        assertTrue(lookup.getRule(new LandId(UUID.randomUUID()), LandRuleType.PVP, env.registry()).isEmpty());
        assertEquals(0, viewsTaken.get(), "a miss must not read the config view");
        assertEquals(0, authsTaken.get(), "a miss must not read the durable snapshot");
    }

    @Test
    void unloadedDurableAnswersEmpty() {
        Env env = env();
        SnapshotLandRuleLookup lookup = new SnapshotLandRuleLookup(
                () -> emptyView(), LandAuthorisationSnapshot::unloaded);
        assertTrue(lookup.getRule(env.land(), LandRuleType.PVP, env.registry()).isEmpty());
    }

    @Test
    void throwingSourcesAnswerEmpty() {
        Env env = env();
        assertTrue(new SnapshotLandRuleLookup(
                () -> {
                    throw new IllegalStateException("config view unreadable");
                },
                () -> authWith(env.land(), Map.of())).getRule(env.land(), LandRuleType.PVP, env.registry()).isEmpty());
        assertTrue(new SnapshotLandRuleLookup(
                () -> emptyView(),
                () -> {
                    throw new IllegalStateException("durable snapshot unreadable");
                }).getRule(env.land(), LandRuleType.PVP, env.registry()).isEmpty());
    }

    @Test
    void nullSourcesAnswerEmpty() {
        Env env = env();
        assertTrue(new SnapshotLandRuleLookup(null, () -> authWith(env.land(), Map.of()))
                .getRule(env.land(), LandRuleType.PVP, env.registry()).isEmpty());
        assertTrue(new SnapshotLandRuleLookup(() -> emptyView(), null)
                .getRule(env.land(), LandRuleType.PVP, env.registry()).isEmpty());
    }

    @Test
    void usesTheSameSnapshotTheCallerObserved() {
        Env env = env();
        LandRegistry published = env.registry();
        SnapshotLandRuleLookup lookup = new SnapshotLandRuleLookup(
                () -> emptyView(), () -> authWith(env.land(), Map.of(LandRuleType.PVP, PermissionState.ALLOW)));
        // A different publication with no lands must not leak into this read.
        LandRegistry other = LandRegistry.empty();
        assertEquals(Optional.of(PermissionState.ALLOW), lookup.getRule(env.land(), LandRuleType.PVP, published));
        assertTrue(lookup.getRule(env.land(), LandRuleType.PVP, other).isEmpty());
    }

    @Test
    void inheritExplicitFallsThroughInsteadOfAnsweringEmpty() {
        Env env = env();
        // An explicit INHERIT entry is the same as unset: it falls through to world/global/built-in.
        EnumMap<LandRuleType, PermissionState> states = new EnumMap<>(LandRuleType.class);
        states.put(LandRuleType.PVP, PermissionState.INHERIT);
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.empty()
                .withDirect(Map.of(), Map.of(), Map.of(),
                        Map.of(env.land(), Map.copyOf(states)),
                        Map.of(env.land(), 0L));
        SnapshotLandRuleLookup lookup = new SnapshotLandRuleLookup(() -> emptyView(), () -> auth);
        assertEquals(Optional.of(LandRuleService.builtInDefaults().get(LandRuleType.PVP)),
                lookup.getRule(env.land(), LandRuleType.PVP, env.registry()));
    }

    @Test
    void readPathTouchesNoForbiddenRuntime() throws Exception {
        var root = java.nio.file.Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (java.nio.file.Files.exists(root.resolve("settings.gradle.kts"))
                    && java.nio.file.Files.isDirectory(root.resolve("chunkland-api"))) {
                break;
            }
            var parent = root.getParent();
            if (parent == null) {
                break;
            }
            root = parent;
        }
        var path = root.resolve(
                "chunkland-plugin/src/main/java/com/smile/chunkland/rule/SnapshotLandRuleLookup.java");
        if (!java.nio.file.Files.exists(path)) {
            path = java.nio.file.Paths.get(
                    "chunkland-plugin/src/main/java/com/smile/chunkland/rule/SnapshotLandRuleLookup.java");
        }
        String src = java.nio.file.Files.readString(path);
        String lower = src.toLowerCase();
        assertFalse(lower.contains("org.bukkit"), "rule lookup must not reference Bukkit");
        assertFalse(lower.contains("getplayer"), "rule lookup must never read a player object");
        assertFalse(lower.contains("java.sql"), "rule lookup must not reference SQL");
        assertFalse(lower.contains("jdbc"), "rule lookup must not reference JDBC");
        assertFalse(lower.contains("sqlite"), "rule lookup must not reference SQLite");
        assertFalse(lower.contains("getchunk"), "rule lookup must never load a chunk");
        assertFalse(lower.contains("thread.sleep"), "rule lookup must not block");
        assertFalse(lower.contains("future.get"), "rule lookup must not wait on a future");
        assertFalse(lower.contains("synchronized"), "rule lookup must not use blocking locks");
    }

    @Test
    void eachReadCapturesEachSourceExactlyOnce() {
        Env env = env();
        AtomicInteger viewsTaken = new AtomicInteger();
        AtomicInteger authsTaken = new AtomicInteger();
        PermissionDefaultsCache.ConfigView view = emptyView();
        LandAuthorisationSnapshot auth = authWith(env.land(), Map.of(LandRuleType.PVP, PermissionState.ALLOW));
        SnapshotLandRuleLookup lookup = new SnapshotLandRuleLookup(
                () -> {
                    viewsTaken.incrementAndGet();
                    return view;
                },
                () -> {
                    authsTaken.incrementAndGet();
                    return auth;
                });
        assertEquals(Optional.of(PermissionState.ALLOW),
                lookup.getRule(env.land(), LandRuleType.PVP, env.registry()));
        assertEquals(1, viewsTaken.get(), "one read must capture one config view");
        assertEquals(1, authsTaken.get(), "one read must capture one durable snapshot");
    }

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

    @Test
    void worldAndGlobalLayersMatchTheEngine() {
        Env env = env();
        // World wins where set, global wins where the world inherits, built-in covers the rest.
        PermissionDefaultsCache.ConfigView view = viewWith(env,
                Map.of(LandRuleType.PVP, PermissionState.ALLOW,
                        LandRuleType.FIRE_SPREAD, PermissionState.ALLOW),
                Map.of(LandRuleType.FIRE_SPREAD, PermissionState.DENY,
                        LandRuleType.EXPLOSION_TERRAIN, PermissionState.DENY));
        LandAuthorisationSnapshot auth = authWith(env.land(), Map.of());
        SnapshotLandRuleLookup lookup = new SnapshotLandRuleLookup(() -> view, () -> auth);
        LandRegistryStore store = new LandRegistryStore();
        store.publish(env.registry());
        ProtectionEngine engine = new ProtectionEngine(store::snapshot,
                SnapshotPermissionContextProvider.atomic(() -> view, () -> auth), () -> true);
        UUID stranger = UUID.randomUUID();
        for (LandRuleType rule : LandRuleType.values()) {
            Optional<PermissionState> shown = lookup.getRule(env.land(), rule, env.registry());
            assertTrue(shown.isPresent(), rule + " must resolve");
            assertEquals(engine.decide(stranger, env.land(), actionFor(rule)).outcome(), shown.get(),
                    rule + ": the public query must equal the engine decision");
        }
        assertEquals(Optional.of(PermissionState.ALLOW),
                lookup.getRule(env.land(), LandRuleType.PVP, env.registry()),
                "world ALLOW must win over the unset global layer");
        assertEquals(Optional.of(PermissionState.ALLOW),
                lookup.getRule(env.land(), LandRuleType.FIRE_SPREAD, env.registry()),
                "world ALLOW must win over global DENY");
        assertEquals(Optional.of(PermissionState.DENY),
                lookup.getRule(env.land(), LandRuleType.EXPLOSION_TERRAIN, env.registry()),
                "global DENY must win where the world inherits");
    }

    @Test
    void concurrentPublishNeverMixesGenerations() throws Exception {
        Env env = env();
        // Both generations answer ALLOW through different layers: A through the
        // land override, B through the global default. Every view/auth pairing
        // across the two generations still answers ALLOW, while an
        // implementation reading world and global through separate view reads
        // could pair B's empty world layer with A's empty global layer and fall
        // through to the built-in DENY.
        PermissionDefaultsCache.ConfigView viewA =
                viewWith(env, Map.of(LandRuleType.PVP, PermissionState.ALLOW), Map.of());
        PermissionDefaultsCache.ConfigView viewB =
                viewWith(env, Map.of(), Map.of(LandRuleType.PVP, PermissionState.ALLOW));
        LandAuthorisationSnapshot authA = authWith(env.land(), Map.of(LandRuleType.PVP, PermissionState.ALLOW));
        LandAuthorisationSnapshot authB = authWith(env.land(), Map.of());
        AtomicReference<PermissionDefaultsCache.ConfigView> views = new AtomicReference<>(viewA);
        AtomicReference<LandAuthorisationSnapshot> auths = new AtomicReference<>(authA);
        SnapshotLandRuleLookup lookup = new SnapshotLandRuleLookup(views::get, auths::get);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger publishes = new AtomicInteger();
        CountDownLatch firstPublish = new CountDownLatch(1);
        Thread publisher = new Thread(() -> {
            boolean flip = false;
            while (!stop.get()) {
                if (flip) {
                    views.set(viewA);
                    auths.set(authA);
                } else {
                    views.set(viewB);
                    auths.set(authB);
                }
                flip = !flip;
                publishes.incrementAndGet();
                firstPublish.countDown();
            }
        }, "rule-mix-publisher");
        publisher.start();
        try {
            assertTrue(firstPublish.await(10, TimeUnit.SECONDS),
                    "the publisher must publish before reads begin");
            for (int i = 0; i < 5000; i++) {
                Optional<PermissionState> found = lookup.getRule(env.land(), LandRuleType.PVP, env.registry());
                assertEquals(Optional.of(PermissionState.ALLOW), found,
                        "read " + i + " must match one generation, never a mix");
            }
        } finally {
            stop.set(true);
            publisher.join(10_000);
        }
        assertTrue(publishes.get() > 0, "the publisher must have published at least once");
    }
}

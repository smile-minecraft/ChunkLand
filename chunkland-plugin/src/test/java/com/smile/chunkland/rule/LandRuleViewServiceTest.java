package com.smile.chunkland.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import com.smile.chunkland.protection.PermissionDefaultsSnapshot;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Explicit, effective and source view over one land: the per-land override
 * decides first, then the world default, then the global default, then the
 * built-in default. An unloaded authorisation snapshot or an unknown land
 * yields no view at all, so callers show "unavailable" instead of a wrong
 * source.
 */
class LandRuleViewServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private record Env(UUID world, LandId land, UUID owner, LandRegistry registry) {
    }

    private static Env env() {
        UUID world = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistry registry = LandRegistry.from(List.of(
                new LandSnapshot(land, "Home", "home", OwnerRef.player(owner),
                        world, Set.of(new ChunkKey(world, 0, 0)),
                        List.of(), 0, 0, NOW, NOW)));
        return new Env(world, land, owner, registry);
    }

    private static LandAuthorisationSnapshot authWith(Env env,
            Map<LandRuleType, PermissionState> rules) {
        EnumMap<LandRuleType, PermissionState> states = new EnumMap<>(LandRuleType.class);
        states.putAll(rules);
        return LandAuthorisationSnapshot.empty()
                .withDirect(Map.of(), Map.of(), Map.of(),
                        Map.of(env.land(), Map.copyOf(states)),
                        Map.of(env.land(), 0L));
    }

    private static Optional<List<LandRuleView>> all(Env env,
            LandAuthorisationSnapshot auth, PermissionDefaultsSnapshot defaults) {
        return LandRuleViewService.all(env.land(), env.registry(), auth, defaults);
    }

    private static LandRuleView only(List<LandRuleView> views, LandRuleType rule) {
        return views.stream().filter(view -> view.rule() == rule).findFirst()
                .orElseThrow(() -> new AssertionError("no view for " + rule));
    }

    @ParameterizedTest
    @EnumSource(LandRuleType.class)
    void explicitAllowAndDenyWinWithLandSource(LandRuleType rule) {
        Env env = env();
        for (PermissionState explicit : List.of(PermissionState.ALLOW, PermissionState.DENY)) {
            LandRuleView view = only(
                    all(env, authWith(env, Map.of(rule, explicit)),
                            PermissionDefaultsSnapshot.empty()).orElseThrow(),
                    rule);
            assertEquals(explicit, view.explicit());
            assertEquals(explicit, view.effective());
            assertEquals(LandRuleView.Source.LAND, view.source());
        }
    }

    @Test
    void inheritFallsThroughWorldThenGlobalThenBuiltIn() {
        Env env = env();
        LandRuleType rule = LandRuleType.PISTON;

        LandRuleView worldWins = only(
                all(env, authWith(env, Map.of()),
                        new PermissionDefaultsSnapshot(
                                Map.<ProtectionActionType, PermissionState>of(),
                                Map.<UUID, Map<ProtectionActionType, PermissionState>>of(),
                                Map.<LandRuleType, PermissionState>of(),
                                Map.of(env.world(), Map.of(rule, PermissionState.ALLOW))))
                        .orElseThrow(),
                rule);
        assertEquals(PermissionState.INHERIT, worldWins.explicit());
        assertEquals(PermissionState.ALLOW, worldWins.effective());
        assertEquals(LandRuleView.Source.WORLD, worldWins.source());

        LandRuleView globalWins = only(
                all(env, authWith(env, Map.of()),
                        new PermissionDefaultsSnapshot(
                                Map.<ProtectionActionType, PermissionState>of(),
                                Map.<UUID, Map<ProtectionActionType, PermissionState>>of(),
                                Map.of(rule, PermissionState.DENY),
                                Map.<UUID, Map<LandRuleType, PermissionState>>of()))
                        .orElseThrow(),
                rule);
        assertEquals(PermissionState.INHERIT, globalWins.explicit());
        assertEquals(PermissionState.DENY, globalWins.effective());
        assertEquals(LandRuleView.Source.GLOBAL, globalWins.source());

        LandRuleView builtIn = only(
                all(env, authWith(env, Map.of()),
                        PermissionDefaultsSnapshot.empty()).orElseThrow(),
                LandRuleType.PVP);
        assertEquals(PermissionState.INHERIT, builtIn.explicit());
        assertEquals(PermissionState.DENY, builtIn.effective());
        assertEquals(LandRuleView.Source.BUILT_IN, builtIn.source());
    }

    @Test
    void unsetLandWithWorldSetProvesTheWorldSource() {
        Env env = env();
        List<LandRuleView> views = all(env, authWith(env, Map.of()),
                new PermissionDefaultsSnapshot(
                        Map.<ProtectionActionType, PermissionState>of(),
                        Map.<UUID, Map<ProtectionActionType, PermissionState>>of(),
                        Map.<LandRuleType, PermissionState>of(),
                        Map.of(env.world(), Map.of(LandRuleType.FIRE_SPREAD,
                                PermissionState.ALLOW)))).orElseThrow();
        LandRuleView view = only(views, LandRuleType.FIRE_SPREAD);
        assertEquals(PermissionState.INHERIT, view.explicit(),
                "the land sets nothing here");
        assertEquals(PermissionState.ALLOW, view.effective());
        assertEquals(LandRuleView.Source.WORLD, view.source(),
                "the effective value comes from the world layer, not the land");
    }

    @Test
    void allElevenRulesResolveToAConcreteEffective() {
        Env env = env();
        List<LandRuleView> views = all(env, authWith(env, Map.of()),
                PermissionDefaultsSnapshot.empty()).orElseThrow();
        assertEquals(11, views.size());
        for (LandRuleView view : views) {
            assertTrue(view.effective() == PermissionState.ALLOW
                    || view.effective() == PermissionState.DENY,
                    view.rule() + " must resolve concretely, got " + view.effective());
            assertTrue(view.source() != LandRuleView.Source.UNAVAILABLE);
        }
    }

    @Test
    void unloadedSnapshotYieldsNoView() {
        Env env = env();
        assertTrue(LandRuleViewService.all(env.land(), env.registry(),
                LandAuthorisationSnapshot.unloaded(),
                PermissionDefaultsSnapshot.empty()).isEmpty(),
                "an unloaded snapshot must read as unavailable, never as INHERIT sources");
        assertTrue(LandRuleViewService.one(env.land(), LandRuleType.PVP, env.registry(),
                LandAuthorisationSnapshot.unloaded(),
                PermissionDefaultsSnapshot.empty()).isEmpty());
    }

    @Test
    void unknownLandYieldsNoView() {
        Env env = env();
        assertTrue(LandRuleViewService.all(new LandId(UUID.randomUUID()), env.registry(),
                authWith(env, Map.of()), PermissionDefaultsSnapshot.empty()).isEmpty());
    }

    @Test
    void nullInputsYieldNoView() {
        Env env = env();
        LandAuthorisationSnapshot auth = authWith(env, Map.of());
        assertTrue(LandRuleViewService.all(null, env.registry(), auth,
                PermissionDefaultsSnapshot.empty()).isEmpty());
        assertTrue(LandRuleViewService.all(env.land(), null, auth,
                PermissionDefaultsSnapshot.empty()).isEmpty());
        assertTrue(LandRuleViewService.all(env.land(), env.registry(), null,
                PermissionDefaultsSnapshot.empty()).isEmpty());
        assertTrue(LandRuleViewService.all(env.land(), env.registry(), auth, null).isEmpty());
    }
}

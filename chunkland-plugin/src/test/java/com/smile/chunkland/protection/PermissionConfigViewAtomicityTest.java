package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigLoader;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.config.ConfigService;
import com.smile.chunkland.protection.PermissionDefaultsCache.ConfigView;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.rule.LandRuleService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * One decision uses one immutable config view.
 *
 * <p>No {@code COMBINED} production action exists, so a single
 * {@link ProtectionActionType} never exposes both the subject and rule
 * halves in one {@link PermissionContext}. Atomicity is therefore proven
 * two ways without adding test-only production actions: the view supplier
 * is read exactly once per {@code provide()} call (a second read would
 * observe the next generation), and each captured {@link ConfigView}
 * internally pairs subject and rule values from the same generation.
 * All coordination is single-threaded and latch/counter based; no sleep
 * is used for correctness.
 */
class PermissionConfigViewAtomicityTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static final class MutableYamlLoader implements ConfigLoader {
        private volatile String yaml;

        MutableYamlLoader(String yaml) {
            this.yaml = yaml;
        }

        void setYaml(String yaml) {
            this.yaml = yaml;
        }

        @Override
        public ChunkLandConfig load() {
            return ConfigSchema.parseYamlText(yaml);
        }

        @Override
        public String describe() {
            return "mutable-yaml";
        }
    }

    private static LandRegistry registryWith(UUID worldId, LandId landId, UUID owner) {
        return LandRegistry.from(List.of(
                new LandSnapshot(landId, "TestLand", "testland", OwnerRef.player(owner),
                        worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                        List.of(), 0, 0, NOW, NOW)));
    }

    private static ConfigView viewFor(PermissionState subject, PermissionState rule) {
        PermissionDefaultsSnapshot snapshot = new PermissionDefaultsSnapshot(
                Map.of(ProtectionActionType.BLOCK_BREAK, subject),
                Map.of(),
                Map.of(LandRuleType.PVP, rule),
                Map.of());
        return new ConfigView(snapshot, LandRuleService.fromRuleSnapshot(snapshot));
    }

    @Test
    void providerReadsViewExactlyOncePerDecision() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistry registry = registryWith(worldId, landId, owner);
        ConfigView oldView = viewFor(PermissionState.ALLOW, PermissionState.ALLOW);
        ConfigView newView = viewFor(PermissionState.DENY, PermissionState.DENY);
        AtomicInteger calls = new AtomicInteger();
        Supplier<ConfigView> views = () -> {
            int seen = calls.incrementAndGet();
            return seen == 1 ? oldView : newView;
        };
        var provider = SnapshotPermissionContextProvider.atomic(
                views, LandAuthorisationSnapshot::empty);

        // Subject action exposes the subject half; a second view read inside
        // the same decision would have returned the NEW generation instead.
        PermissionContext subjectCtx = provider.provide(
                UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK, registry);
        assertEquals(1, calls.get(), "one decision must capture one config view");
        assertEquals(PermissionState.ALLOW, subjectCtx.globalDefault());

        // Rule action exposes the rule half from its own single capture; the
        // supplier now serves the NEW generation to the next decision, which
        // proves reloads still apply to new readers without touching the
        // previous decision.
        PermissionContext ruleCtx = provider.provide(
                UUID.randomUUID(), landId, ProtectionActionType.PLAYER_DAMAGE_PLAYER, registry);
        assertEquals(2, calls.get(), "each decision captures exactly one view");
        assertEquals(PermissionState.DENY, ruleCtx.landRule(),
                "second decision must read the reloaded view through the same provider");
    }

    @Test
    void singleViewNeverMixesSubjectAndRuleGenerations() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistry registry = registryWith(worldId, landId, owner);
        MutableYamlLoader loader = new MutableYamlLoader(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: ALLOW\n"
                + "rule-defaults:\n"
                + "  global:\n"
                + "    PVP: ALLOW\n");
        ConfigService service = new ConfigService(loader);
        PermissionDefaultsCache cache = new PermissionDefaultsCache(service::current,
                name -> Optional.empty(), ignored -> {
                });
        service.addListener(cache);

        ConfigView before = cache.view();
        assertEquals(PermissionState.ALLOW,
                before.snapshot().subjectGlobalDefault(ProtectionActionType.BLOCK_BREAK));
        assertEquals(Optional.of(PermissionState.ALLOW),
                before.rules().getRule(landId, LandRuleType.PVP, registry));

        loader.setYaml(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: DENY\n"
                + "rule-defaults:\n"
                + "  global:\n"
                + "    PVP: DENY\n");
        service.reload();

        ConfigView after = cache.view();
        assertEquals(PermissionState.DENY,
                after.snapshot().subjectGlobalDefault(ProtectionActionType.BLOCK_BREAK));
        assertEquals(Optional.of(PermissionState.DENY),
                after.rules().getRule(landId, LandRuleType.PVP, registry));
        // Old readers finish on their captured view; the reload never rewrites it.
        assertEquals(PermissionState.ALLOW,
                before.snapshot().subjectGlobalDefault(ProtectionActionType.BLOCK_BREAK));
        assertEquals(Optional.of(PermissionState.ALLOW),
                before.rules().getRule(landId, LandRuleType.PVP, registry));
    }

    @Test
    void atomicProviderFollowsReloadsWithoutRebuilding() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        UUID owner = UUID.randomUUID();
        store.publish(registryWith(worldId, landId, owner));
        MutableYamlLoader loader = new MutableYamlLoader(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: ALLOW\n");
        ConfigService service = new ConfigService(loader);
        PermissionDefaultsCache cache = new PermissionDefaultsCache(service::current,
                name -> Optional.empty(), ignored -> {
                });
        // Successful empty load, so the assertions below track config
        // generations instead of the unloaded fail-closed DENY.
        cache.attachLandAuthorisation(LandAuthorisationSnapshot::empty);
        service.addListener(cache);
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, cache.provider());

        assertEquals(PermissionState.ALLOW,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK).outcome());

        loader.setYaml(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: DENY\n");
        service.reload();

        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK).outcome(),
                "new decisions must read the reloaded view through the same provider instance");
    }

    @Test
    void atomicProviderFailsClosedOnMissingView() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistry registry = registryWith(worldId, landId, UUID.randomUUID());
        var provider = SnapshotPermissionContextProvider.atomic(null, null);
        LandRegistryStore store = new LandRegistryStore();
        store.publish(registry);
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, provider);
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK).outcome());
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.PLAYER_DAMAGE_PLAYER).outcome());

        var throwing = SnapshotPermissionContextProvider.atomic(
                () -> {
                    throw new RuntimeException("view backend boom");
                },
                () -> {
                    throw new RuntimeException("land backend boom");
                });
        PermissionContext ctx = throwing.provide(
                UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK, registry);
        assertEquals(PermissionState.INHERIT, ctx.globalDefault());
        assertEquals(PermissionState.INHERIT, ctx.landRule());
    }
}

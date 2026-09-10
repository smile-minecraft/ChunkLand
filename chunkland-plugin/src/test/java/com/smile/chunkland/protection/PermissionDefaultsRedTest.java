package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigLoader;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.config.ConfigService;
import com.smile.chunkland.config.ConfigValidationException;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.rule.LandRuleService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the Direct Trust defaults foundation (written
 * Red-first against the old code, now Green).
 *
 * <p>Subject defaults and rule defaults are two separate config namespaces
 * resolved once into an immutable UUID-keyed snapshot. The production
 * provider serves them on the world/global layers with empty direct
 * bindings; the two namespaces never decide each other's chain; reloads
 * publish new values without polluting previously captured snapshots; and
 * malformed input fails closed.
 */
class PermissionDefaultsRedTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static LandRegistryStore storeWith(UUID worldId, LandId id, UUID owner) {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(owner),
                        worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                        List.of(), 0, 0, NOW, NOW))));
        return store;
    }

    /** Loader whose YAML can be swapped between reloads. */
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

    private static ProtectionEngine engineWithDefaults(LandRegistryStore store,
                                                       PermissionDefaultsCache cache) {
        return new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(
                        cache.ruleLookup(), cache.subjectLookup()));
    }

    // 1) Typed config parse: subject namespace.
    @Test
    void subjectDefaultsSectionParsesAlongsideExistingSections() {
        String yaml = ""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: DENY\n"
                + "  worlds:\n"
                + "    world:\n"
                + "      BLOCK_BREAK: ALLOW\n"
                + "worlds:\n"
                + "  world:\n"
                + "    claim-enabled: true\n";
        ChunkLandConfig config = assertDoesNotThrow(() -> ConfigSchema.parseYamlText(yaml));
        assertTrue(config.worlds().get("world").claimEnabled(),
                "existing sections must keep working next to the new namespace");
        assertEquals(PermissionState.DENY,
                config.subjectDefaults().global().get(ProtectionActionType.BLOCK_BREAK));
        assertEquals(PermissionState.ALLOW, config.subjectDefaults().worlds()
                .get("world").get(ProtectionActionType.BLOCK_BREAK));
        assertTrue(config.ruleDefaults().global().isEmpty(),
                "the untouched rule namespace stays empty");
    }

    // 1) Typed config parse: rule namespace.
    @Test
    void ruleDefaultsSectionParsesAlongsideExistingSections() {
        String yaml = ""
                + "rule-defaults:\n"
                + "  global:\n"
                + "    PVP: DENY\n"
                + "  worlds:\n"
                + "    world:\n"
                + "      PVP: ALLOW\n";
        ChunkLandConfig config = assertDoesNotThrow(() -> ConfigSchema.parseYamlText(yaml));
        assertEquals(PermissionState.DENY,
                config.ruleDefaults().global().get(LandRuleType.PVP));
        assertEquals(PermissionState.ALLOW,
                config.ruleDefaults().worlds().get("world").get(LandRuleType.PVP));
        assertTrue(config.subjectDefaults().global().isEmpty(),
                "the untouched subject namespace stays empty");
    }

    // 2) Load-time name -> UUID snapshot: known resolves, unknown warns and is ignored.
    @Test
    void defaultsSnapshotResolvesKnownWorldsAndIgnoresUnknown() {
        UUID worldId = UUID.randomUUID();
        ChunkLandConfig config = ConfigSchema.parseYamlText(""
                + "subject-defaults:\n"
                + "  worlds:\n"
                + "    world:\n"
                + "      BLOCK_BREAK: ALLOW\n"
                + "    ghost:\n"
                + "      BLOCK_BREAK: ALLOW\n");
        List<String> warnings = new ArrayList<>();
        PermissionDefaultsSnapshot snapshot = PermissionDefaultsSnapshot.resolve(config,
                name -> Optional.ofNullable("world".equals(name) ? worldId : null),
                warnings::add);
        assertEquals(PermissionState.ALLOW,
                snapshot.subjectWorldDefault(worldId, ProtectionActionType.BLOCK_BREAK));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("ghost")),
                "unknown world entry must warn: " + warnings);
        assertEquals(1, snapshot.subjectWorlds().size(),
                "orphan entry must not enter the snapshot");
    }

    // 3) Empty bindings still reach configured world/global subject defaults.
    @Test
    void strangerReachesWorldDefaultWithEmptyBindings() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = storeWith(worldId, landId, UUID.randomUUID());
        MutableYamlLoader loader = new MutableYamlLoader(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: DENY\n"
                + "  worlds:\n"
                + "    world:\n"
                + "      BLOCK_BREAK: ALLOW\n");
        ConfigService service = new ConfigService(loader);
        PermissionDefaultsCache cache = new PermissionDefaultsCache(service::current,
                name -> Optional.ofNullable("world".equals(name) ? worldId : null),
                ignored -> {
                });
        ProtectionEngine engine = engineWithDefaults(store, cache);
        assertEquals(PermissionState.ALLOW,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK).outcome(),
                "world default ALLOW wins over global default DENY with no bindings");
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_PLACE).outcome(),
                "unconfigured actions stay implicit DENY");
    }

    // 3b) Rule chain reads the independent rule namespace, owner included.
    @Test
    void ruleDefaultDecidesRuleChainForOwnerAndStranger() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistryStore store = storeWith(worldId, landId, owner);
        ConfigService service = new ConfigService(new MutableYamlLoader(""
                + "rule-defaults:\n"
                + "  global:\n"
                + "    PVP: DENY\n"
                + "  worlds:\n"
                + "    world:\n"
                + "      PVP: ALLOW\n"));
        PermissionDefaultsCache cache = new PermissionDefaultsCache(service::current,
                name -> Optional.ofNullable("world".equals(name) ? worldId : null),
                ignored -> {
                });
        ProtectionEngine engine = engineWithDefaults(store, cache);
        // The owner guarantee never applies to rules: the owner follows the
        // same world default as a stranger.
        assertEquals(PermissionState.ALLOW,
                engine.decide(owner, landId, ProtectionActionType.PLAYER_DAMAGE_PLAYER).outcome());
        assertEquals(PermissionState.ALLOW,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.PLAYER_DAMAGE_PLAYER).outcome());
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.PISTON_MOVE).outcome(),
                "rule entries only answer their own rule; other mechanics stay fail-closed");
    }

    // 5) Namespace isolation: a subject world default must not decide rule actions.
    @Test
    void subjectWorldDefaultNeverDecidesRuleChain() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = storeWith(worldId, landId, UUID.randomUUID());
        var engine = new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(null,
                        (actor, id, action, snapshot) -> new SubjectPermissionLookup.Grant(
                                List.of(), PermissionState.INHERIT,
                                PermissionState.ALLOW, PermissionState.INHERIT)));
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.PISTON_MOVE).outcome(),
                "subject ALLOW on the shared layers must not leak into rule decisions");
    }

    // 5b) Namespace isolation, other direction: a rule key must not decide subject actions.
    @Test
    void ruleKeyNeverDecidesSubjectChain() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = storeWith(worldId, landId, UUID.randomUUID());
        ConfigService service = new ConfigService(new MutableYamlLoader(""
                + "rule-defaults:\n"
                + "  global:\n"
                + "    PVP: ALLOW\n"));
        PermissionDefaultsCache cache = new PermissionDefaultsCache(service::current,
                name -> Optional.ofNullable("world".equals(name) ? worldId : null),
                ignored -> {
                });
        ProtectionEngine engine = engineWithDefaults(store, cache);
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK).outcome(),
                "a rule ALLOW must not open subject actions");
    }

    // 4) Reload publishes new defaults; previously captured snapshots keep old values.
    @Test
    void reloadPublishesNewDefaultsWithoutPollutingOldSnapshot() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = storeWith(worldId, landId, UUID.randomUUID());
        MutableYamlLoader loader = new MutableYamlLoader(""
                + "subject-defaults:\n"
                + "  worlds:\n"
                + "    world:\n"
                + "      BLOCK_BREAK: ALLOW\n");
        ConfigService service = new ConfigService(loader);
        PermissionDefaultsCache cache = new PermissionDefaultsCache(service::current,
                name -> Optional.ofNullable("world".equals(name) ? worldId : null),
                ignored -> {
                });
        service.addListener(cache);
        ProtectionEngine engine = engineWithDefaults(store, cache);
        assertEquals(PermissionState.ALLOW,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK).outcome());

        PermissionDefaultsSnapshot stale = cache.snapshot();
        long epochBefore = service.current().globalPolicyEpoch();
        loader.setYaml(""
                + "subject-defaults:\n"
                + "  worlds:\n"
                + "    world:\n"
                + "      BLOCK_BREAK: DENY\n");
        service.reload();

        assertTrue(service.current().globalPolicyEpoch() > epochBefore,
                "successful reload must bump the epoch");
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK).outcome(),
                "new decisions must read the reloaded defaults, not the old ones");
        assertEquals(PermissionState.ALLOW,
                stale.subjectWorldDefault(worldId, ProtectionActionType.BLOCK_BREAK),
                "the previously captured snapshot must keep its own values");
    }

    // 4b) Failed reload preserves the previous snapshot and epoch.
    @Test
    void malformedReloadPreservesPreviousSnapshot() {
        MutableYamlLoader loader = new MutableYamlLoader(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: DENY\n");
        ConfigService service = new ConfigService(loader);
        ChunkLandConfig before = service.current();
        loader.setYaml("subject-defaults:\n  global:\n    NOPE_ACTION: ALLOW\n");
        ChunkLandConfig after = service.reload();
        assertTrue(after == before || after.equals(before),
                "failed reload must preserve the previous snapshot");
        assertEquals(before.globalPolicyEpoch(), after.globalPolicyEpoch(),
                "failed reload must not bump any epoch");
        assertEquals(PermissionState.DENY,
                after.subjectDefaults().global().get(ProtectionActionType.BLOCK_BREAK));
    }

    // 6) Malformed defaults fail closed with a path that names the namespace.
    @Test
    void unknownSubjectActionNamesItsOwnKeyPath() {
        String yaml = ""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    NOPE_ACTION: ALLOW\n";
        ConfigValidationException ex = assertThrows(
                ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText(yaml));
        assertTrue(ex.getMessage().contains("subject-defaults.global"),
                "rejection must name the offending entry, got: " + ex.getMessage());
    }

    // SubLand layers stay INHERIT through the config-backed provider.
    @Test
    void subLandLayersStayInherit() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = storeWith(worldId, landId, UUID.randomUUID());
        ConfigService service = new ConfigService(new MutableYamlLoader(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: ALLOW\n"
                + "rule-defaults:\n"
                + "  global:\n"
                + "    PVP: ALLOW\n"));
        PermissionDefaultsCache cache = new PermissionDefaultsCache(service::current,
                name -> Optional.ofNullable("world".equals(name) ? worldId : null),
                ignored -> {
                });
        var provider = new SnapshotPermissionContextProvider(
                cache.ruleLookup(), cache.subjectLookup());
        var subjectCtx = provider.provide(UUID.randomUUID(), landId,
                ProtectionActionType.BLOCK_BREAK, store.snapshot());
        assertEquals(PermissionState.INHERIT, subjectCtx.subLandDefault());
        assertEquals(PermissionState.INHERIT, subjectCtx.subLandRule());
        var ruleCtx = provider.provide(UUID.randomUUID(), landId,
                ProtectionActionType.PLAYER_DAMAGE_PLAYER, store.snapshot());
        assertEquals(PermissionState.INHERIT, ruleCtx.subLandRule());
    }

    // LandRuleService factory honors the rule namespace world/global layers.
    @Test
    void ruleServiceFromSnapshotReadsWorldThenGlobal() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistry snapshot = LandRegistry.from(List.of(
                new LandSnapshot(landId, "TestLand", "testland", OwnerRef.player(owner),
                        worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                        List.of(), 0, 0, NOW, NOW)));
        ChunkLandConfig config = ConfigSchema.parseYamlText(""
                + "rule-defaults:\n"
                + "  global:\n"
                + "    PVP: DENY\n"
                + "    PISTON: DENY\n"
                + "  worlds:\n"
                + "    world:\n"
                + "      PVP: ALLOW\n");
        PermissionDefaultsSnapshot snap = PermissionDefaultsSnapshot.resolve(config,
                name -> Optional.ofNullable("world".equals(name) ? worldId : null),
                ignored -> {
                });
        LandRuleService service = LandRuleService.fromRuleSnapshot(snap);
        assertEquals(Optional.of(PermissionState.ALLOW),
                service.getRule(landId, LandRuleType.PVP, snapshot));
        assertEquals(Optional.of(PermissionState.DENY),
                service.getRule(landId, LandRuleType.PISTON, snapshot));
    }
}

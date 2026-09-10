package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.smile.chunkland.config.ReloadDiff;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Contract for the reload-aware defaults cache: atomic publish, listener
 * refresh, and never-throw degradation.
 */
class PermissionDefaultsCacheTest {

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

    @Test
    void listenerRefreshAppliesReloadedDefaults() {
        UUID worldId = UUID.randomUUID();
        MutableYamlLoader loader = new MutableYamlLoader(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: DENY\n");
        ConfigService service = new ConfigService(loader);
        PermissionDefaultsCache cache = new PermissionDefaultsCache(service::current,
                name -> Optional.empty(), ignored -> {
                });
        service.addListener(cache);
        assertEquals(PermissionState.DENY,
                cache.snapshot().subjectGlobalDefault(ProtectionActionType.BLOCK_BREAK));

        loader.setYaml(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: ALLOW\n");
        service.reload();
        assertEquals(PermissionState.ALLOW,
                cache.snapshot().subjectGlobalDefault(ProtectionActionType.BLOCK_BREAK));
    }

    @Test
    void refreshNeverThrowsOnFailingSource() {
        AtomicReference<ChunkLandConfig> ref = new AtomicReference<>(ChunkLandConfig.defaults());
        List<String> warnings = new ArrayList<>();
        PermissionDefaultsCache cache = new PermissionDefaultsCache(
                () -> {
                    ChunkLandConfig config = ref.get();
                    if (config == null) {
                        throw new RuntimeException("config backend boom");
                    }
                    return config;
                },
                name -> Optional.empty(), warnings::add);
        ref.set(null);
        assertDoesNotThrow(() -> cache.onConfigReload(
                ReloadDiff.of(Set.of(), Set.of(), Set.of(), 0L, 1L)));
        assertEquals(PermissionDefaultsSnapshot.empty(), cache.snapshot());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("fail-closed")),
                "refresh failure must warn, got: " + warnings);
    }

    @Test
    void ruleLookupDegradesToEmptyOnFailure() {
        PermissionDefaultsCache cache = new PermissionDefaultsCache(
                ChunkLandConfig::defaults, name -> Optional.empty(), null);
        assertEquals(Optional.empty(),
                cache.ruleLookup().getRule(new LandId(UUID.randomUUID()), LandRuleType.PVP, null));
    }

    @Test
    void subjectLookupDegradesToEmptyOnUnknownLand() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(
                new LandSnapshot(landId, "TestLand", "testland",
                        OwnerRef.player(UUID.randomUUID()),
                        worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                        List.of(), 0, 0, NOW, NOW)));
        PermissionDefaultsCache cache = new PermissionDefaultsCache(
                ChunkLandConfig::defaults, name -> Optional.empty(), null);
        SubjectPermissionLookup.Grant grant = cache.subjectLookup().grants(
                UUID.randomUUID(), new LandId(UUID.randomUUID()),
                ProtectionActionType.BLOCK_BREAK, snapshot);
        assertEquals(SubjectPermissionLookup.Grant.empty(), grant);
    }

    @Test
    void nullInputsDegradeToEmpty() {
        PermissionDefaultsCache cache = new PermissionDefaultsCache(null, null, null);
        assertEquals(PermissionDefaultsSnapshot.empty(), cache.snapshot());
    }

    @Test
    void worldDefaultsSurviveIntoLookups() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(
                new LandSnapshot(landId, "TestLand", "testland",
                        OwnerRef.player(UUID.randomUUID()),
                        worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                        List.of(), 0, 0, NOW, NOW)));
        ChunkLandConfig parsed = ConfigSchema.parseYamlText(""
                + "subject-defaults:\n"
                + "  worlds:\n"
                + "    world:\n"
                + "      BLOCK_BREAK: ALLOW\n"
                + "rule-defaults:\n"
                + "  worlds:\n"
                + "    world:\n"
                + "      PVP: DENY\n");
        PermissionDefaultsCache cache = new PermissionDefaultsCache(() -> parsed,
                name -> Optional.ofNullable("world".equals(name) ? worldId : null),
                null);
        // Successful empty load: the world default below is read from config,
        // not admitted through an unverifiable land snapshot.
        cache.attachLandAuthorisation(LandAuthorisationSnapshot::empty);
        SubjectPermissionLookup.Grant grant = cache.subjectLookup().grants(
                UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK, snapshot);
        assertEquals(PermissionState.ALLOW, grant.worldDefault());
        assertEquals(Optional.of(PermissionState.DENY),
                cache.ruleLookup().getRule(landId, LandRuleType.PVP, snapshot));
    }

}

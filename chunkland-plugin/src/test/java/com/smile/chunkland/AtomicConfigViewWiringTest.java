package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.command.ManagementGateResolver;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigLoader;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.config.ConfigService;
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.protection.PermissionDefaultsCache;
import com.smile.chunkland.protection.ProtectionEngine;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Production wiring uses one atomic config view per decision.
 *
 * <p>The protection engine and the management gate both run on the shared
 * {@link PermissionDefaultsCache#provider()} instance, so subject and rule
 * halves of a single decision never span a reload. Reloads still apply to
 * new decisions through the same instances.
 */
class AtomicConfigViewWiringTest {

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
    void protectionAndManagementShareAtomicProviderAcrossReload() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistry registry = LandRegistry.from(List.of(
                new LandSnapshot(landId, "TestLand", "testland", OwnerRef.player(owner),
                        worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                        List.of(), 0, 0, NOW, NOW)));
        LandRegistryStore store = new LandRegistryStore();
        store.publish(registry);
        MutableYamlLoader loader = new MutableYamlLoader(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: ALLOW\n");
        ConfigService service = new ConfigService(loader);
        PermissionDefaultsCache cache = new PermissionDefaultsCache(service::current,
                name -> Optional.empty(), ignored -> {
                });
        service.addListener(cache);

        var atomic = cache.provider();
        ProtectionEngine engine = ChunkLandPlugin.buildProtectionEngine(store, atomic);
        assertEquals(PermissionState.ALLOW,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK).outcome());

        ManagementGateResolver resolver = ChunkLandPlugin.buildManagementGateResolver(
                store, () -> atomic, (sender, action, args, snapshot) -> Optional.of(landId));
        assertTrue(resolver instanceof com.smile.chunkland.command.PluginManagementGateResolver);

        // Management gate through the same atomic provider: owner passes,
        // stranger fails closed on a management subject action.
        assertEquals(PermissionState.ALLOW, ManagementPermissionGate.check(
                owner, landId, ProtectionActionType.DELETE_LAND,
                store.snapshot(), false, false, atomic).outcome());
        assertEquals(PermissionState.DENY, ManagementPermissionGate.check(
                UUID.randomUUID(), landId, ProtectionActionType.DELETE_LAND,
                store.snapshot(), false, false, atomic).outcome());

        loader.setYaml(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: DENY\n");
        service.reload();

        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK).outcome(),
                "protection must follow the reload through the same provider");
        assertEquals(PermissionState.ALLOW, ManagementPermissionGate.check(
                owner, landId, ProtectionActionType.DELETE_LAND,
                store.snapshot(), false, false, atomic).outcome(),
                "owner guarantee must survive the reload");
    }

    @Test
    void directTrustLayersStillFlowThroughAtomicProvider() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        LandRegistry registry = LandRegistry.from(List.of(
                new LandSnapshot(landId, "TestLand", "testland", OwnerRef.player(owner),
                        worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                        List.of(), 0, 0, NOW, NOW)));
        LandRegistryStore store = new LandRegistryStore();
        store.publish(registry);
        ConfigService service = new ConfigService(new MutableYamlLoader(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: DENY\n"));
        PermissionDefaultsCache cache = new PermissionDefaultsCache(service::current,
                name -> Optional.empty(), ignored -> {
                });
        service.addListener(cache);
        cache.attachLandAuthorisation(() -> com.smile.chunkland.protection.LandAuthorisationSnapshot.copyOf(
                Map.of(landId, Map.of(stranger, Set.of(ProtectionActionType.BLOCK_BREAK))),
                Map.of()));

        var atomic = cache.provider();
        ProtectionEngine engine = ChunkLandPlugin.buildProtectionEngine(store, atomic);
        assertEquals(PermissionState.ALLOW,
                engine.decide(stranger, landId, ProtectionActionType.BLOCK_BREAK).outcome(),
                "direct trust binding must still grant through the atomic provider");
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_BREAK).outcome());
    }
}

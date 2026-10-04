package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.claim.ClaimRejectedException;
import com.smile.chunkland.claim.WorldClaimPolicy;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigLoader;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.config.ConfigService;
import com.smile.chunkland.config.ConservativeConfigFactory;
import com.smile.chunkland.config.VerticalMode;
import com.smile.chunkland.config.WorldSettings;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.vertical.SnapshotProtectionDepthLookup;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Conservative wiring switches for the production lookup builders.
 *
 * <p>The default overloads keep the existing lenient behaviour for the normal
 * config path; the conservative overloads — used only while the active
 * snapshot is the conservative fallback — resolve unknown worlds to the world
 * minimum and deny their claims.
 */
class ConfigConservativeWiringTest {

    private static final int WORLD_MIN = -64;

    private static ChunkLandConfig parsed(String yaml) {
        return ConfigSchema.parseYamlText(yaml);
    }

    private static LandRegistry registryWithLand(UUID world, LandId landId) {
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey chunk = new ChunkKey(world, 0, 0);
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        LandSnapshot land = new LandSnapshot(landId, "Home", LandName.normalize("Home"),
                owner, world, Set.of(chunk), List.of(), 0, 0, now, now);
        return LandRegistry.fromWithDepths(List.of(land), Map.of(chunk, 60));
    }

    @Test
    void conservativeDepthWiringResolvesUnknownWorldToWorldMin() {
        UUID unknownWorld = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(parsed("worlds:\n  world:\n    claim-enabled: true\n"));

        SnapshotProtectionDepthLookup lookup = ChunkLandPlugin.buildProtectionDepthLookup(
                configs::get, Map.of(), Map.of(unknownWorld, WORLD_MIN), true);

        assertEquals(WORLD_MIN, lookup.getProtectionDepth(
                landId, registryWithLand(unknownWorld, landId)).orElseThrow());
    }

    @Test
    void normalDepthWiringKeepsStoredDepthsForUnknownWorld() {
        UUID unknownWorld = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(parsed("worlds:\n  world:\n    claim-enabled: true\n"));

        SnapshotProtectionDepthLookup lookup = ChunkLandPlugin.buildProtectionDepthLookup(
                configs::get, Map.of(), Map.of(unknownWorld, WORLD_MIN));

        assertEquals(60, lookup.getProtectionDepth(
                landId, registryWithLand(unknownWorld, landId)).orElseThrow());
    }

    private static ConfigService serviceForSnapshot(ChunkLandConfig snapshot) {
        ConfigLoader loader = new ConfigLoader() {
            @Override
            public ChunkLandConfig load() {
                return snapshot;
            }

            @Override
            public String describe() {
                return "test-fixed";
            }
        };
        return new ConfigService(loader, snapshot);
    }

    @Test
    void conservativeSnapshotFailsEveryServerWorldClosed() {
        List<String> serverWorlds = List.of("world", "world_nether", "world_the_end");
        ChunkLandConfig conservative = ConservativeConfigFactory.forWorlds(serverWorlds);

        assertNull(conservative.economy(),
                "conservative pricing stays unavailable, never zero-priced");
        assertEquals(PermissionState.DENY,
                conservative.ruleDefaults().global().get(LandRuleType.PASSIVE_MOB_SPAWN),
                "the built-in ALLOW rule must be pinned to DENY in conservative mode");
        assertNotEquals(PermissionState.ALLOW,
                conservative.subjectDefaults().global()
                        .getOrDefault(ProtectionActionType.ENTRY, PermissionState.INHERIT),
                "ENTRY must not rise towards the shipped ALLOW");

        AtomicReference<ChunkLandConfig> configs = new AtomicReference<>(conservative);
        Map<UUID, String> uuidToName = new LinkedHashMap<>();
        Map<UUID, Integer> mins = new LinkedHashMap<>();
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (String name : serverWorlds) {
            UUID id = UUID.randomUUID();
            ids.put(name, id);
            uuidToName.put(id, name);
            mins.put(id, WORLD_MIN);
        }
        SnapshotProtectionDepthLookup lookup = ChunkLandPlugin.buildProtectionDepthLookup(
                configs::get, uuidToName, mins, true);
        ConfigService service = serviceForSnapshot(conservative);
        // Worlds created after startup resolve to a Bukkit name the damaged
        // config never listed; they must still refuse new claims.
        WorldClaimPolicy policy = ChunkLandPlugin.buildWorldClaimPolicy(
                service, id -> Optional.ofNullable(uuidToName.getOrDefault(id, "world_custom")), true);

        for (String name : serverWorlds) {
            UUID worldId = ids.get(name);
            assertEquals(new WorldSettings(false, VerticalMode.FULL_HEIGHT),
                    conservative.worlds().get(name), "world " + name);
            LandId landId = new LandId(UUID.randomUUID());
            assertEquals(WORLD_MIN, lookup.getProtectionDepth(
                    landId, registryWithLand(worldId, landId)).orElseThrow(),
                    "world " + name + " must protect down to the world minimum");
            ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                    () -> policy.checkClaimAllowed(worldId), "world " + name);
            assertEquals("world.claim_disabled", rejected.diagnosticKey(), "world " + name);
        }

        UUID unknownWorld = UUID.randomUUID();
        LandId unknownLand = new LandId(UUID.randomUUID());
        assertEquals(WORLD_MIN, lookup.getProtectionDepth(
                unknownLand, registryWithLand(unknownWorld, unknownLand)).orElseThrow(),
                "a world absent from every startup table must still resolve to the world minimum");
        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> policy.checkClaimAllowed(unknownWorld));
        assertEquals("world.claim_disabled", rejected.diagnosticKey());
    }

    private static ConfigService serviceFor(String yaml) {
        ChunkLandConfig snapshot = parsed(yaml);
        ConfigLoader loader = new ConfigLoader() {
            @Override
            public ChunkLandConfig load() {
                return snapshot;
            }

            @Override
            public String describe() {
                return "test-fixed";
            }
        };
        return new ConfigService(loader, snapshot);
    }

    @Test
    void conservativeClaimWiringDeniesUnlistedWorld() {
        ConfigService service = serviceFor("worlds:\n  other:\n    claim-enabled: false\n");
        WorldClaimPolicy policy = ChunkLandPlugin.buildWorldClaimPolicy(
                service, ignored -> Optional.of("world"), true);

        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> policy.checkClaimAllowed(UUID.randomUUID()));
        assertEquals("world.claim_disabled", rejected.diagnosticKey());
    }

    @Test
    void normalClaimWiringKeepsEnabledDefaultForUnlistedWorld() {
        ConfigService service = serviceFor("worlds:\n  other:\n    claim-enabled: false\n");
        WorldClaimPolicy policy = ChunkLandPlugin.buildWorldClaimPolicy(
                service, ignored -> Optional.of("world"));

        policy.checkClaimAllowed(UUID.randomUUID());
    }
}

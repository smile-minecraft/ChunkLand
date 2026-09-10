package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigSchema;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Contract for the immutable UUID-keyed defaults snapshot: load-time
 * resolution, orphan warnings, collision handling, immutability, and
 * fail-closed degradation.
 */
class PermissionDefaultsSnapshotTest {

    private static ChunkLandConfig config(String yaml) {
        return ConfigSchema.parseYamlText(yaml);
    }

    @Test
    void emptyConfigResolvesToEmpty() {
        PermissionDefaultsSnapshot snapshot = PermissionDefaultsSnapshot.resolve(
                ChunkLandConfig.defaults(), name -> Optional.empty(), null);
        assertTrue(snapshot.subjectGlobal().isEmpty());
        assertTrue(snapshot.subjectWorlds().isEmpty());
        assertTrue(snapshot.ruleGlobal().isEmpty());
        assertTrue(snapshot.ruleWorlds().isEmpty());
        assertEquals(PermissionState.INHERIT,
                snapshot.subjectGlobalDefault(ProtectionActionType.BLOCK_BREAK));
        assertEquals(PermissionState.INHERIT,
                snapshot.ruleGlobalDefault(LandRuleType.PVP));
    }

    @Test
    void nullConfigResolvesToEmpty() {
        assertEquals(PermissionDefaultsSnapshot.empty(),
                PermissionDefaultsSnapshot.resolve(null, name -> Optional.empty(), null));
    }

    @Test
    void throwingLookupWarnsAndIgnores() {
        ChunkLandConfig parsed = config(""
                + "subject-defaults:\n"
                + "  worlds:\n"
                + "    world:\n"
                + "      BLOCK_BREAK: ALLOW\n");
        List<String> warnings = new ArrayList<>();
        PermissionDefaultsSnapshot snapshot = PermissionDefaultsSnapshot.resolve(parsed,
                name -> {
                    throw new RuntimeException("world backend boom");
                },
                warnings::add);
        assertTrue(snapshot.subjectWorlds().isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("world")),
                "throwing lookup must warn, got: " + warnings);
    }

    @Test
    void collidingNamesKeepFirstAndWarn() {
        UUID shared = UUID.randomUUID();
        ChunkLandConfig parsed = config(""
                + "subject-defaults:\n"
                + "  worlds:\n"
                + "    alpha:\n"
                + "      BLOCK_BREAK: ALLOW\n"
                + "    beta:\n"
                + "      BLOCK_BREAK: DENY\n");
        List<String> warnings = new ArrayList<>();
        PermissionDefaultsSnapshot snapshot = PermissionDefaultsSnapshot.resolve(parsed,
                name -> Optional.of(shared), warnings::add);
        assertEquals(PermissionState.ALLOW,
                snapshot.subjectWorldDefault(shared, ProtectionActionType.BLOCK_BREAK));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("beta")),
                "collision must warn, got: " + warnings);
    }

    @Test
    void snapshotIsImmutableAgainstSourceMutation() {
        UUID worldId = UUID.randomUUID();
        EnumMap<ProtectionActionType, PermissionState> global =
                new EnumMap<>(ProtectionActionType.class);
        global.put(ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW);
        Map<UUID, Map<ProtectionActionType, PermissionState>> worlds = new HashMap<>();
        Map<ProtectionActionType, PermissionState> inner =
                new EnumMap<>(ProtectionActionType.class);
        inner.put(ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW);
        worlds.put(worldId, inner);
        PermissionDefaultsSnapshot snapshot = new PermissionDefaultsSnapshot(
                global, worlds, Map.of(), Map.of());
        global.clear();
        inner.clear();
        worlds.clear();
        assertEquals(PermissionState.ALLOW,
                snapshot.subjectGlobalDefault(ProtectionActionType.BLOCK_BREAK));
        assertEquals(PermissionState.ALLOW,
                snapshot.subjectWorldDefault(worldId, ProtectionActionType.BLOCK_BREAK));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.subjectWorlds().clear());
    }

    @Test
    void nullEntriesAreRejected() {
        assertThrows(NullPointerException.class,
                () -> new PermissionDefaultsSnapshot(null, Map.of(), Map.of(), Map.of()));
    }

    @Test
    void globalAndWorldNamespacesStaySeparate() {
        UUID worldId = UUID.randomUUID();
        ChunkLandConfig parsed = config(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: ALLOW\n"
                + "rule-defaults:\n"
                + "  global:\n"
                + "    PVP: DENY\n"
                + "  worlds:\n"
                + "    world:\n"
                + "      PVP: ALLOW\n");
        PermissionDefaultsSnapshot snapshot = PermissionDefaultsSnapshot.resolve(parsed,
                name -> Optional.ofNullable("world".equals(name) ? worldId : null),
                null);
        assertEquals(PermissionState.ALLOW,
                snapshot.subjectGlobalDefault(ProtectionActionType.BLOCK_BREAK));
        assertEquals(PermissionState.INHERIT,
                snapshot.subjectWorldDefault(worldId, ProtectionActionType.BLOCK_BREAK));
        assertEquals(PermissionState.DENY, snapshot.ruleGlobalDefault(LandRuleType.PVP));
        assertEquals(PermissionState.ALLOW,
                snapshot.ruleWorldDefault(worldId, LandRuleType.PVP));
        assertEquals(PermissionState.INHERIT,
                snapshot.ruleGlobalDefault(LandRuleType.PISTON));
    }
}

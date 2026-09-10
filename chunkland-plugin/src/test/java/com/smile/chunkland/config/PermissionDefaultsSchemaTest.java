package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import org.junit.jupiter.api.Test;

/**
 * Schema contract for the {@code subject-defaults} / {@code rule-defaults}
 * sections: two strictly separated namespaces, backward-compatible absence,
 * case handling, and fail-closed malformed input with key-path diagnostics.
 */
class PermissionDefaultsSchemaTest {

    // Absence stays backward compatible and empty.
    @Test
    void legacyYamlWithoutDefaultsLoadsWithEmptyNamespaces() {
        ChunkLandConfig config = assertDoesNotThrow(() -> ConfigSchema.parseYamlText(""
                + "worlds:\n"
                + "  world:\n"
                + "    claim-enabled: true\n"
                + "limits:\n"
                + "  max-lands-per-player: 5\n"));
        assertTrue(config.subjectDefaults().global().isEmpty());
        assertTrue(config.subjectDefaults().worlds().isEmpty());
        assertTrue(config.ruleDefaults().global().isEmpty());
        assertTrue(config.ruleDefaults().worlds().isEmpty());
        assertTrue(config.worlds().get("world").claimEnabled());
    }

    @Test
    void nullRootLoadsWithEmptyNamespaces() {
        ChunkLandConfig config = ConfigSchema.parseAndValidate(null);
        assertTrue(config.subjectDefaults().global().isEmpty());
        assertTrue(config.ruleDefaults().global().isEmpty());
    }

    @Test
    void emptySectionsLoadAsEmpty() {
        ChunkLandConfig config = assertDoesNotThrow(() -> ConfigSchema.parseYamlText(""
                + "subject-defaults: {}\n"
                + "rule-defaults: {}\n"));
        assertTrue(config.subjectDefaults().global().isEmpty());
        assertTrue(config.ruleDefaults().global().isEmpty());
    }

    // Explicit INHERIT is identical to omitting the entry.
    @Test
    void explicitInheritIsDroppedLikeAnOmission() {
        ChunkLandConfig config = assertDoesNotThrow(() -> ConfigSchema.parseYamlText(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: INHERIT\n"
                + "rule-defaults:\n"
                + "  global:\n"
                + "    PVP: INHERIT\n"));
        assertTrue(config.subjectDefaults().global().isEmpty());
        assertTrue(config.ruleDefaults().global().isEmpty());
    }

    // Enum keys match case-insensitively, but repeats across cases are rejected.
    @Test
    void actionKeysAreCaseInsensitive() {
        ChunkLandConfig config = assertDoesNotThrow(() -> ConfigSchema.parseYamlText(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    block_break: DENY\n"
                + "rule-defaults:\n"
                + "  global:\n"
                + "    pvp: DENY\n"));
        assertEquals(PermissionState.DENY,
                config.subjectDefaults().global().get(ProtectionActionType.BLOCK_BREAK));
        assertEquals(PermissionState.DENY,
                config.ruleDefaults().global().get(LandRuleType.PVP));
    }

    @Test
    void duplicateActionAcrossCasesIsRejected() {
        ConfigValidationException ex = assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText(""
                        + "subject-defaults:\n"
                        + "  global:\n"
                        + "    BLOCK_BREAK: ALLOW\n"
                        + "    block_break: DENY\n"));
        assertTrue(ex.getMessage().contains("duplicate"),
                "must not silently overwrite, got: " + ex.getMessage());
    }

    // World names stay case-sensitive.
    @Test
    void worldNamesStayCaseSensitive() {
        ChunkLandConfig config = assertDoesNotThrow(() -> ConfigSchema.parseYamlText(""
                + "subject-defaults:\n"
                + "  worlds:\n"
                + "    World:\n"
                + "      BLOCK_BREAK: ALLOW\n"
                + "    world:\n"
                + "      BLOCK_BREAK: DENY\n"));
        assertEquals(PermissionState.ALLOW, config.subjectDefaults().worlds()
                .get("World").get(ProtectionActionType.BLOCK_BREAK));
        assertEquals(PermissionState.DENY, config.subjectDefaults().worlds()
                .get("world").get(ProtectionActionType.BLOCK_BREAK));
    }

    // Namespaces are strict: a rule key under subject-defaults (or the reverse) fails.
    @Test
    void ruleKeyUnderSubjectDefaultsIsRejected() {
        ConfigValidationException ex = assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText(""
                        + "subject-defaults:\n"
                        + "  global:\n"
                        + "    PVP: ALLOW\n"));
        assertTrue(ex.getMessage().contains("subject-defaults.global"),
                "got: " + ex.getMessage());
    }

    @Test
    void subjectKeyUnderRuleDefaultsIsRejected() {
        ConfigValidationException ex = assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText(""
                        + "rule-defaults:\n"
                        + "  global:\n"
                        + "    BLOCK_BREAK: ALLOW\n"));
        assertTrue(ex.getMessage().contains("rule-defaults.global"),
                "got: " + ex.getMessage());
    }

    // Malformed values fail closed with a key path.
    @Test
    void badStateValueNamesItsPath() {
        ConfigValidationException ex = assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText(""
                        + "rule-defaults:\n"
                        + "  worlds:\n"
                        + "    world:\n"
                        + "      PVP: sometimes\n"));
        assertTrue(ex.getMessage().contains("rule-defaults.worlds.world.PVP"),
                "got: " + ex.getMessage());
    }

    @Test
    void nonStringStateIsRejected() {
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText(""
                        + "subject-defaults:\n"
                        + "  global:\n"
                        + "    BLOCK_BREAK: true\n"));
    }

    @Test
    void nullSectionIsRejected() {
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText("subject-defaults:\n"));
    }

    @Test
    void unknownSubKeyIsRejected() {
        ConfigValidationException ex = assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText(""
                        + "subject-defaults:\n"
                        + "  universe:\n"
                        + "    BLOCK_BREAK: ALLOW\n"));
        assertTrue(ex.getMessage().contains("subject-defaults"),
                "got: " + ex.getMessage());
    }

    @Test
    void blankWorldNameIsRejected() {
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText(""
                        + "rule-defaults:\n"
                        + "  worlds:\n"
                        + "    \"\":\n"
                        + "      PVP: ALLOW\n"));
    }

    // Defaults travel through the epoch-bump helpers instead of being dropped.
    @Test
    void defaultsSurviveHelperCopies() {
        ChunkLandConfig config = ConfigSchema.parseYamlText(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    BLOCK_BREAK: DENY\n");
        assertEquals(PermissionState.DENY, config.withWorlds(config.worlds())
                .subjectDefaults().global().get(ProtectionActionType.BLOCK_BREAK));
        assertEquals(PermissionState.DENY, config.withLimits(config.limits())
                .subjectDefaults().global().get(ProtectionActionType.BLOCK_BREAK));
        assertEquals(PermissionState.DENY,
                config.withEpochsBumped(config.worlds()).subjectDefaults().global()
                        .get(ProtectionActionType.BLOCK_BREAK));
    }
}

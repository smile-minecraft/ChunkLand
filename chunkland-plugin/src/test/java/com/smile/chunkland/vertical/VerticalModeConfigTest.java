package com.smile.chunkland.vertical;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.config.ConfigValidationException;
import com.smile.chunkland.config.VerticalMode;
import com.smile.chunkland.config.WorldSettings;
import org.junit.jupiter.api.Test;

/**
 * Per-world {@code vertical-mode} parsing: strict, fail-closed, defaulted.
 */
class VerticalModeConfigTest {

    @Test
    void perChunkDepthParses() {
        ChunkLandConfig cfg = ConfigSchema.parseYamlText(
                "worlds:\n  world:\n    claim-enabled: true\n    vertical-mode: PER_CHUNK_DEPTH\n");
        assertEquals(VerticalMode.PER_CHUNK_DEPTH, cfg.worlds().get("world").verticalMode());
        assertTrue(cfg.worlds().get("world").claimEnabled());
    }

    @Test
    void fullHeightParses() {
        ChunkLandConfig cfg = ConfigSchema.parseYamlText(
                "worlds:\n  world:\n    claim-enabled: false\n    vertical-mode: FULL_HEIGHT\n");
        assertEquals(VerticalMode.FULL_HEIGHT, cfg.worlds().get("world").verticalMode());
        assertFalse(cfg.worlds().get("world").claimEnabled());
    }

    @Test
    void absentModeDefaultsToPerChunkDepth() {
        ChunkLandConfig cfg = ConfigSchema.parseYamlText(
                "worlds:\n  world:\n    claim-enabled: true\n");
        assertEquals(VerticalMode.PER_CHUNK_DEPTH, cfg.worlds().get("world").verticalMode());
        assertEquals(VerticalMode.PER_CHUNK_DEPTH, WorldSettings.defaults().verticalMode());
        assertEquals(VerticalMode.PER_CHUNK_DEPTH,
                new WorldSettings(true).verticalMode());
    }

    @Test
    void unknownModeIsRejected() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText(
                "worlds:\n  world:\n    claim-enabled: true\n    vertical-mode: SKY_ONLY\n"));
    }

    @Test
    void lowercaseModeIsRejected() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText(
                "worlds:\n  world:\n    claim-enabled: true\n    vertical-mode: full_height\n"));
    }

    @Test
    void nullModeIsRejected() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText(
                "worlds:\n  world:\n    claim-enabled: true\n    vertical-mode: ~\n"));
    }

    @Test
    void numericModeIsRejected() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText(
                "worlds:\n  world:\n    claim-enabled: true\n    vertical-mode: 1\n"));
    }

    @Test
    void unknownWorldKeyStillRejected() {
        ConfigValidationException ex = assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText(
                        "worlds:\n  world:\n    claim-enabled: true\n    vertical-mode-typo: FULL_HEIGHT\n"));
        assertTrue(ex.getMessage().contains("unknown key"));
    }

    @Test
    void modeChangeIsVisibleToReloadDiff() {
        WorldSettings before = new WorldSettings(true, VerticalMode.PER_CHUNK_DEPTH);
        WorldSettings after = new WorldSettings(true, VerticalMode.FULL_HEIGHT);
        assertNotEquals(before, after);
        assertEquals(before, new WorldSettings(true, VerticalMode.PER_CHUNK_DEPTH));
    }
}

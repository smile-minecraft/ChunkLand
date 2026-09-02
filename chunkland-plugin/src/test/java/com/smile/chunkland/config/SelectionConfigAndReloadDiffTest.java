package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.limit.LimitType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class SelectionConfigAndReloadDiffTest {
    private static final class StringYamlLoader implements ConfigLoader {
        private final String yaml;
        private final Yaml parser = new Yaml();

        StringYamlLoader(String yaml) {
            this.yaml = yaml;
        }

        @Override
        public ChunkLandConfig load() {
            return ConfigSchema.parseAndValidate(parser.load(yaml));
        }

        @Override
        public String describe() {
            return "selection-test";
        }
    }

    @Test
    void missingSelectionSectionAndFieldDefaultToTenMinutes() {
        ChunkLandConfig missingSection = ConfigSchema.parseYamlText("worlds: {}\n");
        ChunkLandConfig missingField = ConfigSchema.parseYamlText("selection: {}\n");

        assertEquals(600, missingSection.selection().sessionTimeoutSeconds());
        assertEquals(Duration.ofMinutes(10), missingSection.selection().sessionTimeout());
        assertEquals(600, missingField.selection().sessionTimeoutSeconds());
    }

    @Test
    void explicitSelectionTimeoutIsTyped() {
        ChunkLandConfig config = ConfigSchema.parseYamlText(
                "selection:\n  session-timeout-seconds: 901\n");

        assertEquals(901, config.selection().sessionTimeoutSeconds());
        assertEquals(Duration.ofSeconds(901), config.selection().sessionTimeout());
    }

    @Test
    void selectionTimeoutRejectsNonPositiveNonIntegerUnknownAndOverflowValues() {
        for (String value : List.of("null", "0", "-1", "1.5", "'600'", "2147483648",
                "999999999999999999999999999999")) {
            assertThrows(ConfigValidationException.class,
                    () -> ConfigSchema.parseYamlText(
                            "selection:\n  session-timeout-seconds: " + value + "\n"),
                    value);
        }
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText("selection:\n  other: 1\n"));
    }

    @Test
    void invalidSelectionReloadPreservesSnapshotEpochAndTimeout() {
        ConfigService service = new ConfigService(new StringYamlLoader(
                "selection:\n  session-timeout-seconds: 300\nworlds: {}\n"));
        ChunkLandConfig before = service.current();
        service.swapLoader(new StringYamlLoader(
                "selection:\n  session-timeout-seconds: 0\nworlds: {}\n"));

        ChunkLandConfig after = service.reload();

        assertSame(before, after);
        assertEquals(0L, after.globalPolicyEpoch());
        assertEquals(300, after.selection().sessionTimeoutSeconds());
    }

    @Test
    void globalPolicyDiffOnlyReportsActualGlobalContentChanges() {
        String initial = "limits:\n  max-lands-per-player: 5\n"
                + "messages:\n  cooldown-seconds: 2\n"
                + "selection:\n  session-timeout-seconds: 600\n"
                + "worlds:\n  world:\n    claim-enabled: true\n";

        ConfigService service = new ConfigService(new StringYamlLoader(initial));
        List<ReloadDiff> received = new ArrayList<>();
        service.addListener(received::add);

        service.swapLoader(new StringYamlLoader(initial.replace("max-lands-per-player: 5", "max-lands-per-player: 6")));
        service.reload();
        assertTrue(received.get(0).globalPolicyChanged());

        ConfigService worldOnly = new ConfigService(new StringYamlLoader(initial));
        List<ReloadDiff> worldOnlyDiffs = new ArrayList<>();
        worldOnly.addListener(worldOnlyDiffs::add);
        worldOnly.swapLoader(new StringYamlLoader(initial.replace("claim-enabled: true", "claim-enabled: false")));
        worldOnly.reload();
        assertFalse(worldOnlyDiffs.get(0).globalPolicyChanged());
        assertTrue(worldOnlyDiffs.get(0).changedWorlds().contains("world"));

        ConfigService noOp = new ConfigService(new StringYamlLoader(initial));
        List<ReloadDiff> noOpDiffs = new ArrayList<>();
        noOp.addListener(noOpDiffs::add);
        noOp.reload();
        assertFalse(noOpDiffs.get(0).globalPolicyChanged());
        assertEquals(1L, noOp.current().globalPolicyEpoch());
        assertEquals(1L, noOp.current().worldPolicyEpoch("world"));
    }

    @Test
    void timeoutOnlyChangeIsGlobalPolicyChange() {
        ConfigService service = new ConfigService(new StringYamlLoader(
                "selection:\n  session-timeout-seconds: 600\nworlds: {}\n"));
        List<ReloadDiff> received = new ArrayList<>();
        service.addListener(received::add);
        service.swapLoader(new StringYamlLoader(
                "selection:\n  session-timeout-seconds: 601\nworlds: {}\n"));

        service.reload();

        assertTrue(received.get(0).globalPolicyChanged());
        assertEquals(601, service.current().selection().sessionTimeoutSeconds());
    }

    @Test
    void messageOnlyChangeIsGlobalPolicyChange() {
        ConfigService service = new ConfigService(new StringYamlLoader(
                "messages:\n  cooldown-seconds: 2\nworlds: {}\n"));
        List<ReloadDiff> received = new ArrayList<>();
        service.addListener(received::add);
        service.swapLoader(new StringYamlLoader(
                "messages:\n  cooldown-seconds: 3\nworlds: {}\n"));

        service.reload();

        assertTrue(received.get(0).globalPolicyChanged());
    }

    @Test
    void selectionHardLimitsUseTheExistingTypedLimitsContract() {
        ChunkLandConfig defaults = ConfigSchema.parseYamlText("limits: {}\n");
        assertEquals(32, defaults.limits().valueFor(LimitType.MAX_SELECTION_SIDE_LENGTH));
        assertEquals(1024, defaults.limits().valueFor(LimitType.MAX_SELECTION_CHUNKS));

        ChunkLandConfig configured = ConfigSchema.parseYamlText(
                "limits:\n"
                        + "  max-selection-side-length: 12\n"
                        + "  max-selection-chunks: 144\n");
        assertEquals(12, configured.limits().maxSelectionSideLength());
        assertEquals(144, configured.limits().maxSelectionChunks());
    }

    @Test
    void selectionHardLimitsRejectUnknownAndNegativeLimitKeys() {
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText("limits:\n  max-selection-side: 32\n"));
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText("limits:\n  max-selection-side-length: -1\n"));
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText("limits:\n  max-selection-chunks: -1\n"));
    }

    @Test
    void selectionHardLimitChangeIsReportedAsGlobalPolicyChange() {
        String initial = "limits:\n  max-selection-side-length: 32\n"
                + "  max-selection-chunks: 1024\n";
        ConfigService service = new ConfigService(new StringYamlLoader(initial));
        List<ReloadDiff> received = new ArrayList<>();
        service.addListener(received::add);
        service.swapLoader(new StringYamlLoader(initial.replace("max-selection-chunks: 1024", "max-selection-chunks: 512")));

        service.reload();

        assertTrue(received.get(0).globalPolicyChanged());
        assertEquals(512, service.current().limits().maxSelectionChunks());
    }
}

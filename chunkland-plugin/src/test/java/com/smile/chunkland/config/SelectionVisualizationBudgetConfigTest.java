package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class SelectionVisualizationBudgetConfigTest {
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
            return "visualization-budget-test";
        }
    }

    @Test
    void missingBudgetFieldsFallBackToDefaults() {
        ChunkLandConfig missingSection = ConfigSchema.parseYamlText("worlds: {}\n");
        ChunkLandConfig emptySection = ConfigSchema.parseYamlText("selection:\n  session-timeout-seconds: 600\n");

        for (ChunkLandConfig config : List.of(missingSection, emptySection)) {
            assertEquals(256, config.selection().visualizationMaxSegments());
            assertEquals(256, config.selection().visualizationMaxParticlesPerTick());
            assertEquals(64, config.selection().visualizationRenderDistanceBlocks());
            assertEquals(10, config.selection().visualizationRefreshIntervalTicks());
            assertEquals(
                    new com.smile.chunkland.selection.SelectionVisualizationBudget(256, 256, 64, 10),
                    config.selection().visualizationBudget());
        }
    }

    @Test
    void explicitBudgetValuesAreTyped() {
        ChunkLandConfig config = ConfigSchema.parseYamlText(
                "selection:\n"
                        + "  session-timeout-seconds: 600\n"
                        + "  visualization-max-segments: 64\n"
                        + "  visualization-max-particles-per-tick: 32\n"
                        + "  visualization-render-distance-blocks: 24\n"
                        + "  visualization-refresh-interval-ticks: 5\n");

        assertEquals(64, config.selection().visualizationMaxSegments());
        assertEquals(32, config.selection().visualizationMaxParticlesPerTick());
        assertEquals(24, config.selection().visualizationRenderDistanceBlocks());
        assertEquals(5, config.selection().visualizationRefreshIntervalTicks());
    }

    @Test
    void budgetRejectsNullZeroNegativeFloatStringUnknownAndOverflowValues() {
        String[] fields = {
            "visualization-max-segments",
            "visualization-max-particles-per-tick",
            "visualization-render-distance-blocks",
            "visualization-refresh-interval-ticks"};
        for (String field : fields) {
            for (String value : List.of("null", "0", "-1", "1.5", "'64'", "2147483648",
                    "999999999999999999999999999999")) {
                assertThrows(ConfigValidationException.class,
                        () -> ConfigSchema.parseYamlText("selection:\n  " + field + ": " + value + "\n"),
                        field + "=" + value);
            }
        }
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText("selection:\n  visualization-max-sigments: 64\n"));
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText(
                        "selection:\n  visualization-render-distance-blocks: 129\n"));
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText(
                        "selection:\n  visualization-refresh-interval-ticks: 201\n"));
    }

    @Test
    void invalidBudgetReloadPreservesSnapshotAndEpoch() {
        ConfigService service = new ConfigService(new StringYamlLoader(
                "selection:\n  session-timeout-seconds: 300\n"
                        + "  visualization-max-segments: 64\nworlds: {}\n"));
        ChunkLandConfig before = service.current();
        service.swapLoader(new StringYamlLoader(
                "selection:\n  visualization-max-segments: 0\nworlds: {}\n"));

        ChunkLandConfig after = service.reload();

        assertSame(before, after);
        assertEquals(0L, after.globalPolicyEpoch());
        assertEquals(64, after.selection().visualizationMaxSegments());
    }

    @Test
    void budgetOnlyChangeIsGlobalPolicyChange() {
        ConfigService service = new ConfigService(new StringYamlLoader(
                "selection:\n  session-timeout-seconds: 600\n"
                        + "  visualization-max-segments: 256\nworlds: {}\n"));
        List<ReloadDiff> received = new ArrayList<>();
        service.addListener(received::add);
        service.swapLoader(new StringYamlLoader(
                "selection:\n  session-timeout-seconds: 600\n"
                        + "  visualization-max-segments: 64\nworlds: {}\n"));

        service.reload();

        assertTrue(received.get(0).globalPolicyChanged());
        assertEquals(64, service.current().selection().visualizationMaxSegments());
    }

    @Test
    void budgetOnlyReloadStillInvalidatesSessionsThroughTheGlobalPolicyPath() {
        com.smile.chunkland.selection.SelectionSessionManager manager = visualizationManager();
        com.smile.chunkland.selection.SelectionSession session = manager.start(initialSession());

        ConfigService service = new ConfigService(new StringYamlLoader(
                "selection:\n  session-timeout-seconds: 600\nworlds: {}\n"));
        service.addListener(manager);
        service.swapLoader(new StringYamlLoader(
                "selection:\n  session-timeout-seconds: 600\n"
                        + "  visualization-refresh-interval-ticks: 5\nworlds: {}\n"));
        service.reload();

        assertTrue(manager.sessionFor(session.playerId()).isEmpty());
        assertFalse(receivedStops(manager).isEmpty());
    }

    private static com.smile.chunkland.selection.SelectionSessionManager visualizationManager() {
        return new com.smile.chunkland.selection.SelectionSessionManager(
                (playerId, delay, task) ->
                        com.smile.chunkland.selection.SelectionTimeoutScheduler.Cancellable.noop(),
                new RecordingVisualization(),
                com.smile.chunkland.selection.SelectionNotifier.noop(),
                () -> java.time.Instant.parse("2026-01-01T00:00:00Z"),
                java.time.Duration.ofMinutes(10));
    }

    private static com.smile.chunkland.selection.SelectionSession initialSession() {
        java.util.UUID player = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        java.util.UUID world = java.util.UUID.fromString("00000000-0000-0000-0000-000000000010");
        return com.smile.chunkland.selection.SelectionSession.initial(
                player,
                world,
                com.smile.chunkland.selection.SelectionMode.CREATE_LAND,
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                0,
                java.time.Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static List<java.util.UUID> receivedStops(
            com.smile.chunkland.selection.SelectionSessionManager manager) {
        // The recording controller below is reachable only through the manager seam;
        // this helper exists so the test reads as arrange/act/assert without reflection.
        return RecordingVisualization.stoppedPlayers();
    }

    static final class RecordingVisualization implements com.smile.chunkland.selection.SelectionVisualizationTaskController {
        private static final List<java.util.UUID> STOPPED = new ArrayList<>();

        static List<java.util.UUID> stoppedPlayers() {
            return List.copyOf(STOPPED);
        }

        @Override
        public void stop(java.util.UUID playerId) {
            STOPPED.add(playerId);
        }
    }
}

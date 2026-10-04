package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.limit.LimitType;
import org.junit.jupiter.api.Test;

/**
 * TDD coverage for limits config: defaults, validation, reload epochs.
 */
class LimitsConfigTest {

    @Test
    void defaultsAreSpecValues() {
        ChunkLandConfig cfg = ChunkLandConfig.defaults();
        assertEquals(5, cfg.limits().maxLandsPerPlayer());
        assertEquals(10, cfg.limits().maxTotalChunksPerPlayer());
        assertEquals(10, cfg.limits().maxChunksPerLand());
        assertEquals(16, cfg.limits().maxSublandsPerLand());
        assertEquals(5, cfg.limits().valueFor(LimitType.MAX_LANDS_PER_PLAYER));
        assertEquals(10, cfg.limits().valueFor(LimitType.MAX_TOTAL_CHUNKS_PER_PLAYER));
    }

    @Test
    void absentLimitsSectionFallsBackToDefaults() {
        String yaml = "worlds: {}\n";
        ChunkLandConfig cfg = ConfigSchema.parseYamlText(yaml);
        assertEquals(5, cfg.limits().maxLandsPerPlayer());
        assertEquals(10, cfg.limits().maxTotalChunksPerPlayer());
    }

    @Test
    void explicitLimitsAreParsed() {
        String yaml = "limits:\n  max-lands-per-player: 10\n  max-total-chunks-per-player: 100\n  max-chunks-per-land: 50\n  max-sublands-per-land: 5\n";
        ChunkLandConfig cfg = ConfigSchema.parseYamlText(yaml);
        assertEquals(10, cfg.limits().maxLandsPerPlayer());
        assertEquals(100, cfg.limits().maxTotalChunksPerPlayer());
        assertEquals(50, cfg.limits().maxChunksPerLand());
        assertEquals(5, cfg.limits().maxSublandsPerLand());
    }

    @Test
    void partialLimitsUseDefaultsForMissingKeys() {
        String yaml = "limits:\n  max-lands-per-player: 7\n";
        ChunkLandConfig cfg = ConfigSchema.parseYamlText(yaml);
        assertEquals(7, cfg.limits().maxLandsPerPlayer());
        assertEquals(10, cfg.limits().maxTotalChunksPerPlayer(), "missing keys must default");
    }

    @Test
    void zeroIsAllowed() {
        String yaml = "limits:\n  max-lands-per-player: 0\n";
        ChunkLandConfig cfg = ConfigSchema.parseYamlText(yaml);
        assertEquals(0, cfg.limits().maxLandsPerPlayer());
    }

    @Test
    void negativeLimitIsRejected() {
        String yaml = "limits:\n  max-lands-per-player: -1\n";
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText(yaml));
    }

    @Test
    void floatingPointLimitIsRejected() {
        String yaml = "limits:\n  max-lands-per-player: 5.5\n";
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText(yaml));
    }

    @Test
    void nonIntegerStringIsRejected() {
        String yaml = "limits:\n  max-lands-per-player: \"five\"\n";
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText(yaml));
    }

    @Test
    void unknownLimitKeyIsRejected() {
        String yaml = "limits:\n  max-foo: 1\n";
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText(yaml));
    }

    @Test
    void overflowIsRejected() {
        String yaml = "limits:\n  max-lands-per-player: 9999999999\n";
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText(yaml));
    }

    @Test
    void bigIntegerBeyondLongIsRejected() {
        String yaml1 = "limits:\n  max-lands-per-player: 18446744073709551621\n";
        ConfigValidationException ex1 = assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText(yaml1));
        assertTrue(ex1.getMessage().contains("out of range"), "must be precise range rejection, got: " + ex1.getMessage());

        String yaml2 = "limits:\n  max-total-chunks-per-player: 9223372036854775808\n"; // Long.MAX_VALUE+1
        ConfigValidationException ex2 = assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText(yaml2));
        assertTrue(ex2.getMessage().contains("out of range"), "must be precise range rejection, got: " + ex2.getMessage());
    }

    @Test
    void bigDecimalBeyondLongIsRejected() {
        java.math.BigDecimal big = new java.math.BigDecimal("18446744073709551621");
        java.util.Map<String, Object> limits = new java.util.HashMap<>();
        limits.put("max-lands-per-player", big);
        java.util.Map<String, Object> root = new java.util.HashMap<>();
        root.put("limits", limits);
        ConfigValidationException ex = assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseAndValidate(root));
        assertTrue(ex.getMessage().contains("out of range"), "BigDecimal must be exact range rejected, got: " + ex.getMessage());

        java.math.BigDecimal fractional = new java.math.BigDecimal("5.5");
        java.util.Map<String, Object> limits2 = new java.util.HashMap<>();
        limits2.put("max-lands-per-player", fractional);
        java.util.Map<String, Object> root2 = new java.util.HashMap<>();
        root2.put("limits", limits2);
        ConfigValidationException ex2 = assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseAndValidate(root2));
        assertTrue(ex2.getMessage().toLowerCase().contains("integer") || ex2.getMessage().contains("out of range"),
                "fractional BigDecimal must be rejected as integer, got: " + ex2.getMessage());
    }

    @Test
    void unknownTopLevelKeyStillRejected() {
        String yaml = "unknown: 1\n";
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText(yaml));
    }

    @Test
    void explicitNullLimitIsRejected() {
        String yaml = "limits:\n  max-lands-per-player: ~\n";
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText(yaml));
        String yaml2 = "limits:\n  max-total-chunks-per-player: null\n";
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText(yaml2));
    }

    @Test
    void missingLimitsStillDefaults() {
        String yaml = "worlds:\n  world:\n    claim-enabled: true\n";
        ChunkLandConfig cfg = ConfigSchema.parseYamlText(yaml);
        assertEquals(5, cfg.limits().maxLandsPerPlayer());
        assertEquals(10, cfg.limits().maxTotalChunksPerPlayer());
        assertEquals(10, cfg.limits().maxChunksPerLand());
        assertEquals(16, cfg.limits().maxSublandsPerLand());
    }

    @Test
    void topLevelLimitsNullIsRejected() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("limits: null\n"));
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("limits:\n"));
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("limits: ~\n"));
    }

    @Test
    void topLevelLimitsEmptyMapStillDefaults() {
        ChunkLandConfig cfg = ConfigSchema.parseYamlText("limits: {}\n");
        assertEquals(5, cfg.limits().maxLandsPerPlayer());
        assertEquals(10, cfg.limits().maxTotalChunksPerPlayer());
        assertEquals(10, cfg.limits().maxChunksPerLand());
        assertEquals(16, cfg.limits().maxSublandsPerLand());
    }

    @Test
    void explicitNullReloadIsRejectedAndPreservesSnapshot() {
        String initial = "limits:\n  max-lands-per-player: 5\nworlds: {}\n";
        ConfigService svc = new ConfigService(new StringYamlLoader(initial));
        ChunkLandConfig before = svc.current();
        long epochBefore = before.globalPolicyEpoch();
        // Swap to loader that yields explicit null -> validation fails, reload must keep snapshot and not bump epoch
        svc.swapLoader(new StringYamlLoader("limits:\n  max-lands-per-player: ~\nworlds: {}\n"));
        ChunkLandConfig after = svc.reload();
        assertSame(before, after, "failed reload must keep same instance");
        assertEquals(epochBefore, after.globalPolicyEpoch(), "failed reload must not bump epoch");
        assertEquals(5, after.limits().maxLandsPerPlayer());
    }

    @Test
    void topLevelNullReloadPreservesSnapshotAndEpochs() {
        String initial = """
                limits:
                  max-lands-per-player: 5
                worlds:
                  world:
                    claim-enabled: true
                """;
        ConfigService svc = new ConfigService(new StringYamlLoader(initial));
        ChunkLandConfig before = svc.current();
        long globalBefore = before.globalPolicyEpoch();
        Long worldBefore = before.worldPolicyEpoch("world");
        // Explicit top-level null
        svc.swapLoader(new StringYamlLoader("limits: null\nworlds:\n  world:\n    claim-enabled: true\n"));
        ChunkLandConfig after = svc.reload();
        assertSame(before, after);
        assertEquals(globalBefore, after.globalPolicyEpoch());
        assertEquals(worldBefore, after.worldPolicyEpoch("world"));
        assertEquals(5, after.limits().maxLandsPerPlayer());

        // Empty value
        svc.swapLoader(new StringYamlLoader("limits:\nworlds:\n  world:\n    claim-enabled: true\n"));
        ChunkLandConfig after2 = svc.reload();
        assertSame(before, after2);
        assertEquals(globalBefore, after2.globalPolicyEpoch());
        assertEquals(worldBefore, after2.worldPolicyEpoch("world"));
    }

    @Test
    void reloadBumpsEpochAndPreservesLimitsSemantics() throws Exception {
        String yaml = "limits:\n  max-lands-per-player: 5\nworlds: {}\n";
        ConfigService svc = new ConfigService(new StringYamlLoader(yaml));
        long before = svc.current().globalPolicyEpoch();
        ChunkLandConfig after = svc.reload();
        assertEquals(before + 1, after.globalPolicyEpoch());
        assertEquals(5, after.limits().maxLandsPerPlayer(), "reload must preserve limits");
    }

    @Test
    void limitsAreImmutableAndDefensive() {
        LimitSettings s = new LimitSettings(5, 256, 128, 16);
        ChunkLandConfig cfg = new ChunkLandConfig(java.util.Map.of(), s, 0L, java.util.Map.of());
        assertEquals(5, cfg.limits().maxLandsPerPlayer());
        // No mutator exists; verify withLimits returns new instance
        LimitSettings next = new LimitSettings(1, 1, 1, 1);
        ChunkLandConfig cfg2 = cfg.withLimits(next);
        assertEquals(5, cfg.limits().maxLandsPerPlayer(), "original must not mutate");
        assertEquals(1, cfg2.limits().maxLandsPerPlayer());
    }

    // Helper loader for ConfigService test
    private static class StringYamlLoader implements ConfigLoader {
        private final String yaml;
        StringYamlLoader(String yaml) { this.yaml = yaml; }
        @Override public ChunkLandConfig load() { return ConfigSchema.parseYamlText(yaml); }
        @Override public String describe() { return "test"; }
    }
}

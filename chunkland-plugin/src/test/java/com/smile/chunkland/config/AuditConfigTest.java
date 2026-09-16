package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AuditConfigTest {

    @Test
    void missingAuditSectionFallsBackToDefaultRetention() {
        ChunkLandConfig cfg = ConfigSchema.parseYamlText("worlds: {}\n");
        assertEquals(180, cfg.audit().retentionDays());
        assertEquals(180, AuditSettings.defaults().retentionDays());
    }

    @Test
    void emptyAuditSectionUsesDefault() {
        ChunkLandConfig cfg = ConfigSchema.parseYamlText("audit: {}\n");
        assertEquals(180, cfg.audit().retentionDays());
    }

    @Test
    void explicitRetentionIsParsed() {
        ChunkLandConfig cfg = ConfigSchema.parseYamlText("audit:\n  retention-days: 30\n");
        assertEquals(30, cfg.audit().retentionDays());
    }

    @Test
    void zeroRetentionMeansForeverAndIsAccepted() {
        ChunkLandConfig cfg = ConfigSchema.parseYamlText("audit:\n  retention-days: 0\n");
        assertEquals(0, cfg.audit().retentionDays());
    }

    @Test
    void negativeRetentionIsRejected() {
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText("audit:\n  retention-days: -1\n"));
    }

    @Test
    void nonIntegerRetentionIsRejected() {
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText("audit:\n  retention-days: 1.5\n"));
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText("audit:\n  retention-days: forever\n"));
    }

    @Test
    void nullAuditSectionIsRejected() {
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText("audit: null\n"));
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText("audit:\n  retention-days: ~\n"));
    }

    @Test
    void unknownAuditKeyIsRejected() {
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText("audit:\n  retention-days: 30\n  unknown: 1\n"));
    }

    @Test
    void unknownTopLevelStillRejectedAfterAddingAudit() {
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText("audit:\n  retention-days: 30\nunknown: 1\n"));
    }

    @Test
    void directConstructionRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> new AuditSettings(-1));
    }

    @Test
    void withAuditRoundTrip() {
        ChunkLandConfig base = ChunkLandConfig.defaults();
        assertEquals(180, base.audit().retentionDays());
        ChunkLandConfig updated = base.withAudit(new AuditSettings(7));
        assertEquals(7, updated.audit().retentionDays());
        assertEquals(180, base.audit().retentionDays());
    }

    @Test
    void reloadAppliesAuditChangeAndBumpsEpoch() {
        ConfigService svc = new ConfigService(new TestLoader("audit:\n  retention-days: 30\nworlds: {}\n"));
        assertEquals(30, svc.current().audit().retentionDays());
        long before = svc.current().globalPolicyEpoch();
        svc.swapLoader(new TestLoader("audit:\n  retention-days: 7\nworlds: {}\n"));
        ChunkLandConfig after = svc.reload();
        assertEquals(before + 1, after.globalPolicyEpoch());
        assertEquals(7, after.audit().retentionDays());
    }

    @Test
    void reloadFailurePreservesAuditSnapshot() {
        ConfigService svc = new ConfigService(new TestLoader("audit:\n  retention-days: 30\nworlds: {}\n"));
        ChunkLandConfig before = svc.current();
        svc.swapLoader(new TestLoader("audit:\n  retention-days: -1\nworlds: {}\n"));
        ChunkLandConfig after = svc.reload();
        assertSame(before, after);
        assertEquals(30, after.audit().retentionDays());
    }

    private static class TestLoader implements ConfigLoader {
        private final String yaml;

        TestLoader(String yaml) {
            this.yaml = yaml;
        }

        @Override public ChunkLandConfig load() {
            return ConfigSchema.parseYamlText(yaml);
        }

        @Override public String describe() {
            return "test";
        }
    }
}

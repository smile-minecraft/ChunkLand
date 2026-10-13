package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Hot-swap policy: hot fields publish, structural fields refuse the whole
 * candidate.
 *
 * <p>Hot (allow): limits, messages, selection, subject-defaults,
 * rule-defaults, feedback, the decision-cache budget, and claim-enabled of
 * already-listed worlds. Not hot (reject with a restart prompt, never a
 * partial apply): added or removed worlds, any vertical-mode switch, the
 * economy section, and the audit section.</p>
 */
class ConfigReloadPolicyTest {

    private static final String BASE = """
            worlds:
              world:
                claim-enabled: true
                vertical-mode: PER_CHUNK_DEPTH
            """;

    private static ChunkLandConfig base() {
        return ConfigSchema.parseYamlText(BASE);
    }

    @Test
    void identicalSnapshotsAreAllowed() {
        ReloadGate.GateVerdict verdict = ConfigReloadPolicy.check(base(), base());
        assertTrue(verdict.allowed(), "identical content must be allowed");
    }

    @Test
    void limitsChangeIsAllowed() {
        ChunkLandConfig next = base().withLimits(
                new LimitSettings(9, 10, 10, 16, 32, 1024));
        assertTrue(ConfigReloadPolicy.check(base(), next).allowed());
    }

    @Test
    void messagesChangeIsAllowed() {
        ChunkLandConfig next = base().withMessages(
                new MessageSettings(java.util.Locale.forLanguageTag("zh-TW"), 5));
        assertTrue(ConfigReloadPolicy.check(base(), next).allowed());
    }

    @Test
    void selectionChangeIsAllowed() {
        ChunkLandConfig next = ConfigSchema.parseYamlText(BASE + """
                selection:
                  session-timeout-seconds: 300
                """);
        assertTrue(ConfigReloadPolicy.check(base(), next).allowed());
    }

    @Test
    void subjectDefaultsChangeIsAllowed() {
        ChunkLandConfig withDefaults = ConfigSchema.parseYamlText(BASE + """
                subject-defaults:
                  global:
                    BLOCK_BREAK: DENY
                """);
        assertTrue(ConfigReloadPolicy.check(base(), withDefaults).allowed());
    }

    @Test
    void ruleDefaultsChangeIsAllowed() {
        ChunkLandConfig withDefaults = ConfigSchema.parseYamlText(BASE + """
                rule-defaults:
                  global:
                    PVP: DENY
                """);
        assertTrue(ConfigReloadPolicy.check(base(), withDefaults).allowed());
    }

    @Test
    void feedbackChangeIsAllowed() {
        FeedbackSettings tuned = new FeedbackSettings(false, 6, 160, 2, 2000,
                true, 110, 400, 3, 500);
        ChunkLandConfig next = base().withFeedback(tuned);
        assertTrue(ConfigReloadPolicy.check(base(), next).allowed());
    }

    @Test
    void claimEnabledFlipOnListedWorldIsAllowed() {
        ChunkLandConfig next = ConfigSchema.parseYamlText("""
                worlds:
                  world:
                    claim-enabled: false
                    vertical-mode: PER_CHUNK_DEPTH
                """);
        assertTrue(ConfigReloadPolicy.check(base(), next).allowed());
    }

    @Test
    void decisionCacheBudgetChangeIsAllowed() {
        ChunkLandConfig next = ConfigSchema.parseYamlText(BASE + """
                limits:
                  max-lands-per-player: 5
                  max-total-chunks-per-player: 10
                  max-chunks-per-land: 10
                  max-sublands-per-land: 16
                  max-selection-side-length: 32
                  max-selection-chunks: 1024
                  max-decision-cache-entries: 8
                """);
        assertTrue(ConfigReloadPolicy.check(base(), next).allowed(),
                "the decision-cache budget is hot and must publish");
    }

    @Test
    void verticalModeSwitchIsRejected() {
        ChunkLandConfig next = ConfigSchema.parseYamlText("""
                worlds:
                  world:
                    claim-enabled: true
                    vertical-mode: FULL_HEIGHT
                """);
        ReloadGate.GateVerdict verdict = ConfigReloadPolicy.check(base(), next);
        assertFalse(verdict.allowed(), "a vertical-mode switch must refuse the whole candidate");
        assertNotNull(verdict.reason());
    }

    @Test
    void addedWorldIsRejected() {
        ChunkLandConfig next = ConfigSchema.parseYamlText("""
                worlds:
                  world:
                    claim-enabled: true
                    vertical-mode: PER_CHUNK_DEPTH
                  world_nether:
                    claim-enabled: true
                    vertical-mode: PER_CHUNK_DEPTH
                """);
        ReloadGate.GateVerdict verdict = ConfigReloadPolicy.check(base(), next);
        assertFalse(verdict.allowed(), "a new world entry must refuse the whole candidate");
        assertNotNull(verdict.reason());
    }

    @Test
    void removedWorldIsRejected() {
        ChunkLandConfig next = ConfigSchema.parseYamlText("worlds: {}\n");
        ReloadGate.GateVerdict verdict = ConfigReloadPolicy.check(base(), next);
        assertFalse(verdict.allowed(), "a removed world entry must refuse the whole candidate");
        assertNotNull(verdict.reason());
    }

    @Test
    void economyEnabledFlipIsRejected() {
        ChunkLandConfig next = ConfigSchema.parseYamlText(BASE + """
                economy:
                  enabled: true
                  currency:
                    code: EMC
                    scale: 2
                  pricing:
                    tiers:
                      - until: 20
                        price-per-chunk: 1.00
                      - until: unbounded
                        price-per-chunk: 2.00
                """);
        ReloadGate.GateVerdict verdict = ConfigReloadPolicy.check(base(), next);
        assertFalse(verdict.allowed(), "an economy change must refuse the whole candidate");
        assertNotNull(verdict.reason());
    }

    @Test
    void economyTierChangeIsRejected() {
        String economyBase = BASE + """
                economy:
                  enabled: true
                  currency:
                    code: EMC
                    scale: 2
                  pricing:
                    tiers:
                      - until: 20
                        price-per-chunk: 1.00
                      - until: unbounded
                        price-per-chunk: 2.00
                """;
        String economyChanged = BASE + """
                economy:
                  enabled: true
                  currency:
                    code: EMC
                    scale: 2
                  pricing:
                    tiers:
                      - until: 20
                        price-per-chunk: 9.00
                      - until: unbounded
                        price-per-chunk: 2.00
                """;
        ReloadGate.GateVerdict verdict = ConfigReloadPolicy.check(
                ConfigSchema.parseYamlText(economyBase),
                ConfigSchema.parseYamlText(economyChanged));
        assertFalse(verdict.allowed(), "a pricing change must refuse the whole candidate");
        assertNotNull(verdict.reason());
    }

    @Test
    void auditRetentionChangeIsRejected() {
        ChunkLandConfig next = ConfigSchema.parseYamlText(BASE + """
                audit:
                  retention-days: 30
                """);
        ReloadGate.GateVerdict verdict = ConfigReloadPolicy.check(base(), next);
        assertFalse(verdict.allowed(), "an audit change must refuse the whole candidate");
        assertNotNull(verdict.reason());
    }

    @Test
    void hotChangeAlongsideNonHotChangeIsStillRejected() {
        ChunkLandConfig next = ConfigSchema.parseYamlText("""
                worlds:
                  world:
                    claim-enabled: true
                    vertical-mode: FULL_HEIGHT
                audit:
                  retention-days: 30
                """);
        ChunkLandConfig withHot = next.withLimits(new LimitSettings(9, 10, 10, 16, 32, 1024));
        ReloadGate.GateVerdict verdict = ConfigReloadPolicy.check(base(), withHot);
        assertFalse(verdict.allowed(),
                "hot fields piggy-backing on a structural change must not slip through");
        assertNotNull(verdict.reason());
    }
}

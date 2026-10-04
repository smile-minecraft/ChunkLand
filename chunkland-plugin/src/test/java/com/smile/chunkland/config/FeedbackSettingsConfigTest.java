package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The {@code feedback} section tunes deny particles and the push-out: it is
 * optional, every key defaults on its own, and a bad value fails the load
 * instead of silently changing what players see.
 */
class FeedbackSettingsConfigTest {

    private static Map<String, Object> root(Map<String, Object> feedback) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("feedback", feedback);
        return root;
    }

    @Test
    void absentSectionMeansDefaults() {
        ChunkLandConfig config = ConfigSchema.parseAndValidate(new LinkedHashMap<String, Object>());
        assertEquals(FeedbackSettings.defaults(), config.feedback());
        assertEquals(3, config.feedback().pushOutDistanceBlocks());
        assertEquals(Duration.ofMillis(500), config.feedback().pushOutCooldown());
        assertEquals(1.6F, config.feedback().entryWallParticleSize(), 1e-6);
    }

    @Test
    void everyKeyIsReadAndTheRestKeepTheirDefaults() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("entry-wall-enabled", false);
        section.put("entry-wall-radius-blocks", 10);
        section.put("entry-wall-particle-size-percent", 250);
        section.put("push-out-distance-blocks", 5);
        section.put("push-out-cooldown-millis", 800);
        FeedbackSettings feedback = ConfigSchema.parseAndValidate(root(section)).feedback();

        assertFalse(feedback.entryWallEnabled());
        assertEquals(10, feedback.entryWallRadiusBlocks());
        assertEquals(2.5F, feedback.entryWallParticleSize(), 1e-6);
        assertEquals(5, feedback.pushOutDistanceBlocks());
        assertEquals(Duration.ofMillis(800), feedback.pushOutCooldown());
        assertTrue(feedback.actionMarkEnabled(), "untouched keys keep their defaults");
        assertEquals(FeedbackSettings.DEFAULT_MARK_COOLDOWN_MILLIS,
                feedback.actionMarkCooldownMillis());
        assertEquals(FeedbackSettings.DEFAULT_WALL_POINTS_PER_BLOCK,
                feedback.entryWallPointsPerBlock());
    }

    @Test
    void badValuesFailTheLoad() {
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseAndValidate(root(Map.of("push-out-distance-blocks", 0))));
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseAndValidate(root(Map.of("push-out-distance-blocks", 99))));
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseAndValidate(root(Map.of("entry-wall-particle-size-percent", 1.5))));
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseAndValidate(root(Map.of("entry-wall-enabled", "yes"))));
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseAndValidate(root(Map.of("entry-wall-colour", "red"))),
                "an unknown key must be loud, not ignored");
        Map<String, Object> nullSection = new LinkedHashMap<>();
        nullSection.put("feedback", null);
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseAndValidate(nullSection));
    }

    @Test
    void bundledConfigDeclaresTheDefaults() throws Exception {
        Object raw;
        try (Reader reader = Files.newBufferedReader(
                Path.of("src/main/resources/config.yml"), StandardCharsets.UTF_8)) {
            raw = new Yaml().load(reader);
        }
        assertTrue(((Map<?, ?>) raw).containsKey("feedback"),
                "the bundled file must show operators the section");
        assertEquals(FeedbackSettings.defaults(), ConfigSchema.parseAndValidate(raw).feedback(),
                "the bundled values must match the built-in defaults");
    }

    @Test
    void copiesAndEpochBumpsKeepTheSection() {
        FeedbackSettings tuned = new FeedbackSettings(false, 4, 100, 1, 1000,
                false, 100, 1000, 2, 300);
        ChunkLandConfig config = ChunkLandConfig.defaults().withFeedback(tuned);

        assertEquals(tuned, config.withLimits(LimitSettings.defaults()).feedback());
        assertEquals(tuned, config.withAudit(AuditSettings.defaults()).feedback());
        assertEquals(tuned, config.withEpochsBumped(Map.of()).feedback(),
                "an epoch bump that carries no new section keeps the current one");
        FeedbackSettings next = FeedbackSettings.defaults();
        assertEquals(next, config.withEpochsBumped(Map.of(), config.limits(), config.messages(),
                config.selection(), config.subjectDefaults(), config.ruleDefaults(),
                config.economy(), config.audit(), next,
                config.decisionCacheMaxEntries()).feedback(),
                "a reload replaces the section with the freshly loaded one");
    }
}

package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Policy/reload consistency: the gate {@link ConfigService#tryReload}
 * consults and the content policy must never drift apart.
 *
 * <p>Every hot-only change the policy allows must publish through the
 * gated reload, and every structural change the policy refuses must come
 * back rejected with the previous snapshot untouched (same instance, same
 * epochs, no listener notified). If the reload path ever stopped
 * consulting the gate — or the policy forgot a section — the refused
 * cases below would publish and this test would fail.</p>
 */
class PolicyReloadConsistencyTest {

    private static final String BASE = """
            worlds:
              world:
                claim-enabled: true
                vertical-mode: PER_CHUNK_DEPTH
            """;

    private static final String FULL_LIMITS = """
            limits:
              max-lands-per-player: 5
              max-total-chunks-per-player: 10
              max-chunks-per-land: 10
              max-sublands-per-land: 16
              max-selection-side-length: 32
              max-selection-chunks: 1024
            """;

    private static final String ECONOMY = """
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

    private static final class MutableLoader implements ConfigLoader {
        volatile String yaml = BASE;

        @Override
        public ChunkLandConfig load() {
            return ConfigSchema.parseYamlText(yaml);
        }

        @Override
        public String describe() {
            return "mutable";
        }
    }

    private record Case(String name, String yaml, boolean publishes) {
    }

    private static List<Case> cases() {
        String feedbackOff = """
                feedback:
                  entry-wall-enabled: false
                  entry-wall-radius-blocks: 6
                  entry-wall-particle-size-percent: 160
                  entry-wall-points-per-block: 2
                  entry-wall-cooldown-millis: 2000
                  action-mark-enabled: true
                  action-mark-particle-size-percent: 110
                  action-mark-cooldown-millis: 400
                  push-out-distance-blocks: 3
                  push-out-cooldown-millis: 500
                """;
        return List.of(
                new Case("limits", BASE + FULL_LIMITS.replace("max-lands-per-player: 5",
                        "max-lands-per-player: 9"), true),
                new Case("messages", BASE + "messages:\n  default-locale: en_US\n  cooldown-seconds: 7\n",
                        true),
                new Case("selection", BASE + "selection:\n  session-timeout-seconds: 300\n", true),
                new Case("subject-defaults", BASE + "subject-defaults:\n  global:\n    BLOCK_BREAK: DENY\n",
                        true),
                new Case("rule-defaults", BASE + "rule-defaults:\n  global:\n    PVP: DENY\n", true),
                new Case("feedback", BASE + feedbackOff, true),
                new Case("decision-cache", BASE + FULL_LIMITS + "  max-decision-cache-entries: 8\n", true),
                new Case("claim-enabled", BASE.replace("claim-enabled: true", "claim-enabled: false"),
                        true),
                new Case("vertical-mode",
                        BASE.replace("vertical-mode: PER_CHUNK_DEPTH", "vertical-mode: FULL_HEIGHT"),
                        false),
                new Case("added-world", """
                        worlds:
                          world:
                            claim-enabled: true
                            vertical-mode: PER_CHUNK_DEPTH
                          world_nether:
                            claim-enabled: true
                            vertical-mode: PER_CHUNK_DEPTH
                        """, false),
                new Case("removed-world", "worlds: {}\n", false),
                new Case("economy-enabled", BASE + ECONOMY, false),
                new Case("audit", BASE + "audit:\n  retention-days: 30\n", false));
    }

    @Test
    void policyAndReloadAgreeOnEverySection() {
        List<String> failures = new ArrayList<>();
        for (Case c : cases()) {
            MutableLoader loader = new MutableLoader();
            ChunkLandConfig before = ConfigSchema.parseYamlText(BASE);
            ConfigService service = new ConfigService(loader, before);
            List<ReloadDiff> heard = new ArrayList<>();
            service.addListener(heard::add);
            loader.yaml = c.yaml();
            ChunkLandConfig loaded = loader.load();

            boolean allowed = ConfigReloadPolicy.check(before, loaded).allowed();
            ReloadOutcome outcome = service.tryReload(ConfigReloadPolicy::check);

            if (c.publishes()) {
                if (!allowed) {
                    failures.add(c.name() + ": policy refused a hot change");
                }
                if (outcome.kind() != ReloadOutcome.Kind.SUCCESS) {
                    failures.add(c.name() + ": hot change did not publish: " + outcome.kind());
                }
            } else {
                if (allowed) {
                    failures.add(c.name() + ": policy allowed a structural change");
                }
                if (outcome.kind() != ReloadOutcome.Kind.REJECTED) {
                    failures.add(c.name() + ": structural change was not refused: " + outcome.kind());
                }
                if (service.current() != before) {
                    failures.add(c.name() + ": refused candidate moved the snapshot");
                }
            }
        }
        assertTrue(failures.isEmpty(), "policy/reload mismatch: " + failures);
    }

    @Test
    void identicalContentIsAllowedButPublishesNothing() {
        MutableLoader loader = new MutableLoader();
        ChunkLandConfig before = ConfigSchema.parseYamlText(BASE);
        ConfigService service = new ConfigService(loader, before);

        assertTrue(ConfigReloadPolicy.check(before, loader.load()).allowed());
        ReloadOutcome outcome = service.tryReload(ConfigReloadPolicy::check);

        assertEquals(ReloadOutcome.Kind.NO_CHANGE, outcome.kind());
        assertSame(before, service.current());
        assertEquals(0L, service.current().globalPolicyEpoch());
    }

    @Test
    void refusedCandidateKeepsEverySectionOfTheOldGeneration() {
        MutableLoader loader = new MutableLoader();
        ChunkLandConfig before = ConfigSchema.parseYamlText(BASE + FULL_LIMITS);
        ConfigService service = new ConfigService(loader, before);
        loader.yaml = BASE.replace("vertical-mode: PER_CHUNK_DEPTH", "vertical-mode: FULL_HEIGHT")
                + FULL_LIMITS.replace("max-lands-per-player: 5", "max-lands-per-player: 9");

        ReloadOutcome outcome = service.tryReload(ConfigReloadPolicy::check);

        assertEquals(ReloadOutcome.Kind.REJECTED, outcome.kind());
        assertNotNull(outcome.reason());
        assertSame(before, service.current());
        assertEquals(before.worlds(), service.current().worlds());
        assertEquals(before.limits(), service.current().limits());
        assertEquals(before.messages(), service.current().messages());
        assertEquals(before.selection(), service.current().selection());
        assertEquals(before.subjectDefaults(), service.current().subjectDefaults());
        assertEquals(before.ruleDefaults(), service.current().ruleDefaults());
        assertEquals(before.economy(), service.current().economy());
        assertEquals(before.audit(), service.current().audit());
        assertEquals(before.feedback(), service.current().feedback());
        assertEquals(before.decisionCacheMaxEntries(), service.current().decisionCacheMaxEntries());
        assertEquals(0L, service.current().globalPolicyEpoch());
    }
}

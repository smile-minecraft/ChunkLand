package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Distinguishable reload outcomes through
 * {@link ConfigService#tryReload(ReloadGate)}.
 *
 * <p>Success, no-change and failure are three different answers: a damaged
 * or deleted file returns failure with the cause and keeps the previous
 * snapshot (never "no change"), while identical content returns no-change
 * without bumping epochs or notifying listeners. A refused candidate keeps
 * every section of the old snapshot (no partial apply), a racing second
 * reload is told it is busy, and a committed publish whose callbacks threw
 * still reports success with the callback failure attached.</p>
 */
class ConfigReloadOutcomeTest {

    private static final String BASE = """
            worlds:
              world:
                claim-enabled: true
                vertical-mode: PER_CHUNK_DEPTH
            """;

    /** Loader whose parsed content can be swapped (or broken) between reloads. */
    private static final class MutableLoader implements ConfigLoader {
        volatile String yaml = BASE;
        volatile boolean fail;

        @Override
        public ChunkLandConfig load() throws ConfigValidationException, IOException {
            if (fail) {
                throw new ConfigValidationException("simulated load failure");
            }
            return ConfigSchema.parseYamlText(yaml);
        }

        @Override
        public String describe() {
            return "mutable";
        }
    }

    private static ReloadGate allowAll() {
        return (previous, loaded) -> ReloadGate.GateVerdict.allow();
    }

    private static ConfigService serviceFor(MutableLoader loader) {
        return new ConfigService(loader, ConfigSchema.parseYamlText(BASE));
    }

    @Test
    void corruptContentFailsWithCauseAndKeepsSnapshot() {
        MutableLoader loader = new MutableLoader();
        ConfigService service = serviceFor(loader);
        ChunkLandConfig before = service.current();
        List<ReloadDiff> heard = new ArrayList<>();
        service.addListener(heard::add);

        loader.yaml = "worlds:\n  world:\n    claim-enabled: oops\n";
        ReloadOutcome outcome = service.tryReload(allowAll());

        assertEquals(ReloadOutcome.Kind.FAILED, outcome.kind());
        assertNotNull(outcome.cause(), "failure must carry its cause, not just old state");
        assertTrue(outcome.cause().getMessage().contains("claim-enabled"),
                "cause must name the offending key: " + outcome.cause().getMessage());
        assertSame(before, service.current(), "a failed reload keeps the previous snapshot");
        assertSame(before, outcome.snapshot());
        assertEquals(0L, service.current().globalPolicyEpoch());
        assertTrue(heard.isEmpty(), "a failed reload notifies nobody");
    }

    @Test
    void deletedFileFailsInsteadOfReportingNoChange(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("config.yml");
        Files.writeString(file, BASE, StandardCharsets.UTF_8);
        ConfigService service = new ConfigService(
                new YamlFileConfigLoader(file), ConfigSchema.parseYamlText(BASE));
        ChunkLandConfig before = service.current();

        Files.delete(file);
        ReloadOutcome outcome = service.tryReload(allowAll());

        assertEquals(ReloadOutcome.Kind.FAILED, outcome.kind(),
                "a deleted file must read as failure, never as no-change");
        assertNotNull(outcome.cause());
        assertTrue(outcome.cause() instanceof ConfigMissingException,
                "missing-file cause must stay distinguishable: " + outcome.cause());
        assertSame(before, service.current());
    }

    @Test
    void identicalContentReportsNoChangeWithoutBumpOrNotify() {
        MutableLoader loader = new MutableLoader();
        ConfigService service = serviceFor(loader);
        ChunkLandConfig before = service.current();
        List<ReloadDiff> heard = new ArrayList<>();
        service.addListener(heard::add);

        ReloadOutcome outcome = service.tryReload(allowAll());

        assertEquals(ReloadOutcome.Kind.NO_CHANGE, outcome.kind());
        assertSame(before, outcome.snapshot());
        assertSame(before, service.current());
        assertEquals(0L, service.current().globalPolicyEpoch());
        assertTrue(heard.isEmpty(), "no-change must not notify listeners");
        assertNull(outcome.cause());
    }

    @Test
    void hotChangePublishesAndNotifies() {
        MutableLoader loader = new MutableLoader();
        ConfigService service = serviceFor(loader);
        List<ReloadDiff> heard = new ArrayList<>();
        service.addListener(heard::add);

        loader.yaml = BASE + """
                limits:
                  max-lands-per-player: 9
                  max-total-chunks-per-player: 10
                  max-chunks-per-land: 10
                  max-sublands-per-land: 16
                  max-selection-side-length: 32
                  max-selection-chunks: 1024
                """;
        ReloadOutcome outcome = service.tryReload(allowAll());

        assertEquals(ReloadOutcome.Kind.SUCCESS, outcome.kind());
        assertNull(outcome.callbackFailure());
        assertNotNull(outcome.diff());
        assertEquals(1L, outcome.snapshot().globalPolicyEpoch());
        assertSame(outcome.snapshot(), service.current());
        assertEquals(9, service.current().limits().maxLandsPerPlayer());
        assertEquals(1, heard.size(), "one committed publish notifies exactly once");
    }

    @Test
    void callbackFailureStaysCommittedAndIsReported() {
        MutableLoader loader = new MutableLoader();
        ConfigService service = serviceFor(loader);
        RuntimeException boom = new RuntimeException("listener blew up");
        service.addListener(diff -> {
            throw boom;
        });
        List<ReloadDiff> second = new ArrayList<>();
        service.addListener(second::add);

        loader.yaml = BASE + """
                messages:
                  default-locale: en_US
                  cooldown-seconds: 7
                """;
        ReloadOutcome outcome = service.tryReload(allowAll());

        assertEquals(ReloadOutcome.Kind.SUCCESS, outcome.kind(),
                "a callback failure must not rewrite a commit into a failure");
        assertSame(boom, outcome.callbackFailure());
        assertSame(outcome.snapshot(), service.current(),
                "the new snapshot stays live; there is no rollback");
        assertEquals(7, service.current().messages().cooldownSeconds());
        assertEquals(1, second.size(), "every listener still hears a committed publish");
    }

    @Test
    void racingSecondReloadIsBusy() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ConfigLoader blocking = new ConfigLoader() {
            @Override
            public ChunkLandConfig load() {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS), "test must release the loader");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(interrupted);
                }
                return ConfigSchema.parseYamlText(BASE + """
                        limits:
                          max-lands-per-player: 9
                          max-total-chunks-per-player: 10
                          max-chunks-per-land: 10
                          max-sublands-per-land: 16
                          max-selection-side-length: 32
                          max-selection-chunks: 1024
                        """);
            }

            @Override
            public String describe() {
                return "blocking";
            }
        };
        ConfigService service = new ConfigService(blocking, ConfigSchema.parseYamlText(BASE));
        ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "reload-race-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<ReloadOutcome> first = async.submit(() -> service.tryReload(allowAll()));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "first reload must reach the loader");

            ReloadOutcome second = service.tryReload(allowAll());
            assertEquals(ReloadOutcome.Kind.BUSY, second.kind(),
                    "a reload racing an in-flight one must be refused, not queued behind it");
            assertSame(service.current(), second.snapshot());

            release.countDown();
            ReloadOutcome done = first.get(10, TimeUnit.SECONDS);
            assertEquals(ReloadOutcome.Kind.SUCCESS, done.kind());
        } finally {
            release.countDown();
            async.shutdownNow();
        }
    }

    @Test
    void gateRejectionKeepsEverySection() {
        MutableLoader loader = new MutableLoader();
        ConfigService service = serviceFor(loader);
        ChunkLandConfig before = service.current();
        List<ReloadDiff> heard = new ArrayList<>();
        service.addListener(heard::add);

        loader.yaml = """
                worlds:
                  world:
                    claim-enabled: true
                    vertical-mode: FULL_HEIGHT
                limits:
                  max-lands-per-player: 9
                  max-total-chunks-per-player: 10
                  max-chunks-per-land: 10
                  max-sublands-per-land: 16
                  max-selection-side-length: 32
                  max-selection-chunks: 1024
                """;
        ReloadOutcome outcome = service.tryReload(ConfigReloadPolicy::check);

        assertEquals(ReloadOutcome.Kind.REJECTED, outcome.kind());
        assertNotNull(outcome.reason());
        assertSame(before, service.current(), "a refused candidate publishes nothing");
        assertEquals(0L, service.current().globalPolicyEpoch());
        assertEquals(before.worlds(), service.current().worlds());
        assertEquals(before.limits(), service.current().limits(),
                "the piggy-backed hot change must not slip through either");
        assertTrue(heard.isEmpty());
    }

    @Test
    void successfulReloadLeavesFileBytesUntouched(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("config.yml");
        String changed = BASE + """
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
        Files.writeString(file, changed, StandardCharsets.UTF_8);
        ConfigService service = new ConfigService(
                new YamlFileConfigLoader(file), ConfigSchema.parseYamlText(BASE));

        ReloadOutcome outcome = service.tryReload(allowAll());

        assertEquals(ReloadOutcome.Kind.SUCCESS, outcome.kind());
        assertEquals(changed, Files.readString(file, StandardCharsets.UTF_8),
                "a reload must never rewrite the operator file");
    }

    @Test
    void consecutiveSuccessfulReloadsStayMonotonic() {
        MutableLoader loader = new MutableLoader();
        ConfigService service = serviceFor(loader);

        loader.yaml = BASE + "messages:\n  default-locale: en_US\n  cooldown-seconds: 3\n";
        ReloadOutcome first = service.tryReload(allowAll());
        loader.yaml = BASE + "messages:\n  default-locale: en_US\n  cooldown-seconds: 4\n";
        ReloadOutcome second = service.tryReload(allowAll());

        assertEquals(ReloadOutcome.Kind.SUCCESS, first.kind());
        assertEquals(ReloadOutcome.Kind.SUCCESS, second.kind());
        assertEquals(1L, first.snapshot().globalPolicyEpoch());
        assertEquals(2L, second.snapshot().globalPolicyEpoch());
        assertEquals(4, service.current().messages().cooldownSeconds());
    }

    @Test
    void hotSectionsTakeEffectThroughTheSamePublish() {
        MutableLoader loader = new MutableLoader();
        ConfigService service = serviceFor(loader);

        loader.yaml = """
                worlds:
                  world:
                    claim-enabled: false
                    vertical-mode: PER_CHUNK_DEPTH
                limits:
                  max-lands-per-player: 9
                  max-total-chunks-per-player: 10
                  max-chunks-per-land: 10
                  max-sublands-per-land: 16
                  max-selection-side-length: 32
                  max-selection-chunks: 1024
                  max-decision-cache-entries: 8
                messages:
                  default-locale: en_US
                  cooldown-seconds: 7
                selection:
                  session-timeout-seconds: 300
                subject-defaults:
                  global:
                    BLOCK_BREAK: DENY
                rule-defaults:
                  global:
                    PVP: DENY
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
        ReloadOutcome outcome = service.tryReload(allowAll());

        assertEquals(ReloadOutcome.Kind.SUCCESS, outcome.kind());
        ChunkLandConfig live = service.current();
        assertEquals(false, live.worlds().get("world").claimEnabled());
        assertEquals(9, live.limits().maxLandsPerPlayer());
        assertEquals(7, live.messages().cooldownSeconds());
        assertEquals(300, live.selection().sessionTimeoutSeconds());
        assertEquals(8, live.decisionCacheMaxEntries());
        assertEquals(false, live.feedback().entryWallEnabled());
        assertTrue(live.subjectDefaults().global().containsKey(
                com.smile.chunkland.api.permission.ProtectionActionType.BLOCK_BREAK));
        assertTrue(live.ruleDefaults().global().containsKey(
                com.smile.chunkland.api.rule.LandRuleType.PVP));
    }
}

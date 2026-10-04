package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * TDD tests for the Config system.
 *
 * <p>Each test fixes one observable contract of {@link ConfigService}:
 * (a) legal YAML loads to a typed immutable snapshot, (b) illegal values are
 * rejected, (c) successful reload bumps both {@code globalPolicyEpoch} and the
 * affected {@code worldPolicyEpoch} values, (d) failed reload preserves the
 * previous snapshot and epoch, (e) no-change reload still succeeds, and the
 * invalidation seam is observable by listeners.</p>
 *
 * <p>These tests run in-process against an in-memory loader and never touch a
 * real server, file system or YAML file on disk; the YAML string is parsed via
 * snakeyaml inside the loader so the parsing path is exercised end-to-end.</p>
 */
class ConfigServiceTest {

    /** Loader that parses a fixed YAML string every time {@link #load()} is called. */
    private static final class StringYamlLoader implements ConfigLoader {
        private final String yaml;
        private final Yaml parser = new Yaml();

        StringYamlLoader(String yaml) {
            this.yaml = yaml;
        }

        @Override
        public ChunkLandConfig load() throws ConfigValidationException {
            Object root = parser.load(yaml);
            return ConfigSchema.parseAndValidate(root);
        }

        @Override
        public String describe() {
            return "string-yaml";
        }
    }

    /** Loader that always fails with the given message; used to assert fail-closed paths. */
    private static final class FailingLoader implements ConfigLoader {
        private final String message;

        FailingLoader(String message) {
            this.message = message;
        }

        @Override
        public ChunkLandConfig load() throws ConfigValidationException {
            throw new ConfigValidationException(message);
        }

        @Override
        public String describe() {
            return "failing:" + message;
        }
    }

    // (a) Default / legal YAML loads.
    @Test
    void defaultConfigExposesZeroEpochs() {
        ConfigService service = new ConfigService(
                new StringYamlLoader("worlds: {}\n"));
        ChunkLandConfig snapshot = service.current();
        assertNotNull(snapshot);
        assertEquals(0L, snapshot.globalPolicyEpoch());
        assertTrue(snapshot.worldPolicyEpochs().isEmpty(),
                "no worlds known -> no worldPolicyEpochs");
        assertTrue(snapshot.worlds().isEmpty(), "default config has no worlds");
    }

    @Test
    void validYamlProducesTypedWorlds() {
        String yaml = ""
                + "worlds:\n"
                + "  world:\n"
                + "    claim-enabled: true\n"
                + "  world_nether:\n"
                + "    claim-enabled: false\n";
        ConfigService service = new ConfigService(new StringYamlLoader(yaml));
        ChunkLandConfig snapshot = service.current();
        assertEquals(2, snapshot.worlds().size());
        assertTrue(snapshot.worlds().get("world").claimEnabled());
        assertFalse(snapshot.worlds().get("world_nether").claimEnabled());
        assertEquals(0L, snapshot.globalPolicyEpoch());
    }

    // (b) Illegal values are rejected.
    @Test
    void nonBooleanClaimEnabledIsRejected() {
        String yaml = ""
                + "worlds:\n"
                + "  world:\n"
                + "    claim-enabled: \"yes\"\n";
        ConfigValidationException ex = assertThrows(
                ConfigValidationException.class,
                () -> new ConfigService(new StringYamlLoader(yaml)));
        assertTrue(ex.getMessage().contains("claim-enabled"),
                "error message must name the offending key: " + ex.getMessage());
    }

    @Test
    void nullClaimEnabledIsRejected() {
        String yaml = ""
                + "worlds:\n"
                + "  world:\n"
                + "    claim-enabled: ~\n";
        assertThrows(
                ConfigValidationException.class,
                () -> new ConfigService(new StringYamlLoader(yaml)));
    }

    @Test
    void worldsMustBeAMap() {
        String yaml = "worlds: not-a-map\n";
        assertThrows(
                ConfigValidationException.class,
                () -> new ConfigService(new StringYamlLoader(yaml)));
    }

    @Test
    void unknownTopLevelKeyIsRejected() {
        String yaml = "limits:\n  max-chunks: 1\n";
        assertThrows(
                ConfigValidationException.class,
                () -> new ConfigService(new StringYamlLoader(yaml)));
    }

    @Test
    void unknownWorldKeyIsRejected() {
        String yaml = ""
                + "worlds:\n"
                + "  world:\n"
                + "    vertical-mode-typo: FULL_HEIGHT\n";
        assertThrows(
                ConfigValidationException.class,
                () -> new ConfigService(new StringYamlLoader(yaml)));
    }

    // (c) Successful reload increments globalPolicyEpoch and every worldPolicyEpoch.
    @Test
    void successfulReloadIncrementsBothEpochs() throws IOException {
        StringYamlLoader loader = new StringYamlLoader("worlds: {}\n");
        ConfigService service = new ConfigService(loader);
        ChunkLandConfig before = service.current();
        assertEquals(0L, before.globalPolicyEpoch());

        ChunkLandConfig after = service.reload();
        assertEquals(1L, after.globalPolicyEpoch(),
                "globalPolicyEpoch must increment by 1 on successful reload");
        assertEquals(before.worldPolicyEpochs(), after.worldPolicyEpochs(),
                "no worlds -> no world epoch change");
    }

    @Test
    void successfulReloadIncrementsWorldEpochsForAllKnownWorlds() {
        String yaml = ""
                + "worlds:\n"
                + "  world:\n"
                + "    claim-enabled: true\n"
                + "  world_nether:\n"
                + "    claim-enabled: false\n";
        ConfigService service = new ConfigService(new StringYamlLoader(yaml));
        ChunkLandConfig before = service.current();
        assertEquals(0L, before.worldPolicyEpochs().get("world"));
        assertEquals(0L, before.worldPolicyEpochs().get("world_nether"));

        ChunkLandConfig after = service.reload();
        assertEquals(1L, after.worldPolicyEpochs().get("world"));
        assertEquals(1L, after.worldPolicyEpochs().get("world_nether"));
        assertEquals(1L, after.globalPolicyEpoch());
    }

    @Test
    void consecutiveReloadsAreMonotonic() {
        StringYamlLoader loader = new StringYamlLoader("worlds: {}\n");
        ConfigService service = new ConfigService(loader);
        long previous = service.current().globalPolicyEpoch();
        for (int i = 0; i < 5; i++) {
            ChunkLandConfig next = service.reload();
            assertTrue(next.globalPolicyEpoch() > previous,
                    "globalPolicyEpoch must stay strictly increasing: "
                            + previous + " -> " + next.globalPolicyEpoch());
            previous = next.globalPolicyEpoch();
        }
    }

    // (d) Failed reload keeps previous snapshot and does not bump epochs.
    @Test
    void failedReloadPreservesPreviousSnapshotAndEpoch() {
        // Initial valid loader -> known good snapshot at epoch 0.
        ConfigService service = new ConfigService(
                new StringYamlLoader("worlds: {}\n"));
        ChunkLandConfig before = service.current();

        // Swap to a failing loader and call reload() — must not throw, must not
        // bump the epoch, and the snapshot must remain the same instance.
        service.swapLoader(new FailingLoader("simulated I/O failure"));
        ChunkLandConfig after = service.reload();
        assertSame(before, after,
                "failed reload must keep the same immutable snapshot instance");
        assertEquals(0L, after.globalPolicyEpoch(),
                "failed reload must not advance globalPolicyEpoch");
    }

    @Test
    void reloadThatThrowsValidationKeepsOldSnapshot() {
        String initial = "worlds: {}\n";
        ConfigService service = new ConfigService(new StringYamlLoader(initial));
        ChunkLandConfig before = service.current();

        // Swap to a loader that runs YAML through the real parser and fails
        // validation — reload() must swallow the validation error and keep
        // the old snapshot, never advance epochs.
        service.swapLoader(new StringYamlLoader("worlds:\n  world:\n    claim-enabled: oops\n"));
        ChunkLandConfig after = service.reload();
        assertSame(before, after);
        assertEquals(0L, after.globalPolicyEpoch());
    }

    // (e) No-change reload still succeeds (does not mistakenly invalidate).
    @Test
    void noChangeReloadStillSucceedsAndBumpsEpoch() {
        String yaml = ""
                + "worlds:\n"
                + "  world:\n"
                + "    claim-enabled: true\n";
        ConfigService service = new ConfigService(new StringYamlLoader(yaml));
        ChunkLandConfig before = service.current();
        ChunkLandConfig after = service.reload();
        // The config CONTENT must be equivalent (same worlds/flags), but
        // the reload itself still bumps the epoch.
        assertEquals(before.worlds(), after.worlds());
        assertEquals(1L, after.globalPolicyEpoch());
        assertTrue(after.globalPolicyEpoch() > before.globalPolicyEpoch());
    }

    // Invalidation seam: listeners are notified on successful reload only.
    @Test
    void invalidationListenerObservesReload() {
        // First loader: world exists. Second loader (swapped before reload):
        // the same world with a different flag value, so the diff reports it
        // as changed.
        StringYamlLoader initial = new StringYamlLoader("worlds:\n  world:\n    claim-enabled: true\n");
        ConfigService service = new ConfigService(initial);

        StringYamlLoader flipped = new StringYamlLoader("worlds:\n  world:\n    claim-enabled: false\n");
        service.swapLoader(flipped);

        List<ReloadDiff> received = new ArrayList<>();
        service.addListener(received::add);
        service.reload();

        assertEquals(1, received.size(), "listener must fire exactly once per reload");
        ReloadDiff diff = received.get(0);
        assertEquals(0L, diff.oldGlobalPolicyEpoch());
        assertEquals(1L, diff.newGlobalPolicyEpoch());
        assertTrue(diff.changedWorlds().contains("world"),
                "world settings present in both old and new with different values must be reported as changed: "
                        + diff.changedWorlds());
        assertTrue(diff.addedWorlds().isEmpty(),
                "no world was added between old and new");
        assertTrue(diff.removedWorlds().isEmpty(),
                "no world was removed between old and new");
    }

    @Test
    void invalidationListenerReportsAddedAndRemovedWorlds() {
        StringYamlLoader initial = new StringYamlLoader("worlds:\n  world:\n    claim-enabled: true\n");
        ConfigService service = new ConfigService(initial);
        // Swap to a config with a brand-new world and the original gone.
        StringYamlLoader reshaped = new StringYamlLoader(
                "worlds:\n  world_nether:\n    claim-enabled: false\n");
        service.swapLoader(reshaped);
        List<ReloadDiff> received = new ArrayList<>();
        service.addListener(received::add);
        service.reload();
        assertEquals(1, received.size());
        ReloadDiff diff = received.get(0);
        assertTrue(diff.removedWorlds().contains("world"));
        assertTrue(diff.addedWorlds().contains("world_nether"));
        assertTrue(diff.changedWorlds().isEmpty(),
                "no common worlds -> no changes reported, only add/remove: "
                        + diff.changedWorlds());
    }

    @Test
    void invalidationListenerIsNotInvokedOnFailedReload() {
        ConfigService service = new ConfigService(
                new StringYamlLoader("worlds: {}\n"));
        List<ReloadDiff> received = new ArrayList<>();
        service.addListener(received::add);
        service.swapLoader(new FailingLoader("nope"));
        service.reload();
        assertTrue(received.isEmpty(),
                "failed reload must not notify reload listeners");
    }

    @Test
    void unregisterStopsListenerInvocation() {
        ConfigService service = new ConfigService(
                new StringYamlLoader("worlds: {}\n"));
        List<ReloadDiff> received = new ArrayList<>();
        ConfigReloadListener listener = received::add;
        service.addListener(listener);
        service.reload();
        service.removeListener(listener);
        service.reload();
        assertEquals(1, received.size(),
                "unregistered listener must not receive further reloads");
    }

    // Concurrent reload safety: parallel callers must observe strictly monotonic
    // epoch values and never read a half-published snapshot.
    @Test
    void concurrentReloadsProduceStrictlyMonotonicEpochs() throws Exception {
        ConfigService service = new ConfigService(
                new StringYamlLoader("worlds: {}\n"));
        int threads = 8;
        int perThread = 25;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.ConcurrentSkipListSet<Long> seenGlobal = new java.util.concurrent.ConcurrentSkipListSet<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            Thread.ofPlatform().name("reload-" + t).start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ChunkLandConfig snap = service.reload();
                        seenGlobal.add(snap.globalPolicyEpoch());
                    }
                } catch (Throwable th) {
                    failure.set(th);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "concurrent reload threads must complete in time");
        assertNull(failure.get(), () -> "no thread may throw: " + failure.get());
        assertEquals(threads * perThread, seenGlobal.size(),
                "every reload must publish a unique strictly-increasing epoch");
        // The observed epoch set must have no gaps and be exactly 1..N.
        long expected = 1L;
        for (Long actual : seenGlobal) {
            assertEquals(expected, actual.longValue(),
                    "epochs must form a contiguous 1..N sequence");
            expected++;
        }
        assertEquals((long) threads * perThread, expected - 1);
    }

    @Test
    void loaderContractAllowsDescribeForDiagnostics() {
        // The describe() seam is intentionally lightweight; assert that the
        // declared methods exist so future diagnostics can rely on them.
        ConfigLoader loader = new StringYamlLoader("worlds: {}\n");
        assertNotNull(loader.describe());
    }
}

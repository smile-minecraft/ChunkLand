package com.smile.chunkland.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.history.WorldHistoryProvider;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Discovery contract for the optional CoreProtect history provider: absent,
 * failing or shape-mismatched CoreProtect yields empty without throwing, so
 * ChunkLand starts and decides protection exactly as before. The lookup
 * factory only runs when presence was confirmed, so discovery never loads
 * optional backend types on servers without them.
 */
class CoreProtectDiscoveryTest {

    private static final Executor DIRECT = Runnable::run;

    @Test
    void absentCoreProtectYieldsEmptyWithoutTouchingFactory() {
        AtomicBoolean factoryRan = new AtomicBoolean(false);
        Optional<WorldHistoryProvider> found = CoreProtectDiscovery.discover(
                () -> false, DIRECT, () -> {
                    factoryRan.set(true);
                    return query -> null;
                });
        assertTrue(found.isEmpty());
        assertFalse(factoryRan.get());
    }

    @Test
    void failingProbeYieldsEmpty() {
        Optional<WorldHistoryProvider> found = CoreProtectDiscovery.discover(
                () -> {
                    throw new IllegalStateException("probe failed");
                },
                DIRECT,
                () -> query -> null);
        assertTrue(found.isEmpty());
    }

    @Test
    void throwingFactoryYieldsEmpty() {
        Optional<WorldHistoryProvider> found = CoreProtectDiscovery.discover(
                () -> true, DIRECT, () -> {
                    throw new IllegalStateException("backend broken");
                });
        assertTrue(found.isEmpty());
    }

    @Test
    void linkageErrorFactoryYieldsEmpty() {
        Optional<WorldHistoryProvider> found = CoreProtectDiscovery.discover(
                () -> true, DIRECT, () -> {
                    throw new NoClassDefFoundError("optional backend missing");
                });
        assertTrue(found.isEmpty());
    }

    @Test
    void nullLookupYieldsEmpty() {
        Optional<WorldHistoryProvider> found =
                CoreProtectDiscovery.discover(() -> true, DIRECT, () -> null);
        assertTrue(found.isEmpty());
    }

    @Test
    void nullExecutorYieldsEmpty() {
        Optional<WorldHistoryProvider> found = CoreProtectDiscovery.discover(
                () -> true, null, () -> query -> null);
        assertTrue(found.isEmpty());
    }

    @Test
    void presentBackendYieldsProvider() {
        Optional<WorldHistoryProvider> found = CoreProtectDiscovery.discover(
                () -> true, DIRECT, () -> query -> null);
        assertTrue(found.isPresent());
        assertEquals(DIRECT, ((CoreProtectHistoryProvider) found.get()).executorForTest());
    }

    @Test
    void productionOverloadStaysEmptyWhenAbsent() {
        Optional<WorldHistoryProvider> found = CoreProtectDiscovery.discover(
                () -> false, DIRECT, () -> {
                    throw new IllegalStateException("must not resolve the handle");
                }, null);
        assertTrue(found.isEmpty());
    }
}

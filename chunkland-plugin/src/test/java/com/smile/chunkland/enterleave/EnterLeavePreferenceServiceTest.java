package com.smile.chunkland.enterleave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.PlayerSettingsRepository;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Preference switch behaviour: default enabled, async load/save through the
 * repository, fail-closed failures, sibling locale preservation and a bounded
 * snapshot.
 */
class EnterLeavePreferenceServiceTest {

    @Test
    void missingEntryReadsEnabledByDefault() {
        EnterLeavePreferenceService service = new EnterLeavePreferenceService();
        assertTrue(service.enabled(UUID.randomUUID()));
    }

    @Test
    void loadDisabledPersistsToSnapshot() throws Exception {
        EnterLeavePreferenceService service = new EnterLeavePreferenceService();
        UUID player = UUID.randomUUID();
        boolean loaded = service
                .loadAsync(player, id -> CompletableFuture.completedFuture(Optional.of(false)))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(loaded);
        assertFalse(service.enabled(player));
    }

    @Test
    void loadMissingFallsBackToEnabled() throws Exception {
        EnterLeavePreferenceService service = new EnterLeavePreferenceService();
        UUID player = UUID.randomUUID();
        service.setForTest(player, false);
        boolean loaded = service
                .loadAsync(player, id -> CompletableFuture.completedFuture(Optional.empty()))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(loaded);
        assertTrue(service.enabled(player));
    }

    @Test
    void loadFailureFailsClosedToEnabled() throws Exception {
        EnterLeavePreferenceService service = new EnterLeavePreferenceService();
        UUID player = UUID.randomUUID();
        service.setForTest(player, false);
        CompletableFuture<Optional<Boolean>> broken = new CompletableFuture<>();
        broken.completeExceptionally(new RuntimeException("db down"));
        boolean loaded = service
                .loadAsync(player, id -> broken)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(loaded);
        assertTrue(service.enabled(player));
    }

    @Test
    void repositoryRoundTrip(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("enter-leave.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
            UUID player = UUID.randomUUID();
            assertEquals(Optional.empty(),
                    repo.findEnterLeaveEnabled(player).toCompletableFuture().get(5, TimeUnit.SECONDS));
            repo.saveEnterLeaveEnabled(player, false).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Optional.of(false),
                    repo.findEnterLeaveEnabled(player).toCompletableFuture().get(5, TimeUnit.SECONDS));
            repo.saveEnterLeaveEnabled(player, true).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Optional.of(true),
                    repo.findEnterLeaveEnabled(player).toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void siblingLocaleSurvivesSwitchSave(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("enter-leave-sibling.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
            UUID player = UUID.randomUUID();
            repo.savePreferredLocale(player, "zh_TW").toCompletableFuture().get(5, TimeUnit.SECONDS);
            repo.saveEnterLeaveEnabled(player, false).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Optional.of("zh_TW"),
                    repo.findPreferredLocale(player).toCompletableFuture().get(5, TimeUnit.SECONDS));
            repo.savePreferredLocale(player, "en_US").toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Optional.of(false),
                    repo.findEnterLeaveEnabled(player).toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void serviceSaveLoadRoundTrip(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("enter-leave-service.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
            EnterLeavePreferenceService service = new EnterLeavePreferenceService();
            UUID player = UUID.randomUUID();
            assertTrue(service.saveAsync(player, false, repo)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertFalse(service.enabled(player));
            EnterLeavePreferenceService fresh = new EnterLeavePreferenceService();
            assertTrue(fresh.enabled(player));
            assertFalse(fresh.loadAsync(player, repo)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertFalse(fresh.enabled(player));
        }
    }

    @Test
    void snapshotIsBounded() {
        EnterLeavePreferenceService service = new EnterLeavePreferenceService();
        for (int i = 0; i < EnterLeavePreferenceService.MAX_ENTRIES + 50; i++) {
            service.setForTest(new UUID(0L, i), false);
        }
        assertTrue(service.sizeForTest() <= EnterLeavePreferenceService.MAX_ENTRIES,
                "snapshot must stay bounded, size=" + service.sizeForTest());
    }

    @Test
    void forgetAndClearDropSnapshot() {
        EnterLeavePreferenceService service = new EnterLeavePreferenceService();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.setForTest(first, false);
        service.setForTest(second, false);
        service.forget(first);
        assertTrue(service.enabled(first));
        assertFalse(service.enabled(second));
        service.clear();
        assertTrue(service.enabled(second));
    }
}

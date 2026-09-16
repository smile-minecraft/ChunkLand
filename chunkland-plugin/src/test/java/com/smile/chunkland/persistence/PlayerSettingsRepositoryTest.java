package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerSettingsRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripPreferredLocale(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("settings.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
            UUID player = UUID.randomUUID();
            assertEquals(Optional.empty(), read(repo, player));
            repo.savePreferredLocale(player, "zh_TW").toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Optional.of("zh_TW"), read(repo, player));
        }
    }

    @Test
    void nullClearsOverride(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("settings-clear.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
            UUID player = UUID.randomUUID();
            repo.savePreferredLocale(player, "en_US").toCompletableFuture().get(5, TimeUnit.SECONDS);
            repo.savePreferredLocale(player, null).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Optional.empty(), read(repo, player));
        }
    }

    @Test
    void blankClearsOverride(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("settings-blank.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
            UUID player = UUID.randomUUID();
            repo.savePreferredLocale(player, "en_US").toCompletableFuture().get(5, TimeUnit.SECONDS);
            repo.savePreferredLocale(player, "   ").toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Optional.empty(), read(repo, player));
        }
    }

    @Test
    void overwriteKeepsLatest(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("settings-overwrite.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
            UUID player = UUID.randomUUID();
            repo.savePreferredLocale(player, "en_US").toCompletableFuture().get(5, TimeUnit.SECONDS);
            repo.savePreferredLocale(player, "zh_TW").toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Optional.of("zh_TW"), read(repo, player));
        }
    }

    @Test
    void missingPlayerReadsEmpty(@TempDir Path temp) {
        Path db = temp.resolve("settings-missing.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
            assertEquals(Optional.empty(), read(repo, UUID.randomUUID()));
        }
    }

    @Test
    void nullPlayerIdRejected(@TempDir Path temp) {
        Path db = temp.resolve("settings-null.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
            assertThrows(NullPointerException.class, () -> repo.findPreferredLocale(null));
            assertThrows(NullPointerException.class, () -> repo.savePreferredLocale(null, "en_US"));
        }
    }

    @Test
    void closedStoreFailsFast(@TempDir Path temp) {
        Path db = temp.resolve("settings-closed.db");
        PersistenceStore store = PersistenceStore.open(db);
        PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
        store.close();
        try {
            var future = repo.findPreferredLocale(UUID.randomUUID()).toCompletableFuture();
            var failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            assertTrue(cause instanceof PersistenceClosedException
                    || cause instanceof PersistenceException
                    || cause instanceof IllegalStateException,
                    "closed store must fail fast, got: " + cause);
        } catch (PersistenceException | IllegalStateException expected) {
            // submitAsync rejects synchronously once closed — also fail-fast.
        }
    }

    private static Optional<String> read(PlayerSettingsRepository repo, UUID player) {
        try {
            return repo.findPreferredLocale(player).toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("read failed", failure);
        }
    }
}

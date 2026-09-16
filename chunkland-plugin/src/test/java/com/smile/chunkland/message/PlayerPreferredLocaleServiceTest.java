package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.PlayerSettingsRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerPreferredLocaleServiceTest {

    @Test
    void parseAcceptsUnderscoreAndDash() {
        assertEquals(Locale.forLanguageTag("zh-TW"), PlayerPreferredLocaleService.parseStoredTag("zh_TW").orElseThrow());
        assertEquals(Locale.forLanguageTag("en-US"), PlayerPreferredLocaleService.parseStoredTag("en-US").orElseThrow());
        assertEquals(Locale.forLanguageTag("en-US"), PlayerPreferredLocaleService.parseStoredTag("  en_US  ").orElseThrow());
    }

    @Test
    void parseRejectsNullBlankAndUnknown() {
        assertEquals(Optional.empty(), PlayerPreferredLocaleService.parseStoredTag(null));
        assertEquals(Optional.empty(), PlayerPreferredLocaleService.parseStoredTag(""));
        assertEquals(Optional.empty(), PlayerPreferredLocaleService.parseStoredTag("   "));
        assertEquals(Optional.empty(), PlayerPreferredLocaleService.parseStoredTag("!!!not-a-tag"));
    }

    @Test
    void loadPopulatesSnapshotFromStorage(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("locale-load.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
            UUID player = UUID.randomUUID();
            repo.savePreferredLocale(player, "zh_TW").toCompletableFuture().get(5, TimeUnit.SECONDS);
            PlayerPreferredLocaleService service = new PlayerPreferredLocaleService();
            assertEquals(Optional.empty(), service.preferred(player));
            Optional<Locale> loaded = service.loadAsync(player, repo).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Locale.forLanguageTag("zh-TW"), loaded.orElseThrow());
            assertEquals(Locale.forLanguageTag("zh-TW"), service.preferred(player).orElseThrow());
        }
    }

    @Test
    void loadMissingClearsSnapshot(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("locale-missing.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
            PlayerPreferredLocaleService service = new PlayerPreferredLocaleService();
            UUID player = UUID.randomUUID();
            service.putForTest(player, Locale.US);
            Optional<Locale> loaded = service.loadAsync(player, repo).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Optional.empty(), loaded);
            assertEquals(Optional.empty(), service.preferred(player));
        }
    }

    @Test
    void loadInvalidTagFailsClosed() throws Exception {
        PlayerPreferredLocaleService service = new PlayerPreferredLocaleService();
        UUID player = UUID.randomUUID();
        service.putForTest(player, Locale.US);
        Optional<Locale> loaded = service
                .loadAsync(player, id -> CompletableFuture.completedFuture(Optional.of("!!!not-a-tag")))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(Optional.empty(), loaded);
        assertEquals(Optional.empty(), service.preferred(player));
    }

    @Test
    void loadFailureFailsClosed() throws Exception {
        PlayerPreferredLocaleService service = new PlayerPreferredLocaleService();
        UUID player = UUID.randomUUID();
        service.putForTest(player, Locale.US);
        CompletableFuture<Optional<String>> broken = new CompletableFuture<>();
        broken.completeExceptionally(new RuntimeException("db down"));
        Optional<Locale> loaded = service
                .loadAsync(player, id -> broken)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(Optional.empty(), loaded);
        assertEquals(Optional.empty(), service.preferred(player));
    }

    @Test
    void renderReadIsMemoryOnly() {
        PlayerPreferredLocaleService service = new PlayerPreferredLocaleService();
        UUID player = UUID.randomUUID();
        service.putForTest(player, Locale.forLanguageTag("zh-TW"));
        // preferred() takes no loader and returns the snapshot directly:
        // a second read with no storage interaction sees the same value.
        Optional<Locale> first = service.preferred(player);
        Optional<Locale> second = service.preferred(player);
        assertTrue(first.isPresent() && second.isPresent());
        assertEquals(first, second);
    }

    @Test
    void saveValidatesBeforeStorage(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("locale-save.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
            PlayerPreferredLocaleService service = new PlayerPreferredLocaleService();
            UUID player = UUID.randomUUID();
            assertFalse(service.saveAsync(player, "!!!not-a-tag", repo)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(Optional.empty(),
                    repo.findPreferredLocale(player).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertTrue(service.saveAsync(player, "zh_TW", repo)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(Locale.forLanguageTag("zh-TW"), service.preferred(player).orElseThrow());
            assertTrue(service.saveAsync(player, null, repo)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(Optional.empty(), service.preferred(player));
        }
    }

    @Test
    void forgetAndClearDropSnapshot() {
        PlayerPreferredLocaleService service = new PlayerPreferredLocaleService();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.putForTest(first, Locale.US);
        service.putForTest(second, Locale.forLanguageTag("zh-TW"));
        service.forget(first);
        assertEquals(Optional.empty(), service.preferred(first));
        assertTrue(service.preferred(second).isPresent());
        service.clear();
        assertEquals(Optional.empty(), service.preferred(second));
    }

    @Test
    void snapshotIsBounded() {
        PlayerPreferredLocaleService service = new PlayerPreferredLocaleService();
        for (int i = 0; i < PlayerPreferredLocaleService.MAX_ENTRIES + 50; i++) {
            service.putForTest(new UUID(0L, i), Locale.US);
        }
        assertTrue(service.sizeForTest() <= PlayerPreferredLocaleService.MAX_ENTRIES,
                "snapshot must stay bounded, size=" + service.sizeForTest());
    }

    @Test
    void noSecondLocaleMap() throws Exception {
        String serviceSrc = Files.readString(
                Paths.get("src/main/java/com/smile/chunkland/message/PlayerPreferredLocaleService.java"));
        assertFalse(serviceSrc.contains("lang/"), "must reuse the existing lang loader, not a second map");
        assertFalse(serviceSrc.contains(".yml"), "must reuse the existing lang loader, not a second map");
        String pipelineSrc = Files.readString(
                Paths.get("src/main/java/com/smile/chunkland/message/ChunkLandMessagePipeline.java"));
        assertFalse(pipelineSrc.contains("preferred_locale_map"),
                "pipeline must not carry a second locale map");
    }
}

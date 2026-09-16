package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.PlayerSettingsRepository;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerSettingsLocaleListenerTest {

    @Test
    void joinLoadsWithoutBlocking(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("listener-join.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
            PlayerPreferredLocaleService service = new PlayerPreferredLocaleService();
            PlayerSettingsLocaleListener listener = new PlayerSettingsLocaleListener(service, repo);
            UUID player = UUID.randomUUID();
            repo.savePreferredLocale(player, "zh_TW").toCompletableFuture().get(5, TimeUnit.SECONDS);
            var stage = listener.onPlayerAvailable(player);
            Optional<Locale> loaded = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Locale.forLanguageTag("zh-TW"), loaded.orElseThrow());
            assertEquals(Locale.forLanguageTag("zh-TW"), service.preferred(player).orElseThrow());
        }
    }

    @Test
    void joinMissingFailsClosed(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("listener-missing.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            PlayerSettingsRepository repo = new PlayerSettingsRepository(store);
            PlayerPreferredLocaleService service = new PlayerPreferredLocaleService();
            PlayerSettingsLocaleListener listener = new PlayerSettingsLocaleListener(service, repo);
            UUID player = UUID.randomUUID();
            Optional<Locale> loaded =
                    listener.onPlayerAvailable(player).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Optional.empty(), loaded);
            assertEquals(Optional.empty(), service.preferred(player));
        }
    }

    @Test
    void quitForgetsSnapshot() {
        PlayerPreferredLocaleService service = new PlayerPreferredLocaleService();
        PlayerSettingsLocaleListener listener = new PlayerSettingsLocaleListener(service, null);
        UUID player = UUID.randomUUID();
        service.putForTest(player, Locale.US);
        listener.onPlayerQuit(player);
        assertEquals(Optional.empty(), service.preferred(player));
    }

    @Test
    void nullRepositoryFailsClosed() throws Exception {
        PlayerPreferredLocaleService service = new PlayerPreferredLocaleService();
        PlayerSettingsLocaleListener listener = new PlayerSettingsLocaleListener(service, null);
        UUID player = UUID.randomUUID();
        Optional<Locale> loaded =
                listener.onPlayerAvailable(player).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(Optional.empty(), loaded);
    }

    @Test
    void eventHandlersStayRegistered() throws Exception {
        int join = 0;
        int quit = 0;
        for (Method method : PlayerSettingsLocaleListener.class.getDeclaredMethods()) {
            if (method.getName().equals("onPlayerJoin")
                    && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0].getSimpleName().equals("PlayerJoinEvent")) {
                join++;
            }
            if (method.getName().equals("onPlayerQuit")
                    && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0].getSimpleName().equals("PlayerQuitEvent")) {
                quit++;
            }
        }
        assertEquals(1, join, "join adapter must stay a single Bukkit entry point");
        assertEquals(1, quit, "quit event adapter must stay a single Bukkit entry point");
    }
}

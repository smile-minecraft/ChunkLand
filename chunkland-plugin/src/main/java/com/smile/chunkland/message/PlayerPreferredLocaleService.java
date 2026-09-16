package com.smile.chunkland.message;

import com.smile.chunkland.config.MessageSettings;
import com.smile.chunkland.persistence.PlayerSettingsRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Bounded in-memory snapshot of per-player {@code preferred_locale}.
 *
 * <p>Thread boundary: {@link #loadAsync} / {@link #saveAsync} run their
 * SQL on the single persistence executor via {@link PlayerSettingsRepository}
 * and only touch the map afterwards. {@link #preferred} is a plain
 * {@link ConcurrentHashMap} read for the render path — never SQL, never
 * blocking. Unknown, blank or failing values fail closed to
 * {@link Optional#empty()} (no override) without leaking errors.</p>
 *
 * <p>Locale parsing reuses only the JDK tag rules (no second locale map,
 * no lang loader): {@code en_US} is normalized to {@code en-US} before
 * {@link Locale#forLanguageTag}.</p>
 */
public final class PlayerPreferredLocaleService {

    static final int MAX_ENTRIES = 2048;

    private final ConcurrentHashMap<UUID, Locale> cache = new ConcurrentHashMap<>();

    /**
     * Parse a stored tag into an override locale. Null, blank or
     * unparseable (empty language) tags yield empty — never an exception,
     * never a guessed locale.
     */
    public static Optional<Locale> parseStoredTag(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().replace('_', '-');
        Locale locale;
        try {
            locale = Locale.forLanguageTag(normalized);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        if (locale == null || locale.getLanguage().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(locale);
    }

    /**
     * Memory-only read for the render path. Never touches SQL.
     */
    public Optional<Locale> preferred(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return Optional.ofNullable(cache.get(playerId));
    }

    /**
     * Async join-time load: reads the raw tag off-thread, then updates the
     * bounded snapshot. Missing, blank, unknown or failed reads leave no
     * override behind. The returned stage never completes exceptionally
     * for data problems; only a rejected submission (closed store)
     * propagates.
     */
    public CompletionStage<Optional<Locale>> loadAsync(
            UUID playerId, PlayerSettingsRepository repository) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(repository, "repository");
        return loadAsync(playerId, repository::findPreferredLocale);
    }

    CompletionStage<Optional<Locale>> loadAsync(
            UUID playerId, Function<UUID, CompletionStage<Optional<String>>> loader) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(loader, "loader");
        CompletionStage<Optional<String>> read;
        try {
            read = loader.apply(playerId);
        } catch (RuntimeException failure) {
            cache.remove(playerId);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (read == null) {
            cache.remove(playerId);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return read.thenApply(raw -> {
            Optional<Locale> parsed = raw.flatMap(PlayerPreferredLocaleService::parseStoredTag);
            if (parsed.isPresent()) {
                putBounded(playerId, parsed.get());
            } else {
                cache.remove(playerId);
            }
            return parsed;
        }).exceptionally(failure -> {
            cache.remove(playerId);
            return Optional.empty();
        });
    }

    /**
     * Management-entry seam: validates before touching storage. Null/blank
     * clears the override, an unknown tag returns {@code false} without a
     * DB write, and a DB failure returns {@code false} with no cached
     * override left behind.
     */
    public CompletionStage<Boolean> saveAsync(
            UUID playerId, String rawTag, PlayerSettingsRepository repository) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(repository, "repository");
        if (rawTag == null || rawTag.isBlank()) {
            return repository.savePreferredLocale(playerId, null)
                    .thenApply(ignored -> {
                        cache.remove(playerId);
                        return true;
                    })
                    .exceptionally(failure -> {
                        cache.remove(playerId);
                        return false;
                    });
        }
        Optional<Locale> parsed = parseStoredTag(rawTag);
        if (parsed.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        String stored = MessageSettings.formatLocale(parsed.get());
        return repository.savePreferredLocale(playerId, stored)
                .thenApply(ignored -> {
                    putBounded(playerId, parsed.get());
                    return true;
                })
                .exceptionally(failure -> {
                    cache.remove(playerId);
                    return false;
                });
    }

    /** Quit-time forget; memory only. */
    public void forget(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        cache.remove(playerId);
    }

    /** Disable-time reset; memory only. */
    public void clear() {
        cache.clear();
    }

    int sizeForTest() {
        return cache.size();
    }

    void putForTest(UUID playerId, Locale locale) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(locale, "locale");
        putBounded(playerId, locale);
    }

    private void putBounded(UUID playerId, Locale locale) {
        if (cache.size() >= MAX_ENTRIES && !cache.containsKey(playerId)) {
            var cursor = cache.keys();
            if (cursor.hasMoreElements()) {
                cache.remove(cursor.nextElement());
            }
        }
        cache.put(playerId, locale);
    }
}

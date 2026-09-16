package com.smile.chunkland.enterleave;

import com.smile.chunkland.persistence.PlayerSettingsRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Bounded in-memory snapshot of the per-player enter-leave prompt switch.
 *
 * <p>Thread boundary: {@link #loadAsync} / {@link #saveAsync} run their SQL
 * on the single persistence executor via {@link PlayerSettingsRepository}
 * and only touch the map afterwards. {@link #enabled} is a plain
 * {@link ConcurrentHashMap} read for the movement path — never SQL, never
 * blocking. A missing row, an unknown value or any failure fails closed to
 * enabled (the column default is {@code 1}).
 */
public final class EnterLeavePreferenceService {

    static final int MAX_ENTRIES = 2048;

    private final ConcurrentHashMap<UUID, Boolean> cache = new ConcurrentHashMap<>();

    /**
     * Memory-only read for the movement path. Never touches SQL. Missing
     * entries (never loaded, forgotten, or failed loads) read as enabled.
     */
    public boolean enabled(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return cache.getOrDefault(playerId, Boolean.TRUE);
    }

    /**
     * Async join-time load: reads the stored switch off-thread, then updates
     * the bounded snapshot. Missing, unknown or failed reads leave the
     * default (enabled) behind. The returned stage never completes
     * exceptionally for data problems; only a rejected submission (closed
     * store) propagates.
     */
    public CompletionStage<Boolean> loadAsync(UUID playerId, PlayerSettingsRepository repository) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(repository, "repository");
        return loadAsync(playerId, repository::findEnterLeaveEnabled);
    }

    CompletionStage<Boolean> loadAsync(
            UUID playerId, Function<UUID, CompletionStage<Optional<Boolean>>> loader) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(loader, "loader");
        CompletionStage<Optional<Boolean>> read;
        try {
            read = loader.apply(playerId);
        } catch (RuntimeException failure) {
            cache.remove(playerId);
            return CompletableFuture.completedFuture(true);
        }
        if (read == null) {
            cache.remove(playerId);
            return CompletableFuture.completedFuture(true);
        }
        return read.thenApply(stored -> {
            if (stored.isPresent()) {
                putBounded(playerId, stored.get());
                return stored.get();
            }
            cache.remove(playerId);
            return true;
        }).exceptionally(failure -> {
            cache.remove(playerId);
            return true;
        });
    }

    /**
     * Persists the switch and updates the snapshot. A storage failure leaves
     * no cached override behind (the next read falls back to enabled) and
     * reports {@code false}.
     */
    public CompletionStage<Boolean> saveAsync(
            UUID playerId, boolean enabled, PlayerSettingsRepository repository) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(repository, "repository");
        return repository.saveEnterLeaveEnabled(playerId, enabled)
                .thenApply(ignored -> {
                    putBounded(playerId, enabled);
                    return true;
                })
                .exceptionally(failure -> {
                    cache.remove(playerId);
                    return false;
                });
    }

    /** Direct snapshot write without storage; reserved for management flows. */
    public void setForTest(UUID playerId, boolean enabled) {
        Objects.requireNonNull(playerId, "playerId");
        putBounded(playerId, enabled);
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

    private void putBounded(UUID playerId, boolean enabled) {
        if (cache.size() >= MAX_ENTRIES && !cache.containsKey(playerId)) {
            var cursor = cache.keys();
            if (cursor.hasMoreElements()) {
                cache.remove(cursor.nextElement());
            }
        }
        cache.put(playerId, enabled);
    }
}

package com.smile.chunkland.message;

import com.smile.chunkland.persistence.PlayerSettingsRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Thin Bukkit adapter between join/quit events and the preferred-locale
 * snapshot. Join submits an async persistence load and never blocks the
 * event thread; quit only drops the in-memory entry. Every failure path
 * is fail-closed and never breaks the lifecycle event.
 */
public final class PlayerSettingsLocaleListener implements Listener {

    private final PlayerPreferredLocaleService service;
    private final PlayerSettingsRepository repository;

    public PlayerSettingsLocaleListener(
            PlayerPreferredLocaleService service, PlayerSettingsRepository repository) {
        this.service = Objects.requireNonNull(service, "service");
        this.repository = repository;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        onPlayerAvailable(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        onPlayerQuit(event.getPlayer().getUniqueId());
    }

    /**
     * Join-time entry point (also drives tests without Bukkit events):
     * submits the async load and returns its stage without blocking.
     */
    public CompletionStage<Optional<Locale>> onPlayerAvailable(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        PlayerSettingsRepository repo = this.repository;
        if (repo == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            return service.loadAsync(playerId, repo);
        } catch (RuntimeException ignored) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /** Quit-time entry point: memory-only forget. */
    public void onPlayerQuit(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try {
            service.forget(playerId);
        } catch (RuntimeException ignored) {
            // A cache failure must never break the lifecycle event.
        }
    }
}

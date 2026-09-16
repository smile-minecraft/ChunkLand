package com.smile.chunkland.selection;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Thin Bukkit adapter; all lifecycle policy lives in SelectionSessionManager. */
public final class SelectionLifecycleListener implements Listener {
    private final SelectionSessionManager manager;
    private final OccupiedPreviewController occupiedPreview;

    public SelectionLifecycleListener(SelectionSessionManager manager) {
        this(manager, OccupiedPreviewController.noop());
    }

    /**
     * @param manager the session manager whose cleanup clears the selection
     * @param occupiedPreview the occupied-land preview loop; stopped on every
     *        lifecycle transition so no orphan render is left behind
     */
    public SelectionLifecycleListener(SelectionSessionManager manager, OccupiedPreviewController occupiedPreview) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.occupiedPreview = Objects.requireNonNull(occupiedPreview, "occupiedPreview");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            UUID playerId = player.getUniqueId();
            manager.onPlayerQuit(playerId);
            stopPreview(playerId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            UUID playerId = player.getUniqueId();
            manager.onWorldChange(playerId);
            stopPreview(playerId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        World world = event.getRespawnLocation() == null ? null : event.getRespawnLocation().getWorld();
        if (world != null) {
            UUID playerId = player.getUniqueId();
            manager.onRespawn(playerId, world.getUID());
            stopPreview(playerId);
        }
    }

    private void stopPreview(UUID playerId) {
        try {
            occupiedPreview.stop(playerId);
        } catch (RuntimeException ignored) {
            // A display failure must never break the lifecycle event.
        }
    }
}

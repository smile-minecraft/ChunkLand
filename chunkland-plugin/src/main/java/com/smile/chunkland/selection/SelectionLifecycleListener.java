package com.smile.chunkland.selection;

import java.util.Objects;
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

    public SelectionLifecycleListener(SelectionSessionManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            manager.onPlayerQuit(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            manager.onWorldChange(player.getUniqueId());
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
            manager.onRespawn(player.getUniqueId(), world.getUID());
        }
    }
}

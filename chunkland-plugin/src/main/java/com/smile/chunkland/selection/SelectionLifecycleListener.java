package com.smile.chunkland.selection;

import java.util.Objects;
import java.util.Optional;
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

    /**
     * Forgets the wand's per-player prompt/preview dedup once the session and
     * its preview have been torn down. Kept as a narrow callback so this
     * listener never depends on the wand package.
     */
    @FunctionalInterface
    public interface WandStateReset {
        void onSelectionCleared(UUID playerId);
    }

    private final SelectionSessionManager manager;
    private final OccupiedPreviewController occupiedPreview;
    private WandStateReset wandStateReset;

    public SelectionLifecycleListener(SelectionSessionManager manager) {
        this(manager, OccupiedPreviewController.noop());
    }

    /**
     * @param manager the session manager whose cleanup clears the selection
     * @param occupiedPreview the occupied-land preview loop; stopped on every
     *        lifecycle transition so no orphan render is left behind
     */
    public SelectionLifecycleListener(SelectionSessionManager manager, OccupiedPreviewController occupiedPreview) {
        this(manager, occupiedPreview, null);
    }

    /**
     * @param wandStateReset drops the wand's per-player prompt/preview dedup
     *        when a transition really tears the selection down; may be
     *        {@code null} when no wand path is wired
     */
    public SelectionLifecycleListener(
            SelectionSessionManager manager,
            OccupiedPreviewController occupiedPreview,
            WandStateReset wandStateReset) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.occupiedPreview = Objects.requireNonNull(occupiedPreview, "occupiedPreview");
        this.wandStateReset = wandStateReset;
    }

    /**
     * Bind the wand dedup reset after construction, for the production assembly
     * that builds this listener before the wand handler exists.
     */
    public void bindWandStateReset(WandStateReset wandStateReset) {
        this.wandStateReset = wandStateReset;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            UUID playerId = player.getUniqueId();
            manager.onPlayerQuit(playerId);
            stopPreview(playerId);
            forgetWandState(playerId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            UUID playerId = player.getUniqueId();
            manager.onWorldChange(playerId);
            stopPreview(playerId);
            forgetWandState(playerId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        World world = event.getRespawnLocation() == null ? null : event.getRespawnLocation().getWorld();
        if (world == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Optional<SelectionSession> before = manager.sessionFor(playerId);
        manager.onRespawn(playerId, world.getUID());
        // A same-world respawn keeps the live session, so its preview is still
        // valid; only a real teardown (cross-world respawn follows world-change
        // semantics) drops the preview and the wand's dedup state.
        boolean cleared = before.isPresent() && manager.sessionFor(playerId).isEmpty();
        if (cleared) {
            stopPreview(playerId);
            forgetWandState(playerId);
        }
    }

    private void forgetWandState(UUID playerId) {
        WandStateReset reset = wandStateReset;
        if (reset == null) {
            return;
        }
        try {
            reset.onSelectionCleared(playerId);
        } catch (RuntimeException ignored) {
            // A reset failure must never break the lifecycle event.
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

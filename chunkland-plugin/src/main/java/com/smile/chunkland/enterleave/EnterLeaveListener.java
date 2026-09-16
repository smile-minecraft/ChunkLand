package com.smile.chunkland.enterleave;

import com.smile.chunkland.api.event.LandEnterEvent;
import com.smile.chunkland.api.event.LandLeaveEvent;
import com.smile.chunkland.api.event.SubLandEnterEvent;
import com.smile.chunkland.api.event.SubLandLeaveEvent;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.event.bukkit.LandEnterBukkitEvent;
import com.smile.chunkland.event.bukkit.LandLeaveBukkitEvent;
import com.smile.chunkland.event.bukkit.SubLandEnterBukkitEvent;
import com.smile.chunkland.event.bukkit.SubLandLeaveBukkitEvent;
import com.smile.chunkland.persistence.PlayerSettingsRepository;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.SubLandIndex;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Movement listener that announces Land / SubLand boundary crossings as
 * ActionBar prompts.
 *
 * <p>Every event reads exactly one volatile {@link LandRegistry} snapshot and
 * resolves both the previous tracker state and the destination from that same
 * snapshot; only coordinates already on the event are used, so nothing here
 * loads a chunk, touches SQL, or looks up an offline player. An unreadable
 * snapshot or an unresolvable destination fails closed: no prompt and no
 * tracker update, so the next good observation still reports the true
 * crossing.
 *
 * <p>Movement shares the entry path's chunk pre-filter: walking inside one
 * chunk never consults the snapshot. Teleports always resolve the
 * destination. Registration runs at {@code MONITOR} with cancelled events
 * ignored, so a denied (cancelled) move never announces a crossing that did
 * not happen.
 *
 * <p>Every {@link Player} touch after the event thread (the send itself)
 * hops through the injected {@link PlayerScheduler} to the player's thread.
 */
public final class EnterLeaveListener implements Listener {

    private final Supplier<LandRegistry> snapshots;
    private final EnterLeaveTracker tracker;
    private final EnterLeavePreferenceService preferences;
    private final PlayerSettingsRepository repository;
    private final EnterLeaveNotifier notifier;
    private final PublicEvents events;

    /**
     * @param snapshots single volatile snapshot source (the protection
     *         store); {@code null} source fails every event closed
     * @param repository persistence for the join-time switch load; {@code null}
     *         keeps the default (enabled) without storage
     */
    public EnterLeaveListener(Supplier<LandRegistry> snapshots,
            EnterLeaveTracker tracker,
            EnterLeavePreferenceService preferences,
            PlayerSettingsRepository repository,
            EnterLeaveNotifier notifier) {
        this(snapshots, tracker, preferences, repository, notifier, PublicEvents.noop());
    }

    /**
     * @param events public Post dispatch for committed boundary transitions;
     *         {@code null} means no public events (prompts still work exactly
     *         as before)
     */
    public EnterLeaveListener(Supplier<LandRegistry> snapshots,
            EnterLeaveTracker tracker,
            EnterLeavePreferenceService preferences,
            PlayerSettingsRepository repository,
            EnterLeaveNotifier notifier,
            PublicEvents events) {
        this.snapshots = snapshots;
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.repository = repository;
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.events = events == null ? PublicEvents.noop() : events;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event == null || event.getPlayer() == null || event.isCancelled()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        if (from != null && from.getWorld() != null
                && from.getWorld().getUID().equals(to.getWorld().getUID())
                && (from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)) {
            return;
        }
        handleArrival(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event == null || event.getPlayer() == null || event.isCancelled()) {
            return;
        }
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        handleArrival(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        Player player = event.getPlayer();
        onPlayerAvailable(player.getUniqueId());
        baselineAt(player, player.getLocation());
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
     * submits the async switch load without blocking and returns its stage.
     */
    public CompletionStage<Boolean> onPlayerAvailable(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        PlayerSettingsRepository repo = this.repository;
        if (repo == null) {
            return CompletableFuture.completedFuture(true);
        }
        try {
            return preferences.loadAsync(playerId, repo);
        } catch (RuntimeException ignored) {
            return CompletableFuture.completedFuture(true);
        }
    }

    /** Quit-time entry point: memory-only forget of switch and boundary. */
    public void onPlayerQuit(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try {
            preferences.forget(playerId);
        } catch (RuntimeException ignored) {
            // A cache failure must never break the lifecycle event.
        }
        try {
            tracker.remove(playerId);
        } catch (RuntimeException ignored) {
            // A cache failure must never break the lifecycle event.
        }
    }

    /** Disable-time reset; memory only. */
    public void clearAll() {
        try {
            preferences.clear();
        } catch (RuntimeException ignored) {
        }
        try {
            tracker.clear();
        } catch (RuntimeException ignored) {
        }
    }

    private void handleArrival(Player player, Location to) {
        UUID playerId;
        try {
            playerId = player.getUniqueId();
        } catch (RuntimeException ignored) {
            return;
        }
        if (playerId == null) {
            return;
        }
        LandRegistry snapshot = currentSnapshot();
        if (snapshot == null) {
            return;
        }
        EnterLeavePosition current = resolve(snapshot, to);
        if (current == null) {
            return;
        }
        List<EnterLeaveNotice> notices;
        try {
            notices = tracker.updateAndDiff(playerId, current);
        } catch (RuntimeException ignored) {
            return;
        }
        if (notices.isEmpty()) {
            return;
        }
        // Public Post: one event per actual transition, on the movement
        // thread without I/O. Emission never touches the Player object —
        // only identities — and never throws, so it cannot disturb the
        // prompt path or its player-scheduler hop below.
        fireTransitionEvents(playerId, notices);
        try {
            notifier.notify(player, notices);
        } catch (RuntimeException ignored) {
            // The notifier already drops retired-scheduler sends; this
            // guards the preference read itself.
        }
    }

    /**
     * Publish one public event per tracker notice. Never throws: per-notice
     * guards isolate construction and listener failures without breaking
     * movement handling.
     */
    private void fireTransitionEvents(UUID playerId, List<EnterLeaveNotice> notices) {
        for (EnterLeaveNotice notice : notices) {
            if (notice == null || notice.position() == null || notice.kind() == null) {
                continue;
            }
            try {
                switch (notice.kind()) {
                    case ENTER_LAND -> {
                        LandEnterEvent event = new LandEnterEvent(playerId,
                                notice.position().landId(), notice.position().worldId());
                        events.firePost(event, () -> new LandEnterBukkitEvent(event.playerId(),
                                event.landId(), event.worldId(), false));
                    }
                    case LEAVE_LAND -> {
                        LandLeaveEvent event = new LandLeaveEvent(playerId,
                                notice.position().landId(), notice.position().worldId());
                        events.firePost(event, () -> new LandLeaveBukkitEvent(event.playerId(),
                                event.landId(), event.worldId(), false));
                    }
                    case ENTER_SUB -> {
                        SubLandEnterEvent event = new SubLandEnterEvent(playerId,
                                notice.position().landId(), notice.position().subLandId());
                        events.firePost(event, () -> new SubLandEnterBukkitEvent(event.playerId(),
                                event.landId(), event.subLandId(), false));
                    }
                    case LEAVE_SUB -> {
                        SubLandLeaveEvent event = new SubLandLeaveEvent(playerId,
                                notice.position().landId(), notice.position().subLandId());
                        events.firePost(event, () -> new SubLandLeaveBukkitEvent(event.playerId(),
                                event.landId(), event.subLandId(), false));
                    }
                }
            } catch (Throwable ignored) {
                // One bad notice (or listener) must never silence the rest
                // of the batch or the prompt path.
            }
        }
    }

    private void baselineAt(Player player, Location at) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        UUID playerId;
        try {
            playerId = player.getUniqueId();
        } catch (RuntimeException ignored) {
            return;
        }
        if (playerId == null) {
            return;
        }
        LandRegistry snapshot = currentSnapshot();
        if (snapshot == null) {
            return;
        }
        EnterLeavePosition current = resolve(snapshot, at);
        if (current == null) {
            return;
        }
        try {
            tracker.updateAndDiff(playerId, current);
        } catch (RuntimeException ignored) {
            // Baseline failures stay silent on the join path.
        }
    }

    private LandRegistry currentSnapshot() {
        Supplier<LandRegistry> source = this.snapshots;
        if (source == null) {
            return null;
        }
        try {
            return source.get();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Resolves one block position against the given snapshot. Returns
     * {@code null} when the position cannot be confirmed (missing world,
     * inconsistent index, or any lookup failure).
     */
    static EnterLeavePosition resolve(LandRegistry snapshot, Location at) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (at == null) {
            return null;
        }
        World world;
        try {
            world = at.getWorld();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (world == null) {
            return null;
        }
        UUID worldId;
        int blockX;
        int blockY;
        int blockZ;
        try {
            worldId = world.getUID();
            blockX = at.getBlockX();
            blockY = at.getBlockY();
            blockZ = at.getBlockZ();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (worldId == null) {
            return null;
        }
        try {
            int chunkX = Math.floorDiv(blockX, 16);
            int chunkZ = Math.floorDiv(blockZ, 16);
            LandId landId = snapshot.findLandId(worldId, chunkX, chunkZ);
            if (landId == null) {
                return EnterLeavePosition.wilderness(worldId);
            }
            LandSnapshot land = snapshot.land(landId);
            if (land == null) {
                return null;
            }
            String landName = land.displayName();
            SubLandIndex subs = snapshot.subLandIndex(landId);
            SubLandSnapshot sub = subs == null ? null : subs.findAtBlock(blockX, blockY, blockZ);
            if (sub == null) {
                return new EnterLeavePosition(worldId, landId, null, landName, null);
            }
            return new EnterLeavePosition(worldId, landId, sub.id(), landName, sub.name());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Reflective seam for the current snapshot source state. */
    Optional<LandRegistry> snapshotForTest() {
        return Optional.ofNullable(currentSnapshot());
    }
}

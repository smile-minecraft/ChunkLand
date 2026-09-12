package com.smile.chunkland.wand;

import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionEditOutcome;
import com.smile.chunkland.selection.SelectionEditService;
import com.smile.chunkland.selection.SelectionEndReason;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionUpdate;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

/**
 * Production wand click handling: turns wand hits into selection corners.
 *
 * <p>Either mouse button records a corner — buttons are interchangeable. The
 * first valid main-hand block click opens a session with the first corner, the
 * second valid click fills the other corner and analyses both as one chunk
 * rectangle through the shared edit service. Every further click keeps the
 * first corner and moves the second one, so the player resizes the rectangle
 * instead of restarting it; only leaving and re-equipping the wand starts a
 * clean range. A rejected analysis keeps the session untouched, so particles
 * only move when the rectangle is actually accepted.
 *
 * <p>Only identifiers and block coordinates cross into the session; no
 * Bukkit object is retained. Reads stay on the event thread and touch no
 * chunk data, storage, or network — position math plus the in-memory
 * collision index are all this path needs.
 */
public final class SelectionWandClickHandler implements WandClickHandler {

    private final SelectionSessionManager manager;
    private final Supplier<SelectionEditService> editServices;
    private final SelectionClock clock;
    private final WandFeedback feedback;

    public SelectionWandClickHandler(
            SelectionSessionManager manager,
            Supplier<SelectionEditService> editServices,
            SelectionClock clock) {
        this(manager, editServices, clock, WandFeedback.none());
    }

    public SelectionWandClickHandler(
            SelectionSessionManager manager,
            Supplier<SelectionEditService> editServices,
            SelectionClock clock,
            WandFeedback feedback) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.editServices = Objects.requireNonNull(editServices, "editServices");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    @Override
    public void onWandUse(Context context) {
        if (context == null || context.isBreak()) {
            // Break events stay safety-cancel only; the interact path already
            // recorded this hit, so handling both would echo the same corner.
            return;
        }
        handleClick(context.player(), context.block());
    }

    @Override
    public void onWandRightClick(Player player, Block block, BlockFace face) {
        handleClick(player, block);
    }

    @Override
    public void onWandUnequipped(Player player) {
        UUID playerId = quietPlayerId(player);
        if (playerId == null) {
            return;
        }
        try {
            // Dropping the range is the point of the transition; the end-reason
            // notifier owns the single "abandoned" prompt when a live range goes.
            manager.clear(playerId, SelectionEndReason.ITEM_CHANGED);
        } catch (RuntimeException ignored) {
            // A lifecycle hook must never throw back onto the item event thread.
        }
    }

    @Override
    public void onWandEquipped(Player player) {
        if (quietPlayerId(player) == null) {
            return;
        }
        // The range was already dropped when the wand left the hand, so this is
        // only guidance: tell the player the fresh range starts at a first click.
        feedback.send(player, WandFeedback.Kind.RESET, Map.of());
    }

    private void handleClick(Player player, Block block) {
        try {
            UUID playerId;
            try {
                if (player == null) {
                    return;
                }
                playerId = player.getUniqueId();
            } catch (RuntimeException ex) {
                return;
            }
            if (playerId == null) {
                return;
            }
            UUID worldId;
            int x;
            int y;
            int z;
            try {
                if (block == null) {
                    return;
                }
                World world = block.getWorld();
                if (world == null) {
                    return;
                }
                worldId = world.getUID();
                x = block.getX();
                y = block.getY();
                z = block.getZ();
            } catch (RuntimeException ex) {
                return;
            }
            if (worldId == null) {
                return;
            }
            SelectionPoint point;
            try {
                point = new SelectionPoint(worldId, x, y, z);
            } catch (RuntimeException ex) {
                return;
            }
            SelectionSession current;
            try {
                Optional<SelectionSession> existing = manager.sessionFor(playerId);
                if (existing == null) {
                    return;
                }
                current = existing.orElse(null);
            } catch (RuntimeException ex) {
                return;
            }
            Instant now;
            try {
                now = clock.now();
            } catch (RuntimeException ex) {
                return;
            }
            if (now == null) {
                return;
            }
            SelectionEditService edits;
            try {
                edits = editServices.get();
            } catch (RuntimeException ex) {
                return;
            }
            if (edits == null) {
                return;
            }
            if (current == null || !worldId.equals(current.worldId())) {
                openSession(playerId, player, worldId, point, now);
                return;
            }
            extendSession(playerId, player, current, edits, point);
        } catch (RuntimeException ex) {
            // Selection must never throw back onto the event thread.
        }
    }

    private void openSession(UUID playerId, Player player, UUID worldId, SelectionPoint point, Instant now) {
        SelectionSession stamped = startEmptySession(playerId, worldId, now);
        if (stamped == null) {
            return;
        }
        SelectionUpdate update;
        try {
            update = new SelectionUpdate(
                    Optional.of(point),
                    Optional.empty(),
                    stamped.selectedChunks(),
                    stamped.pendingChanges());
        } catch (RuntimeException ex) {
            return;
        }
        Optional<SelectionSession> updated;
        try {
            updated = manager.updateSelection(playerId, stamped, update);
        } catch (RuntimeException ex) {
            // The session stays open with no corner; the next hit retries.
            return;
        }
        if (updated != null && updated.isPresent()) {
            feedback.send(player, WandFeedback.Kind.FIRST_POINT, Map.of());
        }
    }

    private SelectionSession startEmptySession(UUID playerId, UUID worldId, Instant now) {
        SelectionSession initial;
        try {
            initial = SelectionSession.initial(
                    playerId,
                    worldId,
                    SelectionMode.EDIT_SELECTION,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    now);
        } catch (RuntimeException ex) {
            return null;
        }
        try {
            return manager.start(initial);
        } catch (RuntimeException ex) {
            // Disabled or racing cleanup stays fail-closed without a session.
            return null;
        }
    }

    private void extendSession(
            UUID playerId,
            Player player,
            SelectionSession current,
            SelectionEditService edits,
            SelectionPoint point) {
        // The first corner always stays; the new click moves the second corner.
        // A session that holds only the second corner is repaired the same way.
        boolean resize = current.pointA().isPresent() && current.pointB().isPresent();
        Optional<SelectionPoint> cornerA =
                current.pointA().isPresent() ? current.pointA() : Optional.of(point);
        Optional<SelectionPoint> cornerB =
                current.pointA().isPresent() ? Optional.of(point) : current.pointB();
        if (cornerA.isPresent() && cornerB.isPresent()) {
            SelectionEditOutcome outcome;
            try {
                outcome = edits.selectRectangle(current, cornerA.get(), cornerB.get());
            } catch (RuntimeException ex) {
                return;
            }
            if (outcome == null || !outcome.accepted() || outcome.update().isEmpty()) {
                // Limits, collisions, or shape rejections leave the session as-is.
                return;
            }
            if (outcome.expectedSession() != current) {
                return;
            }
            Optional<SelectionSession> updated;
            try {
                updated = manager.updateSelection(playerId, outcome.expectedSession(), outcome.update().get());
            } catch (RuntimeException ex) {
                // A stale or disabled session simply keeps its previous corners.
                return;
            }
            if (updated != null && updated.isPresent()) {
                feedback.send(player, resize ? WandFeedback.Kind.RESIZED : WandFeedback.Kind.SECOND_POINT,
                        acceptedSizeVars(updated.get()));
            }
            return;
        }
        SelectionUpdate update;
        try {
            update = new SelectionUpdate(
                    cornerA, cornerB, current.selectedChunks(), current.pendingChanges());
        } catch (RuntimeException ex) {
            return;
        }
        Optional<SelectionSession> updated;
        try {
            updated = manager.updateSelection(playerId, current, update);
        } catch (RuntimeException ex) {
            // Stale or disabled sessions keep their previous corners.
            return;
        }
        if (updated != null && updated.isPresent()) {
            feedback.send(player, WandFeedback.Kind.FIRST_POINT, Map.of());
        }
    }

    /**
     * Announce the accepted rectangle as chunk spans. The accepted session
     * always holds a non-empty chunk set, so the vars are always present; an
     * empty set yields no vars rather than a guessed size.
     */
    private static Map<String, Object> acceptedSizeVars(SelectionSession session) {
        return SelectionRectangleDimensions.from(session.selectedChunks())
                .map(SelectionRectangleDimensions::messageVars)
                .orElse(Map.of());
    }

    /** Read the player id without ever letting a Bukkit failure escape the event path. */
    private static UUID quietPlayerId(Player player) {
        if (player == null) {
            return null;
        }
        try {
            return player.getUniqueId();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}

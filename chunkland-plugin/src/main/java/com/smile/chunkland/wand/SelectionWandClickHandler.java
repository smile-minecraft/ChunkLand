package com.smile.chunkland.wand;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.selection.OccupiedPreviewController;
import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionEditOutcome;
import com.smile.chunkland.selection.SelectionEditReason;
import com.smile.chunkland.selection.SelectionEditService;
import com.smile.chunkland.selection.SelectionEndReason;
import com.smile.chunkland.selection.SelectionLandBoundaryLookup;
import com.smile.chunkland.selection.SelectionLandContext;
import com.smile.chunkland.selection.SelectionLandLookup;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionPreviewColor;
import com.smile.chunkland.selection.SelectionPreviewColorResolver;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionUpdate;
import com.smile.chunkland.selection.SelectionWandGuard;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>The first corner is context-aware: wilderness opens a new {@code
 * CREATE_LAND} claim, the actor's own Player Land opens an {@code
 * EDIT_SELECTION} that targets it so expand/shrink can read the target, and
 * any other land is refused before a session exists. A candidate rectangle
 * that would cover a land other than the session target is refused by the edit
 * service's collision guard and reported once as occupied; while a player
 * keeps hitting a blocked area the prompt is not repeated.
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
    private final SelectionWandGuard guard;
    private final SelectionLandLookup landLookup;
    private final SelectionLandBoundaryLookup boundaryLookup;
    private final OccupiedPreviewController preview;
    private final SelectionPreviewColorResolver previewColors;
    /**
     * Last blocked token per player, so a repeated blocked click does not
     * re-prompt. The token is the live session generation, or {@code
     * NO_SESSION_TOKEN} while no session exists; it is cleared by any accepted
     * update and when the wand leaves the hand.
     */
    private final Map<UUID, Long> blockedNotified = new ConcurrentHashMap<>();
    /** Players already told that the land registry is not hydrated yet. */
    private final Set<UUID> unavailableNotified = ConcurrentHashMap.newKeySet();
    /** Land currently previewed per player, so a repeated click does not restart it. */
    private final Map<UUID, PreviewState> previewedLand = new ConcurrentHashMap<>();

    private record PreviewState(LandId land, SelectionPreviewColor color) {
    }

    private static final long NO_SESSION_TOKEN = -1L;

    public SelectionWandClickHandler(
            SelectionSessionManager manager,
            Supplier<SelectionEditService> editServices,
            SelectionClock clock) {
        this(manager, editServices, clock, WandFeedback.none(), SelectionLandLookup.none());
    }

    public SelectionWandClickHandler(
            SelectionSessionManager manager,
            Supplier<SelectionEditService> editServices,
            SelectionClock clock,
            WandFeedback feedback) {
        this(manager, editServices, clock, feedback, SelectionLandLookup.none());
    }

    public SelectionWandClickHandler(
            SelectionSessionManager manager,
            Supplier<SelectionEditService> editServices,
            SelectionClock clock,
            WandFeedback feedback,
            SelectionLandLookup landLookup) {
        this(manager, editServices, clock, feedback, landLookup,
                SelectionLandBoundaryLookup.none(), OccupiedPreviewController.noop(),
                SelectionPreviewColorResolver.blocked());
    }

    public SelectionWandClickHandler(
            SelectionSessionManager manager,
            Supplier<SelectionEditService> editServices,
            SelectionClock clock,
            WandFeedback feedback,
            SelectionLandLookup landLookup,
            SelectionLandBoundaryLookup boundaryLookup,
            OccupiedPreviewController preview) {
        this(manager, editServices, clock, feedback, landLookup, boundaryLookup, preview,
                SelectionPreviewColorResolver.blocked());
    }

    public SelectionWandClickHandler(
            SelectionSessionManager manager,
            Supplier<SelectionEditService> editServices,
            SelectionClock clock,
            WandFeedback feedback,
            SelectionLandLookup landLookup,
            SelectionLandBoundaryLookup boundaryLookup,
            OccupiedPreviewController preview,
            SelectionPreviewColorResolver previewColors) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.editServices = Objects.requireNonNull(editServices, "editServices");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.landLookup = Objects.requireNonNull(landLookup, "landLookup");
        this.guard = new SelectionWandGuard(landLookup);
        this.boundaryLookup = Objects.requireNonNull(boundaryLookup, "boundaryLookup");
        this.preview = Objects.requireNonNull(preview, "preview");
        this.previewColors = Objects.requireNonNull(previewColors, "previewColors");
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
            forgetDedup(playerId);
            stopPreview(playerId);
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

    /**
     * Forget the per-player blocked prompt, unavailable prompt and preview
     * bookkeeping after a selection teardown (quit, world change, cross-world
     * respawn), so the next click on the same land prompts and previews again.
     * The caller owns stopping the preview renderer; this only resets the
     * dedup, so the handler's view stays consistent with the torn-down render.
     */
    public void onSelectionCleared(UUID playerId) {
        if (playerId == null) {
            return;
        }
        forgetDedup(playerId);
    }

    private void forgetDedup(UUID playerId) {
        blockedNotified.remove(playerId);
        unavailableNotified.remove(playerId);
        previewedLand.remove(playerId);
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
            if (current != null && current.mode() == SelectionMode.CREATE_SUBLAND) {
                if (!worldId.equals(current.worldId())) {
                    notifyOutside(player);
                    return;
                }
                extendSubLandSession(playerId, player, current, point);
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
                openSession(playerId, player, worldId, point, now, current);
                return;
            }
            extendSession(playerId, player, current, edits, point);
        } catch (RuntimeException ex) {
            // Selection must never throw back onto the event thread.
        }
    }

    private void openSession(
            UUID playerId, Player player, UUID worldId, SelectionPoint point, Instant now, SelectionSession current) {
        SelectionWandGuard.FirstPoint firstPoint;
        try {
            firstPoint = guard.classifyFirstPoint(playerId, point);
        } catch (RuntimeException ex) {
            // Not hydrated or unreadable: fail closed and say so rather than
            // treating durable land as wilderness.
            notifyUnavailable(playerId, player);
            return;
        }
        if (firstPoint == null || firstPoint.kind() == SelectionWandGuard.FirstPointKind.BLOCKED) {
            Optional<LandId> occupied = firstPoint == null ? Optional.empty() : firstPoint.occupiedLandId();
            notifyBlocked(playerId, player, current);
            showPreview(playerId, occupied, point, point.blockY() + 1.0);
            return;
        }
        if (firstPoint.kind() == SelectionWandGuard.FirstPointKind.EDIT_SELECTION) {
            // Own land: open the edit context and keep the existing boundary as
            // the baseline until the player accepts a new selection.
            startSession(playerId, player, worldId, point, now, SelectionMode.EDIT_SELECTION,
                    firstPoint.targetLandId(), firstPoint.baseStructureRevision(), WandFeedback.Kind.EDIT_TARGET);
            showPreview(playerId, firstPoint.targetLandId(), point, point.blockY() + 1.0,
                    SelectionPreviewColor.OWN);
            return;
        }
        // Wilderness first point: no existing boundary to preview.
        stopPreview(playerId);
        startSession(playerId, player, worldId, point, now, SelectionMode.CREATE_LAND,
                Optional.empty(), 0L, WandFeedback.Kind.FIRST_POINT);
    }

    private void startSession(
            UUID playerId,
            Player player,
            UUID worldId,
            SelectionPoint point,
            Instant now,
            SelectionMode mode,
            Optional<LandId> targetLandId,
            long baseStructureRevision,
            WandFeedback.Kind opening) {
        SelectionSession stamped = startEmptySession(
                playerId, worldId, mode, targetLandId, baseStructureRevision, now);
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
            clearBlockedNotice(playerId);
            feedback.send(player, opening, Map.of());
        }
    }

    private SelectionSession startEmptySession(
            UUID playerId,
            UUID worldId,
            SelectionMode mode,
            Optional<LandId> targetLandId,
            long baseStructureRevision,
            Instant now) {
        SelectionSession initial;
        try {
            initial = SelectionSession.initial(
                    playerId,
                    worldId,
                    mode,
                    targetLandId,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    baseStructureRevision,
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

    private void extendSubLandSession(
            UUID playerId, Player player, SelectionSession current, SelectionPoint point) {
        if (current.targetLandId().isEmpty()) {
            notifyOutside(player);
            return;
        }
        final SelectionLandContext context;
        try {
            context = landLookup.landAt(
                    point.worldId(), Math.floorDiv(point.blockX(), 16),
                    Math.floorDiv(point.blockZ(), 16));
        } catch (RuntimeException ex) {
            notifyUnavailable(playerId, player);
            return;
        }
        if (context == null || !current.targetLandId().get().equals(context.landId())) {
            notifyOutside(player);
            return;
        }
        Optional<SelectionPoint> cornerA;
        Optional<SelectionPoint> cornerB;
        WandFeedback.Kind kind;
        if (current.pointA().isEmpty()) {
            cornerA = Optional.of(point);
            cornerB = Optional.empty();
            kind = WandFeedback.Kind.FIRST_POINT;
        } else if (current.pointB().isEmpty()) {
            cornerA = current.pointA();
            cornerB = Optional.of(point);
            kind = WandFeedback.Kind.SECOND_POINT;
        } else {
            cornerA = current.pointA();
            cornerB = Optional.of(point);
            kind = WandFeedback.Kind.RESIZED;
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
            return;
        }
        if (updated != null && updated.isPresent()) {
            clearBlockedNotice(playerId);
            feedback.send(player, kind, subLandSizeVars(updated.get()));
        }
    }

    private void extendSession(
            UUID playerId,
            Player player,
            SelectionSession current,
            SelectionEditService edits,
            SelectionPoint point) {
        // Only the wand-owned claim/edit modes use the rectangle editor.
        // CREATE_SUBLAND is routed above and never reinterpreted as a claim/edit.
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
                // Limits and shape rejections leave the session as-is and silent;
                // an occupied chunk is the one rejection the player is told about,
                // once per blocked attempt, together with the blocking land's
                // actual boundary preview.
                if (outcome != null && outcome.reason() == SelectionEditReason.COLLISION) {
                    notifyBlocked(playerId, player, current);
                    showPreview(playerId, occupiedLandAt(outcome.conflictChunk()), point,
                            point.blockY() + 1.0);
                }
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
                clearBlockedNotice(playerId);
                // A committed selection replaces the preview with the normal
                // gold outline.
                stopPreview(playerId);
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
            clearBlockedNotice(playerId);
            feedback.send(player, WandFeedback.Kind.FIRST_POINT, Map.of());
        }
    }

    /**
     * Tell the player once that the click hit an existing land the selection
     * may not cover. The prompt is deduplicated per session generation (or the
     * no-session state) so holding the wand on a blocked area cannot spam chat.
     */
    private void notifyBlocked(UUID playerId, Player player, SelectionSession current) {
        long token = current == null ? NO_SESSION_TOKEN : current.sessionGeneration();
        Long previous = blockedNotified.put(playerId, token);
        if (previous != null && previous == token) {
            return;
        }
        feedback.send(player, WandFeedback.Kind.BLOCKED, Map.of());
    }

    private void clearBlockedNotice(UUID playerId) {
        blockedNotified.remove(playerId);
        unavailableNotified.remove(playerId);
    }

    /** Tell the player once that land data is not hydrated yet, so nothing is judged. */
    private void notifyUnavailable(UUID playerId, Player player) {
        if (!unavailableNotified.add(playerId)) {
            return;
        }
        feedback.send(player, WandFeedback.Kind.UNAVAILABLE, Map.of());
    }

    private void notifyOutside(Player player) {
        feedback.send(player, WandFeedback.Kind.SUBLAND_OUTSIDE, Map.of());
    }

    /** Resolve the land owning a rejected candidate chunk, for the boundary preview. */
    private Optional<LandId> occupiedLandAt(Optional<ChunkKey> chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return Optional.empty();
        }
        ChunkKey key = chunk.get();
        try {
            SelectionLandContext context = landLookup.landAt(key.worldId(), key.chunkX(), key.chunkZ());
            return context == null ? Optional.empty() : Optional.of(context.landId());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * Render the blocking land's real immutable boundary with the preview
     * controller. Best-effort: a missing land or a failing preview never
     * affects the session or the claim tokens.
     */
    private void showPreview(UUID playerId, Optional<LandId> landId,
                             SelectionPoint target, double planeY) {
        showPreview(playerId, landId, target, planeY, null);
    }

    private void showPreview(UUID playerId, Optional<LandId> landId,
                             SelectionPoint target, double planeY,
                             SelectionPreviewColor forcedColor) {
        if (landId == null || landId.isEmpty() || target == null) {
            return;
        }
        LandId land = landId.get();
        SelectionPreviewColor color = forcedColor == null
                ? resolvePreviewColor(playerId, land, target)
                : forcedColor;
        PreviewState current = previewedLand.get(playerId);
        if (current != null && current.land().equals(land) && current.color() == color) {
            // Same land and colour: the running preview already outlines it.
            return;
        }
        Optional<Set<ChunkKey>> chunks;
        try {
            chunks = boundaryLookup.chunksOf(land);
        } catch (RuntimeException ex) {
            return;
        }
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        Set<ChunkKey> boundary = chunks.get();
        if (boundary.isEmpty()) {
            return;
        }
        try {
            preview.show(playerId, boundary, planeY, color);
            previewedLand.put(playerId, new PreviewState(land, color));
        } catch (RuntimeException ignored) {
            // Preview is best-effort; selection data is unaffected.
        }
    }

    private SelectionPreviewColor resolvePreviewColor(UUID playerId, LandId landId,
                                                       SelectionPoint target) {
        final SelectionLandContext context;
        try {
            context = landLookup.landAt(target.worldId(), target.blockX() >> 4, target.blockZ() >> 4);
        } catch (RuntimeException failure) {
            return SelectionPreviewColor.BLOCKED;
        }
        if (context == null || !landId.equals(context.landId())) {
            return SelectionPreviewColor.BLOCKED;
        }
        if (context.ownedBy(playerId)) {
            return SelectionPreviewColor.OWN;
        }
        try {
            SelectionPreviewColor resolved = previewColors.resolve(playerId, context, target);
            return resolved == null ? SelectionPreviewColor.BLOCKED : resolved;
        } catch (RuntimeException failure) {
            return SelectionPreviewColor.BLOCKED;
        }
    }

    private void stopPreview(UUID playerId) {
        previewedLand.remove(playerId);
        try {
            preview.stop(playerId);
        } catch (RuntimeException ignored) {
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

    private static Map<String, Object> subLandSizeVars(SelectionSession session) {
        Optional<SelectionPoint> first = session.pointA();
        Optional<SelectionPoint> second = session.pointB();
        if (first.isEmpty() || second.isEmpty()) {
            return Map.of();
        }
        SelectionPoint a = first.get();
        SelectionPoint b = second.get();
        try {
            int minChunkX = Math.floorDiv(Math.min(a.blockX(), b.blockX()), 16);
            int maxChunkX = Math.floorDiv(Math.max(a.blockX(), b.blockX()), 16);
            int minChunkZ = Math.floorDiv(Math.min(a.blockZ(), b.blockZ()), 16);
            int maxChunkZ = Math.floorDiv(Math.max(a.blockZ(), b.blockZ()), 16);
            return new SelectionRectangleDimensions(
                    maxChunkX - minChunkX + 1, maxChunkZ - minChunkZ + 1).messageVars();
        } catch (RuntimeException invalid) {
            return Map.of();
        }
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

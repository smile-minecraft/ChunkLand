package com.smile.chunkland.selection;

import com.smile.chunkland.api.geometry.BoundaryExtractor;
import com.smile.chunkland.api.geometry.BoundarySegment;
import com.smile.chunkland.api.geometry.ChunkGeometry;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.runtime.index.WorldChunkIndex;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure in-memory selection analysis for rectangles and single-chunk edits.
 *
 * <p>This service only consumes value objects and an immutable packed index. It
 * never receives a Bukkit world, a chunk, or terrain data, and it does not own
 * or mutate a session.</p>
 */
public final class SelectionEditService {
    private final int maxSideLength;
    private final long maxChunks;
    private final SelectionLandIndex landIndex;

    public SelectionEditService(LimitSettings limits, SelectionLandIndex landIndex) {
        Objects.requireNonNull(limits, "limits");
        this.maxSideLength = limits.maxSelectionSideLength();
        this.maxChunks = Math.min(limits.maxSelectionChunks(), Cuboid.MAX_COVERED_CHUNKS);
        this.landIndex = Objects.requireNonNull(landIndex, "landIndex");
    }

    public SelectionEditService(LimitSettings limits, WorldChunkIndex index) {
        this(limits, SelectionLandIndex.from(index));
    }

    /** Analyze the two points stored by the session as one inclusive chunk rectangle. */
    public SelectionEditOutcome selectRectangle(SelectionSession session) {
        Objects.requireNonNull(session, "session");
        if (session.pointA().isEmpty() || session.pointB().isEmpty()) {
            return rejectedWithoutCandidate(session, SelectionEditReason.MISSING_POINT);
        }
        return selectRectangle(session, session.pointA().orElseThrow(), session.pointB().orElseThrow());
    }

    /** Analyze an inclusive chunk rectangle projected from two block positions. */
    public SelectionEditOutcome selectRectangle(
            SelectionSession session, SelectionPoint pointA, SelectionPoint pointB) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(pointA, "pointA");
        Objects.requireNonNull(pointB, "pointB");
        if (!session.worldId().equals(pointA.worldId()) || !session.worldId().equals(pointB.worldId())) {
            return rejectedWithoutCandidate(session, SelectionEditReason.WORLD_MISMATCH);
        }

        int minChunkX = Math.floorDiv(Math.min(pointA.blockX(), pointB.blockX()), 16);
        int maxChunkX = Math.floorDiv(Math.max(pointA.blockX(), pointB.blockX()), 16);
        int minChunkZ = Math.floorDiv(Math.min(pointA.blockZ(), pointB.blockZ()), 16);
        int maxChunkZ = Math.floorDiv(Math.max(pointA.blockZ(), pointB.blockZ()), 16);
        long width = span(minChunkX, maxChunkX);
        long depth = span(minChunkZ, maxChunkZ);
        if (width > maxSideLength || depth > maxSideLength) {
            return rejectedWithoutCandidate(session, SelectionEditReason.SIDE_LIMIT_EXCEEDED);
        }
        long count = Math.multiplyExact(width, depth);
        if (count > maxChunks) {
            return rejectedWithoutCandidate(session, SelectionEditReason.CHUNK_LIMIT_EXCEEDED);
        }

        Set<ChunkKey> rectangle = new Cuboid(
                Math.min(pointA.blockX(), pointB.blockX()),
                Math.min(pointA.blockY(), pointB.blockY()),
                Math.min(pointA.blockZ(), pointB.blockZ()),
                Math.max(pointA.blockX(), pointB.blockX()),
                Math.max(pointA.blockY(), pointB.blockY()),
                Math.max(pointA.blockZ(), pointB.blockZ()))
                .coveredChunks(session.worldId());
        Map<ChunkKey, PendingChange> pending = reconcilePending(
                session.selectedChunks(), session.pendingChanges(), rectangle);
        SelectionUpdate update = new SelectionUpdate(
                Optional.of(pointA), Optional.of(pointB), rectangle, pending);
        return evaluate(session, update, false);
    }

    /** Analyze a single-chunk add or remove in EDIT_SELECTION mode. */
    public SelectionEditOutcome editChunk(
            SelectionSession session, ChunkKey chunk, PendingChange operation) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(operation, "operation");
        if (session.mode() != SelectionMode.EDIT_SELECTION) {
            return rejectedWithoutCandidate(session, SelectionEditReason.WRONG_MODE);
        }
        if (!session.worldId().equals(chunk.worldId())) {
            return rejectedWithoutCandidate(session, SelectionEditReason.WORLD_MISMATCH);
        }

        Set<ChunkKey> candidate = new LinkedHashSet<>(session.selectedChunks());
        boolean shouldBeSelected = operation == PendingChange.ADD;
        if (shouldBeSelected) {
            candidate.add(chunk);
        } else {
            candidate.remove(chunk);
        }
        Map<ChunkKey, PendingChange> pending = reconcilePending(
                session.selectedChunks(), session.pendingChanges(), candidate);
        SelectionUpdate update = new SelectionUpdate(session.pointA(), session.pointB(), candidate, pending);
        return evaluate(session, update, true);
    }

    public SelectionEditOutcome addChunk(SelectionSession session, ChunkKey chunk) {
        return editChunk(session, chunk, PendingChange.ADD);
    }

    public SelectionEditOutcome removeChunk(SelectionSession session, ChunkKey chunk) {
        return editChunk(session, chunk, PendingChange.REMOVE);
    }

    private SelectionEditOutcome evaluate(
            SelectionSession session, SelectionUpdate update, boolean rejectNoChange) {
        Set<ChunkKey> candidate = update.selectedChunks();
        Bounds bounds = bounds(candidate);
        if (bounds.width() > maxSideLength || bounds.depth() > maxSideLength) {
            return rejectedAtLimit(session, update, SelectionEditReason.SIDE_LIMIT_EXCEEDED);
        }
        if (candidate.size() > maxChunks) {
            return rejectedAtLimit(session, update, SelectionEditReason.CHUNK_LIMIT_EXCEEDED);
        }

        ChunkGeometry geometry = new ChunkGeometry(candidate);
        Set<BoundarySegment> boundary = BoundaryExtractor.extract(candidate);
        Optional<ChunkKey> conflict = collision(session, candidate);
        if (conflict.isPresent()) {
            return rejected(session, update, SelectionEditReason.COLLISION, geometry, boundary, conflict);
        }
        if (!geometry.isConnected()) {
            return rejected(session, update, SelectionEditReason.DISCONNECTED, geometry, boundary, Optional.empty());
        }
        if (rejectNoChange
                && candidate.equals(session.selectedChunks())
                && update.pendingChanges().equals(session.pendingChanges())
                && update.pointA().equals(session.pointA())
                && update.pointB().equals(session.pointB())) {
            return rejected(session, update, SelectionEditReason.NO_CHANGE, geometry, boundary, Optional.empty());
        }
        return new SelectionEditOutcome(
                true, SelectionEditReason.ACCEPTED, session, Optional.of(update), candidate, geometry, boundary,
                Optional.empty());
    }

    private Optional<ChunkKey> collision(SelectionSession session, Set<ChunkKey> candidate) {
        ChunkKey firstConflict = null;
        for (ChunkKey chunk : candidate) {
            LandId land = landIndex.landIdAtPacked(session.worldId(), chunk.pack());
            if (firstConflict == null
                    && land != null && session.targetLandId().filter(land::equals).isEmpty()) {
                firstConflict = chunk;
            }
        }
        return Optional.ofNullable(firstConflict);
    }

    private SelectionEditOutcome rejectedWithoutCandidate(
            SelectionSession session, SelectionEditReason reason) {
        return new SelectionEditOutcome(
                false, reason, session, Optional.empty(), Set.of(), new ChunkGeometry(Set.of()), Set.of(), Optional.empty());
    }

    private static SelectionEditOutcome rejected(
            SelectionSession session,
            SelectionUpdate update,
            SelectionEditReason reason,
            ChunkGeometry geometry,
            Set<BoundarySegment> boundary,
            Optional<ChunkKey> conflict) {
        return new SelectionEditOutcome(
                false, reason, session, Optional.empty(), update.selectedChunks(), geometry, boundary, conflict);
    }

    private static SelectionEditOutcome rejectedAtLimit(
            SelectionSession session, SelectionUpdate update, SelectionEditReason reason) {
        return new SelectionEditOutcome(
                false, reason, session, Optional.empty(), update.selectedChunks(),
                new ChunkGeometry(Set.of()), Set.of(), Optional.empty());
    }

    private static Map<ChunkKey, PendingChange> reconcilePending(
            Set<ChunkKey> current,
            Map<ChunkKey, PendingChange> pending,
            Set<ChunkKey> desired) {
        Set<ChunkKey> relevant = new LinkedHashSet<>(current);
        relevant.addAll(pending.keySet());
        relevant.addAll(desired);
        Map<ChunkKey, PendingChange> result = new LinkedHashMap<>();
        for (ChunkKey chunk : relevant) {
            PendingChange existing = pending.get(chunk);
            boolean durable = existing == PendingChange.ADD
                    ? false
                    : existing == PendingChange.REMOVE || current.contains(chunk);
            boolean selected = desired.contains(chunk);
            if (selected != durable) {
                result.put(chunk, selected ? PendingChange.ADD : PendingChange.REMOVE);
            }
        }
        return Map.copyOf(result);
    }

    private static Bounds bounds(Set<ChunkKey> chunks) {
        if (chunks.isEmpty()) {
            return new Bounds(0, 0);
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (ChunkKey chunk : chunks) {
            minX = Math.min(minX, chunk.chunkX());
            maxX = Math.max(maxX, chunk.chunkX());
            minZ = Math.min(minZ, chunk.chunkZ());
            maxZ = Math.max(maxZ, chunk.chunkZ());
        }
        return new Bounds(span(minX, maxX), span(minZ, maxZ));
    }

    private static long span(int min, int max) {
        return (long) max - min + 1L;
    }

    private record Bounds(long width, long depth) {
    }
}

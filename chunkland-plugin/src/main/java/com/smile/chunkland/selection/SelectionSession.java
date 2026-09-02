package com.smile.chunkland.selection;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, server-independent state for one player's active selection.
 *
 * <p>The session contains identifiers and value objects only. In particular, it
 * does not retain Bukkit objects, persistence handles, or task handles. The
 * lifecycle manager owns those runtime resources separately.</p>
 */
public record SelectionSession(
        UUID playerId,
        UUID worldId,
        SelectionMode mode,
        Optional<LandId> targetLandId,
        Optional<SubLandId> targetSubLandId,
        Optional<SelectionPoint> pointA,
        Optional<SelectionPoint> pointB,
        Set<ChunkKey> selectedChunks,
        Map<ChunkKey, PendingChange> pendingChanges,
        long selectionRevision,
        long baseStructureRevision,
        Instant createdAt,
        Instant lastActivity) {

    public SelectionSession {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(mode, "mode");
        targetLandId = requireOptional(targetLandId, "targetLandId");
        targetSubLandId = requireOptional(targetSubLandId, "targetSubLandId");
        pointA = requireOptional(pointA, "pointA");
        pointB = requireOptional(pointB, "pointB");
        selectedChunks = copySet(selectedChunks, "selectedChunks");
        pendingChanges = copyMap(pendingChanges, "pendingChanges");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastActivity, "lastActivity");
        if (selectionRevision < 0) {
            throw new IllegalArgumentException("selectionRevision must be non-negative");
        }
        if (baseStructureRevision < 0) {
            throw new IllegalArgumentException("baseStructureRevision must be non-negative");
        }
        if (lastActivity.isBefore(createdAt)) {
            throw new IllegalArgumentException("lastActivity must not be before createdAt");
        }
        pointA.ifPresent(point -> requireWorld(point.worldId(), worldId, "pointA"));
        pointB.ifPresent(point -> requireWorld(point.worldId(), worldId, "pointB"));
        selectedChunks.forEach(chunk -> requireWorld(chunk.worldId(), worldId, "selectedChunks"));
        pendingChanges.keySet().forEach(chunk -> requireWorld(chunk.worldId(), worldId, "pendingChanges"));
    }

    /** Create a new session at revision zero with the supplied structure snapshot. */
    public static SelectionSession initial(
            UUID playerId,
            UUID worldId,
            SelectionMode mode,
            Optional<LandId> targetLandId,
            Optional<SubLandId> targetSubLandId,
            Optional<SelectionPoint> pointA,
            Optional<SelectionPoint> pointB,
            long baseStructureRevision,
            Instant now) {
        return new SelectionSession(
                playerId,
                worldId,
                mode,
                targetLandId,
                targetSubLandId,
                pointA,
                pointB,
                Set.of(),
                Map.of(),
                0,
                baseStructureRevision,
                now,
                now);
    }

    /** Return a copy with a new mode; useful when the workflow changes ownership. */
    public SelectionSession withMode(SelectionMode newMode) {
        return new SelectionSession(
                playerId,
                worldId,
                newMode,
                targetLandId,
                targetSubLandId,
                pointA,
                pointB,
                selectedChunks,
                pendingChanges,
                selectionRevision,
                baseStructureRevision,
                createdAt,
                lastActivity);
    }

    SelectionSession withSelection(SelectionUpdate update, long nextRevision, Instant activity) {
        return new SelectionSession(
                playerId,
                worldId,
                mode,
                targetLandId,
                targetSubLandId,
                update.pointA(),
                update.pointB(),
                update.selectedChunks(),
                update.pendingChanges(),
                nextRevision,
                baseStructureRevision,
                createdAt,
                activity);
    }

    private static <T> Optional<T> requireOptional(Optional<T> value, String name) {
        return Objects.requireNonNull(value, name);
    }

    private static <T> Set<T> copySet(Set<T> value, String name) {
        Objects.requireNonNull(value, name);
        LinkedHashSet<T> copy = new LinkedHashSet<>();
        for (T item : value) {
            copy.add(Objects.requireNonNull(item, name + " element"));
        }
        return Collections.unmodifiableSet(copy);
    }

    private static <K, V> Map<K, V> copyMap(Map<K, V> value, String name) {
        Objects.requireNonNull(value, name);
        LinkedHashMap<K, V> copy = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : value.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), name + " key"),
                    Objects.requireNonNull(entry.getValue(), name + " value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void requireWorld(UUID actual, UUID expected, String field) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(field + " must belong to the session world");
        }
    }
}

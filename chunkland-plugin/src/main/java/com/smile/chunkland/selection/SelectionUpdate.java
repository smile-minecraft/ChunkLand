package com.smile.chunkland.selection;

import com.smile.chunkland.api.land.ChunkKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable complete replacement payload for a Selection Session update. */
public record SelectionUpdate(
        Optional<SelectionPoint> pointA,
        Optional<SelectionPoint> pointB,
        Set<ChunkKey> selectedChunks,
        Map<ChunkKey, PendingChange> pendingChanges) {

    public SelectionUpdate {
        pointA = Objects.requireNonNull(pointA, "pointA");
        pointB = Objects.requireNonNull(pointB, "pointB");
        selectedChunks = copySet(selectedChunks, "selectedChunks");
        pendingChanges = copyMap(pendingChanges, "pendingChanges");
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
}

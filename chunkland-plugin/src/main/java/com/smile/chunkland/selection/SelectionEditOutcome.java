package com.smile.chunkland.selection;

import com.smile.chunkland.api.geometry.BoundarySegment;
import com.smile.chunkland.api.geometry.ChunkGeometry;
import com.smile.chunkland.api.land.ChunkKey;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable analysis result and, when accepted, the update for a selection session. */
public record SelectionEditOutcome(
        boolean accepted,
        SelectionEditReason reason,
        SelectionSession expectedSession,
        Optional<SelectionUpdate> update,
        Set<ChunkKey> selectedChunks,
        ChunkGeometry geometry,
        Set<BoundarySegment> boundary,
        Optional<ChunkKey> conflictChunk) {

    public SelectionEditOutcome {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(expectedSession, "expectedSession");
        update = Objects.requireNonNull(update, "update");
        selectedChunks = copySet(selectedChunks, "selectedChunks");
        Objects.requireNonNull(geometry, "geometry");
        boundary = copySet(boundary, "boundary");
        conflictChunk = Objects.requireNonNull(conflictChunk, "conflictChunk");
        if (accepted != (reason == SelectionEditReason.ACCEPTED)) {
            throw new IllegalArgumentException("accepted must agree with reason");
        }
        if (accepted != update.isPresent()) {
            throw new IllegalArgumentException("accepted must agree with update presence");
        }
    }

    /** Whether the candidate remains one orthogonally connected component. */
    public boolean connected() {
        return geometry.isConnected();
    }

    /** Empty cells enclosed by the candidate selection. */
    public Set<ChunkKey> holes() {
        return geometry.holes();
    }

    /** Candidate chunks whose removal would split its connected component. */
    public Set<ChunkKey> bridges() {
        return geometry.bridges();
    }

    /** Number of orthogonally connected candidate components. */
    public int componentCount() {
        return geometry.componentCount();
    }

    private static <T> Set<T> copySet(Set<T> value, String name) {
        Objects.requireNonNull(value, name);
        LinkedHashSet<T> copy = new LinkedHashSet<>();
        for (T item : value) {
            copy.add(Objects.requireNonNull(item, name + " element"));
        }
        return Collections.unmodifiableSet(copy);
    }
}

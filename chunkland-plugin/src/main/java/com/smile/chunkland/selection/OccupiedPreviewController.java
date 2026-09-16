package com.smile.chunkland.selection;

import com.smile.chunkland.api.land.ChunkKey;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Player-scoped lifecycle seam for the occupied-land boundary preview.
 *
 * <p>Kept separate from {@link SelectionVisualizationTaskController} on
 * purpose: the preview must never share the active selection's render loop,
 * and it must never create or mutate a {@link SelectionSession}. A wizard that
 * only previews an existing land therefore leaves the claim tokens untouched,
 * so {@code /land claim} can never treat the preview as a candidate.
 *
 * <p>Display is best-effort: a failing preview never breaks selection data, so
 * callers may invoke {@link #show} and {@link #stop} unconditionally.
 */
public interface OccupiedPreviewController {

    /**
     * Begin (or replace) the player's preview loop for the given immutable
     * chunk set, rendered on the plane at {@code planeY}.
     */
    void show(UUID playerId, Set<ChunkKey> chunks, double planeY);

    /** Stop and forget the player's preview loop. */
    void stop(UUID playerId);

    /** Stop every preview loop (plugin disable). */
    default void stopAll() {
    }

    static OccupiedPreviewController noop() {
        return new OccupiedPreviewController() {
            @Override
            public void show(UUID playerId, Set<ChunkKey> chunks, double planeY) {
                Objects.requireNonNull(playerId, "playerId");
            }

            @Override
            public void stop(UUID playerId) {
                Objects.requireNonNull(playerId, "playerId");
            }
        };
    }
}

package com.smile.chunkland.selection;

import java.util.Objects;
import java.util.UUID;

/**
 * Player-scoped visualization lifecycle seam.
 *
 * <p>The session manager owns when rendering runs: it calls {@link #start} after
 * a session is installed, {@link #refresh} after a selection update, and
 * {@link #stop} on every cleanup path (timeout, cancel, quit, world change,
 * plugin disable, replacement) in the established
 * timeout-cancel → visualization-stop → session-remove → notify order. Display
 * is best-effort — a failing render never breaks selection data — so the
 * default {@code start}/{@code refresh} are dormant and managers may call them
 * unconditionally.
 */
public interface SelectionVisualizationTaskController {
    void stop(UUID playerId);

    /** Begin (or replace) rendering for the given session snapshot. Dormant by default. */
    default void start(SelectionSession session) {
        Objects.requireNonNull(session, "session");
    }

    /** Re-render after a selection update. Dormant by default; falls back to {@link #start}. */
    default void refresh(SelectionSession session) {
        start(session);
    }

    /** Whether a render loop is currently tracked for the player. Dormant controllers report false. */
    default boolean isActive(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return false;
    }

    static SelectionVisualizationTaskController noop() {
        return playerId -> { };
    }
}

package com.smile.chunkland.selection;

import java.util.UUID;

/** Player-scoped visualization cleanup seam. */
@FunctionalInterface
public interface SelectionVisualizationTaskController {
    void stop(UUID playerId);

    static SelectionVisualizationTaskController noop() {
        return playerId -> { };
    }
}

package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.claim.ShrinkOutcome;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.ShrinkCommandHandler;
import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Wiring for {@code /land shrink} and {@code /land unclaim}: both aliases
 * share one handler instance and one saga runner.
 */
class ShrinkAliasWiringTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    @Test
    void shrinkAndUnclaimShareOneHandlerInstance() {
        SelectionSessionManager selections = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                (SelectionClock) () -> NOW,
                Duration.ofMinutes(10),
                ignored -> Optional.of("world"),
                SelectionStructureRevisionLookup.unavailable());
        ShrinkCommandHandler.ShrinkRunner runner = request ->
                CompletableFuture.completedFuture(ShrinkOutcome.rejected("shrink.split"));
        Map<String, LandCommand.Handler> handlers = ChunkLandPlugin.buildLandHandlers(
                selections, null, null,
                SelectionStructureRevisionLookup.unavailable(), null, null,
                null, null, null, null, null, null, null, runner, null, null);
        assertSame(handlers.get("shrink"), handlers.get("unclaim"),
                "shrink and unclaim must share one handler instance");
    }

    @Test
    void unwiredAliasesStayFailClosed() {
        SelectionSessionManager selections = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                (SelectionClock) () -> NOW,
                Duration.ofMinutes(10),
                ignored -> Optional.of("world"),
                SelectionStructureRevisionLookup.unavailable());
        Map<String, LandCommand.Handler> handlers = ChunkLandPlugin.buildLandHandlers(
                selections, null, null,
                SelectionStructureRevisionLookup.unavailable(), null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertTrue(handlers.containsKey("shrink") && handlers.containsKey("unclaim"),
                "both aliases must stay registered while unwired");
        assertSame(handlers.get("shrink"), handlers.get("unclaim"),
                "unwired aliases must share one fail-closed handler");
    }
}

package com.smile.chunkland.selection;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.config.ReloadDiff;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Selection sessions across a reload: a cosmetic-only reload (feedback or
 * the decision-cache budget, which the global-policy flag deliberately
 * excludes because their consumers read the live snapshot) keeps in-flight
 * sessions, while a protection-relevant change still invalidates them so
 * the next confirm is decided under the new generation.
 */
class SelectionReloadRetentionTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    private SelectionSessionManager manager(UUID world) {
        return new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                (SelectionClock) () -> NOW,
                Duration.ofMinutes(10),
                ignored -> Optional.of("world"),
                landId -> OptionalLong.of(0L));
    }

    private UUID startSession(SelectionSessionManager sessions, UUID actor, UUID world) {
        SelectionSession initial = SelectionSession.initial(
                actor, world, SelectionMode.CREATE_LAND,
                Optional.empty(), Optional.empty(),
                Optional.of(new SelectionPoint(world, 0, 64, 0)),
                Optional.of(new SelectionPoint(world, 16, 64, 16)),
                0, NOW);
        sessions.start(initial);
        assertTrue(sessions.sessionFor(actor).isPresent(), "the session must exist before the reload");
        return actor;
    }

    private static ReloadDiff diff(boolean globalPolicyChanged) {
        return ReloadDiff.of(Set.of(), Set.of(), Set.of(), 0L, 1L, globalPolicyChanged);
    }

    @Test
    void cosmeticOnlyReloadKeepsInFlightSessions() {
        UUID world = UUID.randomUUID();
        SelectionSessionManager sessions = manager(world);
        UUID actor = UUID.randomUUID();
        startSession(sessions, actor, world);

        sessions.onConfigReload(diff(false));

        assertTrue(sessions.sessionFor(actor).isPresent(),
                "a feedback/budget-only reload must not cancel in-flight selections");
    }

    @Test
    void protectionRelevantReloadInvalidatesSessions() {
        UUID world = UUID.randomUUID();
        SelectionSessionManager sessions = manager(world);
        UUID actor = UUID.randomUUID();
        startSession(sessions, actor, world);

        sessions.onConfigReload(diff(true));

        assertTrue(sessions.sessionFor(actor).isEmpty(),
                "a protection-relevant reload must force a fresh selection under the new generation");
    }

    @Test
    void worldScopedReloadInvalidatesOnlyAffectedSessions() {
        UUID world = UUID.randomUUID();
        SelectionSessionManager sessions = manager(world);
        UUID actor = UUID.randomUUID();
        startSession(sessions, actor, world);

        ReloadDiff worldDiff = ReloadDiff.of(
                Set.of(), Set.of("other"), Set.of(), 0L, 1L, false);
        sessions.onConfigReload(worldDiff);

        assertTrue(sessions.sessionFor(actor).isPresent(),
                "an unrelated world change must not cancel this selection");

        ReloadDiff affectedDiff = ReloadDiff.of(
                Set.of("world"), Set.of(), Set.of(), 0L, 1L, false);
        sessions.onConfigReload(affectedDiff);

        assertTrue(sessions.sessionFor(actor).isEmpty(),
                "a change to the session world must force a fresh selection");
    }
}

package com.smile.chunkland.subland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Token tests for the SubLand confirmation gate: stale, replay,
 * replacement, cross-player and concurrent double-submit behaviour, plus
 * slot independence from the claim confirmation path.
 */
class SubLandConfirmServiceTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());
    private static final SubLandId SUB = new SubLandId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static final class Fixture {
        final AtomicLong liveStructure = new AtomicLong(3);
        final SelectionSessionManager manager = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                () -> NOW,
                Duration.ofMinutes(10));
        final SelectionStructureRevisionLookup structures =
                landId -> OptionalLong.of(liveStructure.get());
        final SubLandConfirmService confirm = new SubLandConfirmService();

        SelectionSession start(UUID player, SelectionMode mode, long baseRevision) {
            return manager.start(SelectionSession.initial(
                    player, WORLD, mode,
                    Optional.of(LAND), Optional.of(SUB),
                    Optional.of(new SelectionPoint(WORLD, 0, 60, 0)),
                    Optional.of(new SelectionPoint(WORLD, 15, 70, 15)),
                    baseRevision, NOW));
        }
    }

    @Test
    void freshTokenAccepts() {
        Fixture f = new Fixture();
        SelectionSession stamped = f.start(PLAYER, SelectionMode.CREATE_SUBLAND, 3);
        Optional<SubLandConfirmService.Accepted> accepted = f.confirm.accept(
                PLAYER, stamped.sessionGeneration(), stamped.selectionRevision(),
                f.manager, f.structures);
        assertTrue(accepted.isPresent());
        assertEquals(LAND, accepted.get().parentId());
        assertEquals(3L, accepted.get().structureRevision());
    }

    @Test
    void staleRevisionAndGenerationReject() {
        Fixture f = new Fixture();
        SelectionSession stamped = f.start(PLAYER, SelectionMode.CREATE_SUBLAND, 3);
        assertTrue(f.confirm.accept(PLAYER,
                stamped.sessionGeneration(), stamped.selectionRevision() + 1,
                f.manager, f.structures).isEmpty());
        assertTrue(f.confirm.accept(PLAYER,
                stamped.sessionGeneration() + 1, stamped.selectionRevision(),
                f.manager, f.structures).isEmpty());
    }

    @Test
    void staleStructureRejects() {
        Fixture f = new Fixture();
        SelectionSession stamped = f.start(PLAYER, SelectionMode.CREATE_SUBLAND, 3);
        f.liveStructure.set(4);
        assertTrue(f.confirm.accept(PLAYER,
                stamped.sessionGeneration(), stamped.selectionRevision(),
                f.manager, f.structures).isEmpty());
    }

    @Test
    void unresolvableStructureFailsClosed() {
        Fixture f = new Fixture();
        SelectionSession stamped = f.start(PLAYER, SelectionMode.CREATE_SUBLAND, 3);
        assertTrue(f.confirm.accept(PLAYER,
                stamped.sessionGeneration(), stamped.selectionRevision(),
                f.manager, SelectionStructureRevisionLookup.unavailable()).isEmpty());
    }

    @Test
    void replayOnSameSessionRejectsSecondSubmit() {
        Fixture f = new Fixture();
        SelectionSession stamped = f.start(PLAYER, SelectionMode.CREATE_SUBLAND, 3);
        assertTrue(f.confirm.accept(PLAYER,
                stamped.sessionGeneration(), stamped.selectionRevision(),
                f.manager, f.structures).isPresent());
        assertTrue(f.confirm.accept(PLAYER,
                stamped.sessionGeneration(), stamped.selectionRevision(),
                f.manager, f.structures).isEmpty());
    }

    @Test
    void releaseAfterFailureAllowsRetry() {
        Fixture f = new Fixture();
        SelectionSession stamped = f.start(PLAYER, SelectionMode.CREATE_SUBLAND, 3);
        var accepted = f.confirm.accept(PLAYER,
                stamped.sessionGeneration(), stamped.selectionRevision(),
                f.manager, f.structures).orElseThrow();
        f.confirm.releaseIfNotSuccess(PLAYER, accepted);
        assertTrue(f.confirm.accept(PLAYER,
                stamped.sessionGeneration(), stamped.selectionRevision(),
                f.manager, f.structures).isPresent());
    }

    @Test
    void replacementSessionIsNotBlockedByOldMark() {
        Fixture f = new Fixture();
        SelectionSession first = f.start(PLAYER, SelectionMode.CREATE_SUBLAND, 3);
        assertTrue(f.confirm.accept(PLAYER,
                first.sessionGeneration(), first.selectionRevision(),
                f.manager, f.structures).isPresent());
        SelectionSession second = f.start(PLAYER, SelectionMode.CREATE_SUBLAND, 3);
        assertTrue(f.confirm.accept(PLAYER,
                second.sessionGeneration(), second.selectionRevision(),
                f.manager, f.structures).isPresent());
    }

    @Test
    void crossPlayerTokenCannotConfirmAnotherSession() {
        Fixture f = new Fixture();
        SelectionSession mine = f.start(PLAYER, SelectionMode.CREATE_SUBLAND, 3);
        // The other player has no session at all: nothing to confirm.
        assertTrue(f.confirm.accept(OTHER,
                mine.sessionGeneration(), mine.selectionRevision(),
                f.manager, f.structures).isEmpty());
        // The other player has their own session: a foreign token can only ever
        // confirm the caller's own session (here the numbers coincide because
        // generations are per-player), never another player's state.
        SelectionSession theirs = f.start(OTHER, SelectionMode.CREATE_SUBLAND, 3);
        Optional<SubLandConfirmService.Accepted> foreign = f.confirm.accept(OTHER,
                mine.sessionGeneration(), mine.selectionRevision(),
                f.manager, f.structures);
        assertTrue(theirs.sessionGeneration() == mine.sessionGeneration()
                && theirs.selectionRevision() == mine.selectionRevision());
        assertTrue(foreign.isPresent());
        assertEquals(OTHER, foreign.get().session().playerId());
    }

    @Test
    void wrongModeRejects() {
        Fixture f = new Fixture();
        SelectionSession stamped = f.start(PLAYER, SelectionMode.CREATE_LAND, 3);
        assertTrue(f.confirm.accept(PLAYER,
                stamped.sessionGeneration(), stamped.selectionRevision(),
                f.manager, f.structures).isEmpty());
    }

    @Test
    void concurrentDoubleSubmitAdmitsExactlyOne() throws Exception {
        Fixture f = new Fixture();
        SelectionSession stamped = f.start(PLAYER, SelectionMode.CREATE_SUBLAND, 3);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        return false;
                    }
                    return f.confirm.accept(PLAYER,
                            stamped.sessionGeneration(), stamped.selectionRevision(),
                            f.manager, f.structures).isPresent();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            int admitted = 0;
            for (Future<Boolean> future : futures) {
                if (Boolean.TRUE.equals(future.get(10, TimeUnit.SECONDS))) {
                    admitted++;
                }
            }
            assertEquals(1, admitted, "exactly one racing submit may be admitted");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void confirmSlotsAreIndependentBetweenInstancesAndFromClaim() throws Exception {
        // Two SubLand service instances never share a consumed slot.
        Fixture f = new Fixture();
        SelectionSession stamped = f.start(PLAYER, SelectionMode.CREATE_SUBLAND, 3);
        SubLandConfirmService other = new SubLandConfirmService();
        assertTrue(f.confirm.accept(PLAYER,
                stamped.sessionGeneration(), stamped.selectionRevision(),
                f.manager, f.structures).isPresent());
        assertTrue(other.accept(PLAYER,
                stamped.sessionGeneration(), stamped.selectionRevision(),
                f.manager, f.structures).isPresent());
        // The claim path owns a separate guard: neither source tree references the other.
        String sublandConfirm = read(
                "chunkland-plugin/src/main/java/com/smile/chunkland/subland/SubLandConfirmService.java");
        String claimConfirm = read(
                "chunkland-plugin/src/main/java/com/smile/chunkland/command/ConfirmCommandHandler.java");
        assertFalse(sublandConfirm.contains("ConfirmCommandHandler"),
                "subland confirm must not touch the claim consumed slot");
        assertFalse(claimConfirm.contains("SubLandConfirmService"),
                "claim confirm must not touch the subland consumed slot");
    }

    private static String read(String relative) throws Exception {
        Path direct = Paths.get(relative);
        if (Files.exists(direct)) {
            return Files.readString(direct);
        }
        Path fromHere = Paths.get(System.getProperty("user.dir")).resolve(relative);
        if (Files.exists(fromHere)) {
            return Files.readString(fromHere);
        }
        if (relative.startsWith("chunkland-plugin/")) {
            Path stripped = Paths.get(System.getProperty("user.dir"))
                    .resolve(relative.substring("chunkland-plugin/".length()));
            if (Files.exists(stripped)) {
                return Files.readString(stripped);
            }
        }
        throw new IllegalStateException("source not found: " + relative);
    }
}

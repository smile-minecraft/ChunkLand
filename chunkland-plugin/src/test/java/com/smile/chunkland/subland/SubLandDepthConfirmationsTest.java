package com.smile.chunkland.subland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SubLandDepthConfirmationsTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());
    private static final SubLandId SUB = new SubLandId(UUID.randomUUID());
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private static final class MutableClock extends Clock {
        private Instant now = START;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock();
        final SelectionSessionManager selections = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                clock::instant,
                Duration.ofMinutes(10));
        final SubLandDepthConfirmations confirmations =
                new SubLandDepthConfirmations(selections, clock, Duration.ofSeconds(10));

        SelectionSession start(UUID actor) {
            return selections.start(SelectionSession.initial(
                    actor, WORLD, SelectionMode.CREATE_SUBLAND,
                    Optional.of(LAND), Optional.of(SUB),
                    Optional.of(new SelectionPoint(WORLD, 0, 30, 0)),
                    Optional.of(new SelectionPoint(WORLD, 15, 70, 15)),
                    0L, clock.instant()));
        }
    }

    @Test
    void actorBindingAndSingleUseAreEnforced() {
        Fixture fixture = new Fixture();
        SelectionSession session = fixture.start(ACTOR);
        assertTrue(fixture.confirmations.record(ACTOR, session, LAND, 30));

        assertFalse(fixture.confirmations.isDepthExtendConfirmed(
                OTHER, LAND, 50, 30), "another actor cannot consume the confirmation");
        assertTrue(fixture.confirmations.isDepthExtendConfirmed(ACTOR, LAND, 50, 30));
        assertFalse(fixture.confirmations.isDepthExtendConfirmed(ACTOR, LAND, 50, 30),
                "the same confirmation is single-use");
    }

    @Test
    void ttlAndSessionReplacementInvalidateConfirmation() {
        Fixture fixture = new Fixture();
        SelectionSession first = fixture.start(ACTOR);
        assertTrue(fixture.confirmations.record(ACTOR, first, LAND, 30));
        fixture.clock.advance(Duration.ofSeconds(11));
        assertFalse(fixture.confirmations.isDepthExtendConfirmed(ACTOR, LAND, 50, 30));

        SelectionSession replacement = fixture.start(ACTOR);
        assertTrue(fixture.confirmations.record(ACTOR, replacement, LAND, 30));
        SelectionSession newer = fixture.start(ACTOR);
        assertEquals(newer.sessionGeneration(), fixture.selections.sessionFor(ACTOR).orElseThrow().sessionGeneration());
        assertFalse(fixture.confirmations.isDepthExtendConfirmed(ACTOR, LAND, 50, 30),
                "a replaced session cannot consume the old confirmation");
    }

    @Test
    void durableFailureRestoresTheConsumedConfirmation() {
        Fixture fixture = new Fixture();
        SelectionSession session = fixture.start(ACTOR);
        assertTrue(fixture.confirmations.record(ACTOR, session, LAND, 30));
        assertTrue(fixture.confirmations.isDepthExtendConfirmed(ACTOR, LAND, 50, 30));

        fixture.confirmations.onDurableFailure(ACTOR, LAND, 30);

        assertTrue(fixture.confirmations.isDepthExtendConfirmed(ACTOR, LAND, 50, 30));
    }

    @Test
    void concurrentConsumersAdmitExactlyOne() throws Exception {
        Fixture fixture = new Fixture();
        SelectionSession session = fixture.start(ACTOR);
        assertTrue(fixture.confirmations.record(ACTOR, session, LAND, 30));
        int count = 8;
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return fixture.confirmations.isDepthExtendConfirmed(ACTOR, LAND, 50, 30);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            int admitted = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }
            assertEquals(1, admitted);
        } finally {
            pool.shutdownNow();
        }
    }
}

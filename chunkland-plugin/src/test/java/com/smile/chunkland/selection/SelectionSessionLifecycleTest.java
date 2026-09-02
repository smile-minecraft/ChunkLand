package com.smile.chunkland.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.config.ConfigLoader;
import com.smile.chunkland.config.ConfigService;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.config.ReloadDiff;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class SelectionSessionLifecycleTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID OTHER_WORLD = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final LandId LAND = new LandId(UUID.fromString("00000000-0000-0000-0000-000000000020"));
    private static final com.smile.chunkland.api.land.SubLandId SUBLAND =
            new com.smile.chunkland.api.land.SubLandId(
                    UUID.fromString("00000000-0000-0000-0000-000000000021"));
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private enum LifecycleCase {
        PLAYER_QUIT,
        ACTIVE_CANCEL,
        WORLD_CHANGE,
        PLUGIN_DISABLE,
        CONFIG_UNCHANGED,
        CONFIG_CHANGED,
        SAME_WORLD_RESPAWN,
        CROSS_WORLD_RESPAWN,
        WAND_DROP,
        TIMEOUT,
        LAND_DELETE,
        LAND_REVISION_MISMATCH,
        SUBLAND_DELETE
    }

    static Stream<LifecycleCase> lifecycleCases() {
        return Stream.of(LifecycleCase.values());
    }

    @ParameterizedTest
    @MethodSource("lifecycleCases")
    void lifecycleTableCoversEverySelectionSessionCase(LifecycleCase lifecycleCase) {
        Fixture fixture = new Fixture();
        fixture.manager.start(fixture.session(1));

        switch (lifecycleCase) {
            case PLAYER_QUIT -> fixture.manager.onPlayerQuit(PLAYER);
            case ACTIVE_CANCEL -> fixture.manager.cancel(PLAYER);
            case WORLD_CHANGE -> fixture.manager.onWorldChange(PLAYER);
            case PLUGIN_DISABLE -> fixture.manager.disable();
            case CONFIG_UNCHANGED -> fixture.manager.onConfigReload(
                    ReloadDiff.of(Set.of("other"), Set.of(), Set.of(), 0, 1));
            case CONFIG_CHANGED -> fixture.manager.onConfigReload(
                    ReloadDiff.of(Set.of("world"), Set.of(), Set.of(), 0, 1));
            case SAME_WORLD_RESPAWN -> fixture.manager.onRespawn(PLAYER, WORLD);
            case CROSS_WORLD_RESPAWN -> fixture.manager.onRespawn(PLAYER, OTHER_WORLD);
            case WAND_DROP -> { }
            case TIMEOUT -> fixture.scheduler.fireLatest();
            case LAND_DELETE -> fixture.manager.onLandDeleted(LAND);
            case LAND_REVISION_MISMATCH -> fixture.manager.onLandStructureRevisionChanged(LAND, 2);
            case SUBLAND_DELETE -> fixture.manager.onSubLandDeleted(SUBLAND);
        }

        boolean retained = lifecycleCase == LifecycleCase.CONFIG_UNCHANGED
                || lifecycleCase == LifecycleCase.SAME_WORLD_RESPAWN
                || lifecycleCase == LifecycleCase.WAND_DROP;
        if (retained) {
            assertTrue(fixture.manager.sessionFor(PLAYER).isPresent(), lifecycleCase.name());
            assertEquals(0, fixture.visualization.stopCount());
        } else {
            assertTrue(fixture.manager.sessionFor(PLAYER).isEmpty(), lifecycleCase.name());
            assertEquals(1, fixture.visualization.stopCount(), lifecycleCase.name());
            assertEquals(1, fixture.scheduler.cancelCount(), lifecycleCase.name());
            assertEquals(List.of("timeout.cancel", "visual.stop", "notify"), fixture.events, lifecycleCase.name());
        }

        if (lifecycleCase == LifecycleCase.PLUGIN_DISABLE) {
            assertTrue(fixture.manager.isDisabled());
            assertEquals(0, fixture.manager.size());
            fixture.manager.disable();
            assertEquals(1, fixture.visualization.stopCount());
            assertEquals(1, fixture.scheduler.cancelCount());
        }
        if (lifecycleCase == LifecycleCase.CONFIG_CHANGED
                || lifecycleCase == LifecycleCase.LAND_DELETE
                || lifecycleCase == LifecycleCase.LAND_REVISION_MISMATCH
                || lifecycleCase == LifecycleCase.SUBLAND_DELETE) {
            assertEquals(1, fixture.notifier.notifications.size());
        }
    }

    @Test
    void globalPolicyReloadInvalidatesAllSessionsWithoutWorldChanges() {
        Fixture fixture = new Fixture();
        fixture.manager.start(fixture.session(1));

        fixture.manager.onConfigReload(ReloadDiff.of(Set.of(), Set.of(), Set.of(), 0, 1, true));

        assertTrue(fixture.manager.sessionFor(PLAYER).isEmpty());
        assertEquals(List.of("timeout.cancel", "visual.stop", "notify"), fixture.events);
    }

    @Test
    void exactNoOpReloadDiffRetainsSessionEvenWhenEpochAdvances() {
        Fixture fixture = new Fixture();
        fixture.manager.start(fixture.session(1));

        fixture.manager.onConfigReload(ReloadDiff.of(Set.of(), Set.of(), Set.of(), 4, 5, false));

        assertTrue(fixture.manager.sessionFor(PLAYER).isPresent());
        assertTrue(fixture.events.isEmpty());
    }

    @Test
    void globalReloadUsesUpdatedTimeoutForTheNextSession() {
        Fixture fixture = new Fixture();
        AtomicReference<Duration> configuredTimeout = new AtomicReference<>(Duration.ofMinutes(10));
        SelectionSessionManager manager = new SelectionSessionManager(
                fixture.scheduler,
                fixture.visualization,
                fixture.notifier,
                fixture.clock,
                configuredTimeout::get,
                ignored -> Optional.of("world"),
                SelectionStructureRevisionLookup.unavailable());

        manager.start(fixture.session(1));
        assertEquals(Duration.ofMinutes(10), fixture.scheduler.delays.get(0));

        configuredTimeout.set(Duration.ofSeconds(901));
        manager.onConfigReload(ReloadDiff.of(Set.of(), Set.of(), Set.of(), 0, 1, true));
        assertTrue(manager.sessionFor(PLAYER).isEmpty());

        manager.start(fixture.session(1));
        assertEquals(Duration.ofSeconds(901), fixture.scheduler.delays.get(1));
    }

    @Test
    void failedConfigReloadLeavesActiveSessionUntouched() {
        Fixture fixture = new Fixture();
        AtomicReference<String> yaml = new AtomicReference<>(
                "selection:\n  session-timeout-seconds: 300\nworlds: {}\n");
        ConfigService config = new ConfigService(new ConfigLoader() {
            @Override
            public ChunkLandConfig load() {
                return ConfigSchema.parseYamlText(yaml.get());
            }

            @Override
            public String describe() {
                return "selection-lifecycle-test";
            }
        });
        SelectionSessionManager manager = new SelectionSessionManager(
                fixture.scheduler,
                fixture.visualization,
                fixture.notifier,
                fixture.clock,
                () -> config.current().selection().sessionTimeout(),
                ignored -> Optional.of("world"),
                SelectionStructureRevisionLookup.unavailable());
        config.addListener(manager);
        manager.start(fixture.session(1));
        ChunkLandConfig before = config.current();

        yaml.set("selection:\n  session-timeout-seconds: null\nworlds: {}\n");
        ChunkLandConfig after = config.reload();

        assertSame(before, after);
        assertTrue(manager.sessionFor(PLAYER).isPresent());
        assertTrue(fixture.events.isEmpty());
    }

    @Test
    void visualizationStopsWhileRegistryEntryStillExistsAndNotificationIsLast() {
        Fixture fixture = new Fixture();
        fixture.visualization.registryPresentAtStop = () -> fixture.manager.registryContains(PLAYER);
        fixture.notifier.registryPresentAtNotify = () -> fixture.manager.registryContains(PLAYER);

        fixture.manager.start(fixture.session(1));
        fixture.manager.cancel(PLAYER);

        assertTrue(fixture.visualization.registryWasPresentAtStop);
        assertFalse(fixture.notifier.registryWasPresentAtNotify);
    }

    @Test
    void scheduleFailureStopsVisualizationBeforeRemovingRegistryEntry() {
        List<String> events = new ArrayList<>();
        FakeVisualization visualization = new FakeVisualization(events);
        SelectionSessionManager manager = new SelectionSessionManager(
                (playerId, delay, task) -> {
                    events.add("schedule.fail");
                    throw new IllegalStateException("schedule failed");
                },
                visualization,
                new FakeNotifier(events),
                () -> START,
                Duration.ofMinutes(10));
        visualization.registryPresentAtStop = () -> manager.registryContains(PLAYER);

        assertThrows(IllegalStateException.class, () -> manager.start(
                SelectionSession.initial(
                        PLAYER,
                        WORLD,
                        SelectionMode.CREATE_LAND,
                        Optional.of(LAND),
                        Optional.of(SUBLAND),
                        Optional.of(new SelectionPoint(WORLD, 0, 64, 0)),
                        Optional.of(new SelectionPoint(WORLD, 16, 64, 16)),
                        1,
                        START)));

        assertTrue(visualization.registryWasPresentAtStop);
        assertTrue(manager.sessionFor(PLAYER).isEmpty());
        assertEquals(List.of("schedule.fail", "visual.stop"), events);
    }

    @Test
    void sessionIsImmutableAndDefensivelyCopiesCollections() {
        Set<ChunkKey> chunks = new LinkedHashSet<>();
        ChunkKey first = new ChunkKey(WORLD, 1, 2);
        chunks.add(first);
        Map<ChunkKey, PendingChange> changes = new LinkedHashMap<>();
        changes.put(first, PendingChange.ADD);
        SelectionSession session = new SelectionSession(
                PLAYER,
                WORLD,
                SelectionMode.EDIT_SELECTION,
                Optional.of(LAND),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                chunks,
                changes,
                3,
                7,
                START,
                START);

        chunks.clear();
        changes.clear();
        assertEquals(Set.of(first), session.selectedChunks());
        assertEquals(Map.of(first, PendingChange.ADD), session.pendingChanges());
        assertThrows(UnsupportedOperationException.class, () -> session.selectedChunks().clear());
        assertThrows(UnsupportedOperationException.class, () -> session.pendingChanges().clear());
    }

    @Test
    void updateCreatesNewRevisionAndDoesNotMutatePreviousSnapshot() {
        Fixture fixture = new Fixture();
        SelectionSession before = fixture.manager.start(fixture.session(1));
        SelectionUpdate update = new SelectionUpdate(
                before.pointA(),
                before.pointB(),
                Set.of(new ChunkKey(WORLD, 4, 5)),
                Map.of());

        SelectionSession after = fixture.manager.updateSelection(PLAYER, before, update).orElseThrow();

        assertNotSame(before, after);
        assertEquals(1, after.selectionRevision());
        assertEquals(0, before.selectionRevision());
        assertEquals(Set.of(new ChunkKey(WORLD, 4, 5)), after.selectedChunks());
        assertEquals(2, fixture.scheduler.scheduleCount());
        assertEquals(1, fixture.scheduler.cancelCount());
    }

    @Test
    void staleRotationCompletionCannotReplaceNewerTimeoutHandle() throws Exception {
        Fixture fixture = new Fixture();
        SelectionSession initial = fixture.manager.start(fixture.session(1));
        fixture.scheduler.blockNextCancellation();
        AtomicReference<Throwable> updateFailure = new AtomicReference<>();
        CountDownLatch updateFinished = new CountDownLatch(1);

        Thread staleRotation = new Thread(() -> {
            try {
                fixture.manager.updateSelection(PLAYER, initial,
                        new SelectionUpdate(initial.pointA(), initial.pointB(), Set.of(new ChunkKey(WORLD, 4, 5)), Map.of()));
            } catch (Throwable failure) {
                updateFailure.set(failure);
            } finally {
                updateFinished.countDown();
            }
        });
        staleRotation.start();

        assertTrue(fixture.scheduler.awaitBlockedCancellation());
        SelectionSession newerSession = fixture.manager.sessionFor(PLAYER).orElseThrow();
        fixture.manager.updateSelection(PLAYER, newerSession,
                new SelectionUpdate(newerSession.pointA(), newerSession.pointB(), Set.of(new ChunkKey(WORLD, 6, 7)), Map.of()));
        SelectionTimeoutScheduler.Cancellable newerHandle = fixture.scheduler.latestHandle();
        assertEquals(2, fixture.scheduler.activeCount());

        fixture.scheduler.releaseBlockedCancellation();
        assertTrue(updateFinished.await(1, TimeUnit.SECONDS));
        if (updateFailure.get() != null) {
            throw new AssertionError("stale rotation failed", updateFailure.get());
        }

        SelectionTimeoutScheduler.Cancellable staleHandle = fixture.scheduler.latestHandle();
        assertEquals(1, fixture.scheduler.activeCount());
        assertTrue(staleHandle.isCancelled());
        assertFalse(newerHandle.isCancelled());

        fixture.manager.cancel(PLAYER);

        assertEquals(0, fixture.scheduler.activeCount());
        assertTrue(newerHandle.isCancelled());
        assertEquals(1, fixture.visualization.stopCount());
        assertEquals(1, fixture.notifier.notifications.size());
        assertEquals(List.of("timeout.cancel", "timeout.cancel", "timeout.cancel", "visual.stop", "notify"), fixture.events);
    }

    @Test
    void failedOldTimeoutRotationCannotCleanNewerTimeoutOwner() throws Exception {
        Fixture fixture = new Fixture();
        SelectionSession initial = fixture.manager.start(fixture.session(1));
        fixture.scheduler.blockNextScheduleAndFail();
        AtomicReference<Throwable> updateFailure = new AtomicReference<>();
        CountDownLatch updateFinished = new CountDownLatch(1);

        Thread staleRotation = new Thread(() -> {
            try {
                fixture.manager.updateSelection(PLAYER, initial,
                        new SelectionUpdate(initial.pointA(), initial.pointB(), Set.of(new ChunkKey(WORLD, 4, 5)), Map.of()));
            } catch (Throwable failure) {
                updateFailure.set(failure);
            } finally {
                updateFinished.countDown();
            }
        });
        staleRotation.start();

        assertTrue(fixture.scheduler.awaitBlockedSchedule());
        SelectionSession newerSession = fixture.manager.sessionFor(PLAYER).orElseThrow();
        SelectionSession current = fixture.manager.updateSelection(PLAYER, newerSession,
                new SelectionUpdate(newerSession.pointA(), newerSession.pointB(), Set.of(new ChunkKey(WORLD, 6, 7)), Map.of()))
                .orElseThrow();
        SelectionTimeoutScheduler.Cancellable newerHandle = fixture.scheduler.latestHandle();
        assertSame(current, fixture.manager.sessionFor(PLAYER).orElseThrow());
        assertFalse(newerHandle.isCancelled());
        assertEquals(0, fixture.visualization.stopCount());
        assertTrue(fixture.notifier.notifications.isEmpty());

        fixture.scheduler.releaseBlockedSchedule();
        assertTrue(updateFinished.await(1, TimeUnit.SECONDS));
        staleRotation.join(1_000);
        assertTrue(updateFailure.get() instanceof IllegalStateException);
        assertSame(current, fixture.manager.sessionFor(PLAYER).orElseThrow());
        assertFalse(newerHandle.isCancelled());
        assertEquals(0, fixture.visualization.stopCount());
        assertTrue(fixture.notifier.notifications.isEmpty());
        assertEquals(List.of("timeout.cancel"), fixture.events);

        fixture.manager.cancel(PLAYER);
        assertTrue(newerHandle.isCancelled());
        assertTrue(fixture.manager.sessionFor(PLAYER).isEmpty());
        assertEquals(List.of("timeout.cancel", "timeout.cancel", "visual.stop", "notify"), fixture.events);
    }

    @Test
    void failedInitialScheduleCannotCleanReplacementAndStillCleansNormalFailure() throws Exception {
        Fixture fixture = new Fixture();
        fixture.scheduler.blockNextScheduleAndFail();
        SelectionSession initial = fixture.session(1);
        AtomicReference<Throwable> initialFailure = new AtomicReference<>();
        CountDownLatch initialFinished = new CountDownLatch(1);
        Thread initialStart = new Thread(() -> {
            try {
                fixture.manager.start(initial);
            } catch (Throwable failure) {
                initialFailure.set(failure);
            } finally {
                initialFinished.countDown();
            }
        });
        initialStart.start();
        assertTrue(fixture.scheduler.awaitBlockedSchedule());

        SelectionSession replacement = fixture.session(9);
        AtomicReference<Throwable> replacementFailure = new AtomicReference<>();
        CountDownLatch replacementFinished = new CountDownLatch(1);
        Thread replacementStart = new Thread(() -> {
            try {
                fixture.manager.start(replacement);
            } catch (Throwable failure) {
                replacementFailure.set(failure);
            } finally {
                replacementFinished.countDown();
            }
        });
        replacementStart.start();
        assertTrue(replacementFinished.await(1, TimeUnit.SECONDS));
        fixture.scheduler.releaseBlockedSchedule();
        assertTrue(initialFinished.await(1, TimeUnit.SECONDS));
        initialStart.join(1_000);
        replacementStart.join(1_000);

        assertTrue(initialFailure.get() instanceof IllegalStateException);
        assertTrue(replacementFailure.get() == null);
        assertSame(replacement, fixture.manager.sessionFor(PLAYER).orElseThrow());
        SelectionTimeoutScheduler.Cancellable replacementHandle = fixture.scheduler.latestHandle();
        assertFalse(replacementHandle.isCancelled());
        assertEquals(1, fixture.visualization.stopCount());
        assertEquals(1, fixture.notifier.notifications.size());
        assertEquals(List.of("visual.stop", "notify"), fixture.events);

        fixture.manager.cancel(PLAYER);
        assertTrue(replacementHandle.isCancelled());
        assertTrue(fixture.manager.sessionFor(PLAYER).isEmpty());
        assertEquals(List.of("visual.stop", "notify", "timeout.cancel", "visual.stop", "notify"), fixture.events);

        Fixture normalFailure = new Fixture();
        normalFailure.scheduler.throwOnSchedule = true;
        assertThrows(IllegalStateException.class, () -> normalFailure.manager.start(normalFailure.session(1)));
        assertTrue(normalFailure.manager.sessionFor(PLAYER).isEmpty());
        assertEquals(1, normalFailure.visualization.stopCount());
        assertTrue(normalFailure.notifier.notifications.isEmpty());
        assertEquals(List.of("visual.stop"), normalFailure.events);
    }

    @Test
    void failedInitialScheduleCannotCleanNewerTimeoutRotation() throws Exception {
        Fixture fixture = new Fixture();
        fixture.scheduler.blockNextScheduleAndFail();
        SelectionSession initial = fixture.session(1);
        AtomicReference<Throwable> initialFailure = new AtomicReference<>();
        CountDownLatch initialFinished = new CountDownLatch(1);
        Thread initialStart = new Thread(() -> {
            try {
                fixture.manager.start(initial);
            } catch (Throwable failure) {
                initialFailure.set(failure);
            } finally {
                initialFinished.countDown();
            }
        });
        initialStart.start();
        assertTrue(fixture.scheduler.awaitBlockedSchedule());

        SelectionSession current = fixture.manager.updateSelection(PLAYER, initial,
                new SelectionUpdate(initial.pointA(), initial.pointB(), Set.of(new ChunkKey(WORLD, 8, 9)), Map.of()))
                .orElseThrow();
        SelectionTimeoutScheduler.Cancellable newerHandle = fixture.scheduler.latestHandle();
        assertSame(current, fixture.manager.sessionFor(PLAYER).orElseThrow());
        assertFalse(newerHandle.isCancelled());

        fixture.scheduler.releaseBlockedSchedule();
        assertTrue(initialFinished.await(1, TimeUnit.SECONDS));
        initialStart.join(1_000);

        assertTrue(initialFailure.get() instanceof IllegalStateException);
        assertSame(current, fixture.manager.sessionFor(PLAYER).orElseThrow());
        assertFalse(newerHandle.isCancelled());
        assertEquals(0, fixture.visualization.stopCount());
        assertTrue(fixture.notifier.notifications.isEmpty());
        assertEquals(List.of(), fixture.events);

        fixture.manager.cancel(PLAYER);
        assertTrue(newerHandle.isCancelled());
        assertTrue(fixture.manager.sessionFor(PLAYER).isEmpty());
        assertEquals(List.of("timeout.cancel", "visual.stop", "notify"), fixture.events);
    }

    @Test
    void staleExpectedRevisionCannotOverwriteNewerSelection() {
        Fixture fixture = new Fixture();
        SelectionSession before = fixture.manager.start(fixture.session(1));
        SelectionUpdate update = new SelectionUpdate(before.pointA(), before.pointB(), Set.of(), Map.of());
        SelectionSession current = fixture.manager.updateSelection(PLAYER, before, update).orElseThrow();

        assertTrue(fixture.manager.updateSelection(PLAYER, before, update).isEmpty());
        assertEquals(1, current.selectionRevision());
        assertEquals(0, fixture.visualization.stopCount());
    }

    @Test
    void replacementStopsOnlyTheReplacedSessionAndOldTimeoutCannotClearNewOne() {
        Fixture fixture = new Fixture();
        fixture.manager.start(fixture.session(1));
        SelectionTimeoutScheduler.Cancellable oldHandle = fixture.scheduler.latestHandle();
        fixture.manager.start(fixture.session(9));

        assertTrue(oldHandle.isCancelled());
        assertEquals(1, fixture.visualization.stopCount());
        fixture.scheduler.fire(oldHandle);
        assertTrue(fixture.manager.sessionFor(PLAYER).isPresent());
        assertEquals(9, fixture.manager.sessionFor(PLAYER).orElseThrow().baseStructureRevision());
    }

    @Test
    void revisionZeroActionFromReplacedSessionCannotUpdateReplacement() {
        Fixture fixture = new Fixture();
        SelectionSession oldSession = fixture.manager.start(fixture.session(1));
        SelectionUpdate update = new SelectionUpdate(
                oldSession.pointA(), oldSession.pointB(), Set.of(new ChunkKey(WORLD, 8, 9)), Map.of());

        fixture.manager.start(fixture.session(1));

        assertTrue(fixture.manager.updateSelection(PLAYER, oldSession, update).isEmpty());
        assertEquals(1, fixture.manager.sessionFor(PLAYER).orElseThrow().baseStructureRevision());
        assertTrue(fixture.manager.sessionFor(PLAYER).orElseThrow().selectedChunks().isEmpty());
    }

    @Test
    void visualizationCallbackCannotReenterAndResurrectDuringCleanup() throws Exception {
        Fixture fixture = new Fixture();
        SelectionSession initial = fixture.session(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        fixture.visualization.onStop = () -> {
            try {
                fixture.manager.updateSelection(PLAYER, initial,
                        new SelectionUpdate(Optional.empty(), Optional.empty(), Set.of(), Map.of()));
                try {
                    fixture.manager.start(fixture.session(9));
                } catch (RuntimeException ignored) {
                    // Cleanup must reject replacement while the old entry is closing.
                }
                Thread probe = new Thread(() -> {
                    fixture.manager.registryContains(PLAYER);
                    callbackFinished.countDown();
                });
                probe.start();
                assertTrue(callbackFinished.await(1, TimeUnit.SECONDS));
                probe.join(1_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        };

        fixture.manager.start(initial);
        fixture.manager.cancel(PLAYER);

        assertTrue(fixture.manager.sessionFor(PLAYER).isEmpty());
        assertEquals(1, fixture.scheduler.scheduleCount());
        assertEquals(1, fixture.scheduler.cancelCount());
        assertEquals(1, fixture.visualization.stopCount());
        assertEquals(1, fixture.notifier.notifications.size());
    }

    @Test
    void callbackFailuresStillLeaveEveryRuntimeResourceDetached() {
        Fixture cancelFailure = new Fixture();
        cancelFailure.scheduler.throwOnCancel = true;
        cancelFailure.manager.start(cancelFailure.session(1));
        cancelFailure.manager.cancel(PLAYER);
        assertCleanupResourcesDetached(cancelFailure);

        Fixture visualFailure = new Fixture();
        visualFailure.visualization.throwOnStop = true;
        visualFailure.manager.start(visualFailure.session(1));
        visualFailure.manager.cancel(PLAYER);
        assertCleanupResourcesDetached(visualFailure);

        Fixture notifyFailure = new Fixture();
        notifyFailure.notifier.throwOnNotify = true;
        notifyFailure.manager.start(notifyFailure.session(1));
        notifyFailure.manager.cancel(PLAYER);
        assertCleanupResourcesDetached(notifyFailure);
    }

    private static void assertCleanupResourcesDetached(Fixture fixture) {
        assertTrue(fixture.manager.sessionFor(PLAYER).isEmpty());
        assertEquals(0, fixture.manager.size());
        assertEquals(0, fixture.scheduler.activeCount());
        assertEquals(1, fixture.visualization.stopCount());
        assertEquals(List.of("timeout.cancel", "visual.stop", "notify"), fixture.events);
    }

    @Test
    void unavailableThrowingAndInvalidStructureLookupsFailClosed() {
        for (SelectionStructureRevisionLookup lookup : List.<SelectionStructureRevisionLookup>of(
                ignored -> OptionalLong.empty(),
                ignored -> { throw new IllegalStateException("lookup failed"); },
                ignored -> OptionalLong.of(-1))) {
            Fixture fixture = new Fixture(lookup);
            SelectionSession session = fixture.manager.start(fixture.session(1));

            assertTrue(fixture.manager.updateSelection(PLAYER, session,
                    new SelectionUpdate(session.pointA(), session.pointB(), Set.of(), Map.of())).isEmpty());
            assertTrue(fixture.manager.sessionFor(PLAYER).isEmpty());
            assertEquals(SelectionEndReason.LAND_STRUCTURE_CHANGED,
                    fixture.notifier.notifications.get(0).reason());
        }
    }

    @Test
    void lookupUnavailableDoesNotBlockSelectionWithoutTargetLand() {
        Fixture fixture = new Fixture(ignored -> OptionalLong.empty());
        SelectionSession session = SelectionSession.initial(
                PLAYER, WORLD, SelectionMode.CREATE_LAND, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), 0, START);

        fixture.manager.start(session);

        assertTrue(fixture.manager.updateSelection(PLAYER, session,
                new SelectionUpdate(Optional.empty(), Optional.empty(), Set.of(), Map.of())).isPresent());
        assertTrue(fixture.manager.sessionFor(PLAYER).isPresent());
    }

    @Test
    void revisionMismatchInvalidatesBeforeUpdateAndRequiresReload() {
        Fixture fixture = new Fixture();
        fixture.structureRevision.set(2);
        SelectionSession before = fixture.manager.start(fixture.session(1));

        assertTrue(fixture.manager.updateSelection(
                PLAYER,
                before,
                new SelectionUpdate(before.pointA(), before.pointB(), Set.of(), Map.of())).isEmpty());
        assertTrue(fixture.manager.sessionFor(PLAYER).isEmpty());
        assertEquals(SelectionEndReason.LAND_STRUCTURE_CHANGED,
                fixture.notifier.notifications.get(0).reason());
        assertTrue(fixture.notifier.notifications.get(0).requiresReload());
    }

    @Test
    void clearAndInvalidateAreIdempotentAndQuitWinsOverTimeout() {
        Fixture fixture = new Fixture();
        fixture.manager.start(fixture.session(1));
        fixture.manager.onPlayerQuit(PLAYER);
        fixture.manager.onLandDeleted(LAND);
        fixture.scheduler.fireLatest();
        fixture.manager.cancel(PLAYER);

        assertEquals(1, fixture.visualization.stopCount());
        assertEquals(1, fixture.scheduler.cancelCount());
        assertEquals(1, fixture.notifier.notifications.size());
    }

    @Test
    void disableStopsTimeoutsAndSessionsBeforeRejectingNewSession() {
        Fixture fixture = new Fixture();
        fixture.manager.start(fixture.session(1));
        fixture.manager.disable();

        assertThrows(IllegalStateException.class, () -> fixture.manager.start(fixture.session(2)));
        assertTrue(fixture.manager.sessionFor(PLAYER).isEmpty());
        assertEquals(0, fixture.scheduler.activeCount());
        assertEquals(1, fixture.visualization.stopCount());
    }

    @Test
    void timeoutUsesLastActivityAndOnlyCurrentHandleCanExpire() {
        Fixture fixture = new Fixture();
        fixture.manager.start(fixture.session(1));
        SelectionSession current = fixture.manager.sessionFor(PLAYER).orElseThrow();
        fixture.clock.advance(Duration.ofMinutes(3));
        fixture.manager.updateSelection(PLAYER, current,
                new SelectionUpdate(current.pointA(), current.pointB(), Set.of(), Map.of())).orElseThrow();
        SelectionTimeoutScheduler.Cancellable old = fixture.scheduler.handles.get(0);
        fixture.scheduler.fire(old);
        assertTrue(fixture.manager.sessionFor(PLAYER).isPresent());
        fixture.scheduler.fireLatest();
        assertTrue(fixture.manager.sessionFor(PLAYER).isEmpty());
    }

    @Test
    void failedConfigReloadDoesNotReachInvalidatorOrClearSession() {
        Fixture fixture = new Fixture();
        ConfigLoader valid = new ConfigLoader() {
            @Override
            public ChunkLandConfig load() {
                return ChunkLandConfig.defaults();
            }

            @Override
            public String describe() {
                return "test-valid";
            }
        };
        ConfigService config = new ConfigService(valid);
        config.addListener(fixture.manager);
        fixture.manager.start(fixture.session(1));
        ChunkLandConfig before = config.current();
        config.swapLoader(new ConfigLoader() {
            @Override
            public ChunkLandConfig load() throws IOException {
                throw new IOException("test failure");
            }

            @Override
            public String describe() {
                return "test-failing";
            }
        });

        assertSame(before, config.reload());
        assertTrue(fixture.manager.sessionFor(PLAYER).isPresent());
        assertTrue(fixture.notifier.notifications.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(SelectionMode.class)
    void everyModeCanBeStoredWithoutLiveBukkitObjects(SelectionMode mode) {
        Fixture fixture = new Fixture();
        SelectionSession session = fixture.session(1).withMode(mode);

        fixture.manager.start(session);

        assertSame(session, fixture.manager.sessionFor(PLAYER).orElseThrow());
        assertEquals(WORLD, fixture.manager.sessionFor(PLAYER).orElseThrow().worldId());
    }

    private static final class Fixture {
        final List<String> events = new ArrayList<>();
        final FakeClock clock = new FakeClock();
        final FakeScheduler scheduler = new FakeScheduler(events);
        final FakeVisualization visualization = new FakeVisualization(events);
        final FakeNotifier notifier = new FakeNotifier(events);
        final AtomicLong structureRevision = new AtomicLong(1);
        final SelectionSessionManager manager;

        Fixture() {
            this((SelectionStructureRevisionLookup) null);
        }

        Fixture(SelectionStructureRevisionLookup structureLookup) {
            manager = new SelectionSessionManager(
                    scheduler,
                    visualization,
                    notifier,
                    clock,
                    Duration.ofMinutes(10),
                    ignored -> Optional.of("world"),
                    structureLookup == null ? land -> OptionalLong.of(structureRevision.get()) : structureLookup);
        }

        SelectionSession session(long baseRevision) {
            return SelectionSession.initial(
                    PLAYER,
                    WORLD,
                    SelectionMode.CREATE_LAND,
                    Optional.of(LAND),
                    Optional.of(SUBLAND),
                    Optional.of(new SelectionPoint(WORLD, 0, 64, 0)),
                    Optional.of(new SelectionPoint(WORLD, 16, 64, 16)),
                    baseRevision,
                    clock.now());
        }
    }

    private static final class FakeClock implements SelectionClock {
        private Instant now = START;

        @Override
        public Instant now() {
            return now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }

    private static final class FakeScheduler implements SelectionTimeoutScheduler {
        private final List<String> events;
        final List<FakeHandle> handles = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Duration> delays = new java.util.concurrent.CopyOnWriteArrayList<>();
        boolean throwOnCancel;
        volatile boolean throwOnSchedule;
        private boolean blockNextCancellation;
        private CountDownLatch blockedCancellationEntered;
        private CountDownLatch releaseBlockedCancellation;
        private final AtomicLong scheduleAttempts = new AtomicLong();
        private volatile boolean blockNextScheduleFailure;
        private CountDownLatch blockedScheduleEntered;
        private CountDownLatch releaseBlockedSchedule;

        FakeScheduler(List<String> events) {
            this.events = events;
        }

        @Override
        public Cancellable schedule(UUID playerId, Duration delay, Runnable task) {
            if (scheduleAttempts.incrementAndGet() > 0 && blockNextScheduleFailure) {
                blockNextScheduleFailure = false;
                blockedScheduleEntered.countDown();
                try {
                    releaseBlockedSchedule.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
                throw new IllegalStateException("schedule failed");
            }
            if (throwOnSchedule) {
                throw new IllegalStateException("schedule failed");
            }
            delays.add(delay);
            FakeHandle handle = new FakeHandle(task, events, () -> throwOnCancel, this::awaitBlockedCancellationIfNeeded);
            handles.add(handle);
            return handle;
        }

        void blockNextScheduleAndFail() {
            blockedScheduleEntered = new CountDownLatch(1);
            releaseBlockedSchedule = new CountDownLatch(1);
            blockNextScheduleFailure = true;
        }

        boolean awaitBlockedSchedule() throws InterruptedException {
            return blockedScheduleEntered.await(1, TimeUnit.SECONDS);
        }

        void releaseBlockedSchedule() {
            releaseBlockedSchedule.countDown();
        }

        void blockNextCancellation() {
            blockedCancellationEntered = new CountDownLatch(1);
            releaseBlockedCancellation = new CountDownLatch(1);
            blockNextCancellation = true;
        }

        boolean awaitBlockedCancellation() throws InterruptedException {
            return blockedCancellationEntered.await(1, TimeUnit.SECONDS);
        }

        void releaseBlockedCancellation() {
            releaseBlockedCancellation.countDown();
        }

        private void awaitBlockedCancellationIfNeeded() {
            if (!blockNextCancellation) {
                return;
            }
            blockNextCancellation = false;
            blockedCancellationEntered.countDown();
            try {
                releaseBlockedCancellation.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }

        void fireLatest() {
            fire(handles.get(handles.size() - 1));
        }

        void fire(Cancellable handle) {
            ((FakeHandle) handle).fire();
        }

        FakeHandle latestHandle() {
            return handles.get(handles.size() - 1);
        }

        int scheduleCount() {
            return handles.size();
        }

        int cancelCount() {
            return (int) handles.stream().filter(FakeHandle::isCancelled).count();
        }

        int activeCount() {
            return (int) handles.stream().filter(handle -> !handle.isCancelled()).count();
        }
    }

    private static final class FakeHandle implements SelectionTimeoutScheduler.Cancellable {
        private final Runnable task;
        private final List<String> events;
        private final BooleanSupplier throwOnCancel;
        private final Runnable beforeCancel;
        private boolean cancelled;

        FakeHandle(Runnable task, List<String> events, BooleanSupplier throwOnCancel, Runnable beforeCancel) {
            this.task = task;
            this.events = events;
            this.throwOnCancel = throwOnCancel;
            this.beforeCancel = beforeCancel;
        }

        @Override
        public void cancel() {
            if (!cancelled) {
                beforeCancel.run();
                cancelled = true;
                events.add("timeout.cancel");
                if (throwOnCancel.getAsBoolean()) {
                    throw new IllegalStateException("cancel failed");
                }
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        void fire() {
            task.run();
        }
    }

    private static final class FakeVisualization implements SelectionVisualizationTaskController {
        private final List<String> events;
        private final List<UUID> stopped = new ArrayList<>();
        BooleanSupplier registryPresentAtStop = () -> true;
        Runnable onStop = () -> { };
        boolean throwOnStop;
        boolean registryWasPresentAtStop;

        FakeVisualization(List<String> events) {
            this.events = events;
        }

        @Override
        public void stop(UUID playerId) {
            registryWasPresentAtStop = registryPresentAtStop.getAsBoolean();
            stopped.add(playerId);
            events.add("visual.stop");
            onStop.run();
            if (throwOnStop) {
                throw new IllegalStateException("stop failed");
            }
        }

        int stopCount() {
            return stopped.size();
        }
    }

    private static final class FakeNotifier implements SelectionNotifier {
        private final List<String> events;
        final List<SelectionNotification> notifications = new ArrayList<>();
        BooleanSupplier registryPresentAtNotify = () -> true;
        boolean registryWasPresentAtNotify;
        boolean throwOnNotify;

        FakeNotifier(List<String> events) {
            this.events = events;
        }

        @Override
        public void notify(SelectionNotification notification) {
            registryWasPresentAtNotify = registryPresentAtNotify.getAsBoolean();
            notifications.add(notification);
            events.add("notify");
            if (throwOnNotify) {
                throw new IllegalStateException("notify failed");
            }
        }
    }
}

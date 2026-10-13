package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.command.RosterChangeOperation.BanGuard;
import com.smile.chunkland.command.RosterChangeOperation.Gate;
import com.smile.chunkland.command.RosterChangeOperation.Lands;
import com.smile.chunkland.command.RosterChangeOperation.Mutation;
import com.smile.chunkland.command.RosterChangeOperation.Outcome;
import com.smile.chunkland.command.RosterChangeOperation.Presence;
import com.smile.chunkland.command.RosterChangeOperation.Result;
import com.smile.chunkland.command.RosterChangeOperation.Slot;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * The shared roster-change operation resolves UUID text, online names and
 * known offline names to the same player without blocking the caller, denies
 * everything else without writing, and re-validates the fixed land and the
 * viewer on the viewer thread before the single allowed write.
 */
class RosterChangeOperationTest {

    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());

    private static Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "hasPermission" -> true;
                    case "getName" -> "Viewer";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Player-proxy";
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
    }

    /** Executor that captures tasks so tests drive async completion by hand. */
    private static final class RecordingExecutor implements Executor {
        final List<Runnable> pending = new CopyOnWriteArrayList<>();

        @Override
        public void execute(Runnable task) {
            pending.add(task);
        }

        void runAll() {
            List<Runnable> due = new ArrayList<>(pending);
            pending.clear();
            for (Runnable task : due) {
                task.run();
            }
        }
    }

    /** Scheduler that captures player-thread tasks so tests drive them by hand. */
    private static final class RecordingScheduler implements PlayerScheduler {
        final List<Runnable> pending = new CopyOnWriteArrayList<>();

        @Override
        public void runForPlayer(Player player, Runnable task) {
            pending.add(task);
        }

        void runAll() {
            List<Runnable> due = new ArrayList<>(pending);
            pending.clear();
            for (Runnable task : due) {
                task.run();
            }
        }
    }

    /**
     * Scheduler that models the Folia discard window: a task accepted by the
     * framework but discarded before running fires only
     * {@code onDiscarded}, never the task itself.
     */
    private static final class DiscardingScheduler implements DiscardingPlayerScheduler {
        final List<Runnable> pending = new CopyOnWriteArrayList<>();
        volatile boolean discardNext;

        @Override
        public void runForPlayer(Player player, Runnable task) {
            runForPlayer(player, task, null);
        }

        @Override
        public void runForPlayer(Player player, Runnable task, Runnable onDiscarded) {
            if (discardNext) {
                discardNext = false;
                if (onDiscarded != null) {
                    onDiscarded.run();
                }
                return;
            }
            pending.add(task);
        }

        void runAll() {
            List<Runnable> due = new ArrayList<>(pending);
            pending.clear();
            for (Runnable task : due) {
                task.run();
            }
        }
    }

    private static final class FakeMutation implements Mutation {
        final CopyOnWriteArrayList<UUID> targets = new CopyOnWriteArrayList<>();
        volatile boolean fail;
        volatile boolean nullStage;

        @Override
        public CompletionStage<Void> apply(UUID actor, LandId landId, UUID target) {
            targets.add(target);
            if (fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("injected"));
            }
            if (nullStage) {
                return null;
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class Fixture {
        final RecordingExecutor executor = new RecordingExecutor();
        final AtomicInteger offlineCalls = new AtomicInteger(0);
        final AtomicReference<Optional<UUID>> offlineAnswer =
                new AtomicReference<>(Optional.of(TARGET));
        volatile boolean offlineThrow;
        final Map<String, UUID> online = new java.util.HashMap<>(Map.of("OnlineExact", TARGET));
        PlayerScheduler scheduler = PlayerScheduler.direct();
        Gate gate = (viewer, land, slot) -> true;
        Presence presence = viewer -> true;
        Lands lands = land -> true;
        AtomicBoolean alive = new AtomicBoolean(true);
        BanGuard banGuard = (land, target) -> true;
        final java.util.EnumMap<Slot, FakeMutation> mutations =
                new java.util.EnumMap<>(Slot.class);
        final List<Result> reports = new CopyOnWriteArrayList<>();
        final AtomicInteger terminals = new AtomicInteger(0);

        Fixture() {
            for (Slot slot : Slot.values()) {
                mutations.put(slot, new FakeMutation());
            }
        }

        OfflinePlayerResolver resolver() {
            Function<String, Optional<UUID>> onlineIds = name -> Optional.ofNullable(online.get(name));
            OfflinePlayerResolver.BlockingLookup offline = name -> {
                offlineCalls.incrementAndGet();
                if (offlineThrow) {
                    throw new IllegalStateException("network down");
                }
                return offlineAnswer.get();
            };
            return new OfflinePlayerResolver(onlineIds, offline, executor);
        }

        RosterChangeOperation operation() {
            return new RosterChangeOperation(
                    name -> Optional.ofNullable(online.get(name)),
                    resolver(), scheduler, gate, presence, lands, alive::get, banGuard,
                    mutations::get);
        }

        void submit(Slot slot, String raw) {
            operation().submit(player(VIEWER), VIEWER, LAND, slot, raw,
                    reports::add, terminals::incrementAndGet);
        }

        int writes(Slot slot) {
            return mutations.get(slot).targets.size();
        }

        int writes() {
            int total = 0;
            for (FakeMutation mutation : mutations.values()) {
                total += mutation.targets.size();
            }
            return total;
        }
    }

    @Test
    void offlineNameResolvesAndWritesAfterAsyncCompletion() {
        Fixture fx = new Fixture();
        fx.online.clear();
        fx.submit(Slot.TRUST, "OfflineSteve");
        assertTrue(fx.reports.isEmpty(), "submit must return before async resolution completes");
        assertEquals(0, fx.writes(), "no write before the offline lookup runs");
        assertEquals(0, fx.offlineCalls.get(), "blocking lookup must not run on the caller");
        assertEquals(1, fx.executor.pending.size(), "exactly one async lookup must be queued");

        fx.executor.runAll();

        assertEquals(1, fx.writes(Slot.TRUST), "resolved trust must write exactly once");
        assertEquals(List.of(TARGET), List.copyOf(fx.mutations.get(Slot.TRUST).targets));
        assertEquals(1, fx.reports.size());
        Result result = fx.reports.get(0);
        assertEquals(Outcome.SUCCESS, result.outcome());
        assertEquals(TARGET, result.target());
        assertEquals(Slot.TRUST, result.slot());
        assertEquals(1, fx.terminals.get(), "terminal signal must fire exactly once");
    }

    @Test
    void uuidTextAndOnlineNameResolveToTheSamePlayer() {
        for (String raw : List.of(TARGET.toString(), "OnlineExact")) {
            Fixture fx = new Fixture();
            fx.submit(Slot.BAN, raw);
            assertEquals(1, fx.writes(Slot.BAN), raw + " must write");
            assertEquals(List.of(TARGET), List.copyOf(fx.mutations.get(Slot.BAN).targets));
            assertEquals(Outcome.SUCCESS, fx.reports.get(0).outcome());
            assertEquals(TARGET, fx.reports.get(0).target());
        }
    }

    @Test
    void paddedNameResolves() {
        Fixture fx = new Fixture();
        fx.online.clear();
        fx.submit(Slot.TRUST, "  OfflineSteve  ");
        fx.executor.runAll();
        assertEquals(1, fx.writes(Slot.TRUST), "surrounding whitespace must be stripped");
        assertEquals(Outcome.SUCCESS, fx.reports.get(0).outcome());
    }

    @Test
    void unknownNameIsDeniedWithoutWritingOrInventingAnId() {
        Fixture fx = new Fixture();
        fx.online.clear();
        fx.offlineAnswer.set(Optional.empty());
        fx.submit(Slot.TRUST, "NobodyEverPlayed");
        fx.executor.runAll();
        assertEquals(0, fx.writes(), "unknown names must never write");
        assertEquals(1, fx.reports.size());
        assertEquals(Outcome.UNKNOWN_TARGET, fx.reports.get(0).outcome());
        assertEquals("trust.unknown_target", fx.reports.get(0).reason());
        assertEquals(null, fx.reports.get(0).target(), "no id may be invented for unknown names");
        assertEquals(1, fx.terminals.get());
    }

    @Test
    void lookupFailureIsDeniedWithoutWriting() {
        Fixture fx = new Fixture();
        fx.online.clear();
        fx.offlineThrow = true;
        fx.submit(Slot.BAN, "OfflineSteve");
        fx.executor.runAll();
        assertEquals(0, fx.writes());
        assertEquals(1, fx.reports.size());
        assertEquals(Outcome.UNKNOWN_TARGET, fx.reports.get(0).outcome());
    }

    @Test
    void stalledLookupTimesOutFailClosedWithoutWriting() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService realExecutor =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread thread = new Thread(r, "roster-change-operation-test");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            OfflinePlayerResolver stalled = new OfflinePlayerResolver(
                    name -> Optional.empty(),
                    name -> {
                        try {
                            release.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return Optional.of(TARGET);
                    },
                    realExecutor);
            RosterChangeOperation operation = new RosterChangeOperation(
                    name -> Optional.empty(), stalled, PlayerScheduler.direct(),
                    (viewer, land, slot) -> true, viewer -> true, land -> true,
                    () -> true, (land, target) -> true,
                    slot -> new FakeMutation(), Duration.ofMillis(100));
            List<Result> reports = new CopyOnWriteArrayList<>();
            AtomicInteger terminals = new AtomicInteger(0);
            operation.submit(player(VIEWER), VIEWER, LAND, Slot.TRUST, "SlowSteve",
                    reports::add, terminals::incrementAndGet);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (reports.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, reports.size(), "a stalled lookup must time out instead of hanging");
            assertEquals(Outcome.UNKNOWN_TARGET, reports.get(0).outcome());
            assertEquals(null, reports.get(0).target(), "a timed-out lookup invents no id");
            assertEquals(1, terminals.get());
        } finally {
            release.countDown();
            realExecutor.shutdownNow();
        }
    }

    @Test
    void reservedSubjectsNeverReachTheBlockingLookup() {
        for (String reserved : List.of("EVERYONE", "everyone", "*", "group(Friends)", "  ")) {
            Fixture fx = new Fixture();
            fx.submit(Slot.TRUST, reserved);
            fx.executor.runAll();
            assertEquals(0, fx.writes(), reserved + " must never write");
            assertEquals(0, fx.offlineCalls.get(), reserved + " must never reach the blocking lookup");
            assertEquals(1, fx.reports.size());
            assertEquals(Outcome.UNKNOWN_TARGET, fx.reports.get(0).outcome());
        }
    }

    @Test
    void onlineExactNameIsCaseSensitive() {
        Fixture fx = new Fixture();
        fx.offlineAnswer.set(Optional.empty());
        fx.submit(Slot.TRUST, "onlineexact");
        fx.executor.runAll();
        assertEquals(0, fx.writes(), "online lookup stays exact; a case mismatch is not an online hit");
        assertEquals(Outcome.UNKNOWN_TARGET, fx.reports.get(0).outcome());
    }

    @Test
    void revokedGateDeniesWithoutWriting() {
        Fixture fx = new Fixture();
        fx.online.clear();
        AtomicBoolean gateOpen = new AtomicBoolean(true);
        fx.gate = (viewer, land, slot) -> gateOpen.get();
        fx.submit(Slot.TRUST, "OfflineSteve");
        gateOpen.set(false);
        fx.executor.runAll();
        assertEquals(0, fx.writes(), "a viewer revoked mid-resolution must not write");
        assertEquals(1, fx.reports.size());
        assertEquals(Outcome.GATE_DENIED, fx.reports.get(0).outcome());
        assertEquals(1, fx.terminals.get());
    }

    @Test
    void deletedLandDeniesWithoutWriting() {
        Fixture fx = new Fixture();
        fx.online.clear();
        AtomicBoolean exists = new AtomicBoolean(true);
        fx.lands = land -> exists.get();
        fx.submit(Slot.BAN, "OfflineSteve");
        exists.set(false);
        fx.executor.runAll();
        assertEquals(0, fx.writes(), "a land deleted mid-resolution must not be written to");
        assertEquals(Outcome.FAILED, fx.reports.get(0).outcome());
    }

    @Test
    void offlineViewerDeniesWithoutWriting() {
        Fixture fx = new Fixture();
        fx.online.clear();
        AtomicBoolean online = new AtomicBoolean(true);
        fx.presence = viewer -> online.get();
        fx.submit(Slot.TRUST, "OfflineSteve");
        online.set(false);
        fx.executor.runAll();
        assertEquals(0, fx.writes(), "a viewer who left mid-resolution must not write");
        assertEquals(Outcome.FAILED, fx.reports.get(0).outcome());
    }

    @Test
    void disabledGenerationDeniesWithoutWriting() {
        Fixture fx = new Fixture();
        fx.online.clear();
        fx.submit(Slot.UNBAN, "OfflineSteve");
        fx.alive.set(false);
        fx.executor.runAll();
        assertEquals(0, fx.writes(), "completion after disable must not write");
        assertEquals(Outcome.FAILED, fx.reports.get(0).outcome());
        assertEquals(1, fx.terminals.get());
    }

    @Test
    void retiredSchedulerDropsWithoutWritingOrReporting() {
        Fixture fx = new Fixture();
        fx.online.clear();
        fx.scheduler = (player, task) -> {
            throw new IllegalStateException("scheduler retired");
        };
        fx.submit(Slot.TRUST, "OfflineSteve");
        fx.executor.runAll();
        assertEquals(0, fx.writes(), "a retired scheduler must drop the write");
        assertTrue(fx.reports.isEmpty(), "a retired scheduler must drop the report without leaking");
        assertEquals(1, fx.terminals.get(), "the terminal signal still fires for flight release");
    }

    @Test
    void continuationRunsOnTheViewerThread() {
        Fixture fx = new Fixture();
        fx.online.clear();
        RecordingScheduler scheduler = new RecordingScheduler();
        fx.scheduler = scheduler;
        fx.submit(Slot.TRUST, "OfflineSteve");
        assertTrue(scheduler.pending.isEmpty(), "nothing scheduled before resolution completes");
        fx.executor.runAll();
        assertEquals(0, fx.writes(), "the write must wait for the viewer-thread hop");
        assertEquals(1, scheduler.pending.size(), "exactly one viewer-thread task must be queued");
        scheduler.runAll();
        assertEquals(1, fx.writes(Slot.TRUST));
        assertEquals(1, scheduler.pending.size(),
                "the report must hop to the viewer thread again, never replying inline");
        assertTrue(fx.reports.isEmpty(), "no report before the second hop runs");
        scheduler.runAll();
        assertEquals(Outcome.SUCCESS, fx.reports.get(0).outcome());
    }

    @Test
    void banGuardDeniesTheOwnerWithoutWriting() {
        Fixture fx = new Fixture();
        fx.banGuard = (land, target) -> false;
        fx.submit(Slot.BAN, TARGET.toString());
        assertEquals(0, fx.writes(), "a non-bannable target must never write");
        assertEquals(1, fx.reports.size());
        assertEquals(Outcome.NOT_ALLOWED, fx.reports.get(0).outcome());
        assertEquals("ban.not_allowed", fx.reports.get(0).reason());
    }

    @Test
    void missingMutationReportsUnavailableWithoutWriting() {
        Fixture fx = new Fixture();
        RosterChangeOperation operation = new RosterChangeOperation(
                name -> Optional.of(TARGET), null, PlayerScheduler.direct(),
                fx.gate, fx.presence, fx.lands, fx.alive::get, fx.banGuard, slot -> null);
        operation.submit(player(VIEWER), VIEWER, LAND, Slot.TRUST, TARGET.toString(),
                fx.reports::add, fx.terminals::incrementAndGet);
        assertEquals(0, fx.writes());
        assertEquals(Outcome.UNAVAILABLE, fx.reports.get(0).outcome());
        assertEquals("trust.unavailable", fx.reports.get(0).reason());
    }

    @Test
    void mutationFailureReportsFailed() {
        Fixture fx = new Fixture();
        fx.mutations.get(Slot.UNTRUST).fail = true;
        fx.submit(Slot.UNTRUST, TARGET.toString());
        assertEquals(1, fx.writes(Slot.UNTRUST), "the attempt happened; the report carries the failure");
        assertEquals(Outcome.FAILED, fx.reports.get(0).outcome());
        assertEquals("untrust.failed", fx.reports.get(0).reason());
        assertEquals(1, fx.terminals.get());
    }

    @Test
    void eachSlotReportsUnderItsOwnKey() {
        for (Slot slot : Slot.values()) {
            Fixture fx = new Fixture();
            fx.online.clear();
            fx.offlineAnswer.set(Optional.empty());
            fx.submit(slot, "NobodyEverPlayed");
            fx.executor.runAll();
            assertEquals(slot.key() + ".unknown_target", fx.reports.get(0).reason(),
                    slot + " must report under its own key");
        }
    }

    @Test
    void discardedTaskStillTerminatesWithoutWritingOrReporting() {
        Fixture fx = new Fixture();
        fx.online.clear();
        DiscardingScheduler scheduler = new DiscardingScheduler();
        scheduler.discardNext = true;
        RosterChangeOperation operation = new RosterChangeOperation(
                name -> Optional.empty(), fx.resolver(), scheduler,
                fx.gate, fx.presence, fx.lands, fx.alive::get, fx.banGuard, fx.mutations::get);
        operation.submit(player(VIEWER), VIEWER, LAND, Slot.TRUST, "OfflineSteve",
                fx.reports::add, fx.terminals::incrementAndGet);
        fx.executor.runAll();
        assertEquals(0, fx.writes(), "a discarded task must never write");
        assertTrue(fx.reports.isEmpty(), "a discarded task must not report");
        assertEquals(1, fx.terminals.get(),
                "a discarded task must still terminate so flight flags are released");
    }

    @Test
    void legacyNullResolverKeepsOnlineOnlyBehaviour() {
        Fixture fx = new Fixture();
        RosterChangeOperation operation = new RosterChangeOperation(
                name -> Optional.ofNullable(fx.online.get(name)), null, PlayerScheduler.direct(),
                fx.gate, fx.presence, fx.lands, fx.alive::get, fx.banGuard, fx.mutations::get);
        operation.submit(player(VIEWER), VIEWER, LAND, Slot.TRUST, "NobodyOnline",
                fx.reports::add, fx.terminals::incrementAndGet);
        assertEquals(0, fx.writes(), "offline names fail closed without a resolver");
        assertEquals(Outcome.UNKNOWN_TARGET, fx.reports.get(0).outcome());
        assertEquals(0, fx.executor.pending.size(), "no async lookup without a resolver");
    }
}

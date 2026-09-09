package com.smile.chunkland.subland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.AuditRepository;
import com.smile.chunkland.persistence.LandRepository;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteAuditRepository;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.persistence.SqliteSubLandRepository;
import com.smile.chunkland.persistence.SubLandRepository;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import com.smile.chunkland.subland.SubLandConfirmService.Accepted;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Durable compare-and-set for SubLand mutations: two mutations racing on the
 * same parent revision serialize so exactly one wins and the loser reports a
 * stale conflict instead of silently overwriting state or breaching the limit.
 */
class SubLandConcurrentMutationTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir Path tmp;

    private static final class Env implements AutoCloseable {
        final PersistenceStore store;
        final LandRepository lands;
        final SubLandRepository subs;
        final AuditRepository audits;
        final LandRegistryStore registry = new LandRegistryStore();
        final SelectionSessionManager selections = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                () -> NOW,
                Duration.ofMinutes(10));
        final SubLandConfirmService confirm = new SubLandConfirmService();
        LandId parentId;

        Env(Path db) {
            store = PersistenceStore.open(db);
            lands = new SqliteLandRepository(store);
            subs = new SqliteSubLandRepository(store);
            audits = new SqliteAuditRepository(store);
        }

        SubLandMutationRunner runner() {
            return new SubLandMutationRunner(lands,
                    new com.smile.chunkland.persistence.SubLandAtomicCommit(store),
                    registry, selections,
                    confirm, SubLandDepthSource.constant(50), DepthExtensionPort.denyAll(),
                    LimitSettings.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));
        }

        void saveParent(long structureRevision) {
            parentId = new LandId(UUID.randomUUID());
            LandName name = LandName.of("Home");
            LandSnapshot parent = new LandSnapshot(parentId, name.displayName(), name.nameKey(),
                    OwnerRef.player(UUID.randomUUID()), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                    List.of(), structureRevision, 0L, NOW, NOW);
            lands.save(parent).toCompletableFuture().join();
            new SqliteChunkRepository(store)
                    .addChunk(parentId, new ChunkKey(WORLD, 0, 0), 50, UUID.randomUUID(), 0L)
                    .toCompletableFuture().join();
        }

        @Override
        public void close() {
            store.close();
        }
    }

    @Test
    void concurrentCreatesOnSameRevisionSerializeToExactlyOneWinner() throws Exception {
        // NOTE: this test uses one shared actor pair with independent sessions;
        // the barrier below makes both mutations enter the runner together
        // without any sleep.
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            Accepted firstAccepted = confirmOne(env, first, 0L);
            Accepted secondAccepted = confirmOne(env, second, 0L);
            SubLandSnapshot firstCandidate = new SubLandSnapshot(new SubLandId(UUID.randomUUID()),
                    env.parentId, "alpha", new Cuboid(0, 60, 0, 15, 70, 15), WORLD);
            SubLandSnapshot secondCandidate = new SubLandSnapshot(new SubLandId(UUID.randomUUID()),
                    env.parentId, "beta", new Cuboid(0, 60, 0, 15, 70, 15), WORLD);

            SubLandMutationRunner runner = env.runner();
            CyclicBarrier gate = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> left = pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    try {
                        runner.create(first, firstAccepted, firstCandidate)
                                .toCompletableFuture().join();
                        return true;
                    } catch (RuntimeException conflict) {
                        return false;
                    }
                });
                Future<Boolean> right = pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    try {
                        runner.create(second, secondAccepted, secondCandidate)
                                .toCompletableFuture().join();
                        return true;
                    } catch (RuntimeException conflict) {
                        return false;
                    }
                });
                boolean leftWon = left.get(30, TimeUnit.SECONDS);
                boolean rightWon = right.get(30, TimeUnit.SECONDS);
                assertEquals(1, (leftWon ? 1 : 0) + (rightWon ? 1 : 0),
                        "two creates on the same parent revision must serialize to exactly one winner");
            } finally {
                pool.shutdownNow();
            }

            assertEquals(1, env.subs.findByLand(env.parentId).toCompletableFuture().join().size(),
                    "the loser must not leave a second child row behind");
            assertEquals(1L, env.lands.findById(env.parentId).toCompletableFuture().join()
                    .orElseThrow().structureRevision(),
                    "the parent revision must advance exactly once");
            List<AuditEntry> audits = env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join();
            assertEquals(1, audits.size(), "exactly one mutation may write an audit row");
            assertEquals("SUBLAND_CREATE", audits.get(0).action());
            // Runtime publish carries only the durable winner, never a phantom.
            assertEquals(1, env.registry.snapshot().lands().get(env.parentId).subLands().size());
        }
    }

    @Test
    void concurrentCreatesAtLimitNeverExceedMax() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            int max = LimitSettings.defaults().maxSublandsPerLand();
            UUID seeder = UUID.randomUUID();
            for (int i = 0; i < max - 1; i++) {
                Accepted accepted = confirmOne(env, seeder, (long) i);
                SubLandSnapshot sub = new SubLandSnapshot(new SubLandId(UUID.randomUUID()),
                        env.parentId, "s" + i,
                        new Cuboid(0, 60 + i * 64, 0, 15, 61 + i * 64, 15), WORLD);
                env.runner().create(seeder, accepted, sub).toCompletableFuture().join();
                env.selections.cancel(seeder);
            }
            long base = max - 1L;
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            Accepted firstAccepted = confirmOne(env, first, base);
            Accepted secondAccepted = confirmOne(env, second, base);
            SubLandSnapshot firstCandidate = new SubLandSnapshot(new SubLandId(UUID.randomUUID()),
                    env.parentId, "alpha",
                    new Cuboid(0, 60 + (max - 1) * 64, 0, 15, 61 + (max - 1) * 64, 15), WORLD);
            SubLandSnapshot secondCandidate = new SubLandSnapshot(new SubLandId(UUID.randomUUID()),
                    env.parentId, "beta",
                    new Cuboid(0, 60 + max * 64, 0, 15, 61 + max * 64, 15), WORLD);

            SubLandMutationRunner runner = env.runner();
            CyclicBarrier gate = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            AtomicInteger wins = new AtomicInteger();
            try {
                Future<?> left = pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    try {
                        runner.create(first, firstAccepted, firstCandidate)
                                .toCompletableFuture().join();
                        wins.incrementAndGet();
                    } catch (RuntimeException conflict) {
                        // Expected for exactly one side.
                    }
                    return null;
                });
                Future<?> right = pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    try {
                        runner.create(second, secondAccepted, secondCandidate)
                                .toCompletableFuture().join();
                        wins.incrementAndGet();
                    } catch (RuntimeException conflict) {
                        // Expected for exactly one side.
                    }
                    return null;
                });
                left.get(30, TimeUnit.SECONDS);
                right.get(30, TimeUnit.SECONDS);
                assertEquals(1, wins.get(), "at the limit, exactly one racer may win the last slot");
            } finally {
                pool.shutdownNow();
            }
            assertEquals(max, env.subs.findByLand(env.parentId).toCompletableFuture().join().size(),
                    "concurrent racers must never push the land past the per-land limit");
        }
    }

    private static Accepted confirmOne(Env env, UUID actor, long baseRevision) {
        SelectionSession session = env.selections.start(SelectionSession.initial(
                actor, WORLD, SelectionMode.CREATE_SUBLAND,
                Optional.of(env.parentId), Optional.empty(),
                Optional.of(new SelectionPoint(WORLD, 0, 60, 0)),
                Optional.of(new SelectionPoint(WORLD, 15, 70, 15)),
                baseRevision, NOW));
        return env.confirm.accept(actor, session.sessionGeneration(), session.selectionRevision(),
                env.selections, landId -> OptionalLong.of(baseRevision))
                .orElseThrow(() -> new IllegalStateException("confirm must accept"));
    }
}

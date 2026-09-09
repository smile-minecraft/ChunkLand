package com.smile.chunkland.subland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.smile.chunkland.persistence.AuditRepository;
import com.smile.chunkland.persistence.LandRepository;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteAuditRepository;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.persistence.SqliteSubLandRepository;
import com.smile.chunkland.persistence.SubLandAtomicCommit;
import com.smile.chunkland.persistence.SubLandRepository;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import com.smile.chunkland.subland.SubLandConfirmService.Accepted;
import com.smile.chunkland.subland.SubLandMutationRunner.RuntimePublisher;
import com.smile.chunkland.subland.SubLandMutationRunner.RuntimeRebuildPendingException;
import java.lang.reflect.Method;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery for durable-commit success with runtime publish failure.
 *
 * <p>The atomic rows must survive exactly once, the failure must read as a
 * rebuild-pending degraded outcome (never a rejection), the confirmation
 * mark must stay consumed, and {@code rebuildRuntime} must repair the
 * snapshot from authoritative state without touching rows again.
 */
class SubLandPublishRecoveryTest {

    private static final UUID ACTOR = UUID.randomUUID();
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
        final AtomicInteger publishCalls = new AtomicInteger();
        final AtomicBoolean failNextPublish = new AtomicBoolean(false);
        LandId parentId;

        Env(Path db) {
            store = PersistenceStore.open(db);
            lands = new SqliteLandRepository(store);
            subs = new SqliteSubLandRepository(store);
            audits = new SqliteAuditRepository(store);
        }

        RuntimePublisher publisher() {
            return () -> {
                publishCalls.incrementAndGet();
                if (failNextPublish.getAndSet(false)) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("injected publish failure"));
                }
                return lands.findAll().thenApply(all -> {
                    LandRegistry next = LandRegistry.fromWithDepths(
                            List.copyOf(all), registry.snapshot().chunkDepths());
                    registry.publish(next);
                    return next;
                });
            };
        }

        SubLandMutationRunner runner() {
            return new SubLandMutationRunner(lands,
                    new SubLandAtomicCommit(store),
                    registry, selections,
                    confirm, SubLandDepthSource.constant(50), DepthExtensionPort.denyAll(),
                    LimitSettings.defaults(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    publisher());
        }

        void saveParent() {
            parentId = new LandId(UUID.randomUUID());
            LandName name = LandName.of("Home");
            LandSnapshot parent = new LandSnapshot(parentId, name.displayName(), name.nameKey(),
                    OwnerRef.player(ACTOR), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                    List.of(), 0L, 0L, NOW, NOW);
            lands.save(parent).toCompletableFuture().join();
            new SqliteChunkRepository(store)
                    .addChunk(parentId, new ChunkKey(WORLD, 0, 0), 50, UUID.randomUUID(), 0L)
                    .toCompletableFuture().join();
        }

        Accepted startSession(long baseRevision, SubLandId target) {
            SelectionSession session = selections.start(SelectionSession.initial(
                    ACTOR, WORLD, SelectionMode.CREATE_SUBLAND,
                    Optional.of(parentId), Optional.ofNullable(target),
                    Optional.of(new SelectionPoint(WORLD, 0, 60, 0)),
                    Optional.of(new SelectionPoint(WORLD, 15, 70, 15)),
                    baseRevision, NOW));
            return confirm.accept(ACTOR, session.sessionGeneration(), session.selectionRevision(),
                    selections, landId -> OptionalLong.of(baseRevision)).orElseThrow();
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static SubLandSnapshot candidate(LandId parent, String name, Cuboid cuboid) {
        return new SubLandSnapshot(new SubLandId(UUID.randomUUID()), parent, name, cuboid, WORLD);
    }

    private static RuntimeRebuildPendingException pendingOf(CompletionException failure) {
        assertTrue(failure.getCause() instanceof RuntimeRebuildPendingException,
                "durable success with publish failure must read as rebuild-pending, got: " + failure.getCause());
        return (RuntimeRebuildPendingException) failure.getCause();
    }

    @Test
    void createPublishFailureIsPendingAndRetryRepairsWithoutDuplicates() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            SubLandMutationRunner runner = env.runner();
            Accepted accepted = env.startSession(0L, null);
            SubLandSnapshot want = candidate(env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15));
            env.failNextPublish.set(true);
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> runner.create(ACTOR, accepted, want).toCompletableFuture().join());
            RuntimeRebuildPendingException pending = pendingOf(failure);
            assertEquals(RuntimeRebuildPendingException.DIAGNOSTIC_KEY, pending.diagnosticKey());
            assertEquals(1, env.publishCalls.get());
            // Durable state exactly once; runtime still stale.
            assertTrue(env.subs.findById(want.id()).toCompletableFuture().join().isPresent());
            assertEquals(1L, env.lands.findById(env.parentId).toCompletableFuture().join()
                    .orElseThrow().structureRevision());
            assertEquals(1, env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join().size());
            assertTrue(env.registry.snapshot().isEmpty());
            // Same token cannot redo the durable mutation.
            Optional<Accepted> replay = env.confirm.accept(ACTOR,
                    accepted.session().sessionGeneration(),
                    accepted.session().selectionRevision(),
                    env.selections, landId -> OptionalLong.of(0L));
            assertTrue(replay.isEmpty());
            // Retry repairs from authoritative state without new rows.
            LandRegistry repaired = runner.rebuildRuntime().toCompletableFuture().join();
            assertEquals(want.id(), repaired.subLandIndex(env.parentId).findById(want.id()).id());
            assertEquals(want.id(), env.registry.snapshot()
                    .subLandIndex(env.parentId).findById(want.id()).id());
            assertEquals(1, env.subs.findByLand(env.parentId).toCompletableFuture().join().size());
            assertEquals(1, env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join().size());
            assertEquals(1L, env.lands.findById(env.parentId).toCompletableFuture().join()
                    .orElseThrow().structureRevision());
            // Second retry stays idempotent.
            runner.rebuildRuntime().toCompletableFuture().join();
            assertEquals(1, env.subs.findByLand(env.parentId).toCompletableFuture().join().size());
            assertEquals(1, env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join().size());
            assertEquals(1L, env.lands.findById(env.parentId).toCompletableFuture().join()
                    .orElseThrow().structureRevision());
        }
    }

    @Test
    void updatePublishFailureIsPendingAndRetryRepairs() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            SubLandMutationRunner runner = env.runner();
            Accepted first = env.startSession(0L, null);
            SubLandSnapshot want = candidate(env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15));
            runner.create(ACTOR, first, want).toCompletableFuture().join();
            env.selections.cancel(ACTOR);
            Accepted second = env.startSession(1L, want.id());
            SubLandSnapshot replacement = new SubLandSnapshot(want.id(), env.parentId, "study",
                    new Cuboid(0, 60, 0, 15, 70, 15), WORLD);
            env.failNextPublish.set(true);
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> runner.update(ACTOR, second, replacement).toCompletableFuture().join());
            pendingOf(failure);
            // Durable replacement applied; runtime still shows the old name.
            assertEquals("study", env.subs.findById(want.id())
                    .toCompletableFuture().join().orElseThrow().name());
            assertEquals(2L, env.lands.findById(env.parentId).toCompletableFuture().join()
                    .orElseThrow().structureRevision());
            assertEquals(2, env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join().size());
            assertEquals("den", env.registry.snapshot()
                    .subLandIndex(env.parentId).findById(want.id()).name());
            runner.rebuildRuntime().toCompletableFuture().join();
            assertEquals("study", env.registry.snapshot()
                    .subLandIndex(env.parentId).findById(want.id()).name());
            assertEquals(2, env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join().size());
            assertEquals(2L, env.lands.findById(env.parentId).toCompletableFuture().join()
                    .orElseThrow().structureRevision());
        }
    }

    @Test
    void deletePublishFailureIsPendingAndRetryRepairs() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            SubLandMutationRunner runner = env.runner();
            Accepted first = env.startSession(0L, null);
            SubLandSnapshot want = candidate(env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15));
            runner.create(ACTOR, first, want).toCompletableFuture().join();
            env.selections.cancel(ACTOR);
            Accepted second = env.startSession(1L, want.id());
            env.failNextPublish.set(true);
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> runner.delete(ACTOR, second, want.id()).toCompletableFuture().join());
            pendingOf(failure);
            // Durable row gone; runtime still carries it until retry.
            assertTrue(env.subs.findById(want.id()).toCompletableFuture().join().isEmpty());
            assertEquals(2L, env.lands.findById(env.parentId).toCompletableFuture().join()
                    .orElseThrow().structureRevision());
            assertEquals(2, env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join().size());
            assertTrue(env.registry.snapshot().subLandIndex(env.parentId).findById(want.id()) != null);
            runner.rebuildRuntime().toCompletableFuture().join();
            assertTrue(env.registry.snapshot().subLandIndex(env.parentId) == null
                    || env.registry.snapshot().subLandIndex(env.parentId).findById(want.id()) == null);
            assertEquals(2, env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join().size());
            assertEquals(2L, env.lands.findById(env.parentId).toCompletableFuture().join()
                    .orElseThrow().structureRevision());
        }
    }

    @Test
    void concurrentRetrySharesOnePublish() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            CompletableFuture<LandRegistry> gate = new CompletableFuture<>();
            AtomicInteger calls = new AtomicInteger();
            RuntimePublisher blocking = () -> {
                calls.incrementAndGet();
                return gate;
            };
            SubLandMutationRunner runner = new SubLandMutationRunner(env.lands,
                    new SubLandAtomicCommit(env.store),
                    env.registry, env.selections,
                    env.confirm, SubLandDepthSource.constant(50), DepthExtensionPort.denyAll(),
                    LimitSettings.defaults(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    blocking);
            var first = runner.rebuildRuntime();
            var second = runner.rebuildRuntime();
            assertSame(first, second, "concurrent retries must join one in-flight publish");
            assertEquals(1, calls.get());
            gate.complete(LandRegistry.empty());
            assertSame(gate.join(), first.toCompletableFuture().join());
            assertSame(first.toCompletableFuture().join(), second.toCompletableFuture().join());
            // After completion a new retry runs again but stays side-effect free.
            var third = runner.rebuildRuntime();
            assertEquals(2, calls.get());
            gate.complete(LandRegistry.empty());
            third.toCompletableFuture().join();
        }
    }

    @Test
    void pendingMapsToDegradedMessageKey() throws Exception {
        Method failureKey = Class.forName("com.smile.chunkland.command.SubLandCommandHandler")
                .getDeclaredMethod("failureKey", Throwable.class);
        failureKey.setAccessible(true);
        RuntimeRebuildPendingException pending = assertThrows(RuntimeRebuildPendingException.class, () -> {
            throw new RuntimeRebuildPendingException("pending", new IllegalStateException("downstream"));
        });
        CompletionException wrapped = new CompletionException(pending);
        assertEquals("command.land.subland.degraded", failureKey.invoke(null, wrapped));
        assertInstanceOf(RuntimeRebuildPendingException.class, pending);
        assertEquals("subland.runtime_rebuild_pending", pending.diagnosticKey());
    }
}

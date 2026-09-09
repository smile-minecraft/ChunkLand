package com.smile.chunkland.subland;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Atomicity for SubLand mutations: the child row, the parent revision bump
 * and the audit row commit in one SQLite transaction, so a failure at any
 * step leaves no partial durable state behind.
 */
class SubLandAtomicityTest {

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
        LandId parentId;

        Env(Path db) {
            store = PersistenceStore.open(db);
            lands = new SqliteLandRepository(store);
            subs = new SqliteSubLandRepository(store);
            audits = new SqliteAuditRepository(store);
        }

        SubLandMutationRunner runner(Consumer<SubLandAtomicCommit.Step> injector) {
            return new SubLandMutationRunner(lands,
                    new SubLandAtomicCommit(store, injector),
                    registry, selections,
                    confirm, SubLandDepthSource.constant(50), DepthExtensionPort.denyAll(),
                    LimitSettings.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));
        }

        SubLandMutationRunner runner() {
            return runner(ignored -> {
            });
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

        Accepted startSession() {
            SelectionSession session = selections.start(SelectionSession.initial(
                    ACTOR, WORLD, SelectionMode.CREATE_SUBLAND,
                    Optional.of(parentId), Optional.empty(),
                    Optional.of(new SelectionPoint(WORLD, 0, 60, 0)),
                    Optional.of(new SelectionPoint(WORLD, 15, 70, 15)),
                    0L, NOW));
            return confirm.accept(ACTOR, session.sessionGeneration(), session.selectionRevision(),
                    selections, landId -> OptionalLong.of(0L)).orElseThrow();
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static SubLandSnapshot candidate(LandId parent) {
        return new SubLandSnapshot(new SubLandId(UUID.randomUUID()), parent, "den",
                new Cuboid(0, 60, 0, 15, 70, 15), WORLD);
    }

    private static Consumer<SubLandAtomicCommit.Step> failAt(SubLandAtomicCommit.Step step) {
        return reached -> {
            if (reached == step) {
                throw new IllegalStateException("injected failure at " + step);
            }
        };
    }

    private static void assertNoDurableTrace(Env env) {
        assertTrue(env.subs.findByLand(env.parentId).toCompletableFuture().join().isEmpty(),
                "a failed mutation must not leave a child row behind");
        assertEquals(0L, env.lands.findById(env.parentId).toCompletableFuture().join()
                .orElseThrow().structureRevision(),
                "a failed mutation must not leave a parent revision bump behind");
        assertTrue(env.audits.findByLand(env.parentId, 10, 0)
                .toCompletableFuture().join().isEmpty(),
                "a failed mutation must not leave an audit row behind");
        assertTrue(env.registry.snapshot().isEmpty(),
                "a failed mutation must never publish runtime state");
    }

    @Test
    void childFailureRollsBackParentAndAudit() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            Accepted accepted = env.startSession();
            SubLandSnapshot want = candidate(env.parentId);
            assertThrows(CompletionException.class, () -> env
                    .runner(failAt(SubLandAtomicCommit.Step.AFTER_CHILD))
                    .create(ACTOR, accepted, want).toCompletableFuture().join());
            assertNoDurableTrace(env);
        }
    }

    @Test
    void parentFailureRollsBackChildAndAudit() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            Accepted accepted = env.startSession();
            SubLandSnapshot want = candidate(env.parentId);
            assertThrows(CompletionException.class, () -> env
                    .runner(failAt(SubLandAtomicCommit.Step.AFTER_PARENT))
                    .create(ACTOR, accepted, want).toCompletableFuture().join());
            assertNoDurableTrace(env);
        }
    }

    @Test
    void auditFailureRollsBackChildAndParent() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            Accepted accepted = env.startSession();
            SubLandSnapshot want = candidate(env.parentId);
            assertThrows(CompletionException.class, () -> env
                    .runner(failAt(SubLandAtomicCommit.Step.AFTER_AUDIT))
                    .create(ACTOR, accepted, want).toCompletableFuture().join());
            assertNoDurableTrace(env);
        }
    }

    @Test
    void updateAuditFailureRollsBackChildAndParent() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            Accepted first = env.startSession();
            SubLandSnapshot want = candidate(env.parentId);
            env.runner().create(ACTOR, first, want).toCompletableFuture().join();
            SubLandSnapshot replacement = new SubLandSnapshot(want.id(), env.parentId, "study",
                    new Cuboid(0, 60, 0, 15, 70, 15), WORLD);
            // Success consumed the session, so the update needs a fresh one bound to revision 1.
            env.selections.cancel(ACTOR);
            SelectionSession updating = env.selections.start(SelectionSession.initial(
                    ACTOR, WORLD, SelectionMode.CREATE_SUBLAND,
                    Optional.of(env.parentId), Optional.of(want.id()),
                    Optional.of(new SelectionPoint(WORLD, 0, 60, 0)),
                    Optional.of(new SelectionPoint(WORLD, 15, 70, 15)),
                    1L, NOW));
            Accepted update = env.confirm.accept(ACTOR, updating.sessionGeneration(),
                    updating.selectionRevision(), env.selections,
                    landId -> OptionalLong.of(1L)).orElseThrow();
            assertThrows(CompletionException.class, () -> env
                    .runner(failAt(SubLandAtomicCommit.Step.AFTER_AUDIT))
                    .update(ACTOR, update, replacement).toCompletableFuture().join());
            assertEquals("den", env.subs.findById(want.id())
                    .toCompletableFuture().join().orElseThrow().name(),
                    "a failed update must not leave the replacement row behind");
            assertEquals(1L, env.lands.findById(env.parentId).toCompletableFuture().join()
                    .orElseThrow().structureRevision(),
                    "a failed update must not bump the parent revision again");
            assertEquals(1, env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join().size(),
                    "a failed update must not append a second audit row");
        }
    }
}

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
import com.smile.chunkland.persistence.AuditEntry;
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
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import com.smile.chunkland.subland.SubLandConfirmService.Accepted;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Durable tests for the SubLand mutation runner: audit rows, runtime
 * publish, selection cleanup, fail-closed stale rejections without side
 * effects, the depth confirmation port, the per-land limit, and FK cascade.
 */
class SubLandMutationRunnerTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir Path tmp;

    private static final class Env implements AutoCloseable {
        final PersistenceStore store;
        final LandRepository lands;
        final SubLandRepository subs;
        final AuditRepository audits;
        final SqliteChunkRepository chunks;
        final LandRegistryStore registry = new LandRegistryStore();
        final SelectionSessionManager selections = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                () -> NOW,
                Duration.ofMinutes(10));
        final SubLandConfirmService confirm = new SubLandConfirmService();
        LandId parentId;
        int effectiveMinY = 50;

        Env(Path db) {
            store = PersistenceStore.open(db);
            lands = new SqliteLandRepository(store);
            subs = new SqliteSubLandRepository(store);
            audits = new SqliteAuditRepository(store);
            chunks = new SqliteChunkRepository(store);
        }

        SubLandMutationRunner runner(DepthExtensionPort port) {
            return new SubLandMutationRunner(lands,
                    new com.smile.chunkland.persistence.SubLandAtomicCommit(store),
                    registry, selections,
                    confirm, SubLandDepthSource.constant(effectiveMinY), port,
                    LimitSettings.defaults(),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        SubLandMutationRunner runner() {
            return runner(DepthExtensionPort.denyAll());
        }

        SelectionStructureRevisionLookup structures() {
            return landId -> selections.sessionFor(ACTOR)
                    .map(session -> OptionalLong.of(session.baseStructureRevision()))
                    .orElseGet(OptionalLong::empty);
        }

        /** Persist a single-chunk parent and remember its id. */
        void saveParent(long structureRevision) {
            parentId = new LandId(UUID.randomUUID());
            LandName name = LandName.of("Home");
            LandSnapshot parent = new LandSnapshot(parentId, name.displayName(), name.nameKey(),
                    OwnerRef.player(ACTOR), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                    List.of(), structureRevision, 0L, NOW, NOW);
            lands.save(parent).toCompletableFuture().join();
            chunks.addChunk(parentId, new ChunkKey(WORLD, 0, 0), 50, UUID.randomUUID(), 0L)
                    .toCompletableFuture().join();
        }

        /** Start a subland session bound to the saved parent and return its tokens. */
        Accepted startSession(Long baseRevision, SubLandId target) {
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

    @Test
    void createPersistsAuditsPublishesAndClearsSession() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            Accepted accepted = env.startSession(0L, null);
            SubLandSnapshot want = candidate(env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15));
            SubLandSnapshot created = env.runner().create(ACTOR, accepted, want)
                    .toCompletableFuture().join();
            assertEquals(want.id(), created.id());
            // Durable subland row.
            assertTrue(env.subs.findById(want.id()).toCompletableFuture().join().isPresent());
            assertEquals(1, env.subs.findByLand(env.parentId).toCompletableFuture().join().size());
            // Parent structure revision bumped durably.
            assertEquals(1L, env.lands.findById(env.parentId).toCompletableFuture().join()
                    .orElseThrow().structureRevision());
            // Correct audit action with after payload and traceable metadata.
            List<AuditEntry> audits = env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join();
            assertEquals(1, audits.size());
            assertEquals("SUBLAND_CREATE", audits.get(0).action());
            assertTrue(audits.get(0).afterJson().contains(want.id().value().toString()));
            assertTrue(audits.get(0).metadataJson().contains("\"depthExtendConfirmed\":false"));
            assertTrue(audits.get(0).metadataJson().contains("\"structureRevision\":0"));
            // Single immutable runtime publish carries the new subland.
            assertEquals(want.id(), env.registry.snapshot()
                    .subLandIndex(env.parentId).findById(want.id()).id());
            // Actor session released on success.
            assertTrue(env.selections.sessionFor(ACTOR).isEmpty());
        }
    }

    @Test
    void updateWritesBeforeAfterAndPublishes() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            Accepted first = env.startSession(0L, null);
            SubLandSnapshot want = candidate(env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15));
            env.runner().create(ACTOR, first, want).toCompletableFuture().join();
            Accepted second = env.startSession(1L, want.id());
            SubLandSnapshot replacement = new SubLandSnapshot(want.id(), env.parentId, "study",
                    new Cuboid(0, 60, 0, 15, 70, 15), WORLD);
            env.runner().update(ACTOR, second, replacement).toCompletableFuture().join();
            assertEquals("study", env.subs.findById(want.id())
                    .toCompletableFuture().join().orElseThrow().name());
            List<AuditEntry> audits = env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join();
            assertEquals(2, audits.size());
            assertEquals("SUBLAND_UPDATE", audits.get(0).action());
            assertTrue(audits.get(0).beforeJson().contains("\"name\":\"den\""));
            assertTrue(audits.get(0).afterJson().contains("\"name\":\"study\""));
            assertEquals("study", env.registry.snapshot()
                    .subLandIndex(env.parentId).findById(want.id()).name());
        }
    }

    @Test
    void deleteRemovesRowAuditsAndClearsMatchingSelections() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            Accepted first = env.startSession(0L, null);
            SubLandSnapshot want = candidate(env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15));
            env.runner().create(ACTOR, first, want).toCompletableFuture().join();
            Accepted second = env.startSession(1L, want.id());
            // A second observer session targeting the same subland must also be cleared.
            UUID observer = UUID.randomUUID();
            env.selections.start(SelectionSession.initial(
                    observer, WORLD, SelectionMode.CREATE_SUBLAND,
                    Optional.of(env.parentId), Optional.of(want.id()),
                    Optional.of(new SelectionPoint(WORLD, 0, 60, 0)),
                    Optional.of(new SelectionPoint(WORLD, 15, 70, 15)),
                    1L, NOW));
            env.runner().delete(ACTOR, second, want.id()).toCompletableFuture().join();
            assertTrue(env.subs.findById(want.id()).toCompletableFuture().join().isEmpty());
            List<AuditEntry> audits = env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join();
            assertEquals("SUBLAND_DELETE", audits.get(0).action());
            assertTrue(audits.get(0).beforeJson().contains(want.id().value().toString()));
            assertTrue(env.registry.snapshot().subLandIndex(env.parentId) == null
                    || env.registry.snapshot().subLandIndex(env.parentId).findById(want.id()) == null);
            assertTrue(env.selections.sessionFor(observer).isEmpty());
        }
    }

    @Test
    void staleStructureUpdateFailsWithoutSideEffect() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(5);
            // Session captured revision 5, but the live land moved to 6 first.
            Accepted accepted = env.startSession(5L, null);
            LandSnapshot live = env.lands.findById(env.parentId).toCompletableFuture().join()
                    .orElseThrow();
            env.lands.save(live.addChunk(new ChunkKey(WORLD, 1, 1))).toCompletableFuture().join();
            SubLandSnapshot want = candidate(env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15));
            CompletionException rejected = assertThrows(CompletionException.class, () -> env.runner()
                    .create(ACTOR, accepted, want).toCompletableFuture().join());
            assertTrue(rejected.getCause() instanceof IllegalStateException);
            // No subland row, no audit row, no publish, no revision move.
            assertTrue(env.subs.findByLand(env.parentId).toCompletableFuture().join().isEmpty());
            assertTrue(env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join().isEmpty());
            assertTrue(env.registry.snapshot().isEmpty());
        }
    }

    @Test
    void depthBelowFloorFailsClosedWithoutConfirmAndSucceedsWithConfirm() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            Accepted denied = env.startSession(0L, null);
            SubLandSnapshot deep = candidate(env.parentId, "mine",
                    new Cuboid(0, 30, 0, 15, 70, 15));
            CompletionException required = assertThrows(CompletionException.class, () -> env.runner()
                    .create(ACTOR, denied, deep).toCompletableFuture().join());
            assertTrue(required.getCause() instanceof DepthExtendConfirmationRequired);
            assertTrue(env.subs.findByLand(env.parentId).toCompletableFuture().join().isEmpty());
            assertTrue(env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join().isEmpty());
            // The failed token is released, so the same session can confirm once more.
            Accepted confirmed = env.confirm.accept(ACTOR,
                    denied.session().sessionGeneration(), denied.session().selectionRevision(),
                    env.selections, landId -> OptionalLong.of(0L)).orElseThrow();
            env.runner(DepthExtensionPort.allowAll()).create(ACTOR, confirmed, deep)
                    .toCompletableFuture().join();
            List<AuditEntry> audits = env.audits.findByLand(env.parentId, 10, 0)
                    .toCompletableFuture().join();
            assertEquals(1, audits.size());
            assertTrue(audits.get(0).metadataJson().contains("\"depthExtendConfirmed\":true"));
            assertTrue(audits.get(0).metadataJson().contains("\"effectiveMinProtectedY\":50"));
            // The parent stored depth itself is never rewritten by the SubLand path.
            assertEquals(Map.of(new ChunkKey(WORLD, 0, 0), 50), env.chunks
                    .listDepthsByLand(env.parentId).toCompletableFuture().join());
        }
    }

    @Test
    void durableCreateFailureRestoresDepthConfirmationForRetry() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            SubLandDepthConfirmations depthConfirmations = new SubLandDepthConfirmations(
                    env.selections, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));
            Accepted accepted = env.startSession(0L, null);
            SelectionSession session = env.selections.sessionFor(ACTOR).orElseThrow();
            assertTrue(depthConfirmations.record(ACTOR, session, env.parentId, 30));
            SubLandSnapshot deep = candidate(env.parentId, "deep",
                    new Cuboid(0, 30, 0, 15, 70, 15));
            SubLandMutationRunner failing = new SubLandMutationRunner(
                    env.lands,
                    new SubLandAtomicCommit(env.store, step -> {
                        throw new IllegalStateException("injected durable failure");
                    }),
                    env.registry, env.selections, env.confirm,
                    SubLandDepthSource.constant(50), depthConfirmations,
                    LimitSettings.defaults(), Clock.fixed(NOW, ZoneOffset.UTC),
                    null, null, depthConfirmations);
            assertThrows(CompletionException.class, () -> failing.create(ACTOR, accepted, deep)
                    .toCompletableFuture().join());

            Accepted retry = env.confirm.accept(ACTOR,
                    accepted.session().sessionGeneration(), accepted.session().selectionRevision(),
                    env.selections, landId -> OptionalLong.of(0L)).orElseThrow();
            env.runner(depthConfirmations).create(ACTOR, retry, deep).toCompletableFuture().join();
            assertTrue(env.subs.findById(deep.id()).toCompletableFuture().join().isPresent());
            assertEquals(0, depthConfirmations.size());
        }
    }

    @Test
    void limitAndDuplicateAreEnforced() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            // Fill exactly maxSublandsPerLand disjoint Y-stacks in the one chunk column.
            for (int i = 0; i < LimitSettings.defaults().maxSublandsPerLand(); i++) {
                int base = i * 64;
                Cuboid stack = new Cuboid(0, 60 + base, 0, 15, 61 + base, 15);
                Accepted accepted = env.startSession((long) i, null);
                SubLandSnapshot sub = new SubLandSnapshot(new SubLandId(UUID.randomUUID()),
                        env.parentId, "s" + i, stack, WORLD);
                env.runner().create(ACTOR, accepted, sub).toCompletableFuture().join();
            }
            assertEquals(LimitSettings.defaults().maxSublandsPerLand(), env.subs
                    .findByLand(env.parentId).toCompletableFuture().join().size());
            // The 17th create breaches the limit without touching rows or audits.
            Accepted overLimit = env.startSession(
                    (long) LimitSettings.defaults().maxSublandsPerLand(), null);
            SubLandSnapshot extra = new SubLandSnapshot(new SubLandId(UUID.randomUUID()),
                    env.parentId, "extra",
                    new Cuboid(0, 60 + 16 * 64, 0, 15, 61 + 16 * 64, 15), WORLD);
            CompletionException limited = assertThrows(CompletionException.class, () -> env.runner()
                    .create(ACTOR, overLimit, extra).toCompletableFuture().join());
            assertTrue(limited.getCause() instanceof IllegalStateException);
            assertTrue(limited.getCause().getMessage().contains("max-sublands-per-land"));
            assertEquals(LimitSettings.defaults().maxSublandsPerLand(), env.subs
                    .findByLand(env.parentId).toCompletableFuture().join().size());
            // A duplicate id is rejected even though the geometry is valid.
            Accepted dupeToken = env.confirm.accept(ACTOR,
                    overLimit.session().sessionGeneration(),
                    overLimit.session().selectionRevision(),
                    env.selections, landId -> OptionalLong.of(
                            (long) LimitSettings.defaults().maxSublandsPerLand())).orElseThrow();
            SubLandSnapshot existing = env.subs.findByLand(env.parentId)
                    .toCompletableFuture().join().stream().findFirst().orElseThrow();
            SubLandSnapshot dupe = new SubLandSnapshot(existing.id(), env.parentId, "dupe",
                    new Cuboid(0, 60, 0, 15, 70, 15), WORLD);
            CompletionException duplicate = assertThrows(CompletionException.class, () -> env.runner()
                    .create(ACTOR, dupeToken, dupe).toCompletableFuture().join());
            assertTrue(duplicate.getCause().getMessage().contains("already exists"));
        }
    }

    @Test
    void landDeleteCascadesSublands() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            SubLandSnapshot want = candidate(env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15));
            env.subs.save(want).toCompletableFuture().join();
            assertTrue(env.subs.findById(want.id()).toCompletableFuture().join().isPresent());
            env.lands.delete(env.parentId).toCompletableFuture().join();
            assertTrue(env.subs.findById(want.id()).toCompletableFuture().join().isEmpty());
        }
    }
}

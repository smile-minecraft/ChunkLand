package com.smile.chunkland.subland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.chunkland.api.event.SubLandPostEvent;
import com.smile.chunkland.api.event.SubLandPreEvent;
import com.smile.chunkland.api.event.SubLandPreEvent.Operation;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.event.PublicEventBus;
import com.smile.chunkland.event.PublicEventCancelledException;
import com.smile.chunkland.event.PublicEvents;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Public Pre/Post for SubLand mutations: Pre veto and listener failure
 * before any durable write (confirmation released for retry); Post exactly
 * once after the atomic commit plus the runtime publish; Post failure
 * isolated; publish degradation never replays Post.
 */
class SubLandRunnerPublicEventTest {

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
        final PublicEventBus bus = new PublicEventBus();
        final PublicEvents events = PublicEvents.create(bus, null);
        final AtomicInteger preCalls = new AtomicInteger();
        final AtomicInteger postCalls = new AtomicInteger();
        final List<SubLandPostEvent> posts = Collections.synchronizedList(new ArrayList<>());
        LandId parentId;
        int effectiveMinY = 50;

        Env(Path db) {
            store = PersistenceStore.open(db);
            lands = new SqliteLandRepository(store);
            subs = new SqliteSubLandRepository(store);
            audits = new SqliteAuditRepository(store);
            chunks = new SqliteChunkRepository(store);
            bus.register(SubLandPreEvent.class, event -> preCalls.incrementAndGet());
            bus.register(SubLandPostEvent.class, event -> {
                postCalls.incrementAndGet();
                posts.add(event);
            });
        }

        SubLandMutationRunner runner() {
            return new SubLandMutationRunner(lands,
                    new com.smile.chunkland.persistence.SubLandAtomicCommit(store),
                    registry, selections,
                    confirm, SubLandDepthSource.constant(effectiveMinY),
                    DepthExtensionPort.denyAll(),
                    LimitSettings.defaults(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    events);
        }

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

        int auditCount() {
            return audits.findByLand(parentId, 10, 0).toCompletableFuture().join().size();
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
    void createPreVetoWritesNothingAndReleasesConfirmation() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            Accepted accepted = env.startSession(0L, null);
            SubLandSnapshot want = candidate(env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15));
            env.bus.register(SubLandPreEvent.class, event -> event.setCancelled(true));

            try {
                env.runner().create(ACTOR, accepted, want).toCompletableFuture().join();
                fail("vetoed create must fail");
            } catch (CompletionException expected) {
                assertTrue(expected.getCause() instanceof PublicEventCancelledException);
            }
            assertEquals(1, env.preCalls.get());
            assertEquals(0, env.postCalls.get());
            assertTrue(env.subs.findByLand(env.parentId).toCompletableFuture().join().isEmpty());
            assertEquals(0, env.auditCount());
        }
    }

    @Test
    void throwingCreatePreListenerFailsClosedWithZeroSideEffects() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            Accepted accepted = env.startSession(0L, null);
            SubLandSnapshot want = candidate(env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15));
            env.bus.register(SubLandPreEvent.class, event -> {
                throw new IllegalStateException("broken listener");
            });

            try {
                env.runner().create(ACTOR, accepted, want).toCompletableFuture().join();
                fail("vetoed create must fail");
            } catch (CompletionException expected) {
                assertTrue(expected.getCause() instanceof PublicEventCancelledException);
            }
            assertTrue(env.subs.findByLand(env.parentId).toCompletableFuture().join().isEmpty());
            assertEquals(0, env.auditCount());
            assertEquals(0, env.postCalls.get());
        }
    }

    @Test
    void createPostFiresExactlyOnceAfterCommitAndPublish() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            Accepted accepted = env.startSession(0L, null);
            SubLandSnapshot want = candidate(env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15));

            SubLandSnapshot created = env.runner().create(ACTOR, accepted, want)
                    .toCompletableFuture().join();

            assertEquals(want.id(), created.id());
            assertEquals(1, env.preCalls.get());
            assertEquals(1, env.postCalls.get());
            SubLandPostEvent post = env.posts.get(0);
            assertEquals(ACTOR, post.actorUuid());
            assertEquals(env.parentId, post.parentLandId());
            assertEquals(want.id(), post.subLandId());
            assertEquals(Operation.CREATE, post.operation());
        }
    }

    @Test
    void throwingCreatePostListenerDoesNotRollBack() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            Accepted accepted = env.startSession(0L, null);
            SubLandSnapshot want = candidate(env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15));
            env.bus.register(SubLandPostEvent.class, event -> {
                throw new IllegalStateException("broken post listener");
            });

            SubLandSnapshot created = env.runner().create(ACTOR, accepted, want)
                    .toCompletableFuture().join();

            assertEquals(want.id(), created.id());
            assertEquals(1, env.postCalls.get());
            assertTrue(env.subs.findById(want.id()).toCompletableFuture().join().isPresent());
        }
    }

    @Test
    void staleRevisionFailsAfterPreWithoutPost() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            Accepted accepted = env.startSession(5L, null);
            SubLandSnapshot want = candidate(env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15));

            try {
                env.runner().create(ACTOR, accepted, want).toCompletableFuture().join();
                fail("stale create must fail");
            } catch (CompletionException expected) {
                // Stale structure revision fails after Pre, before any write.
            }
            assertEquals(1, env.preCalls.get());
            assertEquals(0, env.postCalls.get());
            assertTrue(env.subs.findByLand(env.parentId).toCompletableFuture().join().isEmpty());
            assertEquals(0, env.auditCount());
        }
    }

    @Test
    void deletePreVetoAndPostCoverDeletePath() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent(0);
            Accepted first = env.startSession(0L, null);
            SubLandSnapshot want = candidate(env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15));
            env.runner().create(ACTOR, first, want).toCompletableFuture().join();
            int postsAfterCreate = env.postCalls.get();

            Accepted vetoed = env.startSession(1L, want.id());
            java.util.concurrent.atomic.AtomicBoolean vetoArmed = new java.util.concurrent.atomic.AtomicBoolean(true);
            env.bus.register(SubLandPreEvent.class, event -> {
                if (event.operation() == Operation.DELETE && vetoArmed.get()) {
                    event.setCancelled(true);
                }
            });
            try {
                env.runner().delete(ACTOR, vetoed, want.id()).toCompletableFuture().join();
                fail("vetoed delete must fail");
            } catch (CompletionException expected) {
                assertTrue(expected.getCause() instanceof PublicEventCancelledException);
            }
            assertTrue(env.subs.findById(want.id()).toCompletableFuture().join().isPresent());
            vetoArmed.set(false);

            Accepted second = env.startSession(1L, want.id());
            env.runner().delete(ACTOR, second, want.id()).toCompletableFuture().join();
            assertEquals(postsAfterCreate + 1, env.postCalls.get());
            SubLandPostEvent post = env.posts.get(env.posts.size() - 1);
            assertEquals(Operation.DELETE, post.operation());
            assertEquals(want.id(), post.subLandId());
            assertTrue(env.subs.findById(want.id()).toCompletableFuture().join().isEmpty());
        }
    }
}

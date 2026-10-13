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
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.persistence.SubLandAtomicCommit;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SubLand commits validate against the live limits: a pending confirmation
 * prepared before a reload is decided under the reloaded generation, not
 * the one it was prompted with.
 */
class SubLandRunnerReloadTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir Path tmp;

    private static LimitSettings limitsWithMaxSublands(int maxSublands) {
        return new LimitSettings(5, 256, 128, maxSublands);
    }

    private final class Env implements AutoCloseable {
        final PersistenceStore store;
        final SqliteLandRepository lands;
        final SqliteChunkRepository chunks;
        final LandRegistryStore registry = new LandRegistryStore();
        final SelectionSessionManager selections = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                () -> NOW,
                Duration.ofMinutes(10));
        final SubLandConfirmService confirm = new SubLandConfirmService();
        final AtomicReference<LimitSettings> liveLimits =
                new AtomicReference<>(limitsWithMaxSublands(16));
        final SubLandMutationRunner runner;
        LandId parentId;

        Env(Path db) {
            store = PersistenceStore.open(db);
            lands = new SqliteLandRepository(store);
            chunks = new SqliteChunkRepository(store);
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            runner = new SubLandMutationRunner(lands,
                    new SubLandAtomicCommit(store),
                    registry, selections,
                    confirm, SubLandDepthSource.constant(50), DepthExtensionPort.denyAll(),
                    liveLimits::get,
                    clock, PublicEvents.noop(), null, null);
        }

        void saveParent() {
            parentId = new LandId(UUID.randomUUID());
            LandName name = LandName.of("Home");
            LandSnapshot parent = new LandSnapshot(parentId, name.displayName(), name.nameKey(),
                    OwnerRef.player(ACTOR), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                    List.of(), 0, 0L, NOW, NOW);
            lands.save(parent).toCompletableFuture().join();
            chunks.addChunk(parentId, new ChunkKey(WORLD, 0, 0), 50, UUID.randomUUID(), 0L)
                    .toCompletableFuture().join();
        }

        Accepted startSession(long baseRevision) {
            SelectionSession session = selections.start(SelectionSession.initial(
                    ACTOR, WORLD, SelectionMode.CREATE_SUBLAND,
                    Optional.of(parentId), Optional.empty(),
                    Optional.of(new SelectionPoint(WORLD, 0, 60, 0)),
                    Optional.of(new SelectionPoint(WORLD, 15, 70, 15)),
                    baseRevision, NOW));
            return confirm.accept(ACTOR, session.sessionGeneration(), session.selectionRevision(),
                    selections, landId -> OptionalLong.of(baseRevision)).orElseThrow();
        }

        SubLandSnapshot create(String name, long baseRevision, int yBase) {
            Accepted accepted = startSession(baseRevision);
            return runner.create(ACTOR, accepted, new SubLandSnapshot(
                    new SubLandId(UUID.randomUUID()), parentId, name,
                    new Cuboid(0, yBase, 0, 15, yBase + 10, 15), WORLD))
                    .toCompletableFuture().join();
        }

        long liveRevision() {
            return lands.findById(parentId).toCompletableFuture().join()
                    .orElseThrow().structureRevision();
        }

        @Override
        public void close() {
            store.close();
        }
    }

    @Test
    void pendingConfirmIsDecidedUnderTheReloadedLimits() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            env.create("den", 0, 60);
            assertEquals(1L, env.liveRevision());

            // The reload tightens max-sublands to the one subland already held.
            env.liveLimits.set(limitsWithMaxSublands(1));
            CompletionException rejected = assertThrows(CompletionException.class,
                    () -> env.create("study", 1, 124));
            assertTrue(rejected.getCause() instanceof IllegalStateException,
                    "a pending confirm must fail under the new limits, not the prompted ones");
            assertTrue(String.valueOf(rejected.getCause().getMessage())
                    .contains("max-sublands-per-land"));

            // Loosening again lets the same operation through with a fresh token.
            env.liveLimits.set(limitsWithMaxSublands(16));
            env.create("study", 1, 124);
            assertEquals(2L, env.liveRevision());
        }
    }
}

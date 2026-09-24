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
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.command.SubLandCommandHandler;
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
import com.smile.chunkland.persistence.DepthExtendStore;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.vertical.DepthExtendRequest;
import com.smile.chunkland.runtime.vertical.DepthWriteResult;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import com.smile.chunkland.subland.SubLandConfirmService.Accepted;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubLandDepthExtensionFlowTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir Path temp;

    private static final class Env implements AutoCloseable {
        final PersistenceStore store;
        final LandRepository lands;
        final SubLandRepository subLands;
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
        final SubLandDepthConfirmations depthConfirmations =
                new SubLandDepthConfirmations(selections, Clock.fixed(NOW, ZoneOffset.UTC),
                        Duration.ofSeconds(30));
        final List<DepthExtendRequest> requests = new ArrayList<>();
        final DepthExtendStore durableDepths;
        LandId parentId;

        Env(Path path, boolean durable) {
            store = PersistenceStore.open(path);
            lands = new SqliteLandRepository(store);
            subLands = new SqliteSubLandRepository(store);
            audits = new SqliteAuditRepository(store);
            chunks = new SqliteChunkRepository(store);
            durableDepths = new DepthExtendStore(store);
        }

        SubLandMutationRunner runner(boolean durable) {
            SubLandDepthExtendPort port = durable
                    ? durableDepths::extend
                    : request -> {
                        requests.add(request);
                        if (requests.size() == 1) {
                            return CompletableFuture.completedFuture(new DepthWriteResult(
                                    request.chunk(), request.landId(), true,
                                    50, request.requestedDepth(), request.triggeringOperationY()));
                        }
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("fake depth failure"));
                    };
            return new SubLandMutationRunner(
                    lands, new SubLandAtomicCommit(store), registry, selections,
                    confirm, SubLandDepthSource.constant(50), depthConfirmations,
                    LimitSettings.defaults(), Clock.fixed(NOW, ZoneOffset.UTC),
                    null, port, depthConfirmations);
        }

        void saveParent() {
            parentId = new LandId(UUID.randomUUID());
            LandName name = LandName.of("Home");
            Set<ChunkKey> chunks = Set.of(
                    new ChunkKey(WORLD, 0, 0), new ChunkKey(WORLD, 1, 0),
                    new ChunkKey(WORLD, 0, 1), new ChunkKey(WORLD, 1, 1));
            LandSnapshot parent = new LandSnapshot(
                    parentId, name.displayName(), name.nameKey(), OwnerRef.player(ACTOR),
                    WORLD, chunks, List.of(), 0L, 0L, NOW, NOW);
            lands.save(parent).toCompletableFuture().join();
            for (ChunkKey chunk : chunks) {
                this.chunks.addChunk(parentId, chunk, 50, UUID.randomUUID(), 0L)
                        .toCompletableFuture().join();
            }
            registry.publish(LandRegistry.from(List.of(parent)));
        }

        SelectionSession startSession() {
            return selections.start(SelectionSession.initial(
                    ACTOR, WORLD, SelectionMode.CREATE_SUBLAND,
                    Optional.of(parentId), Optional.empty(),
                    Optional.of(new SelectionPoint(WORLD, 0, 30, 0)),
                    Optional.of(new SelectionPoint(WORLD, 31, 70, 31)),
                    0L, NOW));
        }

        Accepted accept(SelectionSession session) {
            return confirm.accept(ACTOR, session.sessionGeneration(), session.selectionRevision(),
                    selections, ignored -> OptionalLong.of(0L)).orElseThrow();
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "Tester";
                    case "hasPermission" -> true;
                    default -> null;
                });
    }

    private static ReplySink sink(
            List<String> keys, List<Map<String, Object>> vars, CountDownLatch done) {
        return new ReplySink() {
            @Override
            public void reply(String key, Map<String, Object> values) {
                keys.add(key);
                vars.add(values);
                done.countDown();
            }

            @Override
            public void reply(String key, Map<String, Object> values, Locale locale) {
                reply(key, values);
            }
        };
    }

    @Test
    void commandExtendUsesTokenAndReturnsTerminalReply() throws Exception {
        try (Env env = new Env(temp.resolve("command.db"), true)) {
            env.saveParent();
            SelectionSession session = env.startSession();
            SubLandCommandHandler handler = new SubLandCommandHandler(
                    env.selections, env.confirm, env.runner(true),
                    ignored -> OptionalLong.of(0L), com.smile.chunkland.subland.SubLandEntryLookup.unavailable());
            List<String> keys = new ArrayList<>();
            List<Map<String, Object>> vars = new ArrayList<>();
            CountDownLatch done = new CountDownLatch(1);

            handler.handle(player(ACTOR), new String[]{
                    "subland", "extend",
                    Long.toString(session.sessionGeneration()),
                    Long.toString(session.selectionRevision())}, sink(keys, vars, done));

            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(List.of("command.land.subland.extended"), keys);
            assertEquals(4, vars.get(0).get("count"));
            assertEquals(1, env.depthConfirmations.size());
            assertTrue(env.confirm.accept(ACTOR, session.sessionGeneration(), session.selectionRevision(),
                    env.selections, ignored -> OptionalLong.of(0L)).isPresent());
        }
    }

    @Test
    void commandDepthRejectionCarriesTheExtendCommand() throws Exception {
        try (Env env = new Env(temp.resolve("depth-command.db"), false)) {
            env.saveParent();
            SelectionSession session = env.startSession();
            SubLandCommandHandler handler = new SubLandCommandHandler(
                    env.selections, env.confirm, env.runner(false),
                    ignored -> OptionalLong.of(0L), com.smile.chunkland.subland.SubLandEntryLookup.unavailable());
            List<String> keys = new ArrayList<>();
            List<Map<String, Object>> vars = new ArrayList<>();
            CountDownLatch done = new CountDownLatch(1);

            handler.handle(player(ACTOR), new String[]{
                    "subland", "create",
                    Long.toString(session.sessionGeneration()),
                    Long.toString(session.selectionRevision()), "deep"},
                    sink(keys, vars, done));

            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(List.of("command.land.subland.confirm_depth"), keys);
            assertEquals("/land subland extend "
                    + session.sessionGeneration() + " " + session.selectionRevision(),
                    vars.get(0).get("value"));
        }
    }

    @Test
    void successfulExtendWritesAuditAndAllowsCreateRetry() {
        try (Env env = new Env(temp.resolve("success.db"), true)) {
            env.saveParent();
            Accepted accepted = env.accept(env.startSession());
            SubLandMutationRunner.DepthExtensionResult result =
                    env.runner(true).extend(ACTOR, accepted).toCompletableFuture().join();

            assertEquals(4, result.chunkCount());
            List<com.smile.chunkland.persistence.AuditEntry> audits =
                    env.audits.findByAction("DEPTH_EXTEND", 10).toCompletableFuture().join();
            assertEquals(4, audits.size());
            assertTrue(audits.stream().allMatch(audit -> audit.actorOpt().equals(Optional.of(ACTOR))));
            assertEquals(1, env.depthConfirmations.size());

            Accepted retry = env.confirm.accept(ACTOR,
                    accepted.session().sessionGeneration(), accepted.session().selectionRevision(),
                    env.selections, ignored -> OptionalLong.of(0L)).orElseThrow();
            SubLandSnapshot candidate = new SubLandSnapshot(
                    new SubLandId(UUID.randomUUID()), env.parentId, "deep",
                    new Cuboid(0, 30, 0, 31, 70, 31), WORLD);
            env.runner(true).create(ACTOR, retry, candidate).toCompletableFuture().join();

            assertTrue(env.subLands.findById(candidate.id()).toCompletableFuture().join().isPresent());
            assertEquals(0, env.depthConfirmations.size(), "create consumes the retry confirmation");
        }
    }

    @Test
    void partialFailureDoesNotRecordConfirmationAndReleasesExtendToken() {
        try (Env env = new Env(temp.resolve("partial.db"), false)) {
            env.saveParent();
            Accepted accepted = env.accept(env.startSession());

            CompletionException failure = assertThrows(CompletionException.class,
                    () -> env.runner(false).extend(ACTOR, accepted).toCompletableFuture().join());

            assertTrue(failure.getCause().getMessage().contains("fake depth failure"));
            assertEquals(4, env.requests.size());
            assertTrue(env.requests.stream().allMatch(request ->
                    request.landId().equals(env.parentId)
                            && request.actor().equals(ACTOR)
                            && request.requestedDepth() == 30
                            && request.triggeringOperationY() == 30));
            assertEquals(0, env.depthConfirmations.size());
            assertTrue(env.confirm.accept(ACTOR,
                    accepted.session().sessionGeneration(), accepted.session().selectionRevision(),
                    env.selections, ignored -> OptionalLong.of(0L)).isPresent(),
                    "extend must release the single-use confirmation token on failure");
        }
    }

    @Test
    void alreadyDeepNoOpIsSuccessfulAndDoesNotAddAudit() {
        try (Env env = new Env(temp.resolve("noop.db"), true)) {
            env.saveParent();
            Accepted first = env.accept(env.startSession());
            env.runner(true).extend(ACTOR, first).toCompletableFuture().join();
            int auditCount = env.audits.findByAction("DEPTH_EXTEND", 10)
                    .toCompletableFuture().join().size();

            Accepted second = env.accept(env.startSession());
            SubLandMutationRunner.DepthExtensionResult result =
                    env.runner(true).extend(ACTOR, second).toCompletableFuture().join();

            assertEquals(4, result.chunkCount());
            assertEquals(auditCount, env.audits.findByAction("DEPTH_EXTEND", 10)
                    .toCompletableFuture().join().size());
            assertEquals(1, env.depthConfirmations.size());
        }
    }
}

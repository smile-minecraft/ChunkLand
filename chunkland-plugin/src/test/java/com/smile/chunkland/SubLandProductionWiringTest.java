package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.smile.chunkland.command.LandCommand;
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
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import com.smile.chunkland.subland.DepthExtensionPort;
import com.smile.chunkland.subland.SubLandConfirmService;
import com.smile.chunkland.subland.SubLandEntryLookup;
import com.smile.chunkland.subland.SubLandDepthSource;
import com.smile.chunkland.subland.SubLandMutationRunner;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production wiring for {@code /land subland}: the formal handler map must
 * route to the real command handler instead of the not-yet stub, and an
 * unwired slot must fail closed instead of pretending the flow is coming.
 */
class SubLandProductionWiringTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir java.nio.file.Path tmp;

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
        final AtomicLong liveRevision = new AtomicLong(0);
        final UUID actor = UUID.randomUUID();
        final UUID world = UUID.randomUUID();
        LandId parentId;

        Env(java.nio.file.Path db) {
            store = PersistenceStore.open(db);
            lands = new SqliteLandRepository(store);
            subs = new SqliteSubLandRepository(store);
            audits = new SqliteAuditRepository(store);
        }

        SubLandMutationRunner runner() {
            return new SubLandMutationRunner(lands,
                    new SubLandAtomicCommit(store),
                    registry, selections,
                    confirm, SubLandDepthSource.constant(50), DepthExtensionPort.denyAll(),
                    LimitSettings.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));
        }

        SelectionStructureRevisionLookup structures() {
            return landId -> OptionalLong.of(liveRevision.get());
        }

        SubLandCommandHandler handler() {
            return new SubLandCommandHandler(selections, confirm, runner(), structures());
        }

        SubLandCommandHandler handler(SubLandEntryLookup entries) {
            return new SubLandCommandHandler(selections, confirm, runner(), structures(), entries);
        }

        void saveParent() {
            parentId = new LandId(UUID.randomUUID());
            LandName name = LandName.of("Home");
            LandSnapshot parent = new LandSnapshot(parentId, name.displayName(), name.nameKey(),
                    OwnerRef.player(actor), world, Set.of(new ChunkKey(world, 0, 0)),
                    List.of(), 0L, 0L, NOW, NOW);
            lands.save(parent).toCompletableFuture().join();
            new SqliteChunkRepository(store)
                    .addChunk(parentId, new ChunkKey(world, 0, 0), 50, UUID.randomUUID(), 0L)
                    .toCompletableFuture().join();
        }

        SelectionSession startSession(long baseRevision, SubLandId target) {
            return selections.start(SelectionSession.initial(
                    actor, world, SelectionMode.CREATE_SUBLAND,
                    Optional.of(parentId), Optional.ofNullable(target),
                    Optional.of(new SelectionPoint(world, 0, 60, 0)),
                    Optional.of(new SelectionPoint(world, 15, 70, 15)),
                    baseRevision, NOW));
        }

        SelectionSession startEmptySession(long baseRevision, SubLandId target) {
            return selections.start(SelectionSession.initial(
                    actor, world, SelectionMode.CREATE_SUBLAND,
                    Optional.of(parentId), Optional.ofNullable(target),
                    Optional.empty(), Optional.empty(), baseRevision, NOW));
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static final class CapturingSink implements ReplySink {
        final CopyOnWriteArrayList<String> keys = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<Map<String, Object>> vars = new CopyOnWriteArrayList<>();
        final CountDownLatch latch;

        CapturingSink(int expected) {
            latch = new CountDownLatch(expected);
        }

        @Override
        public void reply(String messageKey, Map<String, Object> map) {
            keys.add(messageKey);
            vars.add(map);
            latch.countDown();
        }

        @Override
        public void reply(String messageKey, Map<String, Object> map, Locale localeOverride) {
            reply(messageKey, map);
        }

        void await() throws InterruptedException {
            assertEquals(true, latch.await(10, TimeUnit.SECONDS), "handler must reply");
        }
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return id;
                    }
                    if (name.equals("hasPermission")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "Tester";
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    private static Player playerAt(UUID id, UUID worldId, int x, int z) {
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> method.getName().equals("getUID") ? worldId : null);
        Location location = new Location(world, x, 64, z);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getLocation" -> location;
                    case "hasPermission" -> true;
                    case "getName" -> "Tester";
                    default -> null;
                });
    }

    @Test
    void selectRejectsLandOwnedByAnotherPlayer() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            LandSnapshot parent = env.lands.findById(env.parentId).toCompletableFuture().join().orElseThrow();
            UUID other = UUID.randomUUID();
            CapturingSink sink = new CapturingSink(1);

            env.handler((worldId, blockX, blockZ) -> Optional.of(parent)).handle(
                    playerAt(other, env.world, 0, 0),
                    new String[]{"subland", "select"}, sink);

            sink.await();
            assertEquals("command.land.subland.no_selection", sink.keys.get(0));
            assertTrue(env.selections.sessionFor(other).isEmpty());
        }
    }

    @Test
    void selectCreatesCreateSubLandSessionForOwnedLand() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            LandSnapshot parent = env.lands.findById(env.parentId).toCompletableFuture().join().orElseThrow();
            CapturingSink sink = new CapturingSink(1);

            env.handler((worldId, blockX, blockZ) -> Optional.of(parent)).handle(
                    playerAt(env.actor, env.world, 0, 0),
                    new String[]{"subland", "select"}, sink);

            sink.await();
            assertEquals("command.land.subland.selected", sink.keys.get(0));
            SelectionSession session = env.selections.sessionFor(env.actor).orElseThrow();
            assertEquals(SelectionMode.CREATE_SUBLAND, session.mode());
            assertEquals(Optional.of(parent.id()), session.targetLandId());
            assertEquals(parent.structureRevision(), session.baseStructureRevision());
            assertTrue(session.pointA().isEmpty());
            assertTrue(session.pointB().isEmpty());
        }
    }

    @Test
    void selectNameRequiresOneUniqueExistingSubLand() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            SubLandSnapshot den = new SubLandSnapshot(
                    new SubLandId(UUID.randomUUID()), env.parentId, "den",
                    new Cuboid(0, 60, 0, 15, 70, 15), env.world);
            env.subs.save(den).toCompletableFuture().join();
            LandSnapshot parent = env.lands.findById(env.parentId).toCompletableFuture().join().orElseThrow();
            SubLandEntryLookup entries = (worldId, blockX, blockZ) -> Optional.of(parent);

            CapturingSink success = new CapturingSink(1);
            env.handler(entries).handle(playerAt(env.actor, env.world, 0, 0),
                    new String[]{"subland", "select", "den"}, success);
            success.await();
            assertEquals("command.land.subland.selected", success.keys.get(0));
            assertEquals(Optional.of(den.id()),
                    env.selections.sessionFor(env.actor).orElseThrow().targetSubLandId());

            env.selections.cancel(env.actor);
            CapturingSink missingSink = new CapturingSink(1);
            env.handler(entries).handle(playerAt(env.actor, env.world, 0, 0),
                    new String[]{"subland", "select", "missing"}, missingSink);
            missingSink.await();
            assertEquals("command.land.subland.no_selection", missingSink.keys.get(0));
            assertTrue(env.selections.sessionFor(env.actor).isEmpty());

            SubLandSnapshot duplicate = new SubLandSnapshot(
                    new SubLandId(UUID.randomUUID()), env.parentId, "den",
                    new Cuboid(16, 60, 0, 31, 70, 15), env.world);
            env.subs.save(duplicate).toCompletableFuture().join();
            LandSnapshot ambiguous = env.lands.findById(env.parentId).toCompletableFuture().join().orElseThrow();
            CapturingSink duplicateSink = new CapturingSink(1);
            env.handler((worldId, blockX, blockZ) -> Optional.of(ambiguous)).handle(
                    playerAt(env.actor, env.world, 0, 0),
                    new String[]{"subland", "select", "den"}, duplicateSink);
            duplicateSink.await();
            assertEquals("command.land.subland.no_selection", duplicateSink.keys.get(0));
            assertTrue(env.selections.sessionFor(env.actor).isEmpty());
        }
    }

    @Test
    void createWithoutTokensReturnsLiveSessionPreview() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            SelectionSession session = env.startSession(0L, null);
            CapturingSink sink = new CapturingSink(1);

            env.handler().handle(player(env.actor),
                    new String[]{"subland", "create", "den"}, sink);

            sink.await();
            assertEquals("command.land.subland.preview", sink.keys.get(0));
            assertEquals(session.sessionGeneration(), sink.vars.get(0).get("generation"));
            assertEquals(session.selectionRevision(), sink.vars.get(0).get("revision"));
            assertEquals("/land subland create " + session.sessionGeneration()
                    + " " + session.selectionRevision() + " den",
                    sink.vars.get(0).get("value"));
            assertTrue(env.confirm.accept(env.actor, session.sessionGeneration(),
                    session.selectionRevision(), env.selections, ignored -> OptionalLong.of(0L))
                    .isPresent(), "preview must not consume the live SubLand token");
        }
    }

    @Test
    void deletePreviewUsesOperationNameInsteadOfEmptyName() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            env.startSession(0L, null);
            CapturingSink sink = new CapturingSink(1);

            env.handler().handle(player(env.actor),
                    new String[]{"subland", "delete"}, sink);

            sink.await();
            assertEquals("command.land.subland.preview", sink.keys.get(0));
            assertEquals("delete", sink.vars.get(0).get("land_name"));
            assertEquals("/land subland delete " + env.selections.sessionFor(env.actor)
                            .orElseThrow().sessionGeneration() + " "
                            + env.selections.sessionFor(env.actor).orElseThrow().selectionRevision(),
                    sink.vars.get(0).get("value"));
            assertFalse(String.valueOf(sink.vars.get(0).get("land_name")).isBlank());
        }
    }

    @Test
    void updatePreviewKeepsTheNameInNextCommand() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            SelectionSession session = env.startSession(0L, null);
            CapturingSink sink = new CapturingSink(1);

            env.handler().handle(player(env.actor),
                    new String[]{"subland", "update", "study"}, sink);

            sink.await();
            assertEquals("/land subland update " + session.sessionGeneration()
                    + " " + session.selectionRevision() + " study",
                    sink.vars.get(0).get("value"));
        }
    }

    @Test
    void extendWithoutPointsFailsClosedBeforeMutation() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            env.startEmptySession(0L, null);
            CapturingSink sink = new CapturingSink(1);

            env.handler().handle(player(env.actor),
                    new String[]{"subland", "extend", "0", "0"}, sink);

            sink.await();
            assertEquals("command.land.subland.no_selection", sink.keys.get(0));
        }
    }

    @Test
    void selectWithoutNameWithoutLandFailsClosed() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            CapturingSink sink = new CapturingSink(1);

            env.handler().handle(player(env.actor),
                    new String[]{"subland", "select"}, sink);

            sink.await();
            assertEquals("command.land.subland.no_selection", sink.keys.get(0));
        }
    }

    @Test
    void formalHandlerMapRoutesSublandToRealHandler() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            SubLandCommandHandler handler = env.handler();
            Map<String, LandCommand.Handler> handlers = ChunkLandPlugin.buildLandHandlers(
                    env.selections, null, null, SelectionStructureRevisionLookup.unavailable(),
                    handler);
            assertInstanceOf(SubLandCommandHandler.class, handlers.get("subland"),
                    "formal /land wiring must assemble the real subland handler, not the stub");
        }
    }

    @Test
    void unwiredSublandSlotFailsClosedInsteadOfStub() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            Map<String, LandCommand.Handler> handlers = ChunkLandPlugin.buildLandHandlers(
                    env.selections, null, null, SelectionStructureRevisionLookup.unavailable());
            CommandSender sender = player(env.actor);
            CapturingSink sink = new CapturingSink(1);
            handlers.get("subland").handle(sender, new String[]{"subland"}, sink);
            sink.await();
            assertEquals(1, sink.keys.size());
            assertNotEquals("command.land.not_yet", sink.keys.get(0),
                    "an unwired subland slot must fail closed, never fall back to the stub");
            assertEquals("command.land.subland.failed", sink.keys.get(0));
            assertEquals("subland.unavailable", sink.vars.get(0).get("reason"));
        }
    }

    @Test
    void sublandCreateThroughFormalWiringDoesNotAnswerNotYet() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            SelectionSession session = env.startSession(0L, null);
            Map<String, LandCommand.Handler> handlers = ChunkLandPlugin.buildLandHandlers(
                    env.selections, null, null, SelectionStructureRevisionLookup.unavailable(),
                    env.handler());
            CapturingSink sink = new CapturingSink(1);
            String[] args = new String[]{
                    "subland", "create",
                    Long.toString(session.sessionGeneration()),
                    Long.toString(session.selectionRevision()),
                    "den"};
            handlers.get("subland").handle(player(env.actor), args, sink);
            sink.await();
            assertEquals(1, sink.keys.size());
            assertNotEquals("command.land.not_yet", sink.keys.get(0),
                    "formal /land subland must reach the real handler, never the stub");
            assertEquals("command.land.subland.created", sink.keys.get(0));
            assertEquals(1, env.subs.findByLand(env.parentId)
                    .toCompletableFuture().join().size());
            // Regression guard: the expand slot stays fail-closed without a
            // runner, never falling back to the legacy not-yet stub.
            CapturingSink expandSink = new CapturingSink(1);
            handlers.get("expand").handle(player(env.actor), new String[]{"expand"}, expandSink);
            expandSink.await();
            assertEquals("command.land.expand.failed", expandSink.keys.get(0));
        }
    }

    @Test
    void sublandUpdateAndDeleteThroughFormalWiring() throws Exception {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            SelectionSession created = env.startSession(0L, null);
            Map<String, LandCommand.Handler> handlers = ChunkLandPlugin.buildLandHandlers(
                    env.selections, null, null, SelectionStructureRevisionLookup.unavailable(),
                    env.handler());
            CommandSender sender = player(env.actor);
            CapturingSink createSink = new CapturingSink(1);
            handlers.get("subland").handle(sender,
                    new String[]{"subland", "create",
                            Long.toString(created.sessionGeneration()),
                            Long.toString(created.selectionRevision()), "den"},
                    createSink);
            createSink.await();
            assertEquals("command.land.subland.created", createSink.keys.get(0));
            env.liveRevision.set(1L);
            SubLandId target = env.subs.findByLand(env.parentId)
                    .toCompletableFuture().join().get(0).id();

            // Update targets the stored SubLand through a fresh session.
            env.selections.cancel(env.actor);
            SelectionSession updating = env.startSession(1L, target);
            CapturingSink updateSink = new CapturingSink(1);
            handlers.get("subland").handle(sender,
                    new String[]{"subland", "update",
                            Long.toString(updating.sessionGeneration()),
                            Long.toString(updating.selectionRevision()), "study"},
                    updateSink);
            updateSink.await();
            assertEquals("command.land.subland.updated", updateSink.keys.get(0));
            env.liveRevision.set(2L);

            // Delete clears the row through a fresh session.
            env.selections.cancel(env.actor);
            SelectionSession deleting = env.startSession(2L, target);
            CapturingSink deleteSink = new CapturingSink(1);
            handlers.get("subland").handle(sender,
                    new String[]{"subland", "delete",
                            Long.toString(deleting.sessionGeneration()),
                            Long.toString(deleting.selectionRevision())},
                    deleteSink);
            deleteSink.await();
            assertEquals("command.land.subland.deleted", deleteSink.keys.get(0));
            assertEquals(Optional.empty(), env.subs.findById(target)
                    .toCompletableFuture().join().map(s -> s.id()));
        }
    }

    @Test
    void productionRunnerFactoryFailsClosedOnMissingParts() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            assertNull(ChunkLandPlugin.buildSubLandRunner(null, env.registry, env.selections,
                    env.confirm, SubLandDepthSource.constant(50), LimitSettings.defaults(),
                    Clock.systemUTC()),
                    "a missing persistence store must keep subland fail-closed");
            assertNull(ChunkLandPlugin.buildSubLandRunner(env.store, null, env.selections,
                    env.confirm, SubLandDepthSource.constant(50), LimitSettings.defaults(),
                    Clock.systemUTC()),
                    "a missing registry must keep subland fail-closed");
            assertNull(ChunkLandPlugin.buildSubLandHandler(env.selections, env.confirm, null,
                    SelectionStructureRevisionLookup.unavailable()),
                    "a missing runner must keep the handler fail-closed");
        }
    }

    @Test
    void productionDepthSourceFailsClosedWithoutStore() {
        SubLandDepthSource missing = ChunkLandPlugin.buildSubLandDepthSource(null);
        LandSnapshot parent = new LandSnapshot(new LandId(UUID.randomUUID()), "Home", "home",
                OwnerRef.player(UUID.randomUUID()), UUID.randomUUID(),
                Set.of(), List.of(), 0L, 0L, NOW, NOW);
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> missing.effectiveMinProtectedY(parent));
        assertTrue(failure.getMessage().contains("unavailable"));
    }

    @Test
    void productionDepthSourceReadsStoredDepths() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            LandSnapshot parent = env.lands.findById(env.parentId)
                    .toCompletableFuture().join().orElseThrow();
            ChunkKey chunk = parent.chunks().iterator().next();
            env.registry.publish(LandRegistry.fromWithDepths(
                    List.of(parent), Map.of(chunk, 42)));
            assertEquals(42, ChunkLandPlugin.buildSubLandDepthSource(env.registry)
                    .effectiveMinProtectedY(parent));
            // Unknown chunks fall back instead of guessing zero.
            env.registry.publish(LandRegistry.from(List.of(parent)));
            assertEquals(64, ChunkLandPlugin.buildSubLandDepthSource(env.registry)
                    .effectiveMinProtectedY(parent));
        }
    }

    @Test
    void productionStructureLookupTracksPublishedRevisions() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.saveParent();
            LandSnapshot parent = env.lands.findById(env.parentId)
                    .toCompletableFuture().join().orElseThrow();
            SelectionStructureRevisionLookup lookup =
                    ChunkLandPlugin.buildSubLandStructureLookup(env.registry);
            assertEquals(OptionalLong.empty(), lookup.currentRevision(env.parentId),
                    "an unpublished parent must read empty so confirmation fails closed");
            env.registry.publish(LandRegistry.from(List.of(parent)));
            assertEquals(OptionalLong.of(0L), lookup.currentRevision(env.parentId));
            assertEquals(OptionalLong.empty(),
                    ChunkLandPlugin.buildSubLandStructureLookup(null)
                            .currentRevision(env.parentId),
                    "a missing store must fail closed");
        }
    }
}

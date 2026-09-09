package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.ChunkLandPlugin;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.claim.ClaimOutcome;
import com.smile.chunkland.claim.ClaimRequest;
import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionUpdate;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Chat confirmation ({@code /land confirm <revision> <name>}) revalidation
 * and stale-click protection.
 *
 * <p>The token is the {@code selectionRevision} the confirmation message was
 * rendered for, bound to the sender's own live session: every dispatch
 * re-reads the current session, re-checks the selection revision and the
 * resolvable structure revision, and only then enters the saga. Replays,
 * foreign or malformed tokens, replaced or expired sessions, and structure
 * changes are rejected without saga side effects, and a repeat click on the
 * same live session is consumed by a single-use mark.
 */
class ConfirmCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    static final class Reply {
        final String key;
        final Map<String, Object> vars;

        Reply(String key, Map<String, Object> vars) {
            this.key = key;
            this.vars = Map.copyOf(vars);
        }
    }

    /** Reply sink that releases a latch on every reply so tests wait deterministically. */
    static final class LatchSink implements ReplySink {
        final List<Reply> replies = new CopyOnWriteArrayList<>();
        final CountDownLatch latch;

        LatchSink(int expectedReplies) {
            this.latch = new CountDownLatch(expectedReplies);
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vars) {
            replies.add(new Reply(messageKey, vars));
            latch.countDown();
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
            reply(messageKey, vars);
        }

        void awaitReplies() throws Exception {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "handler must reply the expected times");
        }
    }

    static final class CapturingRunner implements ClaimCommandHandler.ClaimRunner {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<ClaimRequest> lastRequest = new AtomicReference<>();
        volatile ClaimOutcome next = ClaimOutcome.failed("claim.failed");

        @Override
        public CompletionStage<ClaimOutcome> claim(ClaimRequest request) {
            calls.incrementAndGet();
            lastRequest.set(request);
            return CompletableFuture.completedFuture(next);
        }
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return id;
                    }
                    if (name.equals("hasPermission")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "TestPlayer";
                    }
                    if (name.equals("equals") || name.equals("hashCode") || name.equals("toString")) {
                        return switch (name) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "Player-proxy:" + id;
                        };
                    }
                    Class<?> result = method.getReturnType();
                    if (result == boolean.class) {
                        return false;
                    }
                    if (result == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    private static CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class[]{CommandSender.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("hasPermission")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "Console";
                    }
                    Class<?> result = method.getReturnType();
                    if (result == boolean.class) {
                        return false;
                    }
                    if (result == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    private static SelectionSessionManager selections() {
        return selectionsWithStructures(SelectionStructureRevisionLookup.unavailable());
    }

    /**
     * Manager backed by an explicit structure source. Targeted sessions can
     * only be created and updated while the source resolves the base
     * revision, mirroring the production wiring where the manager and the
     * confirmation handler share one live lookup instance.
     */
    private static SelectionSessionManager selectionsWithStructures(
            SelectionStructureRevisionLookup structures) {
        return new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                (SelectionClock) () -> NOW,
                Duration.ofMinutes(10),
                ignored -> Optional.of("world"),
                structures);
    }

    /** Start a target-less session with exactly one selected chunk. */
    private static SelectionSession selectSingleChunk(SelectionSessionManager manager, UUID actor, UUID world) {
        SelectionSession initial = SelectionSession.initial(
                actor, world, SelectionMode.CREATE_LAND,
                Optional.empty(), Optional.empty(),
                Optional.of(new SelectionPoint(world, 0, 64, 0)),
                Optional.of(new SelectionPoint(world, 16, 64, 16)),
                0, NOW);
        SelectionSession stamped = manager.start(initial);
        return manager.updateSelection(actor, stamped, new SelectionUpdate(
                        stamped.pointA(), stamped.pointB(),
                        Set.of(new ChunkKey(world, 3, 4)), Map.of()))
                .orElseThrow(() -> new IllegalStateException("selection update must succeed"));
    }

    /** Start a session targeting an existing land at the given base structure revision. */
    private static SelectionSession selectTargetedChunk(SelectionSessionManager manager, UUID actor,
            UUID world, LandId target, long baseStructureRevision) {
        SelectionSession initial = SelectionSession.initial(
                actor, world, SelectionMode.EDIT_SELECTION,
                Optional.of(target), Optional.empty(),
                Optional.of(new SelectionPoint(world, 0, 64, 0)),
                Optional.of(new SelectionPoint(world, 16, 64, 16)),
                baseStructureRevision, NOW);
        SelectionSession stamped = manager.start(initial);
        return manager.updateSelection(actor, stamped, new SelectionUpdate(
                        stamped.pointA(), stamped.pointB(),
                        Set.of(new ChunkKey(world, 5, 6)), Map.of()))
                .orElseThrow(() -> new IllegalStateException("selection update must succeed"));
    }

    private static Map<String, LandCommand.Handler> wiredHandlers(
            SelectionSessionManager manager, ClaimCommandHandler.ClaimRunner runner) {
        try {
            Method factory = ChunkLandPlugin.class.getDeclaredMethod(
                    "buildLandHandlers", SelectionSessionManager.class,
                    ClaimCommandHandler.ClaimRunner.class);
            factory.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, LandCommand.Handler> handlers =
                    (Map<String, LandCommand.Handler>) factory.invoke(null, manager, runner);
            return handlers;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("production handler factory must stay reachable", failure);
        }
    }

    private static LandCommand commandWith(Map<String, LandCommand.Handler> handlers, LatchSink sink) {
        return new LandCommand(handlers, (sender, pipeline) -> sink);
    }

    private static String[] confirmArgs(long generation, long revision, String name) {
        return new String[]{"confirm", Long.toString(generation), Long.toString(revision), name};
    }

    // ------------------------------------------------------------------
    // Wiring: the production map no longer answers with the stub
    // ------------------------------------------------------------------

    @Test
    void wiredConfirmIsNoLongerAStub() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(wiredHandlers(manager, runner), sink)
                .dispatch(player(actor), confirmArgs(live.sessionGeneration(), live.selectionRevision(), "Home"), null));
        sink.awaitReplies();

        assertEquals(1, sink.replies.size());
        assertNotEquals("command.land.not_yet", sink.replies.get(0).key,
                "wired /land confirm must not answer with the not_yet stub");
        assertEquals(1, runner.calls.get());
    }

    @Test
    void productionMapWiresConfirmHandler() {
        SelectionSessionManager manager = selections();
        Map<String, LandCommand.Handler> handlers = wiredHandlers(manager, new CapturingRunner());
        assertTrue(handlers.get("confirm") instanceof ConfirmCommandHandler,
                "production wiring must route confirm to the revalidating handler");
    }

    @Test
    void confirmClickPayloadCarriesRevisionAndLandName() throws Exception {
        YamlConfiguration en = new YamlConfiguration();
        en.load(new File("src/main/resources/lang/en_US.yml"));
        String confirm = en.getString("land.claim.confirm");
        assertNotNull(confirm);
        assertTrue(confirm.contains("/land confirm <generation> <revision>"),
                "click payload must keep the generation and revision tokens");
        int clickStart = confirm.indexOf("<click:run_command:'");
        assertTrue(clickStart >= 0, "confirm template must keep a run_command click");
        int clickEnd = confirm.indexOf("'", clickStart + "<click:run_command:'".length());
        String clickPayload = confirm.substring(clickStart, clickEnd);
        assertTrue(clickPayload.contains("<land_name>"),
                "click payload must carry the land name so the handler can confirm it: " + clickPayload);
    }

    // ------------------------------------------------------------------
    // Success path pins the token to the live selection
    // ------------------------------------------------------------------

    @Test
    void validRevisionEntersRunnerWithPinnedToken() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        runner.next = ClaimOutcome.success(new LandId(UUID.randomUUID()));
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), confirmArgs(live.sessionGeneration(), live.selectionRevision(), "Home"), null));
        sink.awaitReplies();

        assertEquals(1, runner.calls.get());
        ClaimRequest request = runner.lastRequest.get();
        assertNotNull(request);
        assertEquals(live.selectionRevision(), request.selectionRevision());
        assertEquals(live.sessionGeneration(), request.sessionGeneration());
        assertEquals(live.selectedChunks(), request.chunks());
        assertEquals("Home", request.displayName());
        assertEquals(actor, request.actorUuid());
        assertNull(request.targetLandId());
        assertNull(request.structureRevision());
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.claim.success", sink.replies.get(0).key);
        assertEquals("Home", sink.replies.get(0).vars.get("land_name"));
        assertEquals(1, sink.replies.get(0).vars.get("chunk_count"));
    }

    // ------------------------------------------------------------------
    // Fail-closed inputs never reach the runner
    // ------------------------------------------------------------------

    @Test
    void consoleSenderIsRejected() throws Exception {
        SelectionSessionManager manager = selections();
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(console(), new String[]{"confirm", "0", "1", "Home"}, null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.console", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    @Test
    void missingTokenRepliesUsage() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), new String[]{"confirm"}, null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.usage", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    @Test
    void nonNumericTokenRepliesUsage() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), new String[]{"confirm", "0", "seven", "Home"}, null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.usage", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    @Test
    void nonNumericGenerationRepliesUsage() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), new String[]{"confirm", "seven", "1", "Home"}, null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.usage", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    @Test
    void negativeTokenRepliesUsage() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), new String[]{"confirm", "0", "-3", "Home"}, null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.usage", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    @Test
    void negativeGenerationRepliesUsage() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), new String[]{"confirm", "-1", "1", "Home"}, null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.usage", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    @Test
    void missingNameRepliesUsage() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), new String[]{"confirm", Long.toString(live.sessionGeneration()),
                        Long.toString(live.selectionRevision())}, null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.usage", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    @Test
    void missingSelectionRepliesNoSelection() throws Exception {
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), new String[]{"confirm", "0", "0", "Home"}, null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.no_selection", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    @Test
    void expiredSessionRepliesNoSelection() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        assertTrue(manager.cancel(actor), "test must expire the session first");
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), confirmArgs(live.sessionGeneration(), live.selectionRevision(), "Home"), null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.no_selection", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    // ------------------------------------------------------------------
    // Stale tokens never reach the runner
    // ------------------------------------------------------------------

    @Test
    void wrongRevisionIsStale() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), confirmArgs(live.sessionGeneration(), live.selectionRevision() + 1, "Home"), null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.stale", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    @Test
    void foreignRevisionIsStaleAgainstOwnSession() throws Exception {
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession ownerLive = selectSingleChunk(manager, owner, world);
        SelectionSession intruderLive = selectSingleChunk(manager, intruder, world);
        // Fresh sessions share revision 1; advance the intruder once so the
        // foreign token provably mismatches the intruder's own live revision.
        SelectionSession intruderAdvanced = manager.updateSelection(intruder, intruderLive, new SelectionUpdate(
                        intruderLive.pointA(), intruderLive.pointB(),
                        Set.of(new ChunkKey(world, 3, 4), new ChunkKey(world, 3, 5)), Map.of()))
                .orElseThrow(() -> new IllegalStateException("intruder edit must succeed"));
        assertNotEquals(ownerLive.selectionRevision(), intruderAdvanced.selectionRevision(),
                "test needs distinct revisions to prove the token is a self-check");
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(intruder), confirmArgs(ownerLive.sessionGeneration(),
                        ownerLive.selectionRevision(), "IntruderHome"), null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.stale", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    @Test
    void replacedSessionInvalidatesOldToken() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession first = selectSingleChunk(manager, actor, world);
        long staleGeneration = first.sessionGeneration();
        long staleToken = first.selectionRevision();
        // Replacement reuses the same numeric revision (fresh session edited
        // once) on a new generation; the old pair must still go stale.
        SelectionSession replacement = selectSingleChunk(manager, actor, world);
        assertEquals(staleToken, replacement.selectionRevision(),
                "test needs numeric revision reuse to prove generation binding");
        assertNotEquals(staleGeneration, replacement.sessionGeneration(),
                "replacement must advance the generation");
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), confirmArgs(staleGeneration, staleToken, "Home"), null));
        sink.awaitReplies();

        assertEquals(1, manager.size(), "replacement must leave exactly one live session");
        assertEquals("command.land.confirm.stale", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    @Test
    void wrongGenerationIsStaleAgainstReusedRevision() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor),
                        confirmArgs(live.sessionGeneration() + 1, live.selectionRevision(), "Home"), null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.stale", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    @Test
    void editedSelectionInvalidatesOldToken() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        long staleGeneration = live.sessionGeneration();
        long staleToken = live.selectionRevision();
        assertTrue(manager.updateSelection(actor, live, new SelectionUpdate(
                        live.pointA(), live.pointB(),
                        Set.of(new ChunkKey(world, 3, 4), new ChunkKey(world, 3, 5)), Map.of()))
                .isPresent(), "test must advance the revision first");
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), confirmArgs(staleGeneration, staleToken, "Home"), null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.stale", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    // ------------------------------------------------------------------
    // Single-use mark: replays and races admit exactly one saga entry
    // ------------------------------------------------------------------

    @Test
    void repeatClickIsConsumedWithoutSecondSagaEntry() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        runner.next = ClaimOutcome.success(new LandId(UUID.randomUUID()));
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(2);
        LandCommand command = commandWith(Map.of("confirm", handler), sink);

        assertTrue(command.dispatch(player(actor), confirmArgs(live.sessionGeneration(), live.selectionRevision(), "Home"), null));
        // LatchSink(2) needs both replies; second dispatch below releases the second count.
        assertTrue(command.dispatch(player(actor), confirmArgs(live.sessionGeneration(), live.selectionRevision(), "Home"), null));
        sink.awaitReplies();

        assertEquals(1, runner.calls.get(), "repeat click must not re-enter the saga");
        assertEquals(2, sink.replies.size());
        assertEquals("command.land.claim.success", sink.replies.get(0).key);
        assertEquals("command.land.confirm.stale", sink.replies.get(1).key);
    }

    @Test
    void rejectedSagaReleasesMarkSoRetryIsPossible() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        runner.next = ClaimOutcome.rejected("limit.reached");
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(2);
        LandCommand command = commandWith(Map.of("confirm", handler), sink);

        assertTrue(command.dispatch(player(actor), confirmArgs(live.sessionGeneration(), live.selectionRevision(), "Home"), null));
        assertTrue(command.dispatch(player(actor), confirmArgs(live.sessionGeneration(), live.selectionRevision(), "Home"), null));
        sink.awaitReplies();

        assertEquals(2, runner.calls.get(), "a rejected claim must stay retryable on the same revision");
        assertEquals(2, sink.replies.size());
        assertEquals("command.land.claim.rejected", sink.replies.get(0).key);
        assertEquals("command.land.claim.rejected", sink.replies.get(1).key);
    }

    @Test
    void concurrentDoubleClickAdmitsExactlyOneSagaEntry() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        runner.next = ClaimOutcome.success(new LandId(UUID.randomUUID()));
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(2);
        LandCommand command = commandWith(Map.of("confirm", handler), sink);
        String[] args = confirmArgs(live.sessionGeneration(), live.selectionRevision(), "Home");

        CyclicBarrier gate = new CyclicBarrier(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable task = () -> {
            try {
                gate.await(10, TimeUnit.SECONDS);
                command.dispatch(player(actor), args, null);
            } catch (Throwable boom) {
                failure.compareAndSet(null, boom);
            }
        };
        Thread first = new Thread(task, "confirm-race-1");
        Thread second = new Thread(task, "confirm-race-2");
        first.start();
        second.start();
        first.join(10_000);
        second.join(10_000);
        assertFalse(first.isAlive() || second.isAlive(), "racing confirms must terminate");
        assertNull(failure.get(), "racing confirms must not throw");
        sink.awaitReplies();

        assertEquals(1, runner.calls.get(), "concurrent clicks must admit exactly one saga entry");
        assertEquals(2, sink.replies.size());
        long successCount = sink.replies.stream()
                .filter(reply -> reply.key.equals("command.land.claim.success")).count();
        long staleCount = sink.replies.stream()
                .filter(reply -> reply.key.equals("command.land.confirm.stale")).count();
        assertEquals(1, successCount);
        assertEquals(1, staleCount);
    }

    // ------------------------------------------------------------------
    // Structure revalidation for targeted sessions
    // ------------------------------------------------------------------

    @Test
    void matchingStructureRevisionEntersRunner() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());
        SelectionSessionManager manager = selectionsWithStructures(landId -> OptionalLong.of(7L));
        SelectionSession live = selectTargetedChunk(manager, actor, world, target, 7L);
        CapturingRunner runner = new CapturingRunner();
        runner.next = ClaimOutcome.success(new LandId(UUID.randomUUID()));
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, landId -> OptionalLong.of(7L));
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), confirmArgs(live.sessionGeneration(), live.selectionRevision(), "Annex"), null));
        sink.awaitReplies();

        assertEquals(1, runner.calls.get());
        ClaimRequest request = runner.lastRequest.get();
        assertEquals(target, request.targetLandId());
        assertEquals(7L, request.structureRevision());
        assertEquals("command.land.claim.success", sink.replies.get(0).key);
    }

    @Test
    void changedStructureIsStale() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());
        SelectionSessionManager manager = selectionsWithStructures(landId -> OptionalLong.of(7L));
        SelectionSession live = selectTargetedChunk(manager, actor, world, target, 7L);
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, landId -> OptionalLong.of(8L));
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), confirmArgs(live.sessionGeneration(), live.selectionRevision(), "Annex"), null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.stale", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    @Test
    void unresolvableStructureFailsClosed() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());
        SelectionSessionManager manager = selectionsWithStructures(landId -> OptionalLong.of(7L));
        SelectionSession live = selectTargetedChunk(manager, actor, world, target, 7L);
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), confirmArgs(live.sessionGeneration(), live.selectionRevision(), "Annex"), null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.stale", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get(), "an unknown target must never pass by default");
    }

    @Test
    void failingStructureLookupFailsClosed() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());
        SelectionSessionManager manager = selectionsWithStructures(landId -> OptionalLong.of(7L));
        SelectionSession live = selectTargetedChunk(manager, actor, world, target, 7L);
        CapturingRunner runner = new CapturingRunner();
        SelectionStructureRevisionLookup failing = landId -> {
            throw new RuntimeException("lookup boom");
        };
        ConfirmCommandHandler handler = new ConfirmCommandHandler(manager, runner, null, failing);
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), confirmArgs(live.sessionGeneration(), live.selectionRevision(), "Annex"), null));
        sink.awaitReplies();

        assertEquals("command.land.confirm.stale", sink.replies.get(0).key);
        assertEquals(0, runner.calls.get());
    }

    // ------------------------------------------------------------------
    // Unwired and gated entries stay fail-closed without side effects
    // ------------------------------------------------------------------

    @Test
    void unwiredRunnerRepliesUnavailable() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, null, null, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), confirmArgs(live.sessionGeneration(), live.selectionRevision(), "Home"), null));
        sink.awaitReplies();

        assertEquals("command.land.claim.failed", sink.replies.get(0).key);
        assertEquals("claim.unavailable", sink.replies.get(0).vars.get("reason"));
    }

    @Test
    void recoveryPendingBlocksWithoutSagaEntry() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        CompletableFuture<Object> pending = new CompletableFuture<>();
        ConfirmCommandHandler handler =
                new ConfirmCommandHandler(manager, runner, () -> pending, SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(Map.of("confirm", handler), sink)
                .dispatch(player(actor), confirmArgs(live.sessionGeneration(), live.selectionRevision(), "Home"), null));
        sink.awaitReplies();

        assertEquals("command.land.claim.failed", sink.replies.get(0).key);
        assertEquals("claim.recovery_pending", sink.replies.get(0).vars.get("reason"));
        assertEquals(0, runner.calls.get());
    }
}

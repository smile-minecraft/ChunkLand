package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormResponseStatus;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormSpec;
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
import java.util.HashMap;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Bedrock {@code /land claim <name>} Modal Form confirmation.
 *
 * <p>A Bedrock player must receive an AceLib public Modal Form carrying the
 * same-source preview (land name, chunk count, generation/revision tokens;
 * price, limit, lowest height and refund stay explicitly unavailable until a
 * formal preview source exists) instead of entering the saga directly. Only a
 * {@code VALID} response with button index {@code 0} enters the shared
 * token revalidation, replay guard and saga path; every cancel, close,
 * invalid, send or callback failure stays fail-closed with no saga, ledger or
 * economy side effect. Java senders keep the direct claim path.
 */
class BedrockClaimFormHandlerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    static final class Reply {
        final String key;
        final Map<String, Object> vars;

        Reply(String key, Map<String, Object> vars) {
            this.key = key;
            this.vars = Map.copyOf(vars);
        }
    }

    /** Plain recording sink for form-shown paths where no chat reply is expected. */
    static class RecordingSink implements ReplySink {
        final List<Reply> replies = new CopyOnWriteArrayList<>();

        @Override
        public void reply(String messageKey, Map<String, Object> vars) {
            replies.add(new Reply(messageKey, vars));
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
            reply(messageKey, vars);
        }
    }

    /** Reply sink that releases a latch on every reply so tests wait deterministically. */
    static final class LatchSink extends RecordingSink {
        final CountDownLatch latch;

        LatchSink(int expectedReplies) {
            this.latch = new CountDownLatch(expectedReplies);
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vars) {
            super.reply(messageKey, vars);
            latch.countDown();
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

    static final class FakeBedrockLookup implements BedrockClaimFormHandler.BedrockLookup {
        final AtomicLong lookups = new AtomicLong();
        volatile boolean bedrock;
        volatile boolean throwLookup;

        @Override
        public boolean isBedrock(UUID playerId) {
            lookups.incrementAndGet();
            if (throwLookup) {
                throw new IllegalStateException("bedrock lookup down");
            }
            return bedrock;
        }
    }

    static final class FakeFormSender implements BedrockClaimFormHandler.FormSender {
        final AtomicLong sends = new AtomicLong();
        volatile FormSendResult next = FormSendResult.SENT;
        volatile boolean throwSend;
        volatile boolean deliverOnSend;
        volatile FormResponse scripted;
        volatile boolean swallowCallbackThrow = true;
        volatile UUID lastUuid;
        volatile FormSpec lastSpec;
        volatile Consumer<FormResponse> lastCallback;

        @Override
        public FormSendResult send(UUID playerId, FormSpec spec, Consumer<FormResponse> callback) {
            sends.incrementAndGet();
            lastUuid = playerId;
            lastSpec = spec;
            lastCallback = callback;
            if (throwSend) {
                throw new IllegalStateException("form send down");
            }
            if (deliverOnSend && callback != null && scripted != null) {
                try {
                    callback.accept(scripted);
                } catch (RuntimeException failure) {
                    if (!swallowCallbackThrow) {
                        throw failure;
                    }
                }
            }
            return next;
        }

        void fire(FormResponse response) {
            assertNotNull(lastCallback, "a form must have been sent before firing a response");
            lastCallback.accept(response);
        }
    }

    private static Player player(UUID id) {
        return playerWithPermissions(id, true);
    }

    private static Player playerWithPermissions(UUID id, boolean grant) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return id;
                    }
                    if (name.equals("hasPermission")) {
                        return grant;
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

    private static SelectionSessionManager selections() {
        return selectionsWithStructures(SelectionStructureRevisionLookup.unavailable());
    }

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

    private static FormResponse response(FormResponseStatus status, Integer button) {
        return new FormResponse(status, button, List.of());
    }

    private static BedrockClaimFormHandler gateway(FakeBedrockLookup lookup, FakeFormSender sender,
            LandCommand.Handler confirm) {
        return gateway(lookup, sender, confirm, new ImmediateScheduler());
    }

    private static BedrockClaimFormHandler gateway(FakeBedrockLookup lookup, FakeFormSender sender,
            LandCommand.Handler confirm, com.smile.acelib.scheduler.SafeScheduler scheduler) {
        return new BedrockClaimFormHandler(lookup, sender, confirm,
                locale -> ClaimFormTexts.forLocale(Locale.US), scheduler);
    }

    /**
     * Handler-level tests below use this immediate stub, so they cover the
     * response logic only and prove nothing about Folia threads. The
     * Folia-safe wiring itself is pinned by the {@code productionCallback*}
     * dispatch tests, which capture the {@code runForPlayer} runnable instead
     * of running it inline.
     */
    static class ImmediateScheduler implements com.smile.acelib.scheduler.SafeScheduler {
        final AtomicLong dispatches = new AtomicLong();

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForPlayer(Player player, Runnable runnable) {
            java.util.Objects.requireNonNull(player, "player");
            java.util.Objects.requireNonNull(runnable, "runnable");
            dispatches.incrementAndGet();
            runnable.run();
            return new CapturedTask();
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runGlobal(Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runAsync(Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runLater(Runnable runnable, long delay) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runTimer(Runnable runnable, long delay, long period) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForPlayerLater(
                Player player, Runnable runnable, long delay) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForEntity(
                org.bukkit.entity.Entity entity, Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runAtLocation(
                org.bukkit.Location location, Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public java.util.List<com.smile.acelib.scheduler.TaskErrorRecord> getRecorderErrors(int limit) {
            return java.util.List.of();
        }

        @Override
        public void cancelAll() {
        }
    }

    private static ClaimCommandHandler claimWith(SelectionSessionManager manager, CapturingRunner runner,
            BedrockClaimFormHandler forms) {
        return new ClaimCommandHandler(manager, runner, null, forms);
    }

    private static LandCommand commandWith(Map<String, LandCommand.Handler> handlers, ReplySink sink) {
        return new LandCommand(handlers, (sender, pipeline) -> sink);
    }

    // ------------------------------------------------------------------
    // Entry routing: Java keeps the direct path, Bedrock gets the form
    // ------------------------------------------------------------------

    @Test
    void javaSenderKeepsDirectClaimPath() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        runner.next = ClaimOutcome.success(new LandId(UUID.randomUUID()));
        FakeBedrockLookup lookup = new FakeBedrockLookup();
        lookup.bedrock = false;
        FakeFormSender sender = new FakeFormSender();
        ConfirmCommandHandler confirm =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        ClaimCommandHandler claim = claimWith(manager, runner, gateway(lookup, sender, confirm));
        Map<String, LandCommand.Handler> handlers = new HashMap<>(LandCommand.defaultStubHandlers());
        handlers.put("claim", claim);
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(handlers, sink).dispatch(player(actor), new String[]{"claim", "Home"}, null));
        sink.awaitReplies();

        assertEquals(0, sender.sends.get(), "Java senders must never trigger a form send");
        assertEquals(1, runner.calls.get(), "Java senders keep the direct saga path");
        assertEquals("command.land.claim.success", sink.replies.get(0).key);
    }

    @Test
    void bedrockSenderReceivesModalFormWithSameSourcePreview() {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        FakeBedrockLookup lookup = new FakeBedrockLookup();
        lookup.bedrock = true;
        FakeFormSender sender = new FakeFormSender();
        ConfirmCommandHandler confirm =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        ClaimCommandHandler claim = claimWith(manager, runner, gateway(lookup, sender, confirm));
        RecordingSink sink = new RecordingSink();

        claim.handle(player(actor), new String[]{"claim", "Home"}, sink);

        assertEquals(1, sender.sends.get());
        assertEquals(actor, sender.lastUuid);
        assertEquals(0, runner.calls.get(), "showing the form must not touch the saga");
        assertTrue(sink.replies.isEmpty(), "showing the form sends no chat reply");
        assertInstanceOf(FormSpec.Modal.class, sender.lastSpec, "Bedrock confirm must be a Modal form");
        FormSpec.Modal modal = (FormSpec.Modal) sender.lastSpec;
        assertTrue(modal.content().contains("Home"), "form must carry the land name");
        assertTrue(modal.content().contains("1"), "form must carry the chunk count");
        assertTrue(modal.content().contains(Long.toString(live.sessionGeneration())),
                "form must carry the session generation token");
        assertTrue(modal.content().contains(Long.toString(live.selectionRevision())),
                "form must carry the selection revision token");
        assertFalse(modal.button1().isBlank(), "confirm button must be labelled");
        assertFalse(modal.button2().isBlank(), "cancel button must be labelled");
        assertNotEquals(modal.button1(), modal.button2(), "confirm and cancel must be distinguishable");
    }

    @Test
    void bedrockPreviewMarksUnavailableFieldsInsteadOfFabricating() {
        ClaimPreview preview = ClaimPreview.fromSession(
                selectSingleChunk(selections(), UUID.randomUUID(), UUID.randomUUID()), "Home");
        String body = ClaimFormTexts.forLocale(Locale.US).bodyFor(preview);
        assertTrue(body.contains("unavailable"), "missing price/limit/height/refund must read unavailable");
        assertFalse(body.matches("(?s).*\\$\\d+.*"), "form must never fabricate a price");
    }

    // ------------------------------------------------------------------
    // VALID button 0 enters the shared path
    // ------------------------------------------------------------------

    @Test
    void validConfirmButtonEntersSharedConfirmPath() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        runner.next = ClaimOutcome.success(new LandId(UUID.randomUUID()));
        FakeBedrockLookup lookup = new FakeBedrockLookup();
        lookup.bedrock = true;
        FakeFormSender sender = new FakeFormSender();
        ConfirmCommandHandler confirm =
                new ConfirmCommandHandler(manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        ClaimCommandHandler claim = claimWith(manager, runner, gateway(lookup, sender, confirm));
        LatchSink sink = new LatchSink(1);

        claim.handle(player(actor), new String[]{"claim", "Home"}, sink);
        sender.fire(response(FormResponseStatus.VALID, 0));
        sink.awaitReplies();

        assertEquals(1, runner.calls.get(), "VALID button 0 must enter the saga exactly once");
        ClaimRequest request = runner.lastRequest.get();
        assertEquals(Long.valueOf(live.selectionRevision()), request.selectionRevision());
        assertEquals(Long.valueOf(live.sessionGeneration()), request.sessionGeneration());
        assertEquals("Home", request.displayName());
        assertEquals("command.land.claim.success", sink.replies.get(0).key,
                "Bedrock confirm shares the terminal claim reply contract");
    }

    // ------------------------------------------------------------------
    // Cancel / close / invalid / errors stay fail-closed
    // ------------------------------------------------------------------

    @Test
    void cancelCloseInvalidAndWrongButtonNeverTouchSaga() {
        for (FormResponse probe : new FormResponse[]{
                response(FormResponseStatus.CLOSED, null),
                response(FormResponseStatus.INVALID, null),
                response(FormResponseStatus.VALID, 1),
                response(FormResponseStatus.VALID, 5),
                response(FormResponseStatus.VALID, null)}) {
            UUID world = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            SelectionSessionManager manager = selections();
            selectSingleChunk(manager, actor, world);
            CapturingRunner runner = new CapturingRunner();
            FakeBedrockLookup lookup = new FakeBedrockLookup();
            lookup.bedrock = true;
            FakeFormSender sender = new FakeFormSender();
            ConfirmCommandHandler confirm = new ConfirmCommandHandler(
                    manager, runner, null, SelectionStructureRevisionLookup.unavailable());
            ClaimCommandHandler claim = claimWith(manager, runner, gateway(lookup, sender, confirm));
            RecordingSink sink = new RecordingSink();

            claim.handle(player(actor), new String[]{"claim", "Home"}, sink);
            sender.fire(probe);

            assertEquals(0, runner.calls.get(),
                    "response " + probe.status() + "/" + probe.clickedButton() + " must not touch the saga");
            assertEquals(1, sink.replies.size(), "cancel-ish outcomes explain that nothing was claimed");
            assertEquals("command.land.claim.cancelled", sink.replies.get(0).key);
        }
    }

    @Test
    void nullResponseFailsClosed() {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        FakeBedrockLookup lookup = new FakeBedrockLookup();
        lookup.bedrock = true;
        FakeFormSender sender = new FakeFormSender();
        ConfirmCommandHandler confirm = new ConfirmCommandHandler(
                manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        ClaimCommandHandler claim = claimWith(manager, runner, gateway(lookup, sender, confirm));
        RecordingSink sink = new RecordingSink();

        claim.handle(player(actor), new String[]{"claim", "Home"}, sink);
        sender.fire(null);

        assertEquals(0, runner.calls.get());
        assertEquals("command.land.claim.failed", sink.replies.get(0).key);
    }

    @Test
    void bedrockLookupFailureFailsClosed() {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        FakeBedrockLookup lookup = new FakeBedrockLookup();
        lookup.throwLookup = true;
        FakeFormSender sender = new FakeFormSender();
        ConfirmCommandHandler confirm = new ConfirmCommandHandler(
                manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        ClaimCommandHandler claim = claimWith(manager, runner, gateway(lookup, sender, confirm));
        RecordingSink sink = new RecordingSink();

        claim.handle(player(actor), new String[]{"claim", "Home"}, sink);

        assertEquals(0, sender.sends.get());
        assertEquals(0, runner.calls.get());
        assertEquals("command.land.claim.failed", sink.replies.get(0).key);
    }

    @Test
    void formSendFailureFailsClosed() {
        for (int mode = 0; mode < 3; mode++) {
            UUID world = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            SelectionSessionManager manager = selections();
            selectSingleChunk(manager, actor, world);
            CapturingRunner runner = new CapturingRunner();
            FakeBedrockLookup lookup = new FakeBedrockLookup();
            lookup.bedrock = true;
            FakeFormSender sender = new FakeFormSender();
            if (mode == 0) {
                sender.throwSend = true;
            } else if (mode == 1) {
                sender.next = FormSendResult.REJECTED;
            } else {
                sender.next = null;
            }
            ConfirmCommandHandler confirm = new ConfirmCommandHandler(
                    manager, runner, null, SelectionStructureRevisionLookup.unavailable());
            ClaimCommandHandler claim = claimWith(manager, runner, gateway(lookup, sender, confirm));
            RecordingSink sink = new RecordingSink();

            claim.handle(player(actor), new String[]{"claim", "Home"}, sink);

            assertEquals(0, runner.calls.get(), "send mode " + mode + " must not touch the saga");
            assertEquals("command.land.claim.failed", sink.replies.get(0).key);
        }
    }

    @Test
    void callbackExceptionIsAbsorbedWithoutSagaSideEffect() {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        FakeBedrockLookup lookup = new FakeBedrockLookup();
        lookup.bedrock = true;
        FakeFormSender sender = new FakeFormSender();
        sender.swallowCallbackThrow = false;
        LandCommand.Handler explodingConfirm = (senderRef, args, sink) -> {
            throw new IllegalStateException("confirm entry down");
        };
        ClaimCommandHandler claim = claimWith(manager, runner, gateway(lookup, sender, explodingConfirm));
        RecordingSink sink = new RecordingSink();

        claim.handle(player(actor), new String[]{"claim", "Home"}, sink);
        assertDoesNotThrow(() -> sender.fire(response(FormResponseStatus.VALID, 0)),
                "a throwing confirm entry must not escape the form callback");

        assertEquals(0, runner.calls.get());
        assertEquals("command.land.claim.failed", sink.replies.get(0).key);
    }

    @Test
    void missingConfirmPermissionIsDeniedBeforeAnyForm() {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        FakeBedrockLookup lookup = new FakeBedrockLookup();
        lookup.bedrock = true;
        FakeFormSender sender = new FakeFormSender();
        ConfirmCommandHandler confirm = new ConfirmCommandHandler(
                manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        ClaimCommandHandler claim = claimWith(manager, runner, gateway(lookup, sender, confirm));
        RecordingSink sink = new RecordingSink();

        claim.handle(playerWithPermissions(actor, false), new String[]{"claim", "Home"}, sink);

        assertEquals(0, sender.sends.get());
        assertEquals(0, runner.calls.get());
        assertEquals("command.land.denied", sink.replies.get(0).key);
    }

    // ------------------------------------------------------------------
    // Stale / replay / structure revision still enforced on the shared path
    // ------------------------------------------------------------------

    @Test
    void staleSessionBetweenFormAndClickIsRejected() {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        FakeBedrockLookup lookup = new FakeBedrockLookup();
        lookup.bedrock = true;
        FakeFormSender sender = new FakeFormSender();
        ConfirmCommandHandler confirm = new ConfirmCommandHandler(
                manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        ClaimCommandHandler claim = claimWith(manager, runner, gateway(lookup, sender, confirm));
        RecordingSink sink = new RecordingSink();

        claim.handle(player(actor), new String[]{"claim", "Home"}, sink);
        manager.updateSelection(actor, live, new SelectionUpdate(
                        live.pointA(), live.pointB(),
                        Set.of(new ChunkKey(world, 9, 9)), Map.of()))
                .orElseThrow(() -> new IllegalStateException("selection refresh must succeed"));
        sender.fire(response(FormResponseStatus.VALID, 0));

        assertEquals(0, runner.calls.get(), "a stale form click must not touch the saga");
        assertEquals("command.land.confirm.stale", sink.replies.get(0).key);
    }

    @Test
    void secondClickWithSameTokenIsConsumedByReplayGuard() {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        runner.next = ClaimOutcome.success(new LandId(UUID.randomUUID()));
        FakeBedrockLookup lookup = new FakeBedrockLookup();
        lookup.bedrock = true;
        FakeFormSender sender = new FakeFormSender();
        ConfirmCommandHandler confirm = new ConfirmCommandHandler(
                manager, runner, null, SelectionStructureRevisionLookup.unavailable());
        ClaimCommandHandler claim = claimWith(manager, runner, gateway(lookup, sender, confirm));
        RecordingSink sink = new RecordingSink();

        claim.handle(player(actor), new String[]{"claim", "Home"}, sink);
        sender.fire(response(FormResponseStatus.VALID, 0));
        sender.fire(response(FormResponseStatus.VALID, 0));

        assertEquals(1, runner.calls.get(), "the replay guard admits exactly one saga entry");
        assertEquals("command.land.confirm.stale", sink.replies.get(1).key);
    }

    @Test
    void targetedStructureChangeIsRejected() {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());
        AtomicLong liveRevision = new AtomicLong(7);
        SelectionSessionManager manager =
                selectionsWithStructures(targetId -> OptionalLong.of(liveRevision.get()));
        SelectionSession initial = SelectionSession.initial(
                actor, world, SelectionMode.EDIT_SELECTION,
                Optional.of(target), Optional.empty(),
                Optional.of(new SelectionPoint(world, 0, 64, 0)),
                Optional.of(new SelectionPoint(world, 16, 64, 16)),
                7, NOW);
        SelectionSession stamped = manager.start(initial);
        manager.updateSelection(actor, stamped, new SelectionUpdate(
                        stamped.pointA(), stamped.pointB(),
                        Set.of(new ChunkKey(world, 5, 6)), Map.of()))
                .orElseThrow(() -> new IllegalStateException("targeted selection must succeed"));
        CapturingRunner runner = new CapturingRunner();
        FakeBedrockLookup lookup = new FakeBedrockLookup();
        lookup.bedrock = true;
        FakeFormSender sender = new FakeFormSender();
        ConfirmCommandHandler confirm = new ConfirmCommandHandler(manager, runner, null,
                targetId -> OptionalLong.of(liveRevision.get()));
        ClaimCommandHandler claim = claimWith(manager, runner, gateway(lookup, sender, confirm));
        RecordingSink sink = new RecordingSink();

        claim.handle(player(actor), new String[]{"claim", "Home"}, sink);
        liveRevision.set(8);
        sender.fire(response(FormResponseStatus.VALID, 0));

        assertEquals(0, runner.calls.get(), "a structure change must fail the shared revalidation closed");
        assertEquals("command.land.confirm.stale", sink.replies.get(0).key);
    }

    // ------------------------------------------------------------------
    // Production wiring: one shared confirm entry, on-demand form service
    // ------------------------------------------------------------------

    static final class FakeAceForms implements com.smile.acelib.form.FormService {
        final AtomicLong sends = new AtomicLong();
        volatile com.smile.acelib.form.FormSendResult next =
                com.smile.acelib.form.FormSendResult.SENT;
        volatile FormSpec lastSpec;
        volatile Consumer<FormResponse> lastCallback;

        @Override
        public com.smile.acelib.form.FormSendResult sendForm(UUID player, FormSpec spec) {
            sends.incrementAndGet();
            lastSpec = spec;
            return next;
        }

        @Override
        public com.smile.acelib.form.FormSendResult sendForm(
                UUID player, FormSpec spec, Consumer<FormResponse> consumer) {
            sends.incrementAndGet();
            lastSpec = spec;
            lastCallback = consumer;
            return next;
        }

        @Override
        public String getModuleStatus() {
            return "test-forms";
        }

        @Override
        public void shutdown() {
        }

        void fire(FormResponse response) {
            assertNotNull(lastCallback, "a form must have been sent before firing a response");
            lastCallback.accept(response);
        }
    }

    static final class FakeAceBedrock implements com.smile.acelib.bedrock.BedrockService {
        volatile boolean bedrock;
        final com.smile.acelib.form.FormService forms;

        FakeAceBedrock(com.smile.acelib.form.FormService forms) {
            this.forms = forms;
        }

        @Override
        public boolean isBedrockPlayer(UUID uuid) {
            return bedrock;
        }

        @Override
        public Optional<com.smile.acelib.bedrock.BedrockPlayerInfo> getPlayerInfo(UUID uuid) {
            return Optional.empty();
        }

        @Override
        public com.smile.acelib.form.FormService forms() {
            return forms;
        }

        @Override
        public String getModuleStatus() {
            return "test-bedrock";
        }

        @Override
        public void shutdown() {
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, LandCommand.Handler> productionHandlers(
            SelectionSessionManager manager, ClaimCommandHandler.ClaimRunner runner,
            com.smile.chunkland.capability.Capabilities capabilities) throws Exception {
        Method factory = ChunkLandPlugin.class.getDeclaredMethod(
                "buildLandHandlers",
                SelectionSessionManager.class,
                ClaimCommandHandler.ClaimRunner.class,
                java.util.function.Supplier.class,
                SelectionStructureRevisionLookup.class,
                SubLandCommandHandler.class,
                com.smile.chunkland.capability.Capabilities.class);
        factory.setAccessible(true);
        return (Map<String, LandCommand.Handler>) factory.invoke(null, manager, runner, null,
                SelectionStructureRevisionLookup.unavailable(), null, capabilities);
    }

    @Test
    void productionMapSharesConfirmEntryBetweenChatAndForm() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        runner.next = ClaimOutcome.success(new LandId(UUID.randomUUID()));
        FakeAceForms aceForms = new FakeAceForms();
        FakeAceBedrock aceBedrock = new FakeAceBedrock(aceForms);
        aceBedrock.bedrock = true;
        com.smile.chunkland.capability.Capabilities capabilities =
                com.smile.chunkland.capability.Capabilities.builder()
                        .bedrockService(aceBedrock)
                        .scheduler(new ImmediateScheduler())
                        .build();
        Map<String, LandCommand.Handler> handlers = productionHandlers(manager, runner, capabilities);
        assertTrue(handlers.get("confirm") instanceof ConfirmCommandHandler);
        assertTrue(handlers.get("claim") instanceof ClaimCommandHandler);
        LatchSink sink = new LatchSink(2);
        LandCommand command = new LandCommand(handlers, (sender, pipeline) -> sink);

        assertTrue(command.dispatch(player(actor), new String[]{"claim", "Home"}, null));
        assertEquals(1, aceForms.sends.get(), "Bedrock claim must show the form on the production map");
        assertEquals(0, runner.calls.get());
        aceForms.fire(response(FormResponseStatus.VALID, 0));
        assertTrue(command.dispatch(player(actor),
                new String[]{"confirm",
                        Long.toString(live.sessionGeneration()),
                        Long.toString(live.selectionRevision()), "Home"},
                null));
        sink.awaitReplies();

        assertEquals(1, runner.calls.get(), "chat and form confirmations share one replay guard");
        assertEquals("command.land.claim.success", sink.replies.get(0).key);
        assertEquals("command.land.confirm.stale", sink.replies.get(1).key,
                "the second confirmation with the same token pair must be consumed");
    }

    @Test
    void productionCallbackMustDispatchViaRunForPlayer() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        runner.next = ClaimOutcome.success(new LandId(UUID.randomUUID()));
        FakeAceForms aceForms = new FakeAceForms();
        FakeAceBedrock aceBedrock = new FakeAceBedrock(aceForms);
        aceBedrock.bedrock = true;
        CapturingScheduler capturing = new CapturingScheduler();
        com.smile.chunkland.capability.Capabilities capabilities =
                com.smile.chunkland.capability.Capabilities.builder()
                        .bedrockService(aceBedrock)
                        .scheduler(capturing)
                        .build();
        Map<String, LandCommand.Handler> handlers = productionHandlers(manager, runner, capabilities);
        RecordingSink sink = new RecordingSink();
        LandCommand command = new LandCommand(handlers, (sender, pipeline) -> sink);

        assertTrue(command.dispatch(player(actor), new String[]{"claim", "Home"}, null));
        assertEquals(1, aceForms.sends.get(), "Bedrock claim must show the form on the production map");
        aceForms.fire(response(FormResponseStatus.VALID, 0));

        assertEquals(1, capturing.dispatches.get(),
                "form callback must be dispatched via SafeScheduler.runForPlayer, not run inline");
        assertEquals(0, runner.calls.get(),
                "confirm must not run on the form callback thread; it runs after dispatch");
        assertTrue(sink.replies.isEmpty(), "no reply before the dispatched task runs");

        capturing.runAll();

        assertEquals(1, runner.calls.get(), "the dispatched task must enter the saga exactly once");
        assertEquals(1, sink.replies.size(), "the dispatched task owns the single terminal reply");
        assertEquals("command.land.claim.success", sink.replies.get(0).key);
    }

    @Test
    void productionCallbackDispatchThrowFailsClosedOnce() throws Exception {
        SeamedScheduler seamed = new SeamedScheduler(SeamedScheduler.Mode.THROW);
        ProductionRig rig = new ProductionRig(seamed);
        rig.dispatchClaim();
        rig.aceForms.fire(response(FormResponseStatus.VALID, 0));

        assertEquals(0, rig.runner.calls.get(), "a rejected dispatch must never reach the saga");
        assertEquals(1, rig.sink.replies.size(), "a rejected dispatch replies exactly once");
        assertEquals("command.land.claim.failed", rig.sink.replies.get(0).key);
    }

    @Test
    void productionCallbackDispatchNullResultFailsClosedOnce() throws Exception {
        SeamedScheduler seamed = new SeamedScheduler(SeamedScheduler.Mode.NULL_RESULT);
        ProductionRig rig = new ProductionRig(seamed);
        rig.dispatchClaim();
        rig.aceForms.fire(response(FormResponseStatus.VALID, 0));

        assertEquals(0, rig.runner.calls.get(), "a null schedule result must never reach the saga");
        assertEquals(1, rig.sink.replies.size(), "a null schedule result replies exactly once");
        assertEquals("command.land.claim.failed", rig.sink.replies.get(0).key);
    }

    @Test
    void productionCallbackDispatchCancelledResultFailsClosedOnce() throws Exception {
        SeamedScheduler seamed = new SeamedScheduler(SeamedScheduler.Mode.CANCELLED);
        ProductionRig rig = new ProductionRig(seamed);
        rig.dispatchClaim();
        rig.aceForms.fire(response(FormResponseStatus.VALID, 0));

        assertEquals(0, rig.runner.calls.get(), "an already-cancelled task must never reach the saga");
        assertEquals(1, rig.sink.replies.size(), "an already-cancelled task replies exactly once");
        assertEquals("command.land.claim.failed", rig.sink.replies.get(0).key);
    }

    @Test
    void productionCallbackWithoutSchedulerFailsClosedOnce() throws Exception {
        ProductionRig rig = new ProductionRig(null);
        rig.dispatchClaim();
        rig.aceForms.fire(response(FormResponseStatus.VALID, 0));

        assertEquals(0, rig.runner.calls.get(), "a missing scheduler must never reach the saga");
        assertEquals(1, rig.sink.replies.size(), "a missing scheduler replies exactly once");
        assertEquals("command.land.claim.failed", rig.sink.replies.get(0).key);
    }

    @Test
    void productionClosedResponseViaDispatchHasNoSagaSideEffect() throws Exception {
        CapturingScheduler capturing = new CapturingScheduler();
        ProductionRig rig = new ProductionRig(capturing);
        rig.dispatchClaim();
        rig.aceForms.fire(response(FormResponseStatus.CLOSED, null));

        assertEquals(1, capturing.dispatches.get(), "even a close must travel via runForPlayer");
        assertEquals(0, rig.runner.calls.get());
        assertTrue(rig.sink.replies.isEmpty(), "no reply before the dispatched task runs");

        capturing.runAll();

        assertEquals(0, rig.runner.calls.get(), "a close must never touch the saga");
        assertEquals(1, rig.sink.replies.size());
        assertEquals("command.land.claim.cancelled", rig.sink.replies.get(0).key);
    }

    /** One Bedrock production rig per dispatch test: fresh selection, success runner, live form. */
    static final class ProductionRig {
        final SelectionSessionManager manager = selections();
        final CapturingRunner runner = new CapturingRunner();
        final FakeAceForms aceForms = new FakeAceForms();
        final FakeAceBedrock aceBedrock = new FakeAceBedrock(aceForms);
        final UUID world = UUID.randomUUID();
        final UUID actor = UUID.randomUUID();
        final RecordingSink sink = new RecordingSink();
        final Map<String, LandCommand.Handler> handlers;

        ProductionRig(com.smile.acelib.scheduler.SafeScheduler scheduler) throws Exception {
            runner.next = ClaimOutcome.success(new LandId(UUID.randomUUID()));
            aceBedrock.bedrock = true;
            selectSingleChunk(manager, actor, world);
            com.smile.chunkland.capability.Capabilities capabilities =
                    com.smile.chunkland.capability.Capabilities.builder()
                            .bedrockService(aceBedrock)
                            .scheduler(scheduler)
                            .build();
            handlers = productionHandlers(manager, runner, capabilities);
        }

        void dispatchClaim() {
            assertTrue(commandWith(handlers, sink).dispatch(player(actor), new String[]{"claim", "Home"}, null));
            assertEquals(1, aceForms.sends.get(), "Bedrock claim must show the form on the production map");
            assertEquals(0, runner.calls.get(), "showing the form must not touch the saga");
        }
    }

    /** Configurable dispatch seam: throws, returns null, or returns an already-cancelled task. */
    static final class SeamedScheduler extends ImmediateScheduler {
        enum Mode { THROW, NULL_RESULT, CANCELLED }

        private final Mode mode;

        SeamedScheduler(Mode mode) {
            this.mode = java.util.Objects.requireNonNull(mode, "mode");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForPlayer(Player player, Runnable runnable) {
            java.util.Objects.requireNonNull(player, "player");
            java.util.Objects.requireNonNull(runnable, "runnable");
            return switch (mode) {
                case THROW -> throw new IllegalStateException("scheduler refused the dispatch");
                case NULL_RESULT -> null;
                case CANCELLED -> new CancelledTask();
            };
        }
    }

    /** Already-cancelled handle: the dispatch site must treat it as a failed schedule. */
    static final class CancelledTask implements com.smile.acelib.scheduler.ScheduledTask {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }

        @Override
        public org.bukkit.plugin.java.JavaPlugin getPlugin() {
            return null;
        }

        @Override
        public com.smile.acelib.scheduler.TaskType getType() {
            return com.smile.acelib.scheduler.TaskType.PLAYER;
        }

        @Override
        public long getCreationTick() {
            return 0L;
        }
    }

    /**
     * Capturing {@link com.smile.acelib.scheduler.SafeScheduler} for the Folia-safe
     * dispatch red test: {@code runForPlayer} records the runnable without running
     * it so the test can prove the callback does not execute inline. Only the
     * player-scoped entry is supported; every other entry throws.
     */
    static final class CapturingScheduler implements com.smile.acelib.scheduler.SafeScheduler {
        final List<Runnable> playerTasks = new CopyOnWriteArrayList<>();
        final AtomicLong dispatches = new AtomicLong();

        void runAll() {
            for (Runnable task : playerTasks) {
                task.run();
            }
            playerTasks.clear();
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForPlayer(Player player, Runnable runnable) {
            java.util.Objects.requireNonNull(player, "player");
            java.util.Objects.requireNonNull(runnable, "runnable");
            dispatches.incrementAndGet();
            playerTasks.add(runnable);
            return new CapturedTask();
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runGlobal(Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runAsync(Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runLater(Runnable runnable, long delay) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runTimer(Runnable runnable, long delay, long period) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForPlayerLater(
                Player player, Runnable runnable, long delay) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForEntity(
                org.bukkit.entity.Entity entity, Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runAtLocation(
                org.bukkit.Location location, Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public java.util.List<com.smile.acelib.scheduler.TaskErrorRecord> getRecorderErrors(int limit) {
            return java.util.List.of();
        }

        @Override
        public void cancelAll() {
        }
    }

    /** Never-cancelled handle returned by {@link CapturingScheduler}. */
    static final class CapturedTask implements com.smile.acelib.scheduler.ScheduledTask {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public org.bukkit.plugin.java.JavaPlugin getPlugin() {
            return null;
        }

        @Override
        public com.smile.acelib.scheduler.TaskType getType() {
            return com.smile.acelib.scheduler.TaskType.PLAYER;
        }

        @Override
        public long getCreationTick() {
            return 0L;
        }
    }

    @Test
    void productionMapWithoutCapabilitiesKeepsLegacyDirectPath() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        selectSingleChunk(manager, actor, world);
        CapturingRunner runner = new CapturingRunner();
        runner.next = ClaimOutcome.success(new LandId(UUID.randomUUID()));
        Map<String, LandCommand.Handler> handlers = productionHandlers(manager, runner, null);
        LatchSink sink = new LatchSink(1);

        assertTrue(commandWith(handlers, sink).dispatch(player(actor), new String[]{"claim", "Home"}, null));
        sink.awaitReplies();

        assertEquals(1, runner.calls.get(), "AceLib-less wiring keeps the direct claim path");
        assertEquals("command.land.claim.success", sink.replies.get(0).key);
    }

    @Test
    void productionFormFactoryIsNullWithoutCapabilitiesOrConfirm() throws Exception {
        Method factory = ChunkLandPlugin.class.getDeclaredMethod(
                "buildBedrockClaimForms",
                com.smile.chunkland.capability.Capabilities.class,
                LandCommand.Handler.class);
        factory.setAccessible(true);
        assertNull(factory.invoke(null, null, null),
                "missing capabilities and confirm entry must keep the legacy direct path");
    }

    // ------------------------------------------------------------------
    // Message resources: the cancelled notice exists in both locales
    // ------------------------------------------------------------------

    @Test
    void cancelledNoticeExistsInBothLocales() throws Exception {
        for (String tag : new String[]{"en_US", "zh_TW"}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File("src/main/resources/lang/" + tag + ".yml"));
            String template = cfg.getString("command.land.claim.cancelled");
            assertNotNull(template, tag + " must define command.land.claim.cancelled");
            assertFalse(template.isBlank(), tag + " cancelled notice must not be blank");
            strictValidate("command.land.claim.cancelled", template);
        }
    }

    private static void strictValidate(String key, String template) throws Exception {
        var method = com.smile.chunkland.message.ChunkLandMessagePipeline.class
                .getDeclaredMethod("validateTemplateStrict", String.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(null, key, template);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw (RuntimeException) failure.getCause();
        }
    }

    // ------------------------------------------------------------------
    // Preview seam: immutable, same-source, no fabricated numbers
    // ------------------------------------------------------------------

    @Test
    void previewIsImmutableSameSourceSnapshot() {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SelectionSessionManager manager = selections();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        ClaimPreview preview = ClaimPreview.fromSession(live, "Home");
        assertEquals("Home", preview.landName());
        assertEquals(1, preview.chunkCount());
        assertEquals(live.sessionGeneration(), preview.sessionGeneration());
        assertEquals(live.selectionRevision(), preview.selectionRevision());
        assertTrue(preview.price().isEmpty(), "no formal price source exists at the command layer");
        assertTrue(preview.limit().isEmpty(), "no formal limit source exists at the command layer");
        assertTrue(preview.lowestHeight().isEmpty(), "no formal height source exists at the command layer");
        assertTrue(preview.refund().isEmpty(), "no formal refund source exists at the command layer");
    }
}

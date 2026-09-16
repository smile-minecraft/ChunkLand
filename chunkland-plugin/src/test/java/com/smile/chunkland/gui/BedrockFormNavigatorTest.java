package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormResponseStatus;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Bedrock 表單導航堆疊的五條規則：每玩家獨立堆疊、generation 只接受目前頂層、
 * push/back/replace/close 語意、過期與重複回應 no-op、離開與停用及送出失敗時
 * 清理且不碰 mutation。全部以假 sender 與假 dispatcher 固定，零 sleep。
 */
class BedrockFormNavigatorTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static FormSpec simple(String title, String button) {
        return FormSpec.simple(title).content("content of " + title).button(button).build();
    }

    private static BedrockFormNavigator.FormPage page(String id) {
        return new BedrockFormNavigator.FormPage(id, simple("title-" + id, "ok-" + id));
    }

    static final class FakeSender implements BedrockFormNavigator.FormSender {
        final AtomicLong sends = new AtomicLong();
        volatile FormSendResult next = FormSendResult.SENT;
        volatile boolean throwSend;
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
            return next;
        }

        void fire(FormResponse response) {
            lastCallback.accept(response);
        }
    }

    static class CompletedTask implements com.smile.acelib.scheduler.ScheduledTask {
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

    static final class CancelledTask extends CompletedTask {
        @Override
        public boolean isCancelled() {
            return true;
        }
    }

    static final class ImmediateDispatcher implements BedrockFormNavigator.Dispatcher {
        final AtomicLong dispatches = new AtomicLong();

        @Override
        public com.smile.acelib.scheduler.ScheduledTask dispatch(Runnable task) {
            dispatches.incrementAndGet();
            task.run();
            return new CompletedTask();
        }
    }

    static final class CapturingDispatcher implements BedrockFormNavigator.Dispatcher {
        final List<Runnable> pending = new CopyOnWriteArrayList<>();
        final AtomicLong dispatches = new AtomicLong();

        void runAll() {
            for (Runnable task : pending) {
                task.run();
            }
            pending.clear();
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask dispatch(Runnable task) {
            dispatches.incrementAndGet();
            pending.add(task);
            return new CompletedTask();
        }
    }

    static final class ThrowingDispatcher implements BedrockFormNavigator.Dispatcher {
        @Override
        public com.smile.acelib.scheduler.ScheduledTask dispatch(Runnable task) {
            throw new IllegalStateException("scheduler refused the dispatch");
        }
    }

    static final class NullDispatcher implements BedrockFormNavigator.Dispatcher {
        @Override
        public com.smile.acelib.scheduler.ScheduledTask dispatch(Runnable task) {
            return null;
        }
    }

    static final class CancelledDispatcher implements BedrockFormNavigator.Dispatcher {
        @Override
        public com.smile.acelib.scheduler.ScheduledTask dispatch(Runnable task) {
            return new CancelledTask();
        }
    }

    static final class RecordingFailure implements BedrockFormNavigator.FailureReply {
        final List<String> keys = new CopyOnWriteArrayList<>();

        @Override
        public void reply(String messageKey, Map<String, Object> vars) {
            keys.add(messageKey);
        }
    }

    static final class RecordingHandler implements Consumer<FormResponse> {
        final AtomicLong calls = new AtomicLong();
        volatile FormResponse last;

        @Override
        public void accept(FormResponse response) {
            calls.incrementAndGet();
            last = response;
        }
    }

    private static FormResponse valid(int button) {
        return new FormResponse(FormResponseStatus.VALID, button, List.of());
    }

    private static FormResponse closed() {
        return new FormResponse(FormResponseStatus.CLOSED, null, List.of());
    }

    private static FormResponse invalid() {
        return new FormResponse(FormResponseStatus.INVALID, null, List.of());
    }

    private static BedrockFormNavigator navigator(FakeSender sender) {
        return new BedrockFormNavigator(sender);
    }

    // ------------------------------------------------------------------
    // 規則一：每玩家獨立堆疊
    // ------------------------------------------------------------------

    @Test
    void perPlayerStacksStayIndependent() {
        FakeSender sender = new FakeSender();
        BedrockFormNavigator navigator = navigator(sender);
        RecordingHandler aliceHandler = new RecordingHandler();
        RecordingHandler bobHandler = new RecordingHandler();
        RecordingFailure failure = new RecordingFailure();
        ImmediateDispatcher dispatch = new ImmediateDispatcher();

        long aliceGen = navigator.open(ALICE, page("alice-root"), aliceHandler, dispatch, failure)
                .orElseThrow(() -> new AssertionError("open must succeed"));
        long bobGen = navigator.open(BOB, page("bob-root"), bobHandler, dispatch, failure)
                .orElseThrow(() -> new AssertionError("open must succeed"));

        assertNotEquals(aliceGen, bobGen, "generations must be unique across players");
        assertEquals(1, navigator.depth(ALICE));
        assertEquals(1, navigator.depth(BOB));
        assertEquals("alice-root", navigator.currentPage(ALICE).orElseThrow().id());
        assertEquals("bob-root", navigator.currentPage(BOB).orElseThrow().id());

        navigator.handleResponse(ALICE, aliceGen, valid(0));

        assertEquals(1, aliceHandler.calls.get());
        assertEquals(0, bobHandler.calls.get(), "alice response must not reach bob handler");
        assertEquals(1, navigator.depth(BOB), "bob stack must be untouched");
    }

    // ------------------------------------------------------------------
    // 規則二：generation 只接受目前頂層
    // ------------------------------------------------------------------

    @Test
    void onlyCurrentTopGenerationIsAccepted() {
        FakeSender sender = new FakeSender();
        BedrockFormNavigator navigator = navigator(sender);
        RecordingHandler first = new RecordingHandler();
        RecordingHandler second = new RecordingHandler();
        RecordingFailure failure = new RecordingFailure();
        ImmediateDispatcher dispatch = new ImmediateDispatcher();

        long stale = navigator.open(ALICE, page("first"), first, dispatch, failure)
                .orElseThrow(() -> new AssertionError("open must succeed"));
        long current = navigator.push(ALICE, page("second"), second, dispatch, failure)
                .orElseThrow(() -> new AssertionError("push must succeed"));
        assertNotEquals(stale, current);

        navigator.handleResponse(ALICE, stale, valid(0));

        assertEquals(0, first.calls.get(), "stale generation must be dropped silently");
        assertEquals(0, second.calls.get());
        assertTrue(failure.keys.isEmpty(), "stale response is a silent no-op");
        assertEquals(2, navigator.depth(ALICE), "stale response must not mutate the stack");

        navigator.handleResponse(ALICE, current, valid(0));

        assertEquals(1, second.calls.get(), "current top generation must dispatch");
    }

    @Test
    void unknownPlayerResponseIsNoOp() {
        FakeSender sender = new FakeSender();
        BedrockFormNavigator navigator = navigator(sender);
        RecordingHandler handler = new RecordingHandler();
        RecordingFailure failure = new RecordingFailure();

        navigator.handleResponse(BOB, 999L, valid(0));

        assertEquals(0, handler.calls.get());
        assertTrue(failure.keys.isEmpty());
        assertTrue(navigator.trackedPlayers().isEmpty());
    }

    // ------------------------------------------------------------------
    // 規則三：push/back/replace/close 語意
    // ------------------------------------------------------------------

    @Test
    void pushBackReplaceAndCloseSemantics() {
        FakeSender sender = new FakeSender();
        BedrockFormNavigator navigator = navigator(sender);
        RecordingHandler handler = new RecordingHandler();
        RecordingFailure failure = new RecordingFailure();
        ImmediateDispatcher dispatch = new ImmediateDispatcher();

        long root = navigator.open(ALICE, page("root"), handler, dispatch, failure)
                .orElseThrow(() -> new AssertionError("open must succeed"));
        long second = navigator.push(ALICE, page("second"), handler, dispatch, failure)
                .orElseThrow(() -> new AssertionError("push must succeed"));
        assertEquals(2, navigator.depth(ALICE));
        assertNotEquals(root, second);
        assertEquals("second", navigator.currentPage(ALICE).orElseThrow().id());
        assertEquals(2, sender.sends.get());

        assertTrue(navigator.back(ALICE), "back must resend the previous page");
        assertEquals(1, navigator.depth(ALICE));
        assertEquals("root", navigator.currentPage(ALICE).orElseThrow().id());
        assertEquals(3, sender.sends.get(), "back must resend the previous page");
        long resent = navigator.currentGeneration(ALICE).orElseThrow();
        assertNotEquals(root, resent, "resent page must carry a fresh generation");

        long replaced = navigator.replace(ALICE, page("third"), handler, dispatch, failure)
                .orElseThrow(() -> new AssertionError("replace must succeed"));
        assertEquals(1, navigator.depth(ALICE));
        assertEquals("third", navigator.currentPage(ALICE).orElseThrow().id());
        assertNotEquals(resent, replaced);

        navigator.close(ALICE);
        assertEquals(0, navigator.depth(ALICE));
        assertTrue(navigator.currentPage(ALICE).isEmpty());
        assertEquals(4, sender.sends.get(), "close must not resend anything");
    }

    @Test
    void backOnFirstPageClosesWithoutResend() {
        FakeSender sender = new FakeSender();
        BedrockFormNavigator navigator = navigator(sender);
        RecordingFailure failure = new RecordingFailure();
        ImmediateDispatcher dispatch = new ImmediateDispatcher();

        navigator.open(ALICE, page("only"), new RecordingHandler(), dispatch, failure)
                .orElseThrow(() -> new AssertionError("open must succeed"));

        assertFalse(navigator.back(ALICE), "back on the first page closes instead");
        assertEquals(0, navigator.depth(ALICE));
        assertEquals(1, sender.sends.get(), "closing back must not resend");
    }

    // ------------------------------------------------------------------
    // 規則四：過期與重複回應 no-op，不重複提交
    // ------------------------------------------------------------------

    @Test
    void duplicateResponseAfterConsumeIsNoOp() {
        FakeSender sender = new FakeSender();
        BedrockFormNavigator navigator = navigator(sender);
        RecordingHandler handler = new RecordingHandler();
        RecordingFailure failure = new RecordingFailure();
        ImmediateDispatcher dispatch = new ImmediateDispatcher();

        long generation = navigator.open(ALICE, page("root"), handler, dispatch, failure)
                .orElseThrow(() -> new AssertionError("open must succeed"));

        navigator.handleResponse(ALICE, generation, valid(0));
        navigator.handleResponse(ALICE, generation, valid(1));

        assertEquals(1, handler.calls.get(), "a consumed generation must never submit twice");
        assertTrue(failure.keys.isEmpty(), "duplicate response is a silent no-op");
    }

    @Test
    void closedInvalidAndNullResponsesClearWithoutHandler() {
        for (FormResponse probe : new FormResponse[] {closed(), invalid(), null}) {
            FakeSender sender = new FakeSender();
            BedrockFormNavigator navigator = navigator(sender);
            RecordingHandler handler = new RecordingHandler();
            RecordingFailure failure = new RecordingFailure();
            ImmediateDispatcher dispatch = new ImmediateDispatcher();

            long generation = navigator.open(ALICE, page("root"), handler, dispatch, failure)
                    .orElseThrow(() -> new AssertionError("open must succeed"));
            navigator.handleResponse(ALICE, generation, probe);

            assertEquals(0, handler.calls.get(),
                    "dismissal must never reach the handler: " + probe);
            assertEquals(0, navigator.depth(ALICE), "dismissal clears the stack: " + probe);
            assertEquals(1, sender.sends.get(), "dismissal must not reopen anything");
        }
    }

    // ------------------------------------------------------------------
    // 規則五：離開、停用、送出失敗清理且不碰 mutation
    // ------------------------------------------------------------------

    @Test
    void quitAndDisableCleanupWithoutMutationOrResend() {
        FakeSender sender = new FakeSender();
        BedrockFormNavigator navigator = navigator(sender);
        RecordingHandler handler = new RecordingHandler();
        RecordingFailure failure = new RecordingFailure();
        ImmediateDispatcher dispatch = new ImmediateDispatcher();

        navigator.open(ALICE, page("root"), handler, dispatch, failure)
                .orElseThrow(() -> new AssertionError("open must succeed"));
        navigator.push(ALICE, page("second"), handler, dispatch, failure)
                .orElseThrow(() -> new AssertionError("push must succeed"));

        navigator.close(ALICE);

        assertEquals(0, navigator.depth(ALICE));
        assertEquals(0, handler.calls.get(), "quit cleanup must not invoke any handler");
        assertEquals(2, sender.sends.get(), "quit cleanup must not resend");
        assertTrue(navigator.trackedPlayers().isEmpty());

        navigator.open(ALICE, page("root"), handler, dispatch, failure)
                .orElseThrow(() -> new AssertionError("open must succeed"));
        navigator.open(BOB, page("root"), handler, dispatch, failure)
                .orElseThrow(() -> new AssertionError("open must succeed"));
        navigator.closeAll();

        assertTrue(navigator.trackedPlayers().isEmpty(), "disable must drop all tracking");
        assertEquals(0, handler.calls.get());
        assertEquals(4, sender.sends.get(), "disable must not resend");

        navigator.close(ALICE);
        navigator.closeAll();
    }

    @Test
    void sendFailureKeepsPreviousStack() {
        for (FormSendResult failed : new FormSendResult[] {FormSendResult.REJECTED, null}) {
            FakeSender sender = new FakeSender();
            BedrockFormNavigator navigator = navigator(sender);
            RecordingHandler handler = new RecordingHandler();
            RecordingFailure failure = new RecordingFailure();
            ImmediateDispatcher dispatch = new ImmediateDispatcher();

            long root = navigator.open(ALICE, page("root"), handler, dispatch, failure)
                    .orElseThrow(() -> new AssertionError("open must succeed"));
            sender.next = failed;
            Optional<Long> pushed = navigator.push(ALICE, page("second"), handler, dispatch, failure);

            assertTrue(pushed.isEmpty(), "failed push must report empty: " + failed);
            assertEquals(1, navigator.depth(ALICE), "failed push keeps the previous stack");
            assertEquals(root, navigator.currentGeneration(ALICE).orElseThrow().longValue());
            assertEquals(0, handler.calls.get());
        }
    }

    @Test
    void throwingSendKeepsPreviousStack() {
        FakeSender sender = new FakeSender();
        BedrockFormNavigator navigator = navigator(sender);
        RecordingHandler handler = new RecordingHandler();
        RecordingFailure failure = new RecordingFailure();
        ImmediateDispatcher dispatch = new ImmediateDispatcher();

        navigator.open(ALICE, page("root"), handler, dispatch, failure)
                .orElseThrow(() -> new AssertionError("open must succeed"));
        sender.throwSend = true;

        assertTrue(navigator.push(ALICE, page("second"), handler, dispatch, failure).isEmpty());
        assertEquals(1, navigator.depth(ALICE));
        assertEquals("root", navigator.currentPage(ALICE).orElseThrow().id());
    }

    @Test
    void backSendFailureRestoresPoppedFrame() {
        FakeSender sender = new FakeSender();
        BedrockFormNavigator navigator = navigator(sender);
        RecordingHandler handler = new RecordingHandler();
        RecordingFailure failure = new RecordingFailure();
        ImmediateDispatcher dispatch = new ImmediateDispatcher();

        navigator.open(ALICE, page("root"), handler, dispatch, failure)
                .orElseThrow(() -> new AssertionError("open must succeed"));
        long second = navigator.push(ALICE, page("second"), handler, dispatch, failure)
                .orElseThrow(() -> new AssertionError("push must succeed"));
        sender.next = FormSendResult.REJECTED;

        assertFalse(navigator.back(ALICE), "failed back resend must report false");
        assertEquals(2, navigator.depth(ALICE), "failed back must restore the popped frame");
        assertEquals(second, navigator.currentGeneration(ALICE).orElseThrow().longValue());
    }

    // ------------------------------------------------------------------
    // Folia 安全：回應只走 dispatcher，派送失敗 fail-closed
    // ------------------------------------------------------------------

    @Test
    void responseTravelsViaDispatcherNotInline() {
        FakeSender sender = new FakeSender();
        BedrockFormNavigator navigator = navigator(sender);
        RecordingHandler handler = new RecordingHandler();
        RecordingFailure failure = new RecordingFailure();
        CapturingDispatcher capturing = new CapturingDispatcher();

        long generation = navigator.open(ALICE, page("root"), handler, dispatchOf(capturing), failure)
                .orElseThrow(() -> new AssertionError("open must succeed"));
        sender.fire(valid(0));

        assertEquals(1, capturing.dispatches.get(), "response must travel via the dispatcher");
        assertEquals(0, handler.calls.get(), "handler must not run on the callback thread");

        capturing.runAll();

        assertEquals(1, handler.calls.get(), "the dispatched task runs the handler once");
        assertEquals(valid(0), handler.last);
    }

    private static BedrockFormNavigator.Dispatcher dispatchOf(CapturingDispatcher capturing) {
        return capturing::dispatch;
    }

    @Test
    void dispatchThrowNullAndCancelledFailClosedOnce() {
        BedrockFormNavigator.Dispatcher[] dispatchers = {
                new ThrowingDispatcher(), new NullDispatcher(), new CancelledDispatcher()};
        for (BedrockFormNavigator.Dispatcher dispatcher : dispatchers) {
            FakeSender sender = new FakeSender();
            BedrockFormNavigator navigator = navigator(sender);
            RecordingHandler handler = new RecordingHandler();
            RecordingFailure failure = new RecordingFailure();

            long generation = navigator.open(ALICE, page("root"), handler, dispatcher, failure)
                    .orElseThrow(() -> new AssertionError("open must succeed"));
            sender.fire(valid(0));

            assertEquals(0, handler.calls.get(), "failed dispatch must never reach the handler");
            assertEquals(1, failure.keys.size(), "failed dispatch replies exactly once");
            assertEquals(0, navigator.depth(ALICE), "failed dispatch clears the stack");
        }
    }

    @Test
    void throwingHandlerIsAbsorbedWithoutSecondSubmit() {
        FakeSender sender = new FakeSender();
        BedrockFormNavigator navigator = navigator(sender);
        RecordingFailure failure = new RecordingFailure();
        ImmediateDispatcher dispatch = new ImmediateDispatcher();
        Consumer<FormResponse> exploding = response -> {
            throw new IllegalStateException("owner handler down");
        };

        long generation = navigator.open(ALICE, page("root"), exploding, dispatch, failure)
                .orElseThrow(() -> new AssertionError("open must succeed"));

        navigator.handleResponse(ALICE, generation, valid(0));
        navigator.handleResponse(ALICE, generation, valid(0));

        assertEquals(1, failure.keys.size(), "a throwing handler replies exactly once");
        assertEquals(0, navigator.depth(ALICE));
    }

    @Test
    void nullSeamsFailClosedWithoutSend() {
        FakeSender sender = new FakeSender();
        BedrockFormNavigator navigator = navigator(sender);
        RecordingHandler handler = new RecordingHandler();
        RecordingFailure failure = new RecordingFailure();
        ImmediateDispatcher dispatch = new ImmediateDispatcher();

        assertTrue(navigator.open(null, page("root"), handler, dispatch, failure).isEmpty());
        assertTrue(navigator.open(ALICE, null, handler, dispatch, failure).isEmpty());
        assertTrue(navigator.open(ALICE, page("root"), null, dispatch, failure).isEmpty());
        assertTrue(navigator.open(ALICE, page("root"), handler, null, failure).isEmpty());
        assertTrue(navigator.open(ALICE, page("root"), handler, dispatch, null).isEmpty());
        assertEquals(0, sender.sends.get(), "null seams must never send");
        assertTrue(navigator.trackedPlayers().isEmpty());

        assertTrue(new BedrockFormNavigator(null).open(
                        ALICE, page("root"), handler, dispatch, failure).isEmpty(),
                "missing sender fails every open closed");
        assertFalse(new BedrockFormNavigator(null).isAvailable());
        assertTrue(navigator.isAvailable());
    }
}

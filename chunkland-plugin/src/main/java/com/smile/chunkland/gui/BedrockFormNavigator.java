package com.smile.chunkland.gui;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.scheduler.ScheduledTask;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Bedrock 表單導航堆疊，與 Java 的 GuiNavigator 語意平行，只是呈現層不同。
 *
 * <p>每個玩家擁有獨立的表單頁堆疊，規則如下：
 *
 * <ul>
 *   <li>開新頁 push；成功送出才會留下堆疊紀錄，送出失敗保留舊堆疊。</li>
 *   <li>返回 pop 並重送上一頁；重送失敗還原被 pop 的頁。</li>
 *   <li>replace 以新頁替換頂層；送出失敗還原舊頂層。</li>
 *   <li>玩家主動關閉（CLOSED）、無效或 null 回應清空該玩家堆疊，不自動重開。</li>
 *   <li>generation 不符、重複、未知玩家的回應一律靜默丟棄，不重複提交。</li>
 * </ul>
 *
 * <p>AceLib 只提供表單派送，不提供導航概念；FormService 也沒有像 GuiService
 * 那樣的上游 generation，所以 generation 由這裡本地配發（單調遞增），只接受
 * 目前頂層。每個頂層回應只處理一次，消費旗標保證重複送達不會第二次提交。
 *
 * <p>導航本身不做任何 Bukkit 操作：VALID 回應一律經由呼叫端提供的 dispatcher
 *（正常就是包著 SafeScheduler.runForPlayer 的 lambda）才執行；派送失敗時以
 * 通用管理拒絕回報一次並清理該玩家堆疊，絕不碰 mutation。離線清理由 AceLib
 * 側保證（離線、關閉、過期、disable 的回應零執行），這裡的 close/closeAll
 * 另行提供 quit 與 disable 的清理入口。
 */
public final class BedrockFormNavigator {

    /** 通用管理拒絕鍵：派送失敗的唯一回報，不洩漏任何內部狀態。 */
    static final String DISPATCH_FAILURE_KEY = "command.land.manage.denied";

    /**
     * 實際送出表單的接縫，正常就是包著 FormService 並在每次呼叫時重新解析
     * 目前服務的 lambda；服務不可用時直接拋出，讓導航保持 fail-closed。
     */
    @FunctionalInterface
    public interface FormSender {
        FormSendResult send(UUID playerId, FormSpec spec, Consumer<FormResponse> callback);
    }

    /**
     * 回應執行緒的接縫，正常就是包著 SafeScheduler.runForPlayer 的 lambda。
     * 為了在 reload 後仍拿到新 scheduler，請傳 supplier 背書的 lambda；
     * 回傳的 task 為 null 或已取消都視為派送失敗，導航會 fail-closed。
     */
    @FunctionalInterface
    public interface Dispatcher {
        ScheduledTask dispatch(Runnable task);
    }

    /** 派送失敗時的單次回報出口，呼叫端把自己的 ReplySink 接上即可。 */
    @FunctionalInterface
    public interface FailureReply {
        void reply(String messageKey, Map<String, Object> vars);
    }

    /** 導航中的一頁：不可變的頁面識別加送出當下的表單規格。 */
    public record FormPage(String id, FormSpec spec) {
        public FormPage {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("form page id must not be null or blank");
            }
            Objects.requireNonNull(spec, "spec");
        }
    }

    private record Frame(FormPage page, long generation, Consumer<FormResponse> onResponse,
            Dispatcher dispatch, FailureReply failure, AtomicBoolean consumed) {
    }

    private final FormSender sender;
    private final ConcurrentHashMap<UUID, Deque<Frame>> stacks = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong(0L);

    /**
     * @param sender 表單送出接縫；null 表示上游不可用，所有操作 fail-closed
     */
    public BedrockFormNavigator(FormSender sender) {
        this.sender = sender;
    }

    /** 上游可用時才回傳 true；不可用時所有操作 fail-closed。 */
    public boolean isAvailable() {
        return sender != null;
    }

    /** 以此頁取代玩家整個堆疊；送出失敗時保留舊堆疊並回傳 empty。 */
    public Optional<Long> open(UUID playerId, FormPage page, Consumer<FormResponse> onResponse,
            Dispatcher dispatch, FailureReply failure) {
        long generation = nextGeneration();
        Frame frame = sendSingle(playerId, page, onResponse, dispatch, failure, generation);
        if (frame == null) {
            return Optional.empty();
        }
        Deque<Frame> stack = stacks.computeIfAbsent(playerId, key -> new ArrayDeque<>());
        synchronized (stack) {
            stack.clear();
            stack.push(frame);
        }
        return Optional.of(generation);
    }

    /** 在玩家目前堆疊上推一頁；送出失敗時保留舊堆疊並回傳 empty。 */
    public Optional<Long> push(UUID playerId, FormPage page, Consumer<FormResponse> onResponse,
            Dispatcher dispatch, FailureReply failure) {
        long generation = nextGeneration();
        Frame frame = sendSingle(playerId, page, onResponse, dispatch, failure, generation);
        if (frame == null) {
            return Optional.empty();
        }
        Deque<Frame> stack = stacks.computeIfAbsent(playerId, key -> new ArrayDeque<>());
        synchronized (stack) {
            stack.push(frame);
        }
        return Optional.of(generation);
    }

    /**
     * 以此頁替換頂層，其餘堆疊保留；無堆疊時等同 open，
     * 送出失敗時還原舊頂層並回傳 empty。
     */
    public Optional<Long> replace(UUID playerId, FormPage page, Consumer<FormResponse> onResponse,
            Dispatcher dispatch, FailureReply failure) {
        Deque<Frame> stack = playerId == null ? null : stacks.get(playerId);
        if (stack == null) {
            return open(playerId, page, onResponse, dispatch, failure);
        }
        Frame top;
        synchronized (stack) {
            top = stack.poll();
        }
        if (top == null) {
            return open(playerId, page, onResponse, dispatch, failure);
        }
        long generation = nextGeneration();
        Frame frame = sendSingle(playerId, page, onResponse, dispatch, failure, generation);
        synchronized (stack) {
            if (frame != null) {
                stack.push(frame);
                return Optional.of(generation);
            }
            stack.push(top);
            return Optional.empty();
        }
    }

    /**
     * 回到上一頁並重送；已在第一頁或無堆疊時清空並回傳 false，
     * 重送失敗時還原被 pop 的頁並回傳 false。
     */
    public boolean back(UUID playerId) {
        if (playerId == null || sender == null) {
            if (playerId == null) {
                return false;
            }
            Deque<Frame> existing = stacks.get(playerId);
            if (existing == null) {
                return false;
            }
            synchronized (existing) {
                if (existing.size() <= 1) {
                    close(playerId);
                    return false;
                }
            }
            return false;
        }
        Deque<Frame> stack = stacks.get(playerId);
        if (stack == null) {
            return false;
        }
        Frame popped;
        Frame previous;
        synchronized (stack) {
            if (stack.size() <= 1) {
                popped = null;
                previous = null;
            } else {
                popped = stack.pop();
                previous = stack.peek();
            }
        }
        if (previous == null) {
            close(playerId);
            return false;
        }
        Objects.requireNonNull(popped, "popped");
        long generation = nextGeneration();
        Frame resent = sendSingle(playerId, previous.page(), previous.onResponse(),
                previous.dispatch(), previous.failure(), generation);
        synchronized (stack) {
            if (resent != null) {
                Frame stale = stack.peek();
                if (stale != null && stale.page() == previous.page()) {
                    stack.poll();
                }
                stack.push(resent);
                return true;
            }
            stack.push(popped);
            return false;
        }
    }

    /** 關閉玩家全部追蹤，不做任何上游呼叫；可重複呼叫。 */
    public void close(UUID playerId) {
        if (playerId == null) {
            return;
        }
        Deque<Frame> stack = stacks.remove(playerId);
        if (stack == null) {
            return;
        }
        synchronized (stack) {
            stack.clear();
        }
    }

    /** 停用時清理，關閉全部追蹤；可重複呼叫。 */
    public void closeAll() {
        List<Map.Entry<UUID, Deque<Frame>>> snapshot = new ArrayList<>(stacks.entrySet());
        stacks.clear();
        for (Map.Entry<UUID, Deque<Frame>> entry : snapshot) {
            Deque<Frame> stack = entry.getValue();
            if (stack != null) {
                synchronized (stack) {
                    stack.clear();
                }
            }
        }
    }

    /**
     * 表單回應入口，永不拋出。只接受目前頂層的 generation 且每頂層只處理一次；
     * 過期、重複、未知、CLOSED/INVALID/null 回應一律靜默丟棄，其中關閉類回應
     * 另行清空該玩家堆疊。VALID 回應經由 dispatcher 執行，派送失敗或處理拋出
     * 時以通用拒絕回報一次並清理該玩家堆疊。
     */
    public void handleResponse(UUID playerId, long generation, FormResponse response) {
        if (playerId == null) {
            return;
        }
        Deque<Frame> stack = stacks.get(playerId);
        if (stack == null) {
            return;
        }
        Frame top;
        synchronized (stack) {
            top = stack.peek();
        }
        if (top == null || top.generation() != generation) {
            return;
        }
        if (!top.consumed().compareAndSet(false, true)) {
            return;
        }
        if (response == null || response.status() == null) {
            removeStack(playerId, stack);
            return;
        }
        switch (response.status()) {
            case CLOSED, INVALID -> removeStack(playerId, stack);
            case VALID -> dispatchValid(playerId, stack, top, response);
        }
    }

    /** 玩家目前頂層頁面，無追蹤時回傳 empty。 */
    public Optional<FormPage> currentPage(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        Deque<Frame> stack = stacks.get(playerId);
        if (stack == null) {
            return Optional.empty();
        }
        synchronized (stack) {
            Frame top = stack.peek();
            return top == null ? Optional.empty() : Optional.of(top.page());
        }
    }

    /** 玩家目前頂層 generation，無追蹤時回傳 empty。 */
    public Optional<Long> currentGeneration(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        Deque<Frame> stack = stacks.get(playerId);
        if (stack == null) {
            return Optional.empty();
        }
        synchronized (stack) {
            Frame top = stack.peek();
            return top == null ? Optional.empty() : Optional.of(top.generation());
        }
    }

    /** 玩家堆疊深度，無追蹤時為零。 */
    public int depth(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        Deque<Frame> stack = stacks.get(playerId);
        if (stack == null) {
            return 0;
        }
        synchronized (stack) {
            return stack.size();
        }
    }

    /** 有堆疊追蹤的玩家集合，永不為 null。 */
    public Set<UUID> trackedPlayers() {
        return Set.copyOf(stacks.keySet());
    }

    private long nextGeneration() {
        return generations.incrementAndGet();
    }

    /**
     * 送出一頁並包裝回應回呼；任何接縫缺失或送出失敗都回傳 null，
     * 呼叫端保留舊堆疊。成功時回傳已配發 generation 的 frame。
     */
    private Frame sendSingle(UUID playerId, FormPage page, Consumer<FormResponse> onResponse,
            Dispatcher dispatch, FailureReply failure, long generation) {
        if (playerId == null || page == null || onResponse == null
                || dispatch == null || failure == null || sender == null) {
            return null;
        }
        Frame frame = new Frame(page, generation, onResponse, dispatch, failure, new AtomicBoolean());
        FormSendResult result;
        try {
            result = sender.send(playerId, page.spec(), response ->
                    handleResponse(playerId, generation, response));
        } catch (RuntimeException sendFailure) {
            return null;
        }
        if (result == null || !result.isSent()) {
            return null;
        }
        return frame;
    }

    private void dispatchValid(UUID playerId, Deque<Frame> stack, Frame top, FormResponse response) {
        final ScheduledTask scheduled;
        try {
            scheduled = top.dispatch().dispatch(() -> runHandler(playerId, stack, top, response));
        } catch (RuntimeException dispatchFailure) {
            failDispatch(playerId, stack, top);
            return;
        }
        if (!accepted(scheduled)) {
            failDispatch(playerId, stack, top);
        }
    }

    private void runHandler(UUID playerId, Deque<Frame> stack, Frame top, FormResponse response) {
        try {
            top.onResponse().accept(response);
        } catch (RuntimeException handlerFailure) {
            failDispatch(playerId, stack, top);
        }
    }

    private void failDispatch(UUID playerId, Deque<Frame> stack, Frame top) {
        removeStack(playerId, stack);
        failQuietly(() -> top.failure().reply(DISPATCH_FAILURE_KEY, Map.of()));
    }

    private void removeStack(UUID playerId, Deque<Frame> stack) {
        stacks.remove(playerId, stack);
        synchronized (stack) {
            stack.clear();
        }
    }

    private static boolean accepted(ScheduledTask scheduled) {
        if (scheduled == null) {
            return false;
        }
        try {
            return !scheduled.isCancelled();
        } catch (RuntimeException stateFailure) {
            return false;
        }
    }

    private static void failQuietly(Runnable reply) {
        try {
            reply.run();
        } catch (RuntimeException ignored) {
            // 終端 fail-closed：壞掉的回報出口不可逃出導航。
        }
    }
}

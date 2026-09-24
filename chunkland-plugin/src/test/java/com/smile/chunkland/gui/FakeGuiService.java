package com.smile.chunkland.gui;

import com.smile.acelib.gui.GuiArgument;
import com.smile.acelib.gui.GuiAsyncRequest;
import com.smile.acelib.gui.GuiErrorCode;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.gui.GuiSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic in-memory {@link GuiService} stand-in for framework tests.
 * Generations are issued from a local counter so every open is observable;
 * validation mirrors the production contract: unknown player or generation
 * mismatch is rejected, an out-of-range slot is rejected, a protected slot
 * is rejected with {@link GuiErrorCode#SLOT_PROTECTED} (the upstream
 * internal listener cancels those clicks, so they never arrive as
 * {@code allowed}), and only an in-range unprotected slot is allowed.
 * A second open while the player still holds an active session is rejected
 * with {@link GuiErrorCode#SESSION_EXISTS}, matching production: callers
 * must close the live session before opening the next page.
 * No threads, no sleeps.
 */
final class FakeGuiService implements GuiService {

    /** Exact {@code closeInventory} invocations, in order. */
    record CloseCall(UUID playerUuid, long generation) {
    }

    /** Exact async update begin arguments observed by the renderer tests. */
    record BeginCall(UUID playerUuid, long sessionGeneration, int pageIndex) {
    }

    /** Exact async apply inputs observed by the renderer tests. */
    record ApplyCall(GuiAsyncRequest request, com.smile.acelib.gui.GuiPage<?> page,
            Runnable renderer) {
    }

    private final AtomicLong generations = new AtomicLong(100L);
    private final AtomicLong asyncRequestGenerations = new AtomicLong(100L);
    private final Map<UUID, GuiSession> active = new ConcurrentHashMap<>();
    private final Map<UUID, Long> latestAsyncRequests = new ConcurrentHashMap<>();
    private final List<GuiArgument> openedArgs = new CopyOnWriteArrayList<>();
    private final List<CloseCall> closeCalls = new CopyOnWriteArrayList<>();
    private final List<BeginCall> beginCalls = new CopyOnWriteArrayList<>();
    private final List<ApplyCall> applyCalls = new CopyOnWriteArrayList<>();
    private final List<String> validateCalls = new CopyOnWriteArrayList<>();
    private final AtomicLong shutdownCount = new AtomicLong();
    private final AtomicLong rendererCalls = new AtomicLong();
    private volatile boolean rejectNextOpen;
    private volatile boolean rejectAllClicks;
    private volatile boolean throwOnOpen;
    private volatile boolean rejectNextClose;
    private volatile boolean rejectNextAsyncBegin;
    private volatile boolean rejectNextAsyncApply;

    void rejectNextOpen() {
        rejectNextOpen = true;
    }

    void rejectAllClicks() {
        rejectAllClicks = true;
    }

    void throwOnOpen() {
        throwOnOpen = true;
    }

    void rejectNextClose() {
        rejectNextClose = true;
    }

    void rejectNextAsyncBegin() {
        rejectNextAsyncBegin = true;
    }

    void rejectNextAsyncApply() {
        rejectNextAsyncApply = true;
    }

    long shutdownCount() {
        return shutdownCount.get();
    }

    List<CloseCall> closeCalls() {
        return List.copyOf(closeCalls);
    }

    List<GuiArgument> openedArgs() {
        return List.copyOf(openedArgs);
    }

    List<String> validateCalls() {
        return List.copyOf(validateCalls);
    }

    List<BeginCall> beginCalls() {
        return List.copyOf(beginCalls);
    }

    List<ApplyCall> applyCalls() {
        return List.copyOf(applyCalls);
    }

    long rendererCalls() {
        return rendererCalls.get();
    }

    GuiSession activeSessionOf(UUID playerUuid) {
        return active.get(playerUuid);
    }

    @Override
    public GuiResult openInventory(GuiArgument arg) {
        if (throwOnOpen) {
            throw new RuntimeException("fake open boom");
        }
        if (arg != null) {
            openedArgs.add(arg);
        }
        if (arg != null && arg.playerUuid() != null && active.containsKey(arg.playerUuid())) {
            return GuiResult.rejected(GuiErrorCode.SESSION_EXISTS, "already has an active session");
        }
        if (rejectNextOpen) {
            rejectNextOpen = false;
            return GuiResult.rejected("SESSION_EXISTS", "occupied by fake");
        }
        long generation = generations.incrementAndGet();
        GuiSession session = new GuiSession(
            arg == null ? UUID.randomUUID() : arg.playerUuid(),
            generation,
            "fake",
            arg == null ? "t" : arg.title(),
            arg == null ? 9 : arg.size(),
            arg == null ? java.util.Set.of() : arg.protectedSlots());
        if (arg != null) {
            active.put(arg.playerUuid(), session);
        }
        return GuiResult.success(session);
    }

    @Override
    public GuiResult closeInventory(UUID uuid, long generation) {
        closeCalls.add(new CloseCall(uuid, generation));
        if (rejectNextClose) {
            rejectNextClose = false;
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH, "stale close");
        }
        GuiSession current = active.get(uuid);
        if (current != null && current.generation() == generation) {
            active.remove(uuid);
            return GuiResult.closed(current);
        }
        if (current == null) {
            return GuiResult.rejected("SESSION_NOT_FOUND", "no active session");
        }
        return GuiResult.rejected("GENERATION_MISMATCH", "generation mismatch");
    }

    @Override
    public GuiResult getActiveSession(UUID uuid) {
        GuiSession current = active.get(uuid);
        if (current != null) {
            return GuiResult.allowed(current);
        }
        return GuiResult.rejected("SESSION_NOT_FOUND", "no active session");
    }

    @Override
    public GuiResult validateClick(UUID uuid, long generation, int slot) {
        validateCalls.add(uuid + "#" + generation + "#" + slot);
        if (rejectAllClicks) {
            return GuiResult.rejected("CLICK_REJECTED", "rejected by fake");
        }
        GuiSession current = active.get(uuid);
        if (current == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND, "no active session");
        }
        if (current.generation() != generation) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH, "stale click");
        }
        if (slot < 0 || slot >= current.size()) {
            return GuiResult.rejected(GuiErrorCode.INVALID_INPUT,
                "slot " + slot + " is outside session size " + current.size());
        }
        if (current.protectedSlots().contains(slot)) {
            return GuiResult.rejected(GuiErrorCode.SLOT_PROTECTED,
                "slot " + slot + " is protected");
        }
        return GuiResult.allowed(current);
    }

    @Override
    public GuiResult beginAsyncUpdate(UUID playerUuid, long sessionGeneration,
            int pageIndex) {
        beginCalls.add(new BeginCall(playerUuid, sessionGeneration, pageIndex));
        if (rejectNextAsyncBegin) {
            rejectNextAsyncBegin = false;
            return GuiResult.rejected("STALE", "fake stale begin");
        }
        GuiSession current = active.get(playerUuid);
        if (current == null) {
            return GuiResult.rejected("SESSION_NOT_FOUND", "fake session missing");
        }
        if (current.generation() != sessionGeneration) {
            return GuiResult.rejected("ACELIB-GUI-011", "fake generation mismatch");
        }
        long requestGeneration = asyncRequestGenerations.incrementAndGet();
        latestAsyncRequests.put(playerUuid, requestGeneration);
        GuiAsyncRequest request = newAsyncRequest(playerUuid, sessionGeneration,
                pageIndex, requestGeneration);
        return GuiResult.success(current, request);
    }

    @Override
    public <T> GuiResult applyAsyncUpdate(GuiAsyncRequest request,
            com.smile.acelib.gui.GuiPage<T> page, Runnable renderer) {
        applyCalls.add(new ApplyCall(request, page, renderer));
        if (rejectNextAsyncApply) {
            rejectNextAsyncApply = false;
            return GuiResult.rejected("ACELIB-GUI-016", "fake stale apply");
        }
        if (request == null) {
            return GuiResult.failed("ACELIB-GUI-012", "fake missing request");
        }
        GuiSession current = active.get(request.playerUuid());
        if (current == null || current.generation() != request.sessionGeneration()) {
            return GuiResult.rejected("ACELIB-GUI-011", "fake session mismatch");
        }
        Long latest = latestAsyncRequests.get(request.playerUuid());
        if (latest == null || latest != request.requestGeneration()) {
            return GuiResult.rejected("ACELIB-GUI-016", "fake request mismatch");
        }
        rendererCalls.incrementAndGet();
        try {
            renderer.run();
            return GuiResult.success(current);
        } catch (RuntimeException failure) {
            return GuiResult.failed("ACELIB-GUI-012", failure.getMessage());
        }
    }

    @Override
    public String getModuleStatus() {
        return "fake-gui";
    }

    private static GuiAsyncRequest newAsyncRequest(UUID playerUuid, long sessionGeneration,
            int pageIndex, long requestGeneration) {
        try {
            var constructor = GuiAsyncRequest.class.getDeclaredConstructor(
                    UUID.class, long.class, int.class, long.class);
            constructor.setAccessible(true);
            return constructor.newInstance(playerUuid, sessionGeneration, pageIndex,
                    requestGeneration);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("fake async request construction failed", failure);
        }
    }

    @Override
    public void shutdown() {
        shutdownCount.incrementAndGet();
    }
}

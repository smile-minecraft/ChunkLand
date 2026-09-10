package com.smile.chunkland.gui;

import com.smile.acelib.gui.GuiArgument;
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
 * validation mirrors the production contract (unknown player or generation
 * mismatch is rejected, never throws). No threads, no sleeps.
 */
final class FakeGuiService implements GuiService {

    /** Exact {@code closeInventory} invocations, in order. */
    record CloseCall(UUID playerUuid, long generation) {
    }

    private final AtomicLong generations = new AtomicLong(100L);
    private final Map<UUID, GuiSession> active = new ConcurrentHashMap<>();
    private final List<GuiArgument> openedArgs = new CopyOnWriteArrayList<>();
    private final List<CloseCall> closeCalls = new CopyOnWriteArrayList<>();
    private final List<String> validateCalls = new CopyOnWriteArrayList<>();
    private final AtomicLong shutdownCount = new AtomicLong();
    private volatile boolean rejectNextOpen;
    private volatile boolean rejectAllClicks;
    private volatile boolean throwOnOpen;

    void rejectNextOpen() {
        rejectNextOpen = true;
    }

    void rejectAllClicks() {
        rejectAllClicks = true;
    }

    void throwOnOpen() {
        throwOnOpen = true;
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
        if (current != null && current.generation() == generation) {
            return GuiResult.allowed(current);
        }
        return GuiResult.rejected("GENERATION_MISMATCH", "stale click");
    }

    @Override
    public String getModuleStatus() {
        return "fake-gui";
    }

    @Override
    public void shutdown() {
        shutdownCount.incrementAndGet();
    }
}

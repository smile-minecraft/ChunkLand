package com.smile.chunkland.capability;

import com.smile.acelib.gui.GuiArgument;
import com.smile.acelib.gui.GuiAsyncRequest;
import com.smile.acelib.gui.GuiConfirmation;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.gui.GuiPage;
import com.smile.acelib.gui.GuiSession;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.event.Listener;

/**
 * Test-only {@link GuiService} stand-in. Configurable to return either an accepted or rejected
 * {@link GuiResult} from {@link #openInventory(GuiArgument)}, tracks every call for assertions,
 * and exposes {@link #shutdownCount()} so tests can assert ChunkLand does NOT call shutdown on
 * the shared GuiService during cleanup.
 */
final class StubGuiService implements GuiService {

    private final AtomicReference<GuiResult> nextResult;
    private final AtomicReference<GuiSession> activeSession = new AtomicReference<>();
    private final AtomicLong openCount = new AtomicLong();
    private final AtomicLong closeCount = new AtomicLong();
    private final AtomicLong shutdownCount = new AtomicLong();
    private final CopyOnWriteArrayList<GuiArgument> seenArgs = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<UUID> closeUuids = new CopyOnWriteArrayList<>();

    StubGuiService(GuiResult initialResult) {
        this.nextResult = new AtomicReference<>(initialResult);
    }

    void setNextResult(GuiResult r) {
        nextResult.set(r);
    }

    long openCount() {
        return openCount.get();
    }

    long closeCount() {
        return closeCount.get();
    }

    long shutdownCount() {
        return shutdownCount.get();
    }

    GuiArgument lastArg() {
        return seenArgs.isEmpty() ? null : seenArgs.get(seenArgs.size() - 1);
    }

    @Override
    public org.bukkit.event.Listener getListener() {
        return new Listener() { };
    }

    @Override
    public GuiResult openInventory(GuiArgument arg) {
        openCount.incrementAndGet();
        if (arg != null) {
            seenArgs.add(arg);
        }
        GuiResult r = nextResult.get();
        if (r != null && r.session() != null) {
            activeSession.set(r.session());
        }
        return r;
    }

    @Override
    public GuiResult closeInventory(UUID uuid, long generation) {
        closeCount.incrementAndGet();
        if (uuid != null) {
            closeUuids.add(uuid);
        }
        GuiSession cur = activeSession.get();
        if (cur != null && cur.playerUuid().equals(uuid) && cur.generation() == generation) {
            activeSession.set(null);
            return GuiResult.success(cur);
        }
        if (cur == null) {
            return GuiResult.rejected("SESSION_NOT_FOUND", "no active session");
        }
        return GuiResult.rejected("GENERATION_MISMATCH", "generation mismatch");
    }

    @Override
    public GuiResult getActiveSession(UUID uuid) {
        GuiSession cur = activeSession.get();
        if (cur != null && cur.playerUuid().equals(uuid)) {
            return GuiResult.allowed(cur);
        }
        return GuiResult.rejected("SESSION_NOT_FOUND", "no active session");
    }

    @Override
    public GuiResult validateClick(UUID uuid, long generation, int slot) {
        return nextResult.get();
    }

    @Override
    public GuiResult createConfirmation(UUID uuid, long generation, String actionId, Runnable action) {
        return nextResult.get();
    }

    @Override
    public GuiResult confirm(UUID uuid, long generation, String actionId) {
        return nextResult.get();
    }

    @Override
    public GuiResult cancel(UUID uuid, long generation, String actionId) {
        return nextResult.get();
    }

    @Override
    public GuiResult beginAsyncUpdate(UUID uuid, long generation, int requestId) {
        return nextResult.get();
    }

    @Override
    public <T> GuiResult applyAsyncUpdate(GuiAsyncRequest request, GuiPage<T> page, Runnable followup) {
        return nextResult.get();
    }

    @Override
    public String getModuleStatus() {
        return "stub-gui";
    }

    @Override
    public void shutdown() {
        shutdownCount.incrementAndGet();
    }
}

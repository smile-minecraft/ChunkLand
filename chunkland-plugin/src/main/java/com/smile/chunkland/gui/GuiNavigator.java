package com.smile.chunkland.gui;

import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.gui.GuiSession;
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

/**
 * Per-player GUI navigation over the shared upstream {@link GuiService}.
 *
 * <p>Each player owns an independent back stack of opened pages. Every
 * generation is taken from the upstream session returned at open time and is
 * never minted locally. Click dispatch always calls
 * {@link GuiService#validateClick(UUID, long, int)} first; a rejected or
 * mismatched click is dropped silently and the button callback never runs.</p>
 *
 * <p>Lifecycle: {@link #close(UUID)} and {@link #closeAll()} clear tracking
 * and best-effort close each tracked live session with its exact
 * player-plus-generation identity. Both are idempotent. Cleanup never calls
 * the provider-wide shutdown: the service is shared with other plugins.</p>
 *
 * <p>Everything runs synchronously on the caller's thread; no scheduler, no
 * background work, no Bukkit access. Callers that need region-scoped Bukkit
 * work inside a button callback must schedule it through their own
 * scheduler seam.</p>
 */
public final class GuiNavigator {

    /** One tracked open: the page plus the upstream generation that opened it. */
    private record Frame(GuiPage page, long generation) {
    }

    private final GuiService guiService;
    private final ConcurrentHashMap<UUID, Deque<Frame>> stacks = new ConcurrentHashMap<>();

    /**
     * @param guiService upstream service; may be {@code null}, in which case
     *     every operation fails closed and {@link #isAvailable()} is false
     */
    public GuiNavigator(GuiService guiService) {
        this.guiService = guiService;
    }

    /** @return false when no upstream service is held; all ops then fail closed. */
    public boolean isAvailable() {
        return guiService != null;
    }

    /**
     * Open {@code page} as the player's only screen, replacing any tracked
     * stack. A failed open leaves the previous stack untouched.
     *
     * @return the authoritative upstream generation, or empty on any failure
     */
    public Optional<Long> open(UUID playerUuid, GuiPage page) {
        if (playerUuid == null || page == null || guiService == null) {
            return Optional.empty();
        }
        Optional<Long> generation = openUpstream(playerUuid, page);
        if (generation.isEmpty()) {
            return Optional.empty();
        }
        Deque<Frame> stack = stacks.computeIfAbsent(playerUuid, key -> new ArrayDeque<>());
        synchronized (stack) {
            stack.clear();
            stack.push(new Frame(page, generation.orElseThrow()));
        }
        return generation;
    }

    /**
     * Open {@code page} on top of the player's current stack.
     *
     * @return the authoritative upstream generation, or empty on any failure
     *     (the existing stack is kept in that case)
     */
    public Optional<Long> push(UUID playerUuid, GuiPage page) {
        if (playerUuid == null || page == null || guiService == null) {
            return Optional.empty();
        }
        Optional<Long> generation = openUpstream(playerUuid, page);
        if (generation.isEmpty()) {
            return Optional.empty();
        }
        Deque<Frame> stack = stacks.computeIfAbsent(playerUuid, key -> new ArrayDeque<>());
        synchronized (stack) {
            stack.push(new Frame(page, generation.orElseThrow()));
        }
        return generation;
    }

    /**
     * Go back to the previous page. A failed re-open restores the popped
     * frame and reports {@code false}. When the player sits on the first
     * (or no) page the session is closed instead and {@code false} is
     * returned.
     *
     * @return true when the previous page is live again
     */
    public boolean back(UUID playerUuid) {
        if (playerUuid == null || guiService == null) {
            return false;
        }
        Deque<Frame> stack = stacks.get(playerUuid);
        if (stack == null) {
            return false;
        }
        Frame popped;
        GuiPage previous;
        synchronized (stack) {
            if (stack.size() <= 1) {
                popped = null;
                previous = null;
            } else {
                popped = stack.pop();
                previous = Objects.requireNonNull(stack.peek()).page();
            }
        }
        if (previous == null) {
            close(playerUuid);
            return false;
        }
        Optional<Long> generation = openUpstream(playerUuid, previous);
        synchronized (stack) {
            if (generation.isPresent()) {
                // Swap the stale previous frame for the re-opened one: the
                // fresh upstream generation is the only live session now.
                Frame stale = stack.peek();
                if (stale != null && stale.page() == previous) {
                    stack.poll();
                }
                stack.push(new Frame(previous, generation.orElseThrow()));
                return true;
            }
            stack.push(popped);
            return false;
        }
    }

    /**
     * Swap the top page for {@code page}, keeping the rest of the stack. An
     * empty stack behaves like {@link #open(UUID, GuiPage)}; a failed open
     * restores the previous top.
     *
     * @return the authoritative upstream generation, or empty on any failure
     */
    public Optional<Long> replace(UUID playerUuid, GuiPage page) {
        if (playerUuid == null || page == null || guiService == null) {
            return Optional.empty();
        }
        Deque<Frame> stack = stacks.get(playerUuid);
        if (stack == null) {
            return open(playerUuid, page);
        }
        Frame top;
        synchronized (stack) {
            top = stack.poll();
            if (top == null) {
                stack.clear();
            }
        }
        if (top == null) {
            return open(playerUuid, page);
        }
        Optional<Long> generation = openUpstream(playerUuid, page);
        synchronized (stack) {
            if (generation.isPresent()) {
                stack.push(new Frame(page, generation.orElseThrow()));
                return generation;
            }
            stack.push(top);
            return Optional.empty();
        }
    }

    /**
     * Close the player's live session (when tracked) and drop the whole
     * stack. Idempotent: untracked players issue no upstream call.
     */
    public void close(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        Deque<Frame> stack = stacks.remove(playerUuid);
        if (stack == null) {
            return;
        }
        Frame top;
        synchronized (stack) {
            top = stack.peek();
            stack.clear();
        }
        closeUpstream(playerUuid, top);
    }

    /**
     * Disable-time cleanup: close every tracked live session with its exact
     * player-plus-generation identity, then drop all tracking. Idempotent;
     * sessions never opened through this navigator are left untouched, and
     * the shared provider is never shut down.
     */
    public void closeAll() {
        List<Map.Entry<UUID, Deque<Frame>>> snapshot = new ArrayList<>(stacks.entrySet());
        stacks.clear();
        for (Map.Entry<UUID, Deque<Frame>> entry : snapshot) {
            Deque<Frame> stack = entry.getValue();
            Frame top;
            synchronized (stack) {
                top = stack.peek();
                stack.clear();
            }
            closeUpstream(entry.getKey(), top);
        }
    }

    /**
     * Dispatch one click. The upstream validation runs first; only an
     * accepted click whose generation still matches the tracked top frame and
     * whose slot carries a binding reaches its callback. Everything else is
     * dropped silently. A throwing callback is absorbed so dispatch stays
     * contained.
     */
    public void handleClick(UUID playerUuid, long generation, int slot) {
        if (playerUuid == null || guiService == null) {
            return;
        }
        GuiResult validation;
        try {
            validation = guiService.validateClick(playerUuid, generation, slot);
        } catch (RuntimeException ignored) {
            return;
        }
        if (!isClickAccepted(validation)) {
            return;
        }
        Deque<Frame> stack = stacks.get(playerUuid);
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
        Optional<GuiButton> button = top.page().buttonAt(slot);
        if (button.isEmpty()) {
            return;
        }
        synchronized (stack) {
            if (stack.peek() != top) {
                return;
            }
        }
        try {
            button.orElseThrow().action()
                .handle(new GuiClickContext(playerUuid, generation, slot, top.page().id()));
        } catch (RuntimeException ignored) {
            // A bad button must not break dispatch for other players.
        }
    }

    /** @return the player's top page, or empty when nothing is tracked. */
    public Optional<GuiPage> currentPage(UUID playerUuid) {
        if (playerUuid == null) {
            return Optional.empty();
        }
        Deque<Frame> stack = stacks.get(playerUuid);
        if (stack == null) {
            return Optional.empty();
        }
        synchronized (stack) {
            Frame top = stack.peek();
            return top == null ? Optional.empty() : Optional.of(top.page());
        }
    }

    /** @return the player's live generation, or empty when nothing is tracked. */
    public Optional<Long> currentGeneration(UUID playerUuid) {
        if (playerUuid == null) {
            return Optional.empty();
        }
        Deque<Frame> stack = stacks.get(playerUuid);
        if (stack == null) {
            return Optional.empty();
        }
        synchronized (stack) {
            Frame top = stack.peek();
            return top == null ? Optional.empty() : Optional.of(top.generation());
        }
    }

    /** @return tracked stack depth for the player; zero when untracked. */
    public int depth(UUID playerUuid) {
        if (playerUuid == null) {
            return 0;
        }
        Deque<Frame> stack = stacks.get(playerUuid);
        if (stack == null) {
            return 0;
        }
        synchronized (stack) {
            return stack.size();
        }
    }

    /** @return players with a tracked stack; never {@code null}. */
    public Set<UUID> trackedPlayers() {
        return Set.copyOf(stacks.keySet());
    }

    private Optional<Long> openUpstream(UUID playerUuid, GuiPage page) {
        GuiResult result;
        try {
            result = guiService.openInventory(page.toArgument(playerUuid));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        GuiSession session = result == null ? null : result.session();
        if (result == null || session == null || !playerUuid.equals(session.playerUuid())) {
            return Optional.empty();
        }
        if (!(result.isSuccess() || result.isAccepted() || result.isAllowed())) {
            return Optional.empty();
        }
        return Optional.of(session.generation());
    }

    private void closeUpstream(UUID playerUuid, Frame top) {
        if (top == null || guiService == null) {
            return;
        }
        try {
            guiService.closeInventory(playerUuid, top.generation());
        } catch (RuntimeException ignored) {
            // Cleanup stays best-effort; tracking is already dropped.
        }
    }

    private static boolean isClickAccepted(GuiResult validation) {
        return validation != null
            && (validation.isAllowed() || validation.isAccepted() || validation.isSuccess());
    }
}

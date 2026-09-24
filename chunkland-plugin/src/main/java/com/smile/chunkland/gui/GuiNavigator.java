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
import java.util.logging.Level;
import java.util.logging.Logger;

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
 * <p>Paging over a live session: the upstream service holds at most one
 * active session per player, so opening a second page while the previous one
 * is still live is rejected with {@code SESSION_EXISTS}. Every navigation
 * entry ({@link #open(UUID, GuiPage)}, {@link #push(UUID, GuiPage)},
 * {@link #back(UUID)} and {@link #replace(UUID, GuiPage)}) therefore retries
 * once through a close-then-reopen rollover: the tracked live session is
 * closed with its exact player-plus-generation identity and the next page is
 * opened once. A failed reopen keeps the failure fail-closed and restores
 * the tracked stack; untracked players never trigger a close, so a foreign
 * session is never touched.</p>
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
    private final Logger logger;
    private final ConcurrentHashMap<UUID, Deque<Frame>> stacks = new ConcurrentHashMap<>();

    /**
     * Wire value the upstream service reports in {@link GuiResult#errorCode()}
     * when the clicked slot is protected. It mirrors the public
     * {@code SLOT_PROTECTED} error code constant shipped in the AceLib GUI
     * package (verified against the release jar: the protected-slot branch of
     * {@code validateClick} rejects with exactly this code). Only
     * {@link GuiResult} public API is used here; no AceLib implementation
     * type is referenced.
     */
    private static final String SLOT_PROTECTED_CODE = "ACELIB-GUI-010";

    /**
     * Wire value the upstream service reports in {@link GuiResult#errorCode()}
     * when a second page is opened while the player still holds an active
     * session. It mirrors the public {@code SESSION_EXISTS} error code
     * constant shipped in the AceLib GUI package (verified against the
     * release jar: the already-open branch of {@code openInventory} rejects
     * with exactly this code). Only {@link GuiResult} public API is used
     * here; no AceLib implementation type is referenced.
     */
    private static final String SESSION_EXISTS_CODE = "ACELIB-GUI-009";

    /**
     * @param guiService upstream service; may be {@code null}, in which case
     *     every operation fails closed and {@link #isAvailable()} is false
     */
    public GuiNavigator(GuiService guiService) {
        this(guiService, Logger.getLogger(GuiNavigator.class.getName()));
    }

    /**
     * @param guiService upstream service; may be {@code null}
     * @param logger failure-path logger; may be {@code null}, which disables
     *     navigator diagnostics
     */
    public GuiNavigator(GuiService guiService, Logger logger) {
        this.guiService = guiService;
        this.logger = logger;
    }

    /** @return false when no upstream service is held; all ops then fail closed. */
    public boolean isAvailable() {
        return guiService != null;
    }

    /**
     * Open {@code page} as the player's only screen, replacing any tracked
     * stack. A failed open leaves the previous stack untouched. When the
     * player still holds the tracked live session the upstream rejects the
     * open, so the live session is closed first and the open retried once.
     *
     * @return the authoritative upstream generation, or empty on any failure
     */
    public Optional<Long> open(UUID playerUuid, GuiPage page) {
        if (playerUuid == null || page == null || guiService == null) {
            return Optional.empty();
        }
        Optional<Long> generation = openReplacingLiveSession(playerUuid, page, peekTop(playerUuid));
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
     * Open {@code page} on top of the player's current stack. When the
     * player still holds the tracked live session the upstream rejects the
     * open, so the live session is closed first and the open retried once.
     *
     * @return the authoritative upstream generation, or empty on any failure
     *     (the existing stack is kept in that case)
     */
    public Optional<Long> push(UUID playerUuid, GuiPage page) {
        if (playerUuid == null || page == null || guiService == null) {
            return Optional.empty();
        }
        Optional<Long> generation = openReplacingLiveSession(playerUuid, page, peekTop(playerUuid));
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
     * Go back to the previous page. The live session popped off the top is
     * closed first when the upstream still holds it, then the previous page
     * is re-opened. A failed re-open restores the popped
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
        Optional<Long> generation = openReplacingLiveSession(playerUuid, previous, popped);
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
     * empty stack behaves like {@link #open(UUID, GuiPage)}; the live top
     * session is closed first when the upstream still holds it, then the
     * replacement is opened. A failed open restores the previous top.
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
        Optional<Long> generation = openReplacingLiveSession(playerUuid, page, top);
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
     * Dispatch one click. The upstream validation runs first; an accepted
     * click, or a click rejected only as a protected slot, whose generation
     * still matches the tracked top frame and whose slot carries a binding
     * reaches its callback. Everything else is dropped silently. A throwing
     * callback is absorbed so dispatch stays contained.
     *
     * <p>A bound button always travels as a protected slot
     * ({@link GuiPage#toArgument(UUID)}), so production reports the real
     * button click as a {@code SLOT_PROTECTED} rejection — the upstream
     * internal listener already cancelled it in the inventory view. The
     * page binding stays the authority: a protected rejection without a
     * binding for that exact slot is still dropped.</p>
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
        if (!isClickAccepted(validation) && !isProtectedSlotRejection(validation)) {
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

    /**
     * Close the tracked session before opening {@code page}. A close failure
     * gets one bounded retry; reopening is attempted only after the upstream
     * confirms that the tracked session is gone. If the upstream still reports
     * {@code SESSION_EXISTS}, the active session is checked before one final
     * close-and-reopen attempt. A mismatched generation remains fail-closed so
     * a session that replaced the tracked one is never touched.
     */
    private Optional<Long> openReplacingLiveSession(UUID playerUuid, GuiPage page, Frame live) {
        if (live != null) {
            GuiResult closed = closeUpstream(playerUuid, live);
            if (!isCloseComplete(closed)) {
                if (hasActiveSession(playerUuid)) {
                    log(Level.WARNING, "GUI rollover close failed; retrying once");
                    closed = closeUpstream(playerUuid, live);
                }
                if (!isCloseComplete(closed) && hasActiveSession(playerUuid)) {
                    log(Level.WARNING, "GUI rollover aborted after close retry failed");
                    return Optional.empty();
                }
            }
        }
        GuiResult first = tryOpenUpstream(playerUuid, page);
        Optional<Long> generation = generationOf(playerUuid, first);
        if (generation.isPresent()) {
            return generation;
        }
        if (!isSessionExistsRejection(first) || !hasActiveSession(playerUuid)) {
            log(Level.WARNING, "GUI rollover reopen failed");
            return Optional.empty();
        }
        log(Level.FINE, "GUI rollover reopen found an active session; retrying close");
        if (live == null) {
            log(Level.WARNING, "GUI rollover refused to close an untracked session");
            return Optional.empty();
        }
        GuiResult closed = closeUpstream(playerUuid, live);
        if (!isCloseComplete(closed) && hasActiveSession(playerUuid)) {
            log(Level.WARNING, "GUI rollover retry close failed");
            return Optional.empty();
        }
        Optional<Long> retried = generationOf(playerUuid, tryOpenUpstream(playerUuid, page));
        if (retried.isEmpty()) {
            log(Level.WARNING, "GUI rollover reopen retry failed");
        }
        return retried;
    }

    /** @return the player's tracked top frame, or {@code null} when untracked. */
    private Frame peekTop(UUID playerUuid) {
        Deque<Frame> stack = stacks.get(playerUuid);
        if (stack == null) {
            return null;
        }
        synchronized (stack) {
            return stack.peek();
        }
    }

    /** Raw upstream open; a throwing service reads as {@code null}. */
    private GuiResult tryOpenUpstream(UUID playerUuid, GuiPage page) {
        try {
            return guiService.openInventory(page.toArgument(playerUuid));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Authoritative generation from a raw upstream result, or empty. */
    private static Optional<Long> generationOf(UUID playerUuid, GuiResult result) {
        GuiSession session = result == null ? null : result.session();
        if (result == null || session == null || !playerUuid.equals(session.playerUuid())) {
            return Optional.empty();
        }
        if (!(result.isSuccess() || result.isAccepted() || result.isAllowed())) {
            return Optional.empty();
        }
        return Optional.of(session.generation());
    }

    /**
     * @return true only when the upstream rejected the open solely because
     *     the player still holds an active session. Any other rejection
     *     stays fail-closed without a close attempt.
     */
    private static boolean isSessionExistsRejection(GuiResult result) {
        return result != null && result.isRejected()
            && SESSION_EXISTS_CODE.equals(result.errorCode());
    }

    private GuiResult closeUpstream(UUID playerUuid, Frame top) {
        if (top == null || guiService == null) {
            return null;
        }
        try {
            return guiService.closeInventory(playerUuid, top.generation());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean hasActiveSession(UUID playerUuid) {
        try {
            GuiResult active = guiService.getActiveSession(playerUuid);
            return active != null && active.session() != null
                && playerUuid.equals(active.session().playerUuid());
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static boolean isCloseComplete(GuiResult result) {
        return result != null
            && (result.isSuccess() || result.isAccepted() || result.isAllowed()
                || "CLOSED".equals(String.valueOf(result.state()))
                || "ACELIB-GUI-008".equals(result.errorCode())
                || "SESSION_NOT_FOUND".equals(result.errorCode()));
    }

    private void log(Level level, String message) {
        if (logger != null) {
            logger.log(level, message);
        }
    }

    private static boolean isClickAccepted(GuiResult validation) {
        return validation != null
            && (validation.isAllowed() || validation.isAccepted() || validation.isSuccess());
    }

    /**
     * @return true only when the upstream rejected the click solely because
     *     the slot is protected. Any other rejection (unknown session, stale
     *     generation, out-of-range slot) stays fail-closed.
     */
    private static boolean isProtectedSlotRejection(GuiResult validation) {
        return validation != null && validation.isRejected()
            && SLOT_PROTECTED_CODE.equals(validation.errorCode());
    }
}

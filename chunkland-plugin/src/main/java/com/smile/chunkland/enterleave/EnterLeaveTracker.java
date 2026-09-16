package com.smile.chunkland.enterleave;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player prompt boundary memory.
 *
 * <p>Transition table (only {@code LandId} / {@code SubLandId} changes emit):
 * <ul>
 *   <li>Wilderness to land without SubLand: one land enter.</li>
 *   <li>Wilderness to land with SubLand: one SubLand enter.</li>
 *   <li>Land without SubLand to Wilderness: one land leave.</li>
 *   <li>Land with SubLand to Wilderness: one SubLand leave.</li>
 *   <li>Land A to land B: leave (of the side left) then enter (of the side
 *   entered); each side uses its SubLand kind when that side has a SubLand.</li>
 *   <li>Same land, no SubLand to SubLand: one SubLand enter; the reverse is
 *   one SubLand leave; SubLand A to SubLand B is leave then enter.</li>
 *   <li>Same boundary (for example crossing chunks inside one land): silent.</li>
 *   <li>First observation for a player: silent baseline, no prompt. An
 *   unobserved from-state must never produce an unconfirmed prompt.</li>
 * </ul>
 *
 * <p>All state lives in a {@link ConcurrentHashMap}; the event thread never
 * waits. Rename-only changes keep the same ids and stay silent.
 */
public final class EnterLeaveTracker {

    static final int MAX_ENTRIES = 2048;

    private final ConcurrentHashMap<UUID, EnterLeavePosition> last = new ConcurrentHashMap<>();

    /**
     * Records the current position and returns the prompts for the crossing
     * since the previous observation. Never {@code null}.
     */
    public List<EnterLeaveNotice> updateAndDiff(UUID playerId, EnterLeavePosition current) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(current, "current");
        EnterLeavePosition previous = last.put(playerId, current);
        boundIfNeeded(playerId);
        if (previous == null) {
            return List.of();
        }
        return diff(previous, current);
    }

    /** Quit-time forget; memory only. */
    public void remove(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        last.remove(playerId);
    }

    /** Disable-time reset; memory only. */
    public void clear() {
        last.clear();
    }

    int sizeForTest() {
        return last.size();
    }

    /**
     * Pure boundary diff. The first-observation rule lives in
     * {@link #updateAndDiff}: a direct call with two positions always
     * describes the crossing between them.
     */
    static List<EnterLeaveNotice> diff(EnterLeavePosition from, EnterLeavePosition to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.sameBoundary(to)) {
            return List.of();
        }
        if (Objects.equals(from.landId(), to.landId())) {
            return diffSub(from, to);
        }
        List<EnterLeaveNotice> notices = new ArrayList<>(2);
        if (from.landId() != null) {
            notices.add(from.subLandId() == null
                    ? new EnterLeaveNotice(NoticeKind.LEAVE_LAND, from)
                    : new EnterLeaveNotice(NoticeKind.LEAVE_SUB, from));
        }
        if (to.landId() != null) {
            notices.add(to.subLandId() == null
                    ? new EnterLeaveNotice(NoticeKind.ENTER_LAND, to)
                    : new EnterLeaveNotice(NoticeKind.ENTER_SUB, to));
        }
        return List.copyOf(notices);
    }

    private static List<EnterLeaveNotice> diffSub(EnterLeavePosition from, EnterLeavePosition to) {
        if (from.subLandId() == null) {
            return List.of(new EnterLeaveNotice(NoticeKind.ENTER_SUB, to));
        }
        if (to.subLandId() == null) {
            return List.of(new EnterLeaveNotice(NoticeKind.LEAVE_SUB, from));
        }
        return List.of(
                new EnterLeaveNotice(NoticeKind.LEAVE_SUB, from),
                new EnterLeaveNotice(NoticeKind.ENTER_SUB, to));
    }

    private void boundIfNeeded(UUID justPut) {
        while (last.size() > MAX_ENTRIES) {
            var cursor = last.keys();
            boolean evicted = false;
            while (cursor.hasMoreElements()) {
                UUID candidate = cursor.nextElement();
                if (!candidate.equals(justPut)) {
                    last.remove(candidate);
                    evicted = true;
                    break;
                }
            }
            if (!evicted) {
                break;
            }
        }
    }
}

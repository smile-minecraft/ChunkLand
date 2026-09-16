package com.smile.chunkland.enterleave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Transition table for Land / SubLand enter-leave prompts.
 *
 * <p>Only a {@code LandId} or {@code SubLandId} change emits; staying on the
 * same boundary (for example crossing chunks inside one land) is silent.
 */
class EnterLeaveTrackerTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final LandId LAND_A = new LandId(UUID.randomUUID());
    private static final LandId LAND_B = new LandId(UUID.randomUUID());
    private static final SubLandId SUB_A = new SubLandId(UUID.randomUUID());
    private static final SubLandId SUB_B = new SubLandId(UUID.randomUUID());

    private static EnterLeavePosition wild() {
        return EnterLeavePosition.wilderness(WORLD);
    }

    private static EnterLeavePosition land(LandId land, String name) {
        return new EnterLeavePosition(WORLD, land, null, name, null);
    }

    private static EnterLeavePosition sub(LandId land, String landName, SubLandId sub, String subName) {
        return new EnterLeavePosition(WORLD, land, sub, landName, subName);
    }

    @Test
    void wildernessToLandEmitsEnter() {
        EnterLeaveTracker tracker = new EnterLeaveTracker();
        UUID player = UUID.randomUUID();
        assertTrue(tracker.updateAndDiff(player, wild()).isEmpty());
        List<EnterLeaveNotice> notices = tracker.updateAndDiff(player, land(LAND_A, "Home"));
        assertEquals(1, notices.size());
        assertEquals(NoticeKind.ENTER_LAND, notices.get(0).kind());
        assertEquals(LAND_A, notices.get(0).position().landId());
    }

    @Test
    void landToWildernessEmitsLeave() {
        EnterLeaveTracker tracker = new EnterLeaveTracker();
        UUID player = UUID.randomUUID();
        assertTrue(tracker.updateAndDiff(player, land(LAND_A, "Home")).isEmpty());
        List<EnterLeaveNotice> notices = tracker.updateAndDiff(player, wild());
        assertEquals(1, notices.size());
        assertEquals(NoticeKind.LEAVE_LAND, notices.get(0).kind());
        assertEquals(LAND_A, notices.get(0).position().landId());
    }

    @Test
    void landToLandEmitsLeaveThenEnter() {
        EnterLeaveTracker tracker = new EnterLeaveTracker();
        UUID player = UUID.randomUUID();
        assertTrue(tracker.updateAndDiff(player, land(LAND_A, "Home")).isEmpty());
        List<EnterLeaveNotice> notices = tracker.updateAndDiff(player, land(LAND_B, "Farm"));
        assertEquals(2, notices.size());
        assertEquals(NoticeKind.LEAVE_LAND, notices.get(0).kind());
        assertEquals(LAND_A, notices.get(0).position().landId());
        assertEquals(NoticeKind.ENTER_LAND, notices.get(1).kind());
        assertEquals(LAND_B, notices.get(1).position().landId());
    }

    @Test
    void sameLandAcrossChunksIsSilent() {
        EnterLeaveTracker tracker = new EnterLeaveTracker();
        UUID player = UUID.randomUUID();
        assertTrue(tracker.updateAndDiff(player, land(LAND_A, "Home")).isEmpty());
        assertTrue(tracker.updateAndDiff(player, land(LAND_A, "Home")).isEmpty());
    }

    @Test
    void subLandEnterAndLeave() {
        EnterLeaveTracker tracker = new EnterLeaveTracker();
        UUID player = UUID.randomUUID();
        assertTrue(tracker.updateAndDiff(player, land(LAND_A, "Home")).isEmpty());
        List<EnterLeaveNotice> enter = tracker.updateAndDiff(player, sub(LAND_A, "Home", SUB_A, "Storage"));
        assertEquals(1, enter.size());
        assertEquals(NoticeKind.ENTER_SUB, enter.get(0).kind());
        List<EnterLeaveNotice> leave = tracker.updateAndDiff(player, land(LAND_A, "Home"));
        assertEquals(1, leave.size());
        assertEquals(NoticeKind.LEAVE_SUB, leave.get(0).kind());
        assertEquals(SUB_A, leave.get(0).position().subLandId());
    }

    @Test
    void subLandToSubLandEmitsChange() {
        EnterLeaveTracker tracker = new EnterLeaveTracker();
        UUID player = UUID.randomUUID();
        assertTrue(tracker.updateAndDiff(player, sub(LAND_A, "Home", SUB_A, "Storage")).isEmpty());
        List<EnterLeaveNotice> notices =
                tracker.updateAndDiff(player, sub(LAND_A, "Home", SUB_B, "Shop"));
        assertEquals(2, notices.size());
        assertEquals(NoticeKind.LEAVE_SUB, notices.get(0).kind());
        assertEquals(SUB_A, notices.get(0).position().subLandId());
        assertEquals(NoticeKind.ENTER_SUB, notices.get(1).kind());
        assertEquals(SUB_B, notices.get(1).position().subLandId());
    }

    @Test
    void duplicateUpdateEmitsNothing() {
        EnterLeaveTracker tracker = new EnterLeaveTracker();
        UUID player = UUID.randomUUID();
        assertTrue(tracker.updateAndDiff(player, sub(LAND_A, "Home", SUB_A, "Storage")).isEmpty());
        assertTrue(tracker.updateAndDiff(player, sub(LAND_A, "Home", SUB_A, "Storage")).isEmpty());
    }

    @Test
    void removeClearsPerPlayerState() {
        EnterLeaveTracker tracker = new EnterLeaveTracker();
        UUID player = UUID.randomUUID();
        assertTrue(tracker.updateAndDiff(player, land(LAND_A, "Home")).isEmpty());
        tracker.remove(player);
        assertEquals(0, tracker.sizeForTest());
        assertTrue(tracker.updateAndDiff(player, land(LAND_A, "Home")).isEmpty());
    }
}

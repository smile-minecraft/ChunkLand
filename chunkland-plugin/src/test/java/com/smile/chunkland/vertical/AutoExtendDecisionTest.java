package com.smile.chunkland.vertical;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.runtime.vertical.AutoExtendDecision;
import com.smile.chunkland.runtime.vertical.AutoExtendTrigger;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure gate for the Auto Extend trigger rule (spec §18).
 *
 * <p>Only a legal {@code BLOCK_BREAK} / {@code BLOCK_PLACE} by an authorized
 * actor (owner or a player holding the matching permission) inside a land
 * may propose an extend, and the stored depth only ever moves downward. The
 * nine listed behaviours never trigger, unauthorized attempts never
 * trigger, and the proposal compares against the stored row only — never
 * against the mode-dependent effective plane, so tracking works under both
 * vertical modes.
 */
class AutoExtendDecisionTest {

    private static final int STORED = 60;
    private static final int BUFFER = 5;
    private static final int WORLD_MIN = -64;

    @Test
    void nineListedBehavioursNeverTrigger() {
        AutoExtendTrigger[] silent = {
            AutoExtendTrigger.MOVE_OR_TELEPORT,
            AutoExtendTrigger.MOB_ACTIVITY,
            AutoExtendTrigger.EXPLOSION,
            AutoExtendTrigger.FLUID_FLOW,
            AutoExtendTrigger.FIRE,
            AutoExtendTrigger.FALLING_OR_DROPPED,
            AutoExtendTrigger.PROJECTILE,
            AutoExtendTrigger.PISTON,
            AutoExtendTrigger.UNAUTHORIZED_ATTEMPT,
        };
        assertEquals(9, silent.length, "spec lists exactly nine non-triggering behaviours");
        for (AutoExtendTrigger trigger : silent) {
            assertFalse(trigger.triggersExtend(), trigger + " must be classified silent");
            assertEquals(Optional.empty(),
                    AutoExtendDecision.decide(trigger, true, true, STORED, -100, BUFFER, WORLD_MIN),
                    trigger + " must not propose even far below the plane");
        }
    }

    @Test
    void onlyBreakAndPlaceMayTrigger() {
        assertTrue(AutoExtendTrigger.BLOCK_BREAK.triggersExtend());
        assertTrue(AutoExtendTrigger.BLOCK_PLACE.triggersExtend());
    }

    @Test
    void unauthorizedAttemptNeverTriggers() {
        assertEquals(Optional.empty(),
                AutoExtendDecision.decide(AutoExtendTrigger.BLOCK_BREAK, false, true, STORED, 20, BUFFER, WORLD_MIN));
        assertEquals(Optional.empty(),
                AutoExtendDecision.decide(AutoExtendTrigger.BLOCK_PLACE, false, true, STORED, 20, BUFFER, WORLD_MIN));
        assertEquals(Optional.empty(),
                AutoExtendDecision.decide(AutoExtendTrigger.UNAUTHORIZED_ATTEMPT, false, true, STORED, 20, BUFFER, WORLD_MIN));
    }

    @Test
    void wildernessNeverTriggers() {
        assertEquals(Optional.empty(),
                AutoExtendDecision.decide(AutoExtendTrigger.BLOCK_BREAK, true, false, STORED, 20, BUFFER, WORLD_MIN));
    }

    @Test
    void legalBreakAndPlaceExtendDownward() {
        assertEquals(Optional.of(15),
                AutoExtendDecision.decide(AutoExtendTrigger.BLOCK_BREAK, true, true, STORED, 20, BUFFER, WORLD_MIN));
        assertEquals(Optional.of(15),
                AutoExtendDecision.decide(AutoExtendTrigger.BLOCK_PLACE, true, true, STORED, 20, BUFFER, WORLD_MIN));
    }

    @Test
    void shallowOperationNeverTriggers() {
        // Operation plane at or above the stored row proposes nothing: the
        // stored depth only moves downward, never upward.
        assertEquals(Optional.empty(),
                AutoExtendDecision.decide(AutoExtendTrigger.BLOCK_BREAK, true, true, 15, 20, BUFFER, WORLD_MIN));
        assertEquals(Optional.empty(),
                AutoExtendDecision.decide(AutoExtendTrigger.BLOCK_BREAK, true, true, 15, 15, 0, WORLD_MIN));
    }

    @Test
    void worldMinClampsTheProposal() {
        assertEquals(Optional.of(WORLD_MIN),
                AutoExtendDecision.decide(AutoExtendTrigger.BLOCK_BREAK, true, true, 0, -100, BUFFER, WORLD_MIN));
    }

    @Test
    void storedTracksDevelopmentRegardlessOfMode() {
        // Mode-independent by construction: the gate takes no mode and never
        // consults the effective plane, so under FULL_HEIGHT the stored row
        // still tracks real development while protection reads the world
        // minimum. The effective value is never written back to stored.
        Optional<Integer> proposal = AutoExtendDecision.decide(
                AutoExtendTrigger.BLOCK_BREAK, true, true, 59, 20, BUFFER, WORLD_MIN);
        assertEquals(Optional.of(15), proposal);
        assertNotEquals(WORLD_MIN, proposal.orElseThrow());
    }

    @Test
    void negativeBufferFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> AutoExtendDecision.decide(
                AutoExtendTrigger.BLOCK_BREAK, true, true, STORED, 20, -1, WORLD_MIN));
    }

    @Test
    void nullTriggerFailsClosed() {
        assertThrows(NullPointerException.class, () -> AutoExtendDecision.decide(
                null, true, true, STORED, 20, BUFFER, WORLD_MIN));
    }
}

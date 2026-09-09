package com.smile.chunkland.runtime.vertical;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure Auto Extend decision gate (spec §18).
 *
 * <p>An extend is proposed only when <em>all</em> of the following hold:
 *
 * <ol>
 *   <li>the trigger kind {@linkplain AutoExtendTrigger#triggersExtend may trigger};
 *   <li>the actor is already authorized (owner or holding the matching
 *       {@code BLOCK_BREAK} / {@code BLOCK_PLACE} grant — resolved by the
 *       caller, never re-checked here);
 *   <li>the chunk belongs to a land (wilderness has no stored row);
 *   <li>the stored depth actually moves downward
 *       ({@link VerticalDepths#proposeExtend} enforces the downward-only and
 *       world-minimum rules).
 * </ol>
 *
 * <p>The proposal is mode-independent: it compares the operation plane
 * against the stored row only, never against the mode-dependent effective
 * plane. Under {@code FULL_HEIGHT} the effective plane is the world
 * minimum while the stored row keeps tracking real development, so
 * switching back to {@code PER_CHUNK_DEPTH} restores the accumulated
 * depths. The effective value is never consulted and never written back
 * to stored.
 *
 * <p>No Bukkit, SQL or I/O: the caller supplies the pre-resolved
 * authorization, membership, depths and heights.
 */
public final class AutoExtendDecision {

    private AutoExtendDecision() {
    }

    /**
     * Decide whether an operation proposes a depth extend.
     *
     * @param trigger operation kind, already classified by the caller
     * @param actorAuthorized owner or permission holder, resolved by the caller
     * @param inLand false for wilderness (no stored row to extend)
     * @param currentStored normalized current persisted depth
     * @param operationY block Y of the operation
     * @param buffer non-negative auto-extend buffer
     * @param worldMinHeight inclusive world minimum (lower clamp)
     * @return the deeper stored value to persist, or empty when silent
     */
    public static Optional<Integer> decide(
            AutoExtendTrigger trigger,
            boolean actorAuthorized,
            boolean inLand,
            int currentStored,
            int operationY,
            int buffer,
            int worldMinHeight) {
        Objects.requireNonNull(trigger, "trigger");
        if (!trigger.triggersExtend()) {
            return Optional.empty();
        }
        if (!actorAuthorized) {
            return Optional.empty();
        }
        if (!inLand) {
            return Optional.empty();
        }
        return VerticalDepths.proposeExtend(currentStored, operationY, buffer, worldMinHeight);
    }
}

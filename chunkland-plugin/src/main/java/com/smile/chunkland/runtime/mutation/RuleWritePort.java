package com.smile.chunkland.runtime.mutation;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Durable commit seam for per-land environment rules behind the public
 * typed mutations.
 *
 * <p>The production implementation delegates to the conditional rule
 * write the command and management paths use: it pins the observed
 * explicit value and the durable authorisation generation inside one
 * transaction, publishes the rebuilt snapshot once and fires exactly
 * one public change event. A stale pin fails without writing,
 * publishing or emitting. The explicit baseline is read from the live
 * authorisation snapshot inside the implementation, so the pin is
 * always fresher than the facade's pre-check and the transaction stays
 * the authority.
 */
public interface RuleWritePort {

    /**
     * Durably apply one per-land environment rule ({@code INHERIT} deletes
     * the override row).
     *
     * @param actor mutating operator
     * @param landId affected land
     * @param rule environment rule that changes
     * @param state newly effective state
     * @param expectedRevision authorisation generation observed by the caller
     */
    CompletionStage<Void> apply(
            UUID actor, LandId landId, LandRuleType rule, PermissionState state, long expectedRevision);
}

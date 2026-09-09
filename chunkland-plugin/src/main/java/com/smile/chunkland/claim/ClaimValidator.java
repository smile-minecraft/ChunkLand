package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Step-one revalidation seam: selection, collision, connectivity, limit and
 * structure-revision checks against live state.
 *
 * <p>Implementations must be non-blocking: no SQL, no waiting on futures, no
 * Bukkit world access. The atomic domain commit remains the final authority via
 * its database constraints; this seam rejects stale or conflicting claims
 * before any ledger row or charge exists.
 *
 * @throws ClaimRejectedException when the claim must end as REJECTED
 */
@FunctionalInterface
public interface ClaimValidator {

    ValidatedClaim validate(ClaimRequest request) throws ClaimRejectedException;

    /** Revision source for the optional confirmation token; empty means no token to check. */
    @FunctionalInterface
    interface RevisionSource {
        /** Current revision for the actor, or empty when no live selection exists. */
        OptionalLong currentRevision(UUID actorUuid);

        static RevisionSource none() {
            return actorUuid -> OptionalLong.empty();
        }
    }

    /** Generation source for the optional session token; empty means no live session. */
    @FunctionalInterface
    interface SessionGenerationSource {
        /** Current session generation for the actor, or empty when no live session exists. */
        OptionalLong currentGeneration(UUID actorUuid);

        static SessionGenerationSource none() {
            return actorUuid -> OptionalLong.empty();
        }
    }

    /**
     * Live structure revision for the optional confirmation target; empty
     * means the target cannot be resolved right now.
     *
     * <p>The validator only consults this source when the request carries
     * both a target and a structure token. An empty result then fails closed
     * (the confirmation is rejected as unverifiable) so a stale target can
     * never pass by default; requests without a target skip the check.
     */
    @FunctionalInterface
    interface StructureRevisionSource {
        /** Current structure revision for the target land, or empty when unresolvable. */
        OptionalLong currentRevision(LandId targetLandId);

        static StructureRevisionSource none() {
            return targetLandId -> OptionalLong.empty();
        }
    }
}

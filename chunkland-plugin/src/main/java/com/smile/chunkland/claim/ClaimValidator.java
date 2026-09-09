package com.smile.chunkland.claim;

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
}

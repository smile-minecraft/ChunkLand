package com.smile.chunkland.claim;

/**
 * Step-one revalidation seam for land expansion.
 *
 * <p>Implementations must be non-blocking: no SQL, no waiting on futures, no
 * Bukkit world access, and no chunk loading. The atomic domain commit remains
 * the final authority via its database constraints; this seam rejects stale
 * or conflicting expansions before any ledger row or charge exists.
 *
 * @throws ClaimRejectedException when the expansion must end as REJECTED
 */
@FunctionalInterface
public interface ExpandValidator {

    ValidatedExpand validate(ExpandRequest request) throws ClaimRejectedException;
}

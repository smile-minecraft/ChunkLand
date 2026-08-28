package com.smile.chunkland.api.mutation;

/**
 * Outcome of a mutation request (spec §85).
 */
public enum MutationOutcome {
    /** Durably committed. */
    SUCCESS,
    /** Rejected by a business rule before commit (e.g. name conflict, limit). */
    REJECTED,
    /** Failed due to an internal / external error after validation. */
    FAILED
}

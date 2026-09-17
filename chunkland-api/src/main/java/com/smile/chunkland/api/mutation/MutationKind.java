package com.smile.chunkland.api.mutation;

/**
 * Vocabulary of mutation kinds (spec §85, §86, §77). The names mirror the public
 * events / audit actions so the request envelope and the eventual durable ledger
 * share one stable vocabulary.
 *
 * <p>This is the V1-3 contract core; later milestones add concrete request
 * shapes per kind (claim pricing, subland geometry, stable mutation API).
 *
 * @since 0.1.0
 */
public enum MutationKind {
    LAND_CREATE,
    LAND_DELETE,
    LAND_CHUNK_ADD,
    LAND_CHUNK_REMOVE,
    LAND_RENAME,
    SUBLAND_CREATE,
    SUBLAND_DELETE,
    PERMISSION_CHANGE,
    RULE_CHANGE
}

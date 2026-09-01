package com.smile.chunkland.runtime.storage;

/**
 * Mutation kinds that may target a world. Every value must be rejected when
 * the target world is {@code ISOLATED}.
 */
public enum MutationKind {
    CREATE,
    UPDATE,
    DELETE,
    ADD_CHUNK,
    REMOVE_CHUNK,
    RENAME,
    SUBLAND_CREATE,
    SUBLAND_UPDATE
}

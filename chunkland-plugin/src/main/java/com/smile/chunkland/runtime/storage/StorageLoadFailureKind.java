package com.smile.chunkland.runtime.storage;

/**
 * Classification of a storage bootstrap failure. Used for observability; all
 * values are fail-closed and never mapped to a usable state.
 */
public enum StorageLoadFailureKind {
    CONNECTION_FAILURE,
    SCHEMA_MISMATCH,
    MALFORMED_ROW,
    CORRUPTION,
    UNKNOWN
}

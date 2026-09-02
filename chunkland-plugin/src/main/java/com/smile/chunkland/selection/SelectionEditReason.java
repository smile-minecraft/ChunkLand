package com.smile.chunkland.selection;

/** Stable reasons returned when a selection edit is not accepted. */
public enum SelectionEditReason {
    ACCEPTED,
    NO_CHANGE,
    MISSING_POINT,
    WORLD_MISMATCH,
    WRONG_MODE,
    SIDE_LIMIT_EXCEEDED,
    CHUNK_LIMIT_EXCEEDED,
    COLLISION,
    DISCONNECTED
}

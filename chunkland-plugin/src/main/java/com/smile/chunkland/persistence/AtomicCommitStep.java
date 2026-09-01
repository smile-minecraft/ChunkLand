package com.smile.chunkland.persistence;

/** Testable checkpoints inside the otherwise indivisible domain commit. */
public enum AtomicCommitStep {
    AFTER_LAND,
    AFTER_CHUNKS,
    AFTER_AUDIT,
    AFTER_LEDGER
}

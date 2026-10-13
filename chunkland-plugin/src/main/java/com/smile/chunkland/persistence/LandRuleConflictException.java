package com.smile.chunkland.persistence;

/**
 * A conditional per-land rule write found a durable current that no longer
 * equals the caller-observed expected value. Nothing was written and no
 * audit row was recorded; the caller owns the retry-or-surface decision.
 */
public final class LandRuleConflictException extends IllegalStateException {

    public LandRuleConflictException(String message) {
        super(message);
    }
}

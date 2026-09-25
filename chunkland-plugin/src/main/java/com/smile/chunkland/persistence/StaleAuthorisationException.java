package com.smile.chunkland.persistence;

/**
 * A conditional land-default write found a durable authorisation
 * generation that no longer equals the generation observed at gate time:
 * an authorization-relevant row changed underneath the caller. Nothing
 * was written and no audit row was recorded; the caller owns the
 * retry-or-surface decision. This is distinct from
 * {@link LandDefaultConflictException} (the observed default value
 * itself moved): both fail closed, but only this one means the
 * caller's permission to write may have lapsed.
 */
public final class StaleAuthorisationException extends IllegalStateException {

    public StaleAuthorisationException(String message) {
        super(message);
    }
}

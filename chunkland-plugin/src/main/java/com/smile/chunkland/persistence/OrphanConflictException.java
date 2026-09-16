package com.smile.chunkland.persistence;

/**
 * The purge cannot proceed because the world is no longer a stable orphan:
 * it reappeared in the loaded-world set, its land count moved since the
 * confirmation was issued, or a concurrent purge already removed it.
 *
 * <p>Thrown on the persistence thread inside the purge transaction boundary;
 * the transaction rolls back and the caller reports the refusal without any
 * durable side effect.
 */
public final class OrphanConflictException extends RuntimeException {

    public OrphanConflictException(String message) {
        super(message);
    }

    public OrphanConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}

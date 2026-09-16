package com.smile.chunkland.persistence;

/**
 * The target world has no durable land rows, so there is nothing to purge.
 *
 * <p>Thrown on the persistence thread inside the purge transaction boundary;
 * the transaction rolls back and the caller reports the refusal without any
 * durable side effect.
 */
public final class OrphanUnknownException extends RuntimeException {

    public OrphanUnknownException(String message) {
        super(message);
    }

    public OrphanUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}

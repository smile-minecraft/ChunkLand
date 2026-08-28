package com.smile.chunkland.persistence;

/** Raised when persistence work is submitted after shutdown has started. */
public final class PersistenceClosedException extends PersistenceException {

    PersistenceClosedException(String message) {
        super(message);
    }
}

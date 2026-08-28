package com.smile.chunkland.persistence;

/** Raised when a JDBC connection is accessed outside the persistence thread. */
public final class DirectSqlAccessException extends PersistenceException {

    DirectSqlAccessException(String message) {
        super(message);
    }
}

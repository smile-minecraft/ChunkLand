package com.smile.chunkland.runtime.storage;

/**
 * Server-wide policy when storage cannot be loaded reliably.
 *
 * <p>Both values are fail-closed: the server never silently enters READY.
 * {@code STOP_SERVER} is a global not-READY that should terminate startup;
 * {@code LOCKDOWN} is an explicit global protected state that rejects new mutations
 * but keeps the process alive for diagnostics.
 */
public enum StorageFailurePolicy {
    STOP_SERVER,
    LOCKDOWN
}

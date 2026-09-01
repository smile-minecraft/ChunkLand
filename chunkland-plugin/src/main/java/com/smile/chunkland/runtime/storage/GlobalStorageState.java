package com.smile.chunkland.runtime.storage;

/**
 * Server-wide storage state produced by the startup load.
 *
 * <p>{@code READY} means storage was loaded and healthy worlds are published.
 * {@code LOCKDOWN} is an explicit fail-closed global protection state that rejects
 * new mutations. {@code STOPPED} is a fail-closed not-READY that should terminate
 * startup via the server lifecycle seam. Both {@code LOCKDOWN} and {@code STOPPED}
 * preserve the original failure cause.
 */
public enum GlobalStorageState {
    READY,
    LOCKDOWN,
    STOPPED
}

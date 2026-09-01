package com.smile.chunkland.runtime.storage;

/**
 * Per-world storage status.
 *
 * <p>{@code HEALTHY} means the world's data was validated and, when applicable,
 * published to the runtime index. {@code ISOLATED} means the world UUID does
 * not exist on this server: its data is retained on disk, a WARN is emitted,
 * no index is published, and all mutations targeting the world are rejected.
 * {@code ISOLATED} never escalates to a global lockdown.
 */
public enum WorldStorageStatus {
    HEALTHY,
    ISOLATED
}

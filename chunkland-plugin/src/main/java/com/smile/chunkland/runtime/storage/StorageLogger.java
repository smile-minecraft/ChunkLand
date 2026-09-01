package com.smile.chunkland.runtime.storage;

/**
 * Injectable logger for storage policy decisions. Keeps the policy testable
 * without a real Bukkit logger and makes WARN emissions observable.
 */
public interface StorageLogger {

    void warn(String message);

    void error(String message, Throwable cause);

    static StorageLogger noop() {
        return new StorageLogger() {
            @Override public void warn(String message) {}
            @Override public void error(String message, Throwable cause) {}
        };
    }
}

package com.smile.chunkland.config;

import java.io.IOException;

/**
 * Strategy that produces a fully-validated {@link ChunkLandConfig} on demand.
 *
 * <p>Decoupling loading from {@link ConfigService} keeps the service itself
 * trivially testable (a test can pass any {@code ConfigLoader} implementation,
 * including ones that throw on every call) and lets future loaders (file,
 * network, embedded defaults) plug in without touching the service.</p>
 *
 * <p>Implementations must be safe to call from multiple threads concurrently
 * with respect to {@link ConfigService#reload()}; the service serializes the
 * reload() entry point, so loaders do not need their own mutual exclusion.</p>
 */
public interface ConfigLoader {

    /**
     * Produce a validated snapshot.
     *
     * @return the new immutable configuration; never {@code null}
     * @throws ConfigValidationException when the underlying input cannot be
     *         turned into a typed, validated {@link ChunkLandConfig}
     * @throws IOException when the source cannot be read at all (missing file,
     *         network failure); the service treats this identically to a
     *         validation failure (fail-closed, no epoch bump)
     */
    ChunkLandConfig load() throws ConfigValidationException, IOException;

    /**
     * Short, human-readable description of the source for diagnostic logs.
     * Must not include sensitive information.
     */
    String describe();
}

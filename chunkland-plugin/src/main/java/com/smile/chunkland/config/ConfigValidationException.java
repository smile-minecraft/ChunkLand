package com.smile.chunkland.config;

import java.util.Objects;

/**
 * Thrown when the parsed YAML cannot be turned into a typed
 * {@link ChunkLandConfig} (illegal type for a known key, unexpected structure,
 * value out of range, etc.).
 *
 * <p>This is the single fail-closed signal used by {@link ConfigService}: a
 * reload that throws this exception must keep the previous snapshot and must
 * NOT bump any epoch counter.</p>
 */
public final class ConfigValidationException extends RuntimeException {

    public ConfigValidationException(String message) {
        super(Objects.requireNonNull(message, "message"));
    }

    public ConfigValidationException(String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
    }
}

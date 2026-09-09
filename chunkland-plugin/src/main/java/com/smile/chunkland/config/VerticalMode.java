package com.smile.chunkland.config;

/**
 * Per-world vertical protection mode (spec §14).
 *
 * <p>{@code PER_CHUNK_DEPTH} resolves the effective depth per chunk from its
 * persisted {@code storedMinProtectedY}; {@code FULL_HEIGHT} resolves the
 * effective depth to the world minimum height while still persisting
 * {@code storedMinProtectedY} for a later switch back. The mode only affects
 * effective reads and decisions; it never migrates or rewrites stored data.
 */
public enum VerticalMode {
    PER_CHUNK_DEPTH,
    FULL_HEIGHT;

    /** Default mode for worlds without an explicit {@code vertical-mode} key. */
    public static VerticalMode defaultMode() {
        return PER_CHUNK_DEPTH;
    }

    /**
     * Strict parse for {@code worlds.<name>.vertical-mode}.
     *
     * @param raw raw YAML scalar; must be exactly {@code PER_CHUNK_DEPTH} or
     *            {@code FULL_HEIGHT}
     * @param path dotted key path for error messages
     * @throws com.smile.chunkland.config.ConfigValidationException on unknown,
     *         null or non-string values (fail-closed)
     */
    public static VerticalMode parse(Object raw, String path) {
        if (raw == null) {
            throw new ConfigValidationException(path + " must not be null (expected PER_CHUNK_DEPTH or FULL_HEIGHT)");
        }
        if (!(raw instanceof String text)) {
            throw new ConfigValidationException(path + " must be a string (PER_CHUNK_DEPTH or FULL_HEIGHT), got "
                    + raw.getClass().getSimpleName() + " value '" + raw + "'");
        }
        return switch (text) {
            case "PER_CHUNK_DEPTH" -> PER_CHUNK_DEPTH;
            case "FULL_HEIGHT" -> FULL_HEIGHT;
            default -> throw new ConfigValidationException(path + " has unknown vertical-mode '" + text
                    + "' (only 'PER_CHUNK_DEPTH' and 'FULL_HEIGHT' are supported)");
        };
    }
}

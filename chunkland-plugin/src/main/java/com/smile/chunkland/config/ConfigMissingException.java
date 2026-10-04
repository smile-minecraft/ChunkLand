package com.smile.chunkland.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A {@code config.yml} that is absent where the loader looked for it.
 *
 * <p>This is distinct from {@link ConfigValidationException}: a missing file
 * and a damaged file pick different startup policies (first install vs
 * corrupt), and a reload over a deleted file must keep the previous snapshot
 * instead of publishing defaults. The absolute path travels with the signal
 * so startup alerts can name the file without re-resolving it.
 */
public final class ConfigMissingException extends IOException {

    private final Path file;

    public ConfigMissingException(Path file) {
        super("config file not found: "
                + Objects.requireNonNull(file, "file").toAbsolutePath());
        this.file = file;
    }

    /** The path the loader looked for, as passed in. */
    public Path file() {
        return file;
    }
}

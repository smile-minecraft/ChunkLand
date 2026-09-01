package com.smile.chunkland.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.yaml.snakeyaml.Yaml;

/**
 * {@link ConfigLoader} backed by a file on disk.
 *
 * <p>If the file is missing the loader returns the defaults parsed from
 * {@code null} (which the schema treats as an empty document) so that a fresh
 * install does not block startup. The optional first-run behaviour of writing
 * a default file is intentionally not implemented here — the caller may add it
 * once a future task owns plugin-data bootstrap.</p>
 *
 * <p>This class is intentionally not test-covered by the unit tests in this
 * milestone; integration with a real file system is covered indirectly by the
 * schema tests, which exercise the same parsing pipeline.</p>
 */
public final class YamlFileConfigLoader implements ConfigLoader {

    private final Path file;

    public YamlFileConfigLoader(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    @Override
    public ChunkLandConfig load() throws ConfigValidationException, IOException {
        if (!Files.exists(file)) {
            return ConfigSchema.parseAndValidate(null);
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            return ConfigSchema.parseAndValidate(root);
        }
    }

    @Override
    public String describe() {
        return "file:" + file.toAbsolutePath();
    }
}

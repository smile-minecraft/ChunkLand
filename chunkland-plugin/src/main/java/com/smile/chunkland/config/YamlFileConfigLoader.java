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
 * <p>A missing file throws {@link ConfigMissingException} instead of
 * succeeding with schema defaults: at startup a missing file and a damaged
 * file pick different policies (first install vs corrupt), and at reload a
 * deleted file must keep the previous snapshot instead of publishing defaults
 * over a stricter live config. Callers that need the lenient "absent means
 * defaults" shape must opt in explicitly; this loader never does it silently.
 */
public final class YamlFileConfigLoader implements ConfigLoader {

    private final Path file;

    public YamlFileConfigLoader(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    @Override
    public ChunkLandConfig load() throws ConfigValidationException, IOException {
        if (!Files.isRegularFile(file)) {
            throw new ConfigMissingException(file);
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

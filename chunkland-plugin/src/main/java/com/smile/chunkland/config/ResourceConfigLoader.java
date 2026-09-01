package com.smile.chunkland.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.yaml.snakeyaml.Yaml;

/**
 * {@link ConfigLoader} backed by a classpath resource. Used as a fallback when
 * the on-disk {@code config.yml} is missing or unreadable so the plugin always
 * has a valid snapshot to read from.
 */
public final class ResourceConfigLoader implements ConfigLoader {

    private final Class<?> owner;
    private final String resourcePath;

    public ResourceConfigLoader(Class<?> owner, String resourcePath) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.resourcePath = Objects.requireNonNull(resourcePath, "resourcePath");
    }

    @Override
    public ChunkLandConfig load() throws ConfigValidationException, IOException {
        InputStream in = owner.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IOException(
                    "resource not found on classpath: " + resourcePath);
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            return ConfigSchema.parseAndValidate(root);
        }
    }

    @Override
    public String describe() {
        return "resource:" + resourcePath;
    }
}

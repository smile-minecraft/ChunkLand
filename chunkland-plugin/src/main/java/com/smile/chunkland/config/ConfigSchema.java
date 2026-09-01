package com.smile.chunkland.config;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.Yaml;

/**
 * Parser + validator for the YAML structure produced by {@code config.yml}.
 *
 * <p>This is intentionally a small, hand-written parser rather than a Jackson /
 * snakeyaml-binding generator: the schema is tiny, the failure messages need
 * to point at the offending key path, and the result must be a fully typed
 * {@link ChunkLandConfig} with no {@code null} or {@code Object} escape hatches
 * left in. The YAML library is only used to turn text into a generic
 * {@code Map}/{@code List}/{@code String} tree that this class then validates.</p>
 *
 * <p>Supported top-level structure:</p>
 * <pre>
 * worlds:
 *   &lt;world-name&gt;:
 *     claim-enabled: true|false
 * </pre>
 *
 * <p>Unknown top-level keys are rejected; unknown sub-keys under a world are
 * rejected. The validator never silently drops data — that is how a future
 * refactor that renames a key would otherwise become a silent runtime bug.</p>
 */
public final class ConfigSchema {

    private ConfigSchema() {
        // utility class
    }

    /**
     * Parse {@code rawYamlRoot} (as produced by snakeyaml) into a
     * {@link ChunkLandConfig} with epoch counters set to zero. Validation
     * failures throw {@link ConfigValidationException} with a message that
     * includes the dotted key path that failed.
     *
     * @param rawYamlRoot snakeyaml output (typically a {@code Map<String,Object>},
     *                    but a {@code null} root is accepted and treated as an
     *                    empty document).
     */
    public static ChunkLandConfig parseAndValidate(Object rawYamlRoot) {
        if (rawYamlRoot == null) {
            return ChunkLandConfig.defaults();
        }
        if (!(rawYamlRoot instanceof Map<?, ?> rootMap)) {
            throw new ConfigValidationException(
                    "top-level YAML root must be a mapping (worlds: { ... }), got "
                            + rawYamlRoot.getClass().getSimpleName());
        }
        Map<String, Object> root = castStringKeyMap(rootMap, "");
        // Reject unknown top-level keys explicitly so future renames become
        // loud failures instead of silent runtime bugs.
        for (String key : root.keySet()) {
            if (!"worlds".equals(key)) {
                throw new ConfigValidationException(
                        "unknown top-level key '" + key
                                + "' (only 'worlds' is supported in the current schema)");
            }
        }
        Map<String, WorldSettings> worlds = parseWorlds(root.get("worlds"), "worlds");
        return new ChunkLandConfig(worlds, 0L, deriveWorldEpochs(worlds));
    }

    /** Build the worldPolicyEpochs map (every known world starts at 0). */
    static Map<String, Long> deriveWorldEpochs(Map<String, WorldSettings> worlds) {
        Map<String, Long> out = new LinkedHashMap<>(worlds.size());
        for (String name : worlds.keySet()) {
            out.put(name, 0L);
        }
        return out;
    }

    private static Map<String, WorldSettings> parseWorlds(Object rawWorlds, String path) {
        if (rawWorlds == null) {
            return Map.of();
        }
        if (!(rawWorlds instanceof Map<?, ?> rawMap)) {
            throw new ConfigValidationException(
                    path + " must be a mapping of world name -> settings, got "
                            + rawWorlds.getClass().getSimpleName());
        }
        Map<String, Object> map = castStringKeyMap(rawMap, path);
        Map<String, WorldSettings> result = new LinkedHashMap<>(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String worldName = Objects.requireNonNull(entry.getKey(), "world name");
            if (worldName.isBlank()) {
                throw new ConfigValidationException("world name must not be blank");
            }
            String worldPath = path + "." + worldName;
            Object rawSettings = entry.getValue();
            if (rawSettings == null) {
                throw new ConfigValidationException(
                        worldPath + " must be a mapping of settings, got null");
            }
            if (!(rawSettings instanceof Map<?, ?> rawSettingsMap)) {
                throw new ConfigValidationException(
                        worldPath + " must be a mapping of settings, got "
                                + rawSettings.getClass().getSimpleName());
            }
            Map<String, Object> settingsMap = castStringKeyMap(rawSettingsMap, worldPath);
            // Reject unknown world-level keys — explicit fail-fast over silent defaults.
            for (String key : settingsMap.keySet()) {
                if (!"claim-enabled".equals(key)) {
                throw new ConfigValidationException(
                        worldPath + " has unknown key '" + key
                                + "' (only 'claim-enabled' is supported in the current schema)");
                }
            }
            boolean claimEnabled = parseClaimEnabled(
                    settingsMap.get("claim-enabled"),
                    worldPath + ".claim-enabled");
            result.put(worldName, new WorldSettings(claimEnabled));
        }
        return result;
    }

    private static boolean parseClaimEnabled(Object raw, String path) {
        if (raw == null) {
            throw new ConfigValidationException(
                    path + " must be a boolean (true/false), got null");
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        throw new ConfigValidationException(
                path + " must be a boolean (true/false), got "
                        + raw.getClass().getSimpleName()
                        + " value '" + raw + "'");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringKeyMap(Map<?, ?> raw, String path) {
        Map<String, Object> result = new HashMap<>(raw.size());
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String s)) {
                throw new ConfigValidationException(
                        (path.isEmpty() ? "<root>" : path)
                                + " requires string keys, got "
                                + (key == null ? "null" : key.getClass().getSimpleName()));
            }
            result.put(s, entry.getValue());
        }
        return result;
    }

    /**
     * Convenience: parse raw YAML text (snakeyaml) and validate. Useful as a
     * single-call entry point when the loader only has access to a string.
     *
     * @throws ConfigValidationException for schema / type violations
     */
    public static ChunkLandConfig parseYamlText(String yaml) {
        Object root = new Yaml().load(yaml);
        return parseAndValidate(root);
    }
}

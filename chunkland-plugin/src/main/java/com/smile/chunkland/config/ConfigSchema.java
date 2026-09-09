package com.smile.chunkland.config;

import com.smile.chunkland.selection.SelectionVisualizationBudget;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
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
 *     vertical-mode: PER_CHUNK_DEPTH|FULL_HEIGHT
 * selection:
 *   session-timeout-seconds: 600
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
            if (!"worlds".equals(key) && !"limits".equals(key) && !"messages".equals(key)
                    && !"selection".equals(key)) {
                throw new ConfigValidationException(
                        "unknown top-level key '" + key
                                + "' (only 'worlds', 'limits', 'messages' and 'selection' are supported in the current schema)");
            }
        }
        Map<String, WorldSettings> worlds = parseWorlds(root.get("worlds"), "worlds");
        LimitSettings limits;
        if (!root.containsKey("limits")) {
            limits = LimitSettings.defaults();
        } else {
            Object rawLimits = root.get("limits");
            if (rawLimits == null) {
                throw new ConfigValidationException("limits must not be null");
            }
            limits = parseLimits(rawLimits, "limits");
        }
        MessageSettings messages;
        if (!root.containsKey("messages")) {
            messages = MessageSettings.defaults();
        } else {
            Object rawMessages = root.get("messages");
            if (rawMessages == null) {
                throw new ConfigValidationException("messages must not be null");
            }
            messages = parseMessages(rawMessages, "messages");
        }
        SelectionSettings selection;
        if (!root.containsKey("selection")) {
            selection = SelectionSettings.defaults();
        } else {
            Object rawSelection = root.get("selection");
            if (rawSelection == null) {
                throw new ConfigValidationException("selection must not be null");
            }
            selection = parseSelection(rawSelection, "selection");
        }
        return new ChunkLandConfig(worlds, limits, messages, selection, 0L, deriveWorldEpochs(worlds));
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
                if (!"claim-enabled".equals(key) && !"vertical-mode".equals(key)) {
                throw new ConfigValidationException(
                        worldPath + " has unknown key '" + key
                                + "' (only 'claim-enabled' and 'vertical-mode' are supported in the current schema)");
                }
            }
            boolean claimEnabled = parseClaimEnabled(
                    settingsMap.get("claim-enabled"),
                    worldPath + ".claim-enabled");
            VerticalMode verticalMode;
            if (!settingsMap.containsKey("vertical-mode")) {
                verticalMode = VerticalMode.defaultMode();
            } else {
                verticalMode = VerticalMode.parse(
                        settingsMap.get("vertical-mode"), worldPath + ".vertical-mode");
            }
            result.put(worldName, new WorldSettings(claimEnabled, verticalMode));
        }
        return result;
    }

    private static LimitSettings parseLimits(Object raw, String path) {
        if (raw == null) {
            throw new ConfigValidationException(path + " must not be null");
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new ConfigValidationException(
                    path + " must be a mapping, got " + raw.getClass().getSimpleName());
        }
        Map<String, Object> map = castStringKeyMap(rawMap, path);
        // Reject unknown keys under limits.
        for (String key : map.keySet()) {
            if (!key.equals("max-lands-per-player")
                    && !key.equals("max-total-chunks-per-player")
                    && !key.equals("max-chunks-per-land")
                    && !key.equals("max-sublands-per-land")
                    && !key.equals("max-selection-side-length")
                    && !key.equals("max-selection-chunks")) {
                throw new ConfigValidationException(
                        path + " has unknown key '" + key + "'");
            }
        }
        LimitSettings defaults = LimitSettings.defaults();
        int maxLands = parseLimitField(map, "max-lands-per-player",
                path + ".max-lands-per-player", defaults.maxLandsPerPlayer());
        int maxChunksPerPlayer = parseLimitField(map, "max-total-chunks-per-player",
                path + ".max-total-chunks-per-player", defaults.maxTotalChunksPerPlayer());
        int maxChunksPerLand = parseLimitField(map, "max-chunks-per-land",
                path + ".max-chunks-per-land", defaults.maxChunksPerLand());
        int maxSublands = parseLimitField(map, "max-sublands-per-land",
                path + ".max-sublands-per-land", defaults.maxSublandsPerLand());
        int maxSelectionSide = parseLimitField(map, "max-selection-side-length",
                path + ".max-selection-side-length", defaults.maxSelectionSideLength());
        int maxSelectionChunks = parseLimitField(map, "max-selection-chunks",
                path + ".max-selection-chunks", defaults.maxSelectionChunks());
        return new LimitSettings(maxLands, maxChunksPerPlayer, maxChunksPerLand, maxSublands,
                maxSelectionSide, maxSelectionChunks);
    }

    private static int parseLimitField(Map<String, Object> map, String key, String path, int defaultValue) {
        if (!map.containsKey(key)) {
            return defaultValue;
        }
        Object raw = map.get(key);
        if (raw == null) {
            throw new ConfigValidationException(path + " must not be null");
        }
        return parseIntLimit(raw, path);
    }

    private static MessageSettings parseMessages(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new ConfigValidationException(
                    path + " must be a mapping, got " + raw.getClass().getSimpleName());
        }
        Map<String, Object> map = castStringKeyMap(rawMap, path);
        for (String key : map.keySet()) {
            if (!"default-locale".equals(key) && !"cooldown-seconds".equals(key)) {
                throw new ConfigValidationException(path + " has unknown key '" + key + "'");
            }
        }
        // default-locale
        Locale defaultLocale;
        if (!map.containsKey("default-locale")) {
            defaultLocale = MessageSettings.defaults().defaultLocale();
        } else {
            Object rawLocale = map.get("default-locale");
            if (rawLocale == null) {
                throw new ConfigValidationException(path + ".default-locale must not be null");
            }
            if (!(rawLocale instanceof String s)) {
                throw new ConfigValidationException(
                        path + ".default-locale must be a string, got " + rawLocale.getClass().getSimpleName());
            }
            defaultLocale = MessageSettings.parseLocaleTag(s, path + ".default-locale");
        }
        // cooldown-seconds
        int cooldown;
        if (!map.containsKey("cooldown-seconds")) {
            cooldown = MessageSettings.defaults().cooldownSeconds();
        } else {
            Object rawCooldown = map.get("cooldown-seconds");
            if (rawCooldown == null) {
                throw new ConfigValidationException(path + ".cooldown-seconds must not be null");
            }
            cooldown = parseIntLimit(rawCooldown, path + ".cooldown-seconds");
        }
        return new MessageSettings(defaultLocale, cooldown);
    }

    private static SelectionSettings parseSelection(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new ConfigValidationException(
                    path + " must be a mapping, got " + raw.getClass().getSimpleName());
        }
        Map<String, Object> map = castStringKeyMap(rawMap, path);
        for (String key : map.keySet()) {
            if (!"session-timeout-seconds".equals(key)
                    && !"visualization-max-segments".equals(key)
                    && !"visualization-max-particles-per-tick".equals(key)
                    && !"visualization-render-distance-blocks".equals(key)
                    && !"visualization-refresh-interval-ticks".equals(key)) {
                throw new ConfigValidationException(path + " has unknown key '" + key + "'");
            }
        }
        int timeout;
        if (!map.containsKey("session-timeout-seconds")) {
            timeout = SelectionSettings.DEFAULT_SESSION_TIMEOUT_SECONDS;
        } else {
            Object rawTimeout = map.get("session-timeout-seconds");
            if (rawTimeout == null) {
                throw new ConfigValidationException(path + ".session-timeout-seconds must not be null");
            }
            timeout = parseIntLimit(rawTimeout, path + ".session-timeout-seconds");
            if (timeout <= 0) {
                throw new ConfigValidationException(
                        path + ".session-timeout-seconds must be > 0, got " + timeout);
            }
        }
        SelectionSettings defaults = SelectionSettings.defaults();
        int maxSegments = parseVisualizationField(
                map, "visualization-max-segments", path, defaults.visualizationMaxSegments(), 1,
                SelectionVisualizationBudget.MAX_SEGMENTS_LIMIT);
        int maxParticles = parseVisualizationField(
                map, "visualization-max-particles-per-tick", path, defaults.visualizationMaxParticlesPerTick(), 1,
                SelectionVisualizationBudget.MAX_PARTICLES_PER_TICK_LIMIT);
        int renderDistance = parseVisualizationField(
                map, "visualization-render-distance-blocks", path, defaults.visualizationRenderDistanceBlocks(),
                SelectionVisualizationBudget.MIN_RENDER_DISTANCE_BLOCKS,
                SelectionVisualizationBudget.RENDER_DISTANCE_BLOCKS_LIMIT);
        int refreshInterval = parseVisualizationField(
                map, "visualization-refresh-interval-ticks", path, defaults.visualizationRefreshIntervalTicks(), 1,
                SelectionVisualizationBudget.REFRESH_INTERVAL_TICKS_LIMIT);
        try {
            return new SelectionSettings(timeout, maxSegments, maxParticles, renderDistance, refreshInterval);
        } catch (IllegalArgumentException ex) {
            throw new ConfigValidationException(path + " has an out-of-range visualization budget: " + ex.getMessage());
        }
    }

    private static int parseVisualizationField(
            Map<String, Object> map, String key, String path, int defaultValue, int min, int max) {
        if (!map.containsKey(key)) {
            return defaultValue;
        }
        Object raw = map.get(key);
        if (raw == null) {
            throw new ConfigValidationException(path + "." + key + " must not be null");
        }
        int value = parseIntLimit(raw, path + "." + key);
        if (value < min || value > max) {
            throw new ConfigValidationException(
                    path + "." + key + " must be in [" + min + ", " + max + "], got " + value);
        }
        return value;
    }

    private static int parseIntLimit(Object raw, String path) {
        if (raw instanceof Double || raw instanceof Float) {
            throw new ConfigValidationException(
                    path + " must be an integer (no floating point), got " + raw);
        }
        if (raw instanceof BigInteger bi) {
            if (bi.signum() < 0) {
                throw new ConfigValidationException(path + " must be >= 0, got " + bi);
            }
            if (bi.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new ConfigValidationException(path + " out of range (> Integer.MAX_VALUE): " + bi);
            }
            return bi.intValue();
        }
        if (raw instanceof BigDecimal bd) {
            BigInteger bi;
            try {
                bi = bd.toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw new ConfigValidationException(path + " must be an integer, got " + raw);
            }
            if (bi.signum() < 0) {
                throw new ConfigValidationException(path + " must be >= 0, got " + bi);
            }
            if (bi.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new ConfigValidationException(path + " out of range (> Integer.MAX_VALUE): " + bi);
            }
            return bi.intValue();
        }
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
            long lv = ((Number) raw).longValue();
            if (lv < 0) {
                throw new ConfigValidationException(path + " must be >= 0, got " + lv);
            }
            if (lv > Integer.MAX_VALUE) {
                throw new ConfigValidationException(path + " out of range (> Integer.MAX_VALUE): " + lv);
            }
            return (int) lv;
        }
        if (raw instanceof Number) {
            throw new ConfigValidationException(
                    path + " must be an integer, got " + raw.getClass().getSimpleName() + " value '" + raw + "'");
        }
        throw new ConfigValidationException(
                path + " must be an integer, got " + raw.getClass().getSimpleName() + " value '" + raw + "'");
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

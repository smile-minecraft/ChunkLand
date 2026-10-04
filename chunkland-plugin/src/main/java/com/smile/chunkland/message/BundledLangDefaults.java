package com.smile.chunkland.message;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Language lookup that answers from the server's own lang files first and
 * from the bundled defaults second.
 *
 * <p>Lang files on disk are never overwritten, so a server that upgrades the
 * plugin keeps the files written by the older version. Every key added since
 * then is missing there, and a missing key drops the whole message. This
 * provider closes that gap without touching the files: an edited entry still
 * wins, and only a key the disk copy does not have is read from the jar.
 *
 * <p>The bundled files are parsed once at build time; lookups afterwards are
 * memory-only map reads.
 */
public final class BundledLangDefaults implements ChunkLandMessagePipeline.LangProvider {

    private final ChunkLandMessagePipeline.LangProvider primary;
    private final Map<String, YamlConfiguration> bundled;
    private final Locale defaultLocale;

    /**
     * @param primary lookup over the server's lang files; never {@code null}
     * @param bundled bundled defaults keyed by locale tag (for example
     *                {@code zh_TW}); never {@code null}, may be empty
     * @param defaultLocale locale tried when the requested one is not bundled
     */
    public BundledLangDefaults(ChunkLandMessagePipeline.LangProvider primary,
            Map<String, YamlConfiguration> bundled, Locale defaultLocale) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.bundled = Map.copyOf(Objects.requireNonNull(bundled, "bundled"));
        this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
    }

    /**
     * Reads the bundled lang resources out of the plugin jar. A resource
     * that is missing or unreadable is skipped, which only narrows the
     * fallback; it never fails the pipeline build.
     *
     * @param resourcePaths jar paths such as {@code lang/zh_TW.yml}
     */
    public static BundledLangDefaults fromPlugin(JavaPlugin plugin,
            ChunkLandMessagePipeline.LangProvider primary, List<String> resourcePaths,
            Locale defaultLocale) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(resourcePaths, "resourcePaths");
        Map<String, YamlConfiguration> loaded = new HashMap<>();
        for (String path : resourcePaths) {
            try (InputStream in = plugin.getResource(path)) {
                if (in == null) {
                    continue;
                }
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                loaded.put(localeTag(path), cfg);
            } catch (Exception unreadable) {
                // A broken bundled resource only narrows the fallback.
            }
        }
        return new BundledLangDefaults(primary, loaded, defaultLocale);
    }

    /** {@code lang/zh_TW.yml} names the locale tag {@code zh_TW}. */
    static String localeTag(String resourcePath) {
        String name = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    @Override
    public Optional<String> get(Locale locale, String key) {
        if (key == null) {
            return Optional.empty();
        }
        Optional<String> onDisk = primary.get(locale, key);
        if (onDisk != null && onDisk.isPresent()) {
            return onDisk;
        }
        Optional<String> found = bundledValue(locale, key);
        if (found.isEmpty() && !defaultLocale.equals(locale)) {
            found = bundledValue(defaultLocale, key);
        }
        return found;
    }

    private Optional<String> bundledValue(Locale locale, String key) {
        if (locale == null) {
            return Optional.empty();
        }
        YamlConfiguration cfg = bundled.get(locale.toString());
        if (cfg == null || !cfg.isString(key)) {
            return Optional.empty();
        }
        return Optional.ofNullable(cfg.getString(key));
    }
}

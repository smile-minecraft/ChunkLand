package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * An upgraded server keeps its older lang files; keys added since then must
 * still resolve, and an entry the server edited must still win.
 */
class BundledLangDefaultsTest {

    private static YamlConfiguration yaml(Map<String, String> entries) {
        YamlConfiguration cfg = new YamlConfiguration();
        entries.forEach(cfg::set);
        return cfg;
    }

    private static BundledLangDefaults provider(Map<String, String> disk) {
        Locale zh = Locale.forLanguageTag("zh-TW");
        return new BundledLangDefaults(
                (locale, key) -> Optional.ofNullable(disk.get(key)),
                Map.of("zh_TW", yaml(Map.of(
                                "permission.action.block_break", "破壞方塊",
                                "land.enter.message", "已進入")),
                        "en_US", yaml(Map.of(
                                "permission.action.block_break", "Breaking blocks",
                                "only.english", "English only"))),
                zh);
    }

    @Test
    void keyMissingOnDiskComesFromTheBundledFileOfTheSameLocale() {
        BundledLangDefaults lang = provider(Map.of());
        assertEquals(Optional.of("破壞方塊"),
                lang.get(Locale.forLanguageTag("zh-TW"), "permission.action.block_break"));
        assertEquals(Optional.of("Breaking blocks"),
                lang.get(Locale.US, "permission.action.block_break"));
    }

    @Test
    void entryOnDiskAlwaysWins() {
        BundledLangDefaults lang = provider(Map.of("land.enter.message", "歡迎回家"));
        assertEquals(Optional.of("歡迎回家"),
                lang.get(Locale.forLanguageTag("zh-TW"), "land.enter.message"));
    }

    @Test
    void unbundledLocaleFallsBackToTheDefaultLocale() {
        BundledLangDefaults lang = provider(Map.of());
        assertEquals(Optional.of("破壞方塊"),
                lang.get(Locale.JAPAN, "permission.action.block_break"));
    }

    @Test
    void unknownKeysAndSectionsStayEmpty() {
        BundledLangDefaults lang = provider(Map.of());
        assertEquals(Optional.empty(), lang.get(Locale.US, "no.such.key"));
        assertEquals(Optional.empty(), lang.get(Locale.US, "permission.action"),
                "a section is not a message");
        assertEquals(Optional.empty(), lang.get(Locale.forLanguageTag("zh-TW"), "only.english"),
                "a key bundled only for another locale is not borrowed across locales");
        assertEquals(Optional.empty(), lang.get(Locale.US, null));
    }

    @Test
    void resourcePathNamesItsLocaleTag() {
        assertEquals("zh_TW", BundledLangDefaults.localeTag("lang/zh_TW.yml"));
        assertEquals("en_US", BundledLangDefaults.localeTag("en_US.yml"));
    }
}

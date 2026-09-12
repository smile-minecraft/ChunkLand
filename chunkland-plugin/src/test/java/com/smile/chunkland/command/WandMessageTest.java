package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The wand already-have reply must exist in both locales, render through the
 * same strict MiniMessage contract as every other command key, and carry the
 * same placeholders on both sides so neither locale breaks the parser.
 */
class WandMessageTest {

    private static final String KEY = "command.land.wand.already_have";

    private static final Set<String> WAND_KEYS = Set.of(
            "command.land.wand.given",
            "command.land.wand.inventory_full",
            "command.land.wand.console",
            "command.land.wand.error",
            "command.land.wand.already_have");

    @Test
    void wandKeysMirrorBetweenLocales() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        Set<String> enWand = wandKeys(en);
        Set<String> zhWand = wandKeys(zh);
        assertEquals(WAND_KEYS, enWand);
        assertEquals(enWand, zhWand, "wand keys must mirror between en_US and zh_TW");
    }

    @Test
    void alreadyHaveIsNonBlankAndDiffersByLocale() throws Exception {
        String en = load("en_US").getString(KEY);
        String zh = load("zh_TW").getString(KEY);
        assertNotNull(en, "en_US missing " + KEY);
        assertNotNull(zh, "zh_TW missing " + KEY);
        assertFalse(en.isBlank(), "en_US blank " + KEY);
        assertFalse(zh.isBlank(), "zh_TW blank " + KEY);
        assertNotEquals(en, zh, "zh render must differ from en for " + KEY);
    }

    @Test
    void wandTemplatesPassStrictValidationWithMirroredPlaceholders() throws Exception {
        for (String tag : new String[] {"en_US", "zh_TW"}) {
            YamlConfiguration cfg = load(tag);
            for (String key : WAND_KEYS) {
                validateStrict(key, cfg.getString(key));
            }
        }
        // Strict validation already rejects unknown tags and placeholders; here
        // only the cross-locale mirror matters so neither side breaks the parser.
        for (String key : WAND_KEYS) {
            assertEquals(placeholders(load("en_US").getString(key)),
                    placeholders(load("zh_TW").getString(key)),
                    "placeholder parity broken for " + key);
        }
    }

    private static YamlConfiguration load(String tag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        File direct = new File("src/main/resources/lang/" + tag + ".yml");
        File module = new File("chunkland-plugin/src/main/resources/lang/" + tag + ".yml");
        cfg.load(direct.isFile() ? direct : module);
        return cfg;
    }

    private static Set<String> wandKeys(YamlConfiguration cfg) {
        Set<String> out = new HashSet<>();
        for (String k : cfg.getKeys(true)) {
            if (k.startsWith("command.land.wand.") && cfg.get(k) instanceof String) {
                out.add(k);
            }
        }
        return out;
    }

    private static Set<String> placeholders(String template) {
        Set<String> found = new HashSet<>();
        var matcher = java.util.regex.Pattern.compile("<(/?[a-z_]+)>").matcher(template);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static void validateStrict(String key, String template) throws Exception {
        var method = Class.forName("com.smile.chunkland.message.ChunkLandMessagePipeline")
                .getDeclaredMethod("validateTemplateStrict", String.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(null, key, template);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        }
    }

}

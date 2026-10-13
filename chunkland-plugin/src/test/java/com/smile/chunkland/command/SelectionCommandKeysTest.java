package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Every {@code /land selection} reply literal must resolve in both bundled
 * locales with matching placeholders, otherwise the fail-closed reply sink
 * drops the message and the player sees nothing.
 */
class SelectionCommandKeysTest {

    private static final Set<String> SELECTION_KEYS = Set.of(
            "command.land.selection.console",
            "command.land.selection.usage",
            "command.land.selection.no_selection",
            "command.land.selection.wrong_mode",
            "command.land.selection.stale",
            "command.land.selection.mode_chunk",
            "command.land.selection.mode_rectangle",
            "command.land.selection.added",
            "command.land.selection.removed",
            "command.land.selection.no_change",
            "command.land.selection.rejected",
            "command.land.selection.failed",
            "command.land.selection.unavailable",
            "command.land.selection.cancelled",
            "command.land.selection.info");

    private static YamlConfiguration load(String tag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        File direct = new File("src/main/resources/lang/" + tag + ".yml");
        File module = new File("chunkland-plugin/src/main/resources/lang/" + tag + ".yml");
        cfg.load(direct.isFile() ? direct : module);
        return cfg;
    }

    private static Set<String> selectionKeys(YamlConfiguration cfg) {
        Set<String> out = new HashSet<>();
        for (String key : cfg.getKeys(true)) {
            if (key.startsWith("command.land.selection.") && cfg.get(key) instanceof String) {
                out.add(key);
            }
        }
        return out;
    }

    @Test
    void selectionKeysMirrorBetweenLocales() throws Exception {
        Set<String> en = selectionKeys(load("en_US"));
        Set<String> zh = selectionKeys(load("zh_TW"));

        assertEquals(SELECTION_KEYS, en);
        assertEquals(en, zh, "selection command keys must mirror between en_US and zh_TW");
    }

    @Test
    void selectionKeysUseOnlyWhitelistedPlaceholdersWithParity() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        for (String key : SELECTION_KEYS) {
            String enText = en.getString(key);
            String zhText = zh.getString(key);
            assertNotNull(enText, "en_US missing " + key);
            assertNotNull(zhText, "zh_TW missing " + key);
            assertFalse(enText.isBlank(), "en_US blank " + key);
            assertFalse(zhText.isBlank(), "zh_TW blank " + key);
            assertNotEquals(enText, zhText, "zh render must differ from en for " + key);
            assertEquals(placeholders(enText), placeholders(zhText),
                    "placeholder parity broken for " + key);
            validateStrict(key, enText);
            validateStrict(key, zhText);
        }
    }

    private static Set<String> placeholders(String template) {
        Set<String> found = new HashSet<>();
        Matcher matcher = Pattern.compile("<([a-z_]+)>").matcher(template);
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

    @Test
    void helpDetailCoversSelectionInBothLocales() throws Exception {
        for (String tag : new String[] {"en_US", "zh_TW"}) {
            YamlConfiguration cfg = load(tag);
            for (String key : LandHelpKeys.keysFor("selection")) {
                String value = cfg.getString(key);
                assertNotNull(value, tag + " missing " + key);
                assertFalse(value.isBlank(), tag + " blank " + key);
            }
            assertTrue(LandCommand.SUBCOMMANDS.contains("selection"));
            assertNotNull(LandPermissions.forSubcommand("selection"),
                    "selection must have a permission node");
        }
    }
}

package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.selection.SelectionEndReason;
import com.smile.chunkland.wand.WandFeedback;
import com.smile.chunkland.wand.WandSelectionNotifier;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The guided-selection prompts must exist in both locales, render through the
 * same strict MiniMessage contract as every other key, and mirror their keys
 * exactly so neither side can drift out of sync with the handler/notifier.
 */
class SelectionMessageTest {

    private static final Set<String> SELECTION_KEYS = Set.of(
            "selection.wand.first_point",
            "selection.wand.edit_target",
            "selection.wand.second_point",
            "selection.wand.resized",
            "selection.wand.blocked",
            "selection.wand.unavailable",
            "selection.wand.reset",
            "selection.wand.abandoned",
            "selection.wand.cancelled");

    /** Keys that announce the accepted rectangle size A×B to the player. */
    private static final Set<String> DIMENSION_KEYS = Set.of(
            "selection.wand.second_point",
            "selection.wand.resized");

    @Test
    void selectionKeysMirrorBetweenLocales() throws Exception {
        Set<String> en = selectionKeys(load("en_US"));
        Set<String> zh = selectionKeys(load("zh_TW"));

        assertEquals(SELECTION_KEYS, en);
        assertEquals(en, zh, "selection keys must mirror between en_US and zh_TW");
    }

    @Test
    void dimensionPromptsCarryWidthAndHeightOnBothLocales() throws Exception {
        for (String key : DIMENSION_KEYS) {
            Set<String> enVars = placeholders(load("en_US").getString(key));
            assertTrue(enVars.containsAll(Set.of("width", "height")),
                    "en_US " + key + " must carry the <width>/<height> placeholders, found " + enVars);
            assertEquals(enVars, placeholders(load("zh_TW").getString(key)),
                    "placeholder parity broken for " + key);
        }
        for (String key : SELECTION_KEYS) {
            if (!DIMENSION_KEYS.contains(key)) {
                String en = load("en_US").getString(key);
                assertFalse(placeholders(en).contains("width") || placeholders(en).contains("height"),
                        key + " has no vars in production and must not carry size placeholders");
            }
        }
    }

    @Test
    void dimensionPromptsRenderWithWidthHeightVars() throws Exception {
        for (String key : DIMENSION_KEYS) {
            for (String tag : new String[] {"en_US", "zh_TW"}) {
                String template = load(tag).getString(key);
                var parsed = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                        template,
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("width", "2"),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("height", "3"));
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(parsed);
                assertTrue(plain.contains("2×3"), tag + " " + key + " must render the A×B size, got: " + plain);
                assertFalse(plain.contains("width") || plain.contains("height"),
                        tag + " " + key + " must not leak the placeholder name, got: " + plain);
            }
        }
    }

    @Test
    void everyPromptIsNonBlankStrictAndDiffersByLocale() throws Exception {
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
            validateStrict(key, enText);
            validateStrict(key, zhText);
        }
    }

    @Test
    void pipelineAllowsWidthAndHeightPlaceholders() throws Exception {
        var field = Class.forName("com.smile.chunkland.message.ChunkLandMessagePipeline")
                .getDeclaredField("ALLOWED_PLACEHOLDERS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> allowed = (java.util.Set<String>) field.get(null);
        assertTrue(allowed.contains("width") && allowed.contains("height"),
                "the shared pipeline whitelist must accept the <width>/<height> placeholders");
    }

    @Test
    void handlerAndNotifierKeysResolveShouldBePresent() throws Exception {
        Set<String> en = selectionKeys(load("en_US"));
        for (WandFeedback.Kind kind : WandFeedback.Kind.values()) {
            assertTrue(en.contains(kind.messageKey()), "missing lang key for " + kind + ": " + kind.messageKey());
        }
        for (SelectionEndReason reason : new SelectionEndReason[] {
                SelectionEndReason.CANCELLED, SelectionEndReason.ITEM_CHANGED}) {
            String key = WandSelectionNotifier.messageKey(reason);
            assertNotNull(key, "notifier reason must map to a key: " + reason);
            assertTrue(en.contains(key), "missing lang key for " + reason + ": " + key);
        }
    }

    private static YamlConfiguration load(String tag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        File direct = new File("src/main/resources/lang/" + tag + ".yml");
        File module = new File("chunkland-plugin/src/main/resources/lang/" + tag + ".yml");
        cfg.load(direct.isFile() ? direct : module);
        return cfg;
    }

    private static Set<String> selectionKeys(YamlConfiguration cfg) {
        Set<String> out = new HashSet<>();
        for (String k : cfg.getKeys(true)) {
            if (k.startsWith("selection.wand.") && cfg.get(k) instanceof String) {
                out.add(k);
            }
        }
        return out;
    }

    private static Set<String> placeholders(String template) {
        Set<String> found = new HashSet<>();
        var matcher = java.util.regex.Pattern.compile("<([a-z_]+)>").matcher(template);
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

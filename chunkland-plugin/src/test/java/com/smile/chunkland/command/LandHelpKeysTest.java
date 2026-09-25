package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Pins the help i18n surface this milestone introduces: one prompt line,
 * one unknown-subcommand hint, and a short/detail/usage triple for every
 * {@code /land} subcommand, in both bundled locales with matching
 * placeholders. The current help reply keeps its layout; these keys are
 * the entry point the help-UX follow-up composes from.
 *
 * <p>Keys live under {@code command.land.help_detail}: the existing
 * {@code command.land.help} entry is a scalar block, so YAML cannot nest
 * anything below it without changing the live help reply.</p>
 */
class LandHelpKeysTest {

    static final String PREFIX = "command.land.help_detail.";
    static final String PROMPT_KEY = PREFIX + "prompt";
    static final String UNKNOWN_HINT_KEY = PREFIX + "unknown_hint";
    static final String PERMISSION_KEY = PREFIX + "permission";

    static List<String> expectedKeys() {
        List<String> keys = new ArrayList<>();
        keys.add(PROMPT_KEY);
        keys.add(UNKNOWN_HINT_KEY);
        keys.add(PERMISSION_KEY);
        for (String sub : LandCommand.SUBCOMMANDS) {
            keys.add(PREFIX + sub + ".short");
            keys.add(PREFIX + sub + ".detail");
            keys.add(PREFIX + sub + ".usage");
        }
        return List.copyOf(keys);
    }

    private static YamlConfiguration load(String tag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        File direct = new File("src/main/resources/lang/" + tag + ".yml");
        File module = new File("chunkland-plugin/src/main/resources/lang/" + tag + ".yml");
        cfg.load(direct.isFile() ? direct : module);
        return cfg;
    }

    @Test
    void everySubcommandCarriesAHelpTriple() {
        assertEquals(25, LandCommand.SUBCOMMANDS.size(),
                "help keys must cover all 25 subcommands");
    }

    @Test
    void everyHelpKeyExistsNonBlankInBothLocales() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        List<String> missing = new ArrayList<>();
        List<String> blank = new ArrayList<>();
        for (String key : expectedKeys()) {
            String enValue = en.getString(key);
            String zhValue = zh.getString(key);
            if (enValue == null || zhValue == null) {
                missing.add(key + " (en=" + (enValue != null)
                        + ", zh=" + (zhValue != null) + ")");
                continue;
            }
            if (enValue.isBlank() || zhValue.isBlank()) {
                blank.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "help keys missing: " + missing);
        assertTrue(blank.isEmpty(), "help keys blank: " + blank);
    }

    @Test
    void helpKeyPlaceholdersMatchAcrossLocales() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        for (String key : expectedKeys()) {
            String enValue = en.getString(key);
            String zhValue = zh.getString(key);
            assertNotNull(enValue, "en_US missing " + key);
            assertNotNull(zhValue, "zh_TW missing " + key);
            assertFalse(enValue.isBlank(), "en_US blank " + key);
            assertFalse(zhValue.isBlank(), "zh_TW blank " + key);
            assertEquals(placeholders(enValue), placeholders(zhValue),
                    "placeholder parity broken for " + key);
        }
    }

    @Test
    void keyHelpersCoverEverySubcommand() {
        for (String sub : LandCommand.SUBCOMMANDS) {
            assertEquals(PREFIX + sub + ".short", LandHelpKeys.shortKey(sub));
            assertEquals(PREFIX + sub + ".detail", LandHelpKeys.detailKey(sub));
            assertEquals(PREFIX + sub + ".usage", LandHelpKeys.usageKey(sub));
            assertEquals(
                    List.of(PREFIX + sub + ".short", PREFIX + sub + ".detail",
                            PREFIX + sub + ".usage"),
                    LandHelpKeys.keysFor(sub));
        }
        assertEquals(PROMPT_KEY, LandHelpKeys.PROMPT);
        assertEquals(UNKNOWN_HINT_KEY, LandHelpKeys.UNKNOWN_HINT);
        assertEquals(PERMISSION_KEY, LandHelpKeys.PERMISSION_LINE);
    }

    @Test
    void keyHelpersRejectUnknownSubcommands() {
        assertThrows(IllegalArgumentException.class, () -> LandHelpKeys.shortKey("nope"));
        assertThrows(IllegalArgumentException.class, () -> LandHelpKeys.detailKey("nope"));
        assertThrows(IllegalArgumentException.class, () -> LandHelpKeys.usageKey("nope"));
        assertThrows(IllegalArgumentException.class, () -> LandHelpKeys.keysFor("nope"));
        assertThrows(NullPointerException.class, () -> LandHelpKeys.shortKey(null));
    }

    @Test
    void everyHelpKeyRendersNonEmptyInBothLocales() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        for (String key : expectedKeys()) {
            for (String tag : new String[] {"en_US", "zh_TW"}) {
                String template = (tag.equals("en_US") ? en : zh).getString(key);
                assertNotNull(template, tag + " missing " + key);
                validateStrict(key, template);
                var parsed = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize(template, toResolvers(SAMPLE_VARS));
                String plain = net.kyori.adventure.text.serializer.plain
                        .PlainTextComponentSerializer.plainText().serialize(parsed);
                assertFalse(plain.isBlank(), tag + " " + key + " must render a non-empty message");
                assertNotEquals(template, plain,
                        tag + " " + key + " must render through MiniMessage");
            }
            assertNotEquals(en.getString(key), zh.getString(key),
                    "zh render must differ from en for " + key);
        }
    }

    /** Sample values for every placeholder the help templates use. */
    private static final Map<String, String> SAMPLE_VARS = Map.ofEntries(
            Map.entry("subcommand", "trust"),
            Map.entry("player", "Alex"),
            Map.entry("action", "BLOCK_BREAK"),
            Map.entry("state", "DENY"),
            Map.entry("reason", "test reason"),
            Map.entry("land_name", "Home"),
            Map.entry("chunk_count", "3"),
            Map.entry("price", "10"),
            Map.entry("generation", "7"),
            Map.entry("revision", "9"),
            Map.entry("group", "builders"),
            Map.entry("profile", "default"),
            Map.entry("new_name", "Base2"),
            Map.entry("old_name", "Base"),
            Map.entry("permission", "chunkland.command.land.trust"));

    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] toResolvers(
            Map<String, String> vars) {
        Map<String, String> copy = new HashMap<>(vars);
        return copy.entrySet().stream()
                .map(e -> net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
                        .unparsed(e.getKey(), e.getValue()))
                .toArray(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]::new);
    }

    private static Set<String> placeholders(String template) {
        Set<String> found = new HashSet<>();
        Matcher matcher = Pattern.compile("<([a-z_]+)>").matcher(template);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        found.retainAll(allowedPlaceholders());
        return found;
    }

    private static Set<String> allowedPlaceholders() {
        try {
            var field = Class.forName("com.smile.chunkland.message.ChunkLandMessagePipeline")
                    .getDeclaredField("ALLOWED_PLACEHOLDERS");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> allowed = (Set<String>) field.get(null);
            return allowed;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read pipeline placeholder whitelist", e);
        }
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

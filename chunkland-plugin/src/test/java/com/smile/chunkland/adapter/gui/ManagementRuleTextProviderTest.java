package com.smile.chunkland.adapter.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.gui.ManagementRuleTexts;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Rule GUI copy resolves per viewer like the permission pages: every
 * {@code gui.manage.rules.*} template exists in both bundled locales with
 * matching placeholders, the key constants stay in sync with the pinned
 * set, and a missing pipeline still yields usable English fallbacks.
 */
class ManagementRuleTextProviderTest {

    static final List<String> RULE_GUI_KEYS = List.of(
            "gui.manage.rules.entry_name",
            "gui.manage.rules.entry_hint",
            "gui.manage.rules.details.title",
            "gui.manage.rules.row.head",
            "gui.manage.rules.row.explicit",
            "gui.manage.rules.row.effective",
            "gui.manage.rules.row.change_hint",
            "gui.manage.rules.row.unset",
            "gui.manage.rules.confirm.title",
            "gui.manage.rules.confirm.current",
            "gui.manage.rules.confirm.target",
            "gui.manage.rules.confirm.allow_name",
            "gui.manage.rules.confirm.deny_name",
            "gui.manage.rules.confirm.inherit_name",
            "gui.manage.rules.confirm.cancel_name",
            "gui.manage.rules.confirm.back_name",
            "gui.manage.rules.confirm.failed_line",
            "gui.manage.rules.unavailable.title",
            "gui.manage.rules.unavailable.lines");

    private static YamlConfiguration load(String tag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        File direct = new File("src/main/resources/lang/" + tag + ".yml");
        File module = new File("chunkland-plugin/src/main/resources/lang/" + tag + ".yml");
        cfg.load(direct.isFile() ? direct : module);
        return cfg;
    }

    private static Set<String> placeholders(String template) {
        Set<String> found = new HashSet<>();
        Matcher matcher = Pattern.compile("<([a-z_]+)>").matcher(template);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        found.retainAll(Set.of("action", "state", "outcome", "layer",
                "deny_count", "allow_count"));
        return found;
    }

    @Test
    void everyRuleGuiKeyExistsNonBlankInBothLocales() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        for (String key : RULE_GUI_KEYS) {
            String enValue = en.getString(key);
            String zhValue = zh.getString(key);
            assertNotNull(enValue, "en_US missing " + key);
            assertNotNull(zhValue, "zh_TW missing " + key);
            assertFalse(enValue.isBlank(), "en_US blank " + key);
            assertFalse(zhValue.isBlank(), "zh_TW blank " + key);
            assertEquals(placeholders(enValue), placeholders(zhValue),
                    "placeholder parity broken for " + key);
            assertNotEquals(enValue, zhValue, "zh render must differ from en for " + key);
        }
    }

    @Test
    void ruleUnavailableBlockHasNoStrayQuotesAndMatchesAcrossLocales() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        String enLines = en.getString("gui.manage.rules.unavailable.lines");
        String zhLines = zh.getString("gui.manage.rules.unavailable.lines");
        assertNotNull(enLines, "en_US missing gui.manage.rules.unavailable.lines");
        assertNotNull(zhLines, "zh_TW missing gui.manage.rules.unavailable.lines");
        assertFalse(enLines.contains("\""),
                "en unavailable block must not leak a stray quote, got:\n" + enLines);
        assertFalse(zhLines.contains("\""),
                "zh unavailable block must not leak a stray quote, got:\n" + zhLines);
        assertFalse(en.getString("gui.manage.rules.unavailable.title").contains("\""));
        assertFalse(zh.getString("gui.manage.rules.unavailable.title").contains("\""));
        assertEquals(enLines.split("\n").length, zhLines.split("\n").length,
                "the unavailable block must carry the same line count in both locales");
    }

    @Test
    void providerKeyConstantsMatchThePinnedKeySet() {        Set<String> constants = new TreeSet<>();
        for (Field field : ManagementRuleTextProvider.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class
                    && field.getName().endsWith("_KEY")) {
                try {
                    constants.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        assertEquals(new TreeSet<>(RULE_GUI_KEYS), constants,
                "rule provider key constants must stay in sync with the pinned key set");
    }

    @Test
    void nullRendererResolvesFallbackTexts() {
        ManagementRuleTexts texts =
                new ManagementRuleTextProvider(null, null).resolve(UUID.randomUUID());
        assertNotNull(texts);
        assertFalse(texts.entryName().isBlank());
        assertFalse(texts.detailsTitle().title(2, 1).isBlank());
        assertFalse(texts.rowHead().head("PVP", "INHERIT", "DENY", "BUILT_IN").isBlank());
        assertFalse(texts.explicitLine().line("PVP", null).isBlank());
        assertFalse(texts.effectiveLine().line("DENY", "BUILT_IN").isBlank());
        assertFalse(texts.confirmTitle().title("PVP").isBlank());
        assertFalse(texts.confirmTargetLine().line("ALLOW").isBlank());
        assertEquals(3, texts.unavailableLines().size(),
                "the unavailable block must carry three lines like the permission pages");
    }

    @Test
    void resolveBuildsEverySlotFromTheRenderer() {
        Map<String, String> seen = new java.util.HashMap<>();
        GuiTemplateRenderer fake = (key, vars, locale) -> {
            StringBuilder out = new StringBuilder("T[").append(key);
            for (Map.Entry<String, Object> entry
                    : new java.util.TreeMap<>(vars).entrySet()) {
                out.append('|').append(entry.getKey()).append('=').append(entry.getValue());
            }
            seen.put(key, out.append(']').toString());
            return seen.get(key);
        };
        ManagementRuleTextProvider provider =
                new ManagementRuleTextProvider(fake, uuid -> Locale.GERMAN);
        ManagementRuleTexts texts = provider.resolve(UUID.randomUUID());

        assertNotNull(texts);
        assertTrue(texts.entryName().contains("gui.manage.rules.entry_name"));
        String title = texts.detailsTitle().title(2, 1);
        assertTrue(title.contains("deny_count=2") && title.contains("allow_count=1"),
                "counts must reach the title template, got: " + title);
        String head = texts.rowHead().head("PVP", "INHERIT", "DENY", "BUILT_IN");
        assertTrue(head.contains("PVP") && head.contains("DENY")
                && head.contains("BUILT_IN"),
                "rule head must interpolate the triple plus the source, got: " + head);
        String explicit = texts.explicitLine().line("PVP", null);
        assertTrue(explicit.contains("gui.manage.rules.row.explicit"),
                "explicit line must render, got: " + explicit);
        String effective = texts.effectiveLine().line("DENY", "BUILT_IN");
        assertTrue(effective.contains("gui.manage.rules.row.effective"),
                "effective line must render, got: " + effective);
        assertTrue(texts.changeHintLine().contains("gui.manage.rules.row.change_hint"));
        assertTrue(texts.confirmTitle().title("PVP")
                .contains("gui.manage.rules.confirm.title"));
        String current = texts.confirmCurrentLine().line("INHERIT", "DENY", "BUILT_IN");
        assertTrue(current.contains("INHERIT") && current.contains("BUILT_IN"),
                "confirm current must carry explicit and source, got: " + current);
        assertTrue(texts.confirmTargetLine().line("ALLOW")
                .contains("gui.manage.rules.confirm.target"));
        assertTrue(texts.confirmAllowName().contains("gui.manage.rules.confirm.allow_name"));
        assertTrue(texts.confirmDenyName().contains("gui.manage.rules.confirm.deny_name"));
        assertTrue(texts.confirmInheritName()
                .contains("gui.manage.rules.confirm.inherit_name"));
        assertTrue(texts.confirmCancelName().contains("gui.manage.rules.confirm.cancel_name"));
        assertTrue(texts.backName().contains("gui.manage.rules.confirm.back_name"));
        assertTrue(texts.confirmFailedLine()
                .contains("gui.manage.rules.confirm.failed_line"));
        assertTrue(texts.unavailableTitle().contains("gui.manage.rules.unavailable.title"));
        assertTrue(texts.unavailableLines().size() >= 1);
        for (String key : RULE_GUI_KEYS) {
            assertTrue(seen.containsKey(key), "resolve must render " + key);
        }
    }
}

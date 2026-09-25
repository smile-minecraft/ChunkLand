package com.smile.chunkland.adapter.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.gui.ManagementGuiTexts;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Pins the management-GUI i18n surface: every key the injected GUI texts
 * resolve from must exist non-blank in both bundled locales. Keys marked
 * reserved are not rendered yet — they are the entry point the
 * confirm-toggle follow-up composes from.
 */
class ManagementGuiTextProviderTest {

    /** All GUI text keys, including the confirm-toggle reservations. */
    static final List<String> GUI_KEYS = List.of(
            "gui.manage.root.title",
            "gui.manage.root.entry_name",
            "gui.manage.root.entry_hint",
            "gui.manage.details.title",
            "gui.manage.details.back_name",
            "gui.manage.row.head",
            "gui.manage.row.conflict_suffix",
            "gui.manage.row.remedy_line",
            "gui.manage.row.default_lore",
            "gui.manage.row.default_unset",
            "gui.manage.remedy.unavailable",
            "gui.manage.remedy.conflict",
            "gui.manage.remedy.plain",
            "gui.manage.unavailable.title",
            "gui.manage.confirm.title",
            "gui.manage.confirm.confirm_name",
            "gui.manage.confirm.cancel_name",
            "gui.manage.confirm.back_name",
            "gui.manage.confirm.target_line",
            "gui.manage.confirm.failed_line",
            "gui.manage.row.readonly_line");

    /**
     * Tag-free templates: plain values by design, so they render to
     * themselves and are exempt from the renders-through-MiniMessage check.
     */
    private static final Set<String> TAG_FREE_KEYS = Set.of(
            "gui.manage.row.conflict_suffix",
            "gui.manage.row.default_unset");

    /**
     * Structural format templates: the punctuation skeleton
     * ({@code ": "}, {@code " @ "}, the lore bullet) is locale-invariant
     * by design because the interpolated slots are runtime display names
     * (action/outcome/layer identifiers), not prose. These still carry
     * MiniMessage tags and placeholder parity, but are exempt from the
     * must-differ-across-locales check.
     */
    private static final Set<String> STRUCTURAL_KEYS = Set.of(
            "gui.manage.row.head",
            "gui.manage.row.remedy_line");

    private static YamlConfiguration load(String tag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        File direct = new File("src/main/resources/lang/" + tag + ".yml");
        File module = new File("chunkland-plugin/src/main/resources/lang/" + tag + ".yml");
        cfg.load(direct.isFile() ? direct : module);
        return cfg;
    }

    @Test
    void everyGuiKeyExistsNonBlankInBothLocales() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        List<String> missing = new ArrayList<>();
        List<String> blank = new ArrayList<>();
        for (String key : GUI_KEYS) {
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
        assertTrue(missing.isEmpty(), "GUI keys missing: " + missing);
        assertTrue(blank.isEmpty(), "GUI keys blank: " + blank);
    }

    @Test
    void guiKeysDifferAcrossLocales() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        for (String key : GUI_KEYS) {
            assertNotNull(en.getString(key), "en_US missing " + key);
            assertNotNull(zh.getString(key), "zh_TW missing " + key);
            if (TAG_FREE_KEYS.contains(key)) {
                continue;
            }
            if (STRUCTURAL_KEYS.contains(key)) {
                continue;
            }
            assertFalse(en.getString(key).equals(zh.getString(key)),
                    "zh render must differ from en for " + key);
        }
    }

    @Test
    void providerKeyConstantsMatchThePinnedKeySet() {
        Set<String> constants = new TreeSet<>();
        for (Field field : ManagementGuiTextProvider.class.getDeclaredFields()) {
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
        assertEquals(new TreeSet<>(GUI_KEYS), constants,
                "provider key constants must stay in sync with the pinned GUI key set");
    }

    @Test
    void everyGuiTemplateValidatesAndRendersInBothLocales() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        for (String key : GUI_KEYS) {
            for (String tag : new String[] {"en_US", "zh_TW"}) {
                String template = (tag.equals("en_US") ? en : zh).getString(key);
                assertNotNull(template, tag + " missing " + key);
                validateStrict(key, template);
                var parsed = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize(template, toResolvers(SAMPLE_VARS));
                String plain = net.kyori.adventure.text.serializer.plain
                        .PlainTextComponentSerializer.plainText().serialize(parsed);
                assertFalse(plain.isBlank(), tag + " " + key + " must render non-blank");
                if (!TAG_FREE_KEYS.contains(key)) {
                    assertNotEquals(template, plain,
                            tag + " " + key + " must render through MiniMessage");
                }
            }
        }
    }

    @Test
    void remedyBlocksResolveToExactlyThreeLines() throws Exception {
        for (String tag : new String[] {"en_US", "zh_TW"}) {
            YamlConfiguration cfg = load(tag);
            for (String key : List.of("gui.manage.remedy.unavailable",
                    "gui.manage.remedy.conflict", "gui.manage.remedy.plain")) {
                String template = cfg.getString(key);
                assertNotNull(template, tag + " missing " + key);
                String[] lines = template.split("\n");
                assertEquals(3, lines.length, tag + " " + key + " must carry three lines");
                for (String line : lines) {
                    assertFalse(line.isBlank(), tag + " " + key + " has a blank line");
                }
            }
        }
    }

    @Test
    void resolveBuildsEverySlotFromTheRenderer() {
        Map<String, String> seen = new HashMap<>();
        AtomicReference<Locale> seenLocale = new AtomicReference<>();
        GuiTemplateRenderer fake = (key, vars, locale) -> {
            seenLocale.set(locale);
            StringBuilder out = new StringBuilder("T[").append(key);
            for (Map.Entry<String, Object> entry : new java.util.TreeMap<>(vars).entrySet()) {
                out.append('|').append(entry.getKey()).append('=').append(entry.getValue());
            }
            String rendered = out.append(']').toString();
            seen.put(key, rendered);
            return rendered;
        };
        UUID viewer = UUID.randomUUID();
        ManagementGuiTextProvider provider =
                new ManagementGuiTextProvider(fake, uuid -> Locale.GERMAN);

        ManagementGuiTexts texts = provider.resolve(viewer);

        assertNotNull(texts);
        assertEquals(Locale.GERMAN, seenLocale.get(), "viewer locale must reach the renderer");
        assertTrue(texts.rootTitle().contains("gui.manage.root.title"));
        assertTrue(texts.entryName().contains("gui.manage.root.entry_name"));
        assertEquals(1, texts.entryLines().size());
        assertTrue(texts.unavailableLines().size() >= 1);
        assertTrue(texts.backName().contains("gui.manage.details.back_name"));
        String title = texts.detailsTitle().title(2, 1);
        assertTrue(title.contains("deny_count=2") && title.contains("allow_count=1"),
                "counts must reach the title template, got: " + title);
        String head = texts.rowHead().head("BLOCK_BREAK", "DENY", "LAND_BINDING", true);
        assertTrue(head.contains("BLOCK_BREAK") && head.contains("DENY")
                && head.contains("LAND_BINDING") && head.contains("gui.manage.row.conflict_suffix"),
                "conflict head must interpolate names plus the suffix template, got: " + head);
        String plainHead = texts.rowHead().head("BLOCK_BREAK", "DENY", "LAND_BINDING", false);
        assertFalse(plainHead.contains("gui.manage.row.conflict_suffix"),
                "quiet rows must not carry the conflict marker, got: " + plainHead);
        List<String> lore = texts.rowRemedies().lore("LAND_BINDING", "DENY", true);
        assertEquals(1, lore.size(), "one fake remedy line stays one lore line");
        assertTrue(lore.get(0).contains("gui.manage.row.remedy_line"), "lore wraps remedies");
        String unset = texts.defaultLore().line("BLOCK_BREAK", null);
        assertTrue(unset.contains("gui.manage.row.default_unset"),
                "null state must render the unset wording, got: " + unset);
        assertTrue(texts.confirmTexts().title().title("BLOCK_BREAK")
                .contains("gui.manage.confirm.title"));
        assertTrue(texts.confirmTexts().confirmName().contains("gui.manage.confirm.confirm_name"));
        assertTrue(texts.confirmTexts().cancelName().contains("gui.manage.confirm.cancel_name"));
        assertTrue(texts.confirmTexts().backName().contains("gui.manage.confirm.back_name"));
        assertTrue(texts.confirmTexts().targetLine().line("DENY")
                .contains("gui.manage.confirm.target_line"));
        assertTrue(texts.confirmTexts().failedLine()
                .contains("gui.manage.confirm.failed_line"));
        assertTrue(texts.readOnlyLine().contains("gui.manage.row.readonly_line"));
    }

    @Test
    void resolveFallsBackToEnglishWhenRenderingFails() {
        GuiTemplateRenderer broken = (key, vars, locale) -> {
            throw new RuntimeException("lang boom");
        };
        ManagementGuiTexts texts =
                new ManagementGuiTextProvider(broken, uuid -> null).resolve(UUID.randomUUID());

        assertNotNull(texts);
        assertEquals("Land Management", texts.rootTitle());
        assertEquals("Back", texts.backName());
        assertTrue(texts.detailsTitle().title(2, 1).contains("2 DENY"));
        assertTrue(texts.rowHead().head("A", "DENY", "L", true).contains("!CONFLICT"));
        assertEquals(3, texts.rowRemedies().lore("L", "DENY", true).size());
        assertEquals(3, texts.unavailableLines().size());
    }

    @Test
    void nullRendererResolvesFallbackTexts() {
        ManagementGuiTexts texts =
                new ManagementGuiTextProvider(null, null).resolve(UUID.randomUUID());
        assertNotNull(texts);
        assertEquals("Land Management", texts.rootTitle());
        assertEquals("Land Management (unavailable)", texts.unavailableTitle());
    }

    /** Sample values for every placeholder the GUI templates use. */
    private static final Map<String, String> SAMPLE_VARS = Map.ofEntries(
            Map.entry("action", "BLOCK_BREAK"),
            Map.entry("outcome", "DENY"),
            Map.entry("layer", "LAND_BINDING"),
            Map.entry("conflict", " !CONFLICT"),
            Map.entry("remedy", "sample remedy"),
            Map.entry("state", "DENY"),
            Map.entry("deny_count", "2"),
            Map.entry("allow_count", "1"));

    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] toResolvers(
            Map<String, String> vars) {
        Map<String, String> copy = new HashMap<>(vars);
        return copy.entrySet().stream()
                .map(e -> net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
                        .unparsed(e.getKey(), e.getValue()))
                .toArray(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]::new);
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
    void guiKeyPlaceholdersMatchAcrossLocales() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        for (String key : GUI_KEYS) {
            assertEquals(placeholders(en.getString(key)), placeholders(zh.getString(key)),
                    "placeholder parity broken for " + key);
        }
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
}

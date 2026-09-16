package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.smile.chunkland.enterleave.EnterLeaveNotifier;
import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The four enter-leave prompt keys resolve in both locales with identical
 * placeholder sets, and every template passes the pipeline's strict
 * MiniMessage check.
 */
class EnterLeaveMessageParityTest {

    private static final Map<String, Set<String>> EXPECTED_VARS = Map.of(
            "land.enter.message", Set.of("land_name"),
            "land.leave.message", Set.of("land_name"),
            "land.subland.enter", Set.of("land_name", "sub_name"),
            "land.subland.leave", Set.of("land_name", "sub_name"));

    private static final Pattern PLACEHOLDER = Pattern.compile("<([a-z_]+)>");

    @Test
    void keysResolveInBothLocalesWithParity() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        for (String key : EXPECTED_VARS.keySet()) {
            String enTemplate = en.getString(key);
            String zhTemplate = zh.getString(key);
            assertNotNull(enTemplate, "en_US missing " + key);
            assertNotNull(zhTemplate, "zh_TW missing " + key);
            assertEquals(placeholders(enTemplate), placeholders(zhTemplate),
                    "placeholder parity for " + key);
            assertEquals(EXPECTED_VARS.get(key), placeholders(enTemplate),
                    "unexpected placeholders for " + key);
        }
    }

    @Test
    void templatesPassStrictValidation() throws Exception {
        for (String localeTag : new String[]{"en_US", "zh_TW"}) {
            YamlConfiguration cfg = load(localeTag);
            for (String key : EXPECTED_VARS.keySet()) {
                String raw = cfg.getString(key);
                assertNotNull(raw, localeTag + " missing " + key);
                ChunkLandMessagePipeline.validateTemplateStrict(key, raw);
            }
        }
    }

    @Test
    void notifierKeysMatchLangKeys() throws Exception {
        YamlConfiguration en = load("en_US");
        Set<String> notifierKeys = Set.of(
                EnterLeaveNotifier.ENTER_LAND_KEY,
                EnterLeaveNotifier.LEAVE_LAND_KEY,
                EnterLeaveNotifier.ENTER_SUB_KEY,
                EnterLeaveNotifier.LEAVE_SUB_KEY);
        assertEquals(EXPECTED_VARS.keySet(), notifierKeys);
        for (String key : notifierKeys) {
            assertNotNull(en.getString(key), "en_US missing notifier key " + key);
        }
    }

    private static YamlConfiguration load(String localeTag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new File("src/main/resources/lang/" + localeTag + ".yml"));
        return cfg;
    }

    private static Set<String> placeholders(String template) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        // Formatting tags are not placeholders.
        names.remove("green");
        names.remove("gray");
        return names;
    }
}

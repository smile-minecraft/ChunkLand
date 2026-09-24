package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Guards the complete bundled language key surface, not one message subtree.
 */
class LangKeySetParityTest {

    @Test
    void bundledLanguageKeySetsAreIdentical() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        assertKeySetsMatch(en, zh);
    }

    @Test
    void parityCheckRejectsMissingKeyFixture() {
        YamlConfiguration en = new YamlConfiguration();
        en.set("land.enter.message", "entered");
        YamlConfiguration zh = new YamlConfiguration();
        zh.set("land.enter.message", "entered");
        zh.set("land.leave.message", "left");

        AssertionError failure = assertThrows(AssertionError.class,
                () -> assertKeySetsMatch(en, zh));
        assertTrue(failure.getMessage().contains("language key sets differ"),
                "missing-key fixture must exercise the real parity assertion");
    }

    private static void assertKeySetsMatch(YamlConfiguration en, YamlConfiguration zh) {
        Set<String> enKeys = new TreeSet<>(en.getKeys(true));
        Set<String> zhKeys = new TreeSet<>(zh.getKeys(true));
        assertEquals(enKeys, zhKeys, "language key sets differ between en_US and zh_TW");
    }

    private static YamlConfiguration load(String localeTag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new File("src/main/resources/lang/" + localeTag + ".yml"));
        return cfg;
    }
}

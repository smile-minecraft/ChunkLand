package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class MessagesConfigTest {

    @Test
    void missingMessagesSectionFallsBackToDefaults() {
        String yaml = "worlds: {}\n";
        ChunkLandConfig cfg = ConfigSchema.parseYamlText(yaml);
        assertEquals(Locale.forLanguageTag("en-US"), cfg.messages().defaultLocale());
        assertEquals(2, cfg.messages().cooldownSeconds());
    }

    @Test
    void emptyMapDefaults() {
        ChunkLandConfig cfg = ConfigSchema.parseYamlText("messages: {}\n");
        assertEquals(Locale.forLanguageTag("en-US"), cfg.messages().defaultLocale());
        assertEquals(2, cfg.messages().cooldownSeconds());
    }

    @Test
    void explicitMessagesAreParsed() {
        String yaml = "messages:\n  default-locale: zh_TW\n  cooldown-seconds: 5\n";
        ChunkLandConfig cfg = ConfigSchema.parseYamlText(yaml);
        assertEquals("zh", cfg.messages().defaultLocale().getLanguage());
        assertEquals("TW", cfg.messages().defaultLocale().getCountry());
        assertEquals(5, cfg.messages().cooldownSeconds());
    }

    @Test
    void partialMessagesUseDefaultsForMissingKeys() {
        ChunkLandConfig cfg = ConfigSchema.parseYamlText("messages:\n  default-locale: en_US\n");
        assertEquals(0, cfg.messages().defaultLocale().getCountry().compareToIgnoreCase("US"));
        assertEquals(2, cfg.messages().cooldownSeconds());

        ChunkLandConfig cfg2 = ConfigSchema.parseYamlText("messages:\n  cooldown-seconds: 0\n");
        assertEquals(Locale.forLanguageTag("en-US"), cfg2.messages().defaultLocale());
        assertEquals(0, cfg2.messages().cooldownSeconds());
    }

    @Test
    void topLevelMessagesNullIsRejected() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages: null\n"));
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages: ~\n"));
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages:\n"));
    }

    @Test
    void nestedNullIsRejected() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages:\n  default-locale: ~\n"));
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages:\n  cooldown-seconds: ~\n"));
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages:\n  default-locale: null\n"));
    }

    @Test
    void unknownKeyIsRejected() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages:\n  foo: 1\n"));
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages:\n  default-locale: en_US\n  unknown: 1\n"));
    }

    @Test
    void negativeCooldownIsRejected() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages:\n  cooldown-seconds: -1\n"));
    }

    @Test
    void floatingPointCooldownIsRejected() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages:\n  cooldown-seconds: 2.5\n"));
    }

    @Test
    void nonIntegerStringCooldownIsRejected() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages:\n  cooldown-seconds: \"two\"\n"));
    }

    @Test
    void invalidLocaleTagIsRejected() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages:\n  default-locale: \"\"\n"));
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages:\n  default-locale: \"   \"\n"));
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages:\n  default-locale: \"!!!\"\n"));
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages:\n  default-locale: 123\n"));
    }

    @Test
    void unknownTopLevelStillRejected() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("messages:\n  default-locale: en_US\nunknown: 1\n"));
    }

    @Test
    void reloadFailurePreservesSnapshotAndEpoch() {
        String initial = "messages:\n  default-locale: en_US\n  cooldown-seconds: 2\nworlds: {}\n";
        ConfigService svc = new ConfigService(new StringYamlLoader(initial));
        ChunkLandConfig before = svc.current();
        long epochBefore = before.globalPolicyEpoch();
        Locale localeBefore = before.messages().defaultLocale();
        svc.swapLoader(new StringYamlLoader("messages:\n  cooldown-seconds: -1\nworlds: {}\n"));
        ChunkLandConfig after = svc.reload();
        assertSame(before, after);
        assertEquals(epochBefore, after.globalPolicyEpoch());
        assertEquals(localeBefore, after.messages().defaultLocale());
        assertEquals(2, after.messages().cooldownSeconds());

        // null variant
        svc.swapLoader(new StringYamlLoader("messages: null\n"));
        ChunkLandConfig after2 = svc.reload();
        assertSame(before, after2);
        assertEquals(epochBefore, after2.globalPolicyEpoch());
    }

    @Test
    void reloadSuccessBumpsEpochAndAppliesMessages() {
        String yaml = "messages:\n  default-locale: en_US\n  cooldown-seconds: 2\nworlds: {}\n";
        ConfigService svc = new ConfigService(new StringYamlLoader(yaml));
        long before = svc.current().globalPolicyEpoch();
        svc.swapLoader(new StringYamlLoader("messages:\n  default-locale: zh_TW\n  cooldown-seconds: 5\nworlds: {}\n"));
        ChunkLandConfig after = svc.reload();
        assertEquals(before + 1, after.globalPolicyEpoch());
        assertEquals("zh", after.messages().defaultLocale().getLanguage());
        assertEquals(5, after.messages().cooldownSeconds());
    }

    // helper
    private static class StringYamlLoader implements ConfigLoader {
        private final String yaml;
        StringYamlLoader(String yaml) { this.yaml = yaml; }
        @Override public ChunkLandConfig load() { return ConfigSchema.parseYamlText(yaml); }
        @Override public String describe() { return "test"; }
    }
}

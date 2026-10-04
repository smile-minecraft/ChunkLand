package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.bedrock.BedrockPlayerInfo;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.form.FormService;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M0MessageProbeTest {

    // --- isApiUsable (fail-closed) ---

    @Test
    void isApiUsableFalseForNullApi() {
        assertFalse(M0MessageProbe.isApiUsable(null));
    }

    @Test
    void isApiUsableFalseForUnreadyApi() {
        assertFalse(M0MessageProbe.isApiUsable(StubAceLibApi.unready()));
    }

    @Test
    void isApiUsableTrueForReadyApiWithBedrock() {
        assertTrue(M0MessageProbe.isApiUsable(StubAceLibApi.readyWithBedrock()));
    }

    // --- tryBuild (fail-closed) ---

    @Test
    void tryBuildEmptyForNullApi() {
        assertTrue(M0MessageProbe.tryBuild(null, null).isEmpty());
    }

    @Test
    void tryBuildEmptyForUnreadyApi() {
        assertTrue(M0MessageProbe.tryBuild(null, StubAceLibApi.unready()).isEmpty());
    }

    // --- ALLOW isolated contract guard ---

    @Test
    void allowDecisionTouchesNoMessageLayer() {
        // ALLOW must return false without rendering or sending (zero message cost).
        M0MessageProbe probe = new M0MessageProbe(null, null, null);
        assertFalse(probe.dispatch(true, null, null));
    }

    @Test
    void denyFailsClosedWhenServiceMissing() {
        // DENY with no ready service must refuse to operate (fail-closed), not silently no-op.
        M0MessageProbe probe = new M0MessageProbe(null, null, null);
        assertThrows(IllegalStateException.class, () -> probe.dispatch(false, null, null));
    }

    @Test
    void sendProbeFailsClosedWhenServiceMissing() {
        M0MessageProbe probe = new M0MessageProbe(null, null, null);
        assertThrows(IllegalStateException.class, () -> probe.sendProbe(null, null, M0MessageProbe.Channel.CHAT));
    }

    // --- channel parsing (review: four channels, no silent broadcast) ---

    @Test
    void channelFromStringOnlyAcceptsKnownKeywords() {
        assertTrue(M0MessageProbe.Channel.fromString("chat").isPresent());
        assertTrue(M0MessageProbe.Channel.fromString("actionbar").isPresent());
        assertTrue(M0MessageProbe.Channel.fromString("title").isPresent());
        assertTrue(M0MessageProbe.Channel.fromString("broadcast").isPresent());
        assertFalse(M0MessageProbe.Channel.fromString("garbage").isPresent());
        assertFalse(M0MessageProbe.Channel.fromString(null).isPresent());
    }

    @Test
    void parseProbeArgsDefaultsToChat() {
        M0MessageProbe.ProbeArgs args = M0MessageProbe.parseProbeArgs(new String[]{"m0message"});
        assertEquals(M0MessageProbe.Channel.CHAT, args.channel());
        assertNull(args.override());
    }

    @Test
    void parseProbeArgsParsesLocale() {
        M0MessageProbe.ProbeArgs args = M0MessageProbe.parseProbeArgs(new String[]{"m0message", "zh_TW"});
        assertEquals(M0MessageProbe.Channel.CHAT, args.channel());
        assertEquals("zh", args.override().getLanguage());
    }

    @Test
    void parseProbeArgsParsesChannel() {
        M0MessageProbe.ProbeArgs args = M0MessageProbe.parseProbeArgs(new String[]{"m0message", "broadcast"});
        assertEquals(M0MessageProbe.Channel.BROADCAST, args.channel());
        assertNull(args.override());
    }

    @Test
    void parseProbeArgsParsesLocaleAndChannelOrderIndependent() {
        M0MessageProbe.ProbeArgs a = M0MessageProbe.parseProbeArgs(new String[]{"m0message", "zh_TW", "title"});
        assertEquals(M0MessageProbe.Channel.TITLE, a.channel());
        assertEquals("zh", a.override().getLanguage());

        M0MessageProbe.ProbeArgs b = M0MessageProbe.parseProbeArgs(new String[]{"m0message", "actionbar", "en_US"});
        assertEquals(M0MessageProbe.Channel.ACTIONBAR, b.channel());
        assertEquals("en", b.override().getLanguage());
    }

    @Test
    void parseProbeArgsUnknownTokenDoesNotSelectBroadcast() {
        // An unrecognized token is treated as a locale override, never as a silent broadcast.
        M0MessageProbe.ProbeArgs args = M0MessageProbe.parseProbeArgs(new String[]{"m0message", "garbage"});
        assertEquals(M0MessageProbe.Channel.CHAT, args.channel());
    }

    // --- four-entry-point wiring (contract/structure evidence; not runtime renderer execution) ---

    @Test
    void productionSourceWiresAllFourFallbackEntryPoints() throws Exception {
        String src = Files.readString(Paths.get(
            "src/main/java/com/smile/chunkland/message/M0MessageProbe.java"));
        assertAll("bedrock-aware *WithFallback entry points",
            () -> assertTrue(src.contains("sendChatWithFallback("), "sendChatWithFallback"),
            () -> assertTrue(src.contains("sendActionBarWithFallback("), "sendActionBarWithFallback"),
            () -> assertTrue(src.contains("sendTitleWithFallback("), "sendTitleWithFallback"),
            () -> assertTrue(src.contains("broadcastWithFallback("), "broadcastWithFallback"));
        assertAll("java component counterparts (per-player channels)",
            () -> assertTrue(src.contains("service.sendChat("), "sendChat"),
            () -> assertTrue(src.contains("service.sendActionBar("), "sendActionBar"),
            () -> assertTrue(src.contains("service.sendTitle("), "sendTitle"));
        assertAll("no custom abstraction",
            () -> assertFalse(src.contains("FallbackMessageGateway"), "no FallbackMessageGateway"),
            () -> assertFalse(src.contains("BedrockFallbackRenderer"), "no BedrockFallbackRenderer"),
            () -> assertFalse(src.contains("MessageGateway"), "no MessageGateway"));
        // Bedrock split is per-player (chat/actionbar/title) and uses the fallback variant only inside the branch.
        assertEquals(3, countOccurrences(src, "if (useBedrockFallback(bedrock, player.getUniqueId()))"),
            "chat/actionbar/title each split on Bedrock detection");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // --- resource path / overwrite (review fixes) ---

    @Test
    void langResourcesResideUnderLangDirectoryNotMessages() {
        assertNotNull(getClass().getResource("/lang/en_US.yml"),
            "bundled lang fixture must live under lang/ on the classpath");
        assertNull(getClass().getResource("/messages/en_US.yml"),
            "lang fixtures must NOT be under messages/ (matches LangManager.LANG_DIR)");
    }

    @Test
    void probeInitializationUsesNoOverwrite() {
        assertFalse(M0MessageProbe.LANG_RESOURCE_OVERWRITE,
            "saveResource must use overwrite=false so player-edited lang files are preserved");
    }

    // --- resource contract (AceLib v1.3.0): <value> placeholder + bedrock fallback keys ---

    @Test
    void langResourcesUseAceLibPlaceholderAndFallbackKeys() throws Exception {
        // AceLib v1.3.0 parseMiniMessage uses <key> placeholders (not {var}); the Bedrock
        // fallback prompts live at root-level dotted keys message.bedrock.fallback.* with a
        // <payload> placeholder. This guards both the chat value substitution and the
        // ACELIB-MSG-004 missing-key warning without needing a live Folia server.
        String[] locales = {"en_US", "zh_TW"};
        String[] fallbackKeys = {
            "message.bedrock.fallback.run_command",
            "message.bedrock.fallback.suggest_command",
            "message.bedrock.fallback.open_url",
            "message.bedrock.fallback.copy_to_clipboard",
            "message.bedrock.fallback.unknown"
        };
        for (String locale : locales) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File("src/main/resources/lang/" + locale + ".yml"));
            String smoke = cfg.getString("m0.message.smoke");
            assertNotNull(smoke, locale + ": m0.message.smoke must exist");
            assertTrue(smoke.contains("<value>"),
                locale + ": smoke template must use <value> placeholder for parseMiniMessage");
            assertFalse(smoke.contains("{value}"),
                locale + ": smoke template must not use {value} (AceLib v1.3.0 parseMiniMessage)");
            for (String key : fallbackKeys) {
                String value = cfg.getString(key);
                assertNotNull(value, locale + ": bedrock fallback key missing: " + key);
                assertTrue(value.contains("<payload>"),
                    locale + ": bedrock fallback key must use <payload> placeholder: " + key);
            }
        }
    }

    // --- locale chain (review fix: Bedrock Floodgate languageCode fallback) ---

    @Test
    void resolveLocaleChainOverrideWins() {
        Locale resolved = M0MessageProbe.resolveLocaleChain(Locale.CHINA, Locale.JAPAN, "zh-TW", Locale.US);
        assertEquals(Locale.CHINA, resolved);
    }

    @Test
    void resolveLocaleChainPlayerLocaleAfterOverride() {
        Locale resolved = M0MessageProbe.resolveLocaleChain(null, Locale.JAPAN, "zh-TW", Locale.US);
        assertEquals(Locale.JAPAN, resolved);
    }

    @Test
    void resolveLocaleChainBedrockLanguageCodeAfterPlayerLocale() {
        Locale resolved = M0MessageProbe.resolveLocaleChain(null, null, "zh_TW", Locale.US);
        assertEquals("zh", resolved.getLanguage());
        assertEquals("TW", resolved.getCountry());
    }

    @Test
    void resolveLocaleChainDefaultWhenNothingSet() {
        Locale resolved = M0MessageProbe.resolveLocaleChain(null, null, null, Locale.US);
        assertEquals(Locale.US, resolved);
    }

    @Test
    void resolveLocaleChainMalformedBedrockTagFallsBackToDefault() {
        Locale resolved = M0MessageProbe.resolveLocaleChain(null, null, "!!!not-a-tag", Locale.US);
        assertEquals(Locale.US, resolved);
    }

    @Test
    void resolveLocaleChainBlankBedrockTagFallsBackToDefault() {
        Locale resolved = M0MessageProbe.resolveLocaleChain(null, null, "   ", Locale.US);
        assertEquals(Locale.US, resolved);
    }

    // --- Bedrock split (review fix: never raw-click to Bedrock) ---

    @Test
    void useBedrockFallbackTrueForBedrockPlayer() {
        BedrockService bedrock = new FakeBedrockService(true);
        assertTrue(M0MessageProbe.useBedrockFallback(bedrock, UUID.randomUUID()));
    }

    @Test
    void useBedrockFallbackFalseForJavaPlayer() {
        BedrockService bedrock = new FakeBedrockService(false);
        assertFalse(M0MessageProbe.useBedrockFallback(bedrock, UUID.randomUUID()));
    }

    @Test
    void useBedrockFallbackFalseForNullBedrock() {
        assertFalse(M0MessageProbe.useBedrockFallback(null, UUID.randomUUID()));
    }

    @Test
    void useBedrockFallbackFalseForThrowingBedrock() {
        BedrockService bedrock = new ThrowingBedrockService();
        assertFalse(M0MessageProbe.useBedrockFallback(bedrock, UUID.randomUUID()));
    }

    // --- test fakes ---

    static final class FakeBedrockService implements BedrockService {
        private final boolean bedrock;

        FakeBedrockService(boolean bedrock) {
            this.bedrock = bedrock;
        }

        @Override
        public boolean isBedrockPlayer(UUID uuid) {
            return bedrock;
        }

        @Override
        public Optional<BedrockPlayerInfo> getPlayerInfo(UUID uuid) {
            return Optional.empty();
        }

        @Override
        public FormService forms() {
            return null;
        }

        @Override
        public String getModuleStatus() {
            return "";
        }

        @Override
        public void shutdown() {
            // no-op
        }
    }

    static final class ThrowingBedrockService implements BedrockService {
        @Override
        public boolean isBedrockPlayer(UUID uuid) {
            throw new RuntimeException("boom");
        }

        @Override
        public Optional<BedrockPlayerInfo> getPlayerInfo(UUID uuid) {
            return Optional.empty();
        }

        @Override
        public FormService forms() {
            return null;
        }

        @Override
        public String getModuleStatus() {
            return "";
        }

        @Override
        public void shutdown() {
            // no-op
        }
    }
}

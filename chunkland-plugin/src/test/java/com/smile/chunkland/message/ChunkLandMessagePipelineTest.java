package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.acelib.bedrock.BedrockPlayerInfo;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.form.FormService;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkLandMessagePipelineTest {

    @Test
    void resolveLocaleChainOverrideWins() {
        Locale r = ChunkLandMessagePipeline.resolveLocaleChain(Locale.CHINA, Locale.JAPAN, "zh-TW", Locale.US);
        assertEquals(Locale.CHINA, r);
    }

    @Test
    void resolveLocaleChainPlayerLocaleAfterOverride() {
        Locale r = ChunkLandMessagePipeline.resolveLocaleChain(null, Locale.JAPAN, "zh-TW", Locale.US);
        assertEquals(Locale.JAPAN, r);
    }

    @Test
    void resolveLocaleChainBedrockLanguageCodeAfterPlayerLocale() {
        Locale r = ChunkLandMessagePipeline.resolveLocaleChain(null, null, "zh_TW", Locale.US);
        assertEquals("zh", r.getLanguage());
        assertEquals("TW", r.getCountry());
    }

    @Test
    void resolveLocaleChainDefaultWhenNothingSet() {
        Locale r = ChunkLandMessagePipeline.resolveLocaleChain(null, null, null, Locale.US);
        assertEquals(Locale.US, r);
    }

    @Test
    void resolveLocaleChainMalformedBedrockTagFallsBackToDefault() {
        Locale r = ChunkLandMessagePipeline.resolveLocaleChain(null, null, "!!!not-a-tag", Locale.US);
        assertEquals(Locale.US, r);
    }

    @Test
    void unknownKeyThrowsMessageException(@TempDir File tempDir) throws Exception {
        ChunkLandMessagePipeline pipeline = buildPipeline(tempDir, Locale.US, false);
        assertThrows(MessageException.class, () -> pipeline.render("does.not.exist", Map.of(), null, null));
    }

    @Test
    void emptyKeyThrowsMessageException(@TempDir File tempDir) throws Exception {
        ChunkLandMessagePipeline pipeline = buildPipeline(tempDir, Locale.US, false);
        assertThrows(MessageException.class, () -> pipeline.render("", Map.of(), null, null));
    }

    @Test
    void pipelineCachesTemplatePerKeyLocaleNotPerVars(@TempDir File tempDir) throws Exception {
        ChunkLandMessagePipeline pipeline = buildPipeline(tempDir, Locale.US, false);
        Component a = pipeline.render("m0.message.smoke", Map.of("value", "A"), Locale.US, null);
        Component b = pipeline.render("m0.message.smoke", Map.of("value", "B"), Locale.US, null);
        assertEquals(1, pipeline.cachedTemplateCount(), "same (key,locale) should cache template");
        assertNotEquals(a, b, "different vars must not share Component");
        pipeline.render("m0.message.smoke", Map.of("value", "C"), Locale.forLanguageTag("zh-TW"), null);
        assertEquals(2, pipeline.cachedTemplateCount());
    }

    @Test
    void callerDataNotSharedAcrossPlayers(@TempDir File tempDir) throws Exception {
        ChunkLandMessagePipeline pipeline = buildPipeline(tempDir, Locale.US, false);
        Component c1 = pipeline.render("land.claim.confirm", Map.of("land_name", "AlphaLandX", "chunk_count", "1", "price", "$10", "conflict_count", "0", "min_y", "59", "revision", "1"), Locale.US, null);
        Component c2 = pipeline.render("land.claim.confirm", Map.of("land_name", "BetaLandY", "chunk_count", "2", "price", "$20", "conflict_count", "1", "min_y", "10", "revision", "2"), Locale.US, null);
        String s1 = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c1);
        String s2 = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c2);
        assertTrue(s1.contains("AlphaLandX"));
        assertTrue(s2.contains("BetaLandY"));
        assertFalse(s1.contains("BetaLandY"));
        assertFalse(s2.contains("AlphaLandX"));
    }

    @Test
    void allowGuardDoesNotRenderOrCallService(@TempDir File tempDir) throws Exception {
        CountingSender sender = new CountingSender();
        ChunkLandMessagePipeline pipeline = buildPipelineWithSender(tempDir, Locale.US, false, sender);
        Player player = playerWithLocale(UUID.randomUUID(), Locale.US);
        boolean sent = pipeline.dispatchIfDenied(player, "m0.message.smoke", Map.of("value", "x"), null, true, false);
        assertFalse(sent);
        assertEquals(0, sender.chatCalls);
        assertEquals(0, sender.chatFallbackCalls);
        boolean sent2 = pipeline.dispatchIfDenied(player, "m0.message.smoke", Map.of("value", "x"), null, false, true);
        assertFalse(sent2);
        assertEquals(0, sender.chatCalls);
    }

    @Test
    void denyPathDoesRenderAndCallService(@TempDir File tempDir) throws Exception {
        CountingSender sender = new CountingSender();
        ChunkLandMessagePipeline pipeline = buildPipelineWithSender(tempDir, Locale.US, false, sender);
        Player player = playerWithLocale(UUID.randomUUID(), Locale.US);
        boolean sent = pipeline.dispatchIfDenied(player, "m0.message.smoke", Map.of("value", "x"), null, false, false);
        assertTrue(sent);
        assertEquals(1, sender.chatCalls + sender.chatFallbackCalls);
    }

    @Test
    void javaPlayerUsesComponentOverloadBedrockUsesFallback(@TempDir File tempDir) throws Exception {
        // Java
        CountingSender javaSender = new CountingSender();
        ChunkLandMessagePipeline javaPipe = buildPipelineWithSender(tempDir, Locale.US, false, javaSender);
        Player javaPlayer = playerWithLocale(UUID.randomUUID(), Locale.US);
        javaPipe.sendChat(javaPlayer, "m0.message.smoke", Map.of("value", "v"), null);
        assertEquals(1, javaSender.chatCalls);
        assertEquals(0, javaSender.chatFallbackCalls);

        // Bedrock
        CountingSender bedrockSender = new CountingSender();
        ChunkLandMessagePipeline bedrockPipe = buildPipelineWithSender(tempDir, Locale.US, true, bedrockSender);
        Player bedrockPlayer = playerWithLocale(UUID.randomUUID(), Locale.US);
        bedrockPipe.sendChat(bedrockPlayer, "m0.message.smoke", Map.of("value", "v"), null);
        assertEquals(0, bedrockSender.chatCalls);
        assertEquals(1, bedrockSender.chatFallbackCalls);
        assertNotNull(bedrockSender.lastFallbackLocale);

        // actionbar
        javaSender.reset();
        javaPipe.sendActionBar(javaPlayer, "m0.message.smoke", Map.of("value", "v"), null);
        assertEquals(1, javaSender.actionBarCalls);
        assertEquals(0, javaSender.actionBarFallbackCalls);
        bedrockSender.reset();
        bedrockPipe.sendActionBar(bedrockPlayer, "m0.message.smoke", Map.of("value", "v"), null);
        assertEquals(1, bedrockSender.actionBarFallbackCalls);

        // title
        javaSender.reset();
        javaPipe.sendTitle(javaPlayer, "m0.message.smoke", Map.of("value", "v"), null);
        assertEquals(1, javaSender.titleCalls);
        assertEquals(0, javaSender.titleFallbackCalls);
        bedrockSender.reset();
        bedrockPipe.sendTitle(bedrockPlayer, "m0.message.smoke", Map.of("value", "v"), null);
        assertEquals(1, bedrockSender.titleFallbackCalls);

        // broadcast
        javaSender.reset();
        javaPipe.broadcast("m0.message.smoke", Map.of("value", "v"), Locale.US);
        assertEquals(1, javaSender.broadcastFallbackCalls);
    }

    @Test
    void saveResourceDoesNotOverwrite() throws Exception {
        assertFalse(ChunkLandMessagePipeline.LANG_RESOURCE_OVERWRITE);
        String src = Files.readString(Paths.get("src/main/java/com/smile/chunkland/message/ChunkLandMessagePipeline.java"));
        assertTrue(src.contains("LANG_RESOURCE_OVERWRITE = false"));
        assertTrue(src.contains("saveResource(path, LANG_RESOURCE_OVERWRITE)"));
    }

    @Test
    void noCustomGatewayOrRenderer() throws Exception {
        String src = Files.readString(Paths.get("src/main/java/com/smile/chunkland/message/ChunkLandMessagePipeline.java"));
        assertFalse(src.contains("MessageGateway"));
        assertFalse(src.contains("BedrockFallbackRenderer"));
        assertFalse(src.contains("FallbackMessageGateway"));
    }

    @Test
    void productionSourceUsesFourFallbackEntryPoints() throws Exception {
        String src = Files.readString(Paths.get("src/main/java/com/smile/chunkland/message/ChunkLandMessagePipeline.java"));
        assertTrue(src.contains("sendChatWithFallback("));
        assertTrue(src.contains("sendActionBarWithFallback("));
        assertTrue(src.contains("sendTitleWithFallback("));
        assertTrue(src.contains("broadcastWithFallback("));
        assertTrue(src.contains("sender.sendChat("));
        assertTrue(src.contains("sender.sendActionBar("));
        assertTrue(src.contains("sender.sendTitle("));
    }

    @Test
    void langResourcesHaveProductKeysAndFallbacks() throws Exception {
        for (String locale : new String[]{"en_US", "zh_TW"}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File("src/main/resources/lang/" + locale + ".yml"));
            for (String k : new String[]{"message.bedrock.fallback.run_command", "message.bedrock.fallback.suggest_command", "message.bedrock.fallback.open_url", "message.bedrock.fallback.copy_to_clipboard", "message.bedrock.fallback.unknown"}) {
                assertNotNull(cfg.getString(k), locale + " missing " + k);
                assertTrue(cfg.getString(k).contains("<payload>"));
            }
            assertNotNull(cfg.getString("land.claim.confirm"), locale + " land.claim.confirm");
            String confirm = cfg.getString("land.claim.confirm");
            assertTrue(confirm.contains("<price>") || confirm.contains("<chunk_count>"), locale + " confirm must contain price/chunk_count in body");
            assertTrue(confirm.contains("<conflict_count>") || confirm.contains("<min_y>"), locale + " confirm must contain conflict/min_y");
        }
    }

    @Test
    void localeOverrideRespected(@TempDir File tempDir) throws Exception {
        ChunkLandMessagePipeline pipeline = buildPipeline(tempDir, Locale.US, false);
        Component en = pipeline.render("land.claim.success", Map.of("land_name", "X", "chunk_count", "1", "price", "$1", "min_y", "0"), Locale.US, null);
        Component zh = pipeline.render("land.claim.success", Map.of("land_name", "X", "chunk_count", "1", "price", "$1", "min_y", "0"), Locale.forLanguageTag("zh-TW"), null);
        assertNotEquals(en.toString(), zh.toString());
    }

    @Test
    void malformedUnknownTagFailClosed() {
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<unknown>"));
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<unknown>hello</unknown>"));
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<typo>"));
    }

    @Test
    void malformedUnclosedTagFailClosed() {
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<red>text"));
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<click:run_command:'/foo'>text"));
    }

    @Test
    void malformedMismatchedTagFailClosed() {
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<red>text</blue>"));
    }

    @Test
    void validStandardTagsAndPlaceholdersPass() {
        assertDoesNotThrow(() -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<white>hello</white>"));
        assertDoesNotThrow(() -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<hover:show_text:'hi'>text</hover>"));
        assertDoesNotThrow(() -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<click:run_command:'/foo'>text</click>"));
        assertDoesNotThrow(() -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<land_name> — <chunk_count> chunks for <price>"));
        assertDoesNotThrow(() -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<value> and <payload> and <min_y>"));
        assertDoesNotThrow(() -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<click:run_command:'/land confirm <revision>'>text</click>"));
        assertDoesNotThrow(() -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<hover:show_text:'Price <price>'>text</hover>"));
    }

    @Test
    void nestedHoverUnknownFailClosed() {
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<hover:show_text:'<unknown>'>text</hover>"));
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<hover:show_text:'hello <typo>'>text</hover>"));
    }

    @Test
    void nestedHoverUnclosedFailClosed() {
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<hover:show_text:'<red>hi'>text</hover>"));
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<hover:show_text:'hi'>text"));
    }

    @Test
    void nestedHoverMismatchedFailClosed() {
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<hover:show_text:'<red>hi</blue>'>text</hover>"));
    }

    @Test
    void nestedClickUnknownFailClosed() {
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<click:run_command:'<unknown>'>text</click>"));
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<click:run_command:'/foo <typo>'>text</click>"));
    }

    @Test
    void nestedClickUnclosedFailClosed() {
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<click:run_command:'/foo'>text"));
        assertThrows(MessageException.class, () -> ChunkLandMessagePipeline.validateTemplateStrict("k", "<click:run_command:'/foo'>text</hover>"));
    }

    @Test
    void varsWithTagsAreUnparsed(@TempDir File tempDir) throws Exception {
        ChunkLandMessagePipeline pipeline = buildPipeline(tempDir, Locale.US, false);
        String evil = "<red>evil</red>";
        Component comp = pipeline.render("m0.message.smoke", Map.of("value", evil), Locale.US, null);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(comp);
        assertTrue(plain.contains(evil), "var value must be inserted as plain text, not parsed as tag");
        assertFalse(plain.contains("evil") && !plain.contains("<red>"), "should contain literal <red> from var");
    }

    @Test
    void productionParserDelegatesToAceLib(@TempDir File tempDir) throws Exception {
        String src = Files.readString(Paths.get("src/main/java/com/smile/chunkland/message/ChunkLandMessagePipeline.java"));
        assertTrue(src.contains("service.parseMiniMessage(template, vars)"), "production parser must delegate to MessageService.parseMiniMessage");
        assertTrue(src.contains("class AceLibParser"), "production adapter must exist");
        // verification via spy parser
        CountingParser spy = new CountingParser();
        ChunkLandMessagePipeline.LangProvider provider = buildLangProvider();
        FakeBedrockService bedrock = new FakeBedrockService(false);
        ChunkLandMessagePipeline pipeline = new ChunkLandMessagePipeline(new CountingSender(), spy, provider, bedrock, Locale.US);
        pipeline.render("m0.message.smoke", Map.of("value", "x"), Locale.US, null);
        assertEquals(1, spy.calls, "render must go through parser seam");
        Component comp = spy.lastResult;
        assertNotNull(comp);
    }

    @Test
    void directMiniMessageOnlyForValidation() throws Exception {
        String src = Files.readString(Paths.get("src/main/java/com/smile/chunkland/message/ChunkLandMessagePipeline.java"));
        // production renderer must not use direct MiniMessage.deserialize as final output
        // validateTemplateStrict is allowed to use STRICT_MINIMESSAGE
        assertTrue(src.contains("STRICT_MINIMESSAGE"), "strict validation may use MiniMessage");
        // ensure render methods use parser, not direct MiniMessage
        assertTrue(src.contains("parser.parse(template, vars)"), "render must use parser seam");
    }

    @Test
    void noPublicLenientConstructor() throws Exception {
        var publicCtors = ChunkLandMessagePipeline.class.getConstructors();
        for (var c : publicCtors) {
            var params = c.getParameterTypes();
            // public 4-arg lenient (PipelineSender, LangProvider, BedrockService, Locale) must not exist
            if (params.length == 4 && params[0] == ChunkLandMessagePipeline.PipelineSender.class) {
                fail("public lenient 4-arg constructor must not exist: " + c);
            }
        }
        // explicit parser injection must be at least package-private (not public)
        var declared5 = ChunkLandMessagePipeline.class.getDeclaredConstructor(
            ChunkLandMessagePipeline.PipelineSender.class,
            ChunkLandMessagePipeline.MessageParser.class,
            ChunkLandMessagePipeline.LangProvider.class,
            BedrockService.class,
            Locale.class);
        assertFalse(java.lang.reflect.Modifier.isPublic(declared5.getModifiers()), "explicit parser seam must not be public");
        // also ensure lenient factory removed
        String src = Files.readString(Paths.get("src/main/java/com/smile/chunkland/message/ChunkLandMessagePipeline.java"));
        assertFalse(src.contains("createLenientParserForVerification"), "lenient factory must be removed");
    }

    // helpers
    private ChunkLandMessagePipeline buildPipeline(File tempDir, Locale def, boolean bedrockMode) throws Exception {
        CountingSender sender = new CountingSender();
        return buildPipelineWithSender(tempDir, def, bedrockMode, sender);
    }

    private ChunkLandMessagePipeline buildPipelineWithSender(File tempDir, Locale def, boolean bedrockMode, CountingSender sender) throws Exception {
        ChunkLandMessagePipeline.LangProvider provider = buildLangProvider();
        FakeBedrockService bedrock = new FakeBedrockService(bedrockMode);
        ChunkLandMessagePipeline.MessageParser parser = (template, vars) -> {
            net.kyori.adventure.text.minimessage.tag.resolver.TagResolver resolver;
            if (vars == null || vars.isEmpty()) resolver = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.empty();
            else {
                net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] rs = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[vars.size()];
                int i = 0;
                for (var e : vars.entrySet()) rs[i++] = net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(e.getKey(), String.valueOf(e.getValue()));
                resolver = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(rs);
            }
            return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(template, resolver);
        };
        return new ChunkLandMessagePipeline(sender, parser, provider, bedrock, def);
    }

    private ChunkLandMessagePipeline.LangProvider buildLangProvider() throws Exception {
        Map<Locale, Map<String, String>> data = new java.util.HashMap<>();
        for (String localeTag : new String[]{"en_US", "zh_TW"}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File("src/main/resources/lang/" + localeTag + ".yml"));
            Map<String, String> map = new java.util.HashMap<>();
            for (String key : cfg.getKeys(true)) {
                Object val = cfg.get(key);
                if (val instanceof String s) {
                    map.put(key, s);
                }
            }
            Locale loc = localeTag.equals("en_US") ? Locale.US : Locale.forLanguageTag("zh-TW");
            data.put(loc, map);
            // also store with underscore variant
            data.put(Locale.forLanguageTag(localeTag.replace('_','-')), map);
            // store directly with Locale en_US parse
            data.put(Locale.forLanguageTag(localeTag.replace('_','-')), map);
        }
        // also support en_US Locale constructed via new Locale("en","US")
        data.put(new Locale("en","US"), data.get(Locale.US));
        return (locale, key) -> {
            Map<String,String> m = data.get(locale);
            if (m != null && m.containsKey(key)) return Optional.of(m.get(key));
            // fallback to en_US
            Map<String,String> defMap = data.get(Locale.US);
            if (defMap != null && defMap.containsKey(key)) return Optional.of(defMap.get(key));
            Map<String,String> enUs = data.get(new Locale("en","US"));
            if (enUs != null && enUs.containsKey(key)) return Optional.of(enUs.get(key));
            return Optional.empty();
        };
    }

    static Player playerWithLocale(UUID id, Locale locale) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class[]{Player.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("getUniqueId")) return id;
                if (name.equals("locale")) return locale;
                if (name.equals("isOnline")) return true;
                if (name.equals("getName")) return "TestPlayer";
                if (name.equals("hashCode")) return id.hashCode();
                if (name.equals("equals") && args != null && args.length == 1) {
                    Object other = args[0];
                    if (other == null) return false;
                    try {
                        UUID otherId = (UUID) other.getClass().getMethod("getUniqueId").invoke(other);
                        return id.equals(otherId);
                    } catch (Exception e) { return proxy == other; }
                }
                if (name.equals("toString")) return "FakePlayer:" + id;
                // default for other methods
                Class<?> ret = method.getReturnType();
                if (ret == void.class) return null;
                if (ret == boolean.class) return false;
                if (ret == int.class) return 0;
                if (ret == long.class) return 0L;
                if (ret == double.class) return 0.0;
                if (ret == float.class) return 0f;
                return null;
            });
    }

    static class FakeBedrockService implements BedrockService {
        private final boolean bedrock;
        FakeBedrockService(boolean bedrock) { this.bedrock = bedrock; }
        @Override public boolean isBedrockPlayer(UUID uuid) { return bedrock; }
        @Override public Optional<BedrockPlayerInfo> getPlayerInfo(UUID uuid) { return Optional.empty(); }
        @Override public FormService forms() { return null; }
        @Override public String getModuleStatus() { return ""; }
        @Override public void shutdown() {}
    }

    static class CountingSender implements ChunkLandMessagePipeline.PipelineSender {
        int chatCalls = 0;
        int chatFallbackCalls = 0;
        int actionBarCalls = 0;
        int actionBarFallbackCalls = 0;
        int titleCalls = 0;
        int titleFallbackCalls = 0;
        int broadcastFallbackCalls = 0;
        Locale lastFallbackLocale = null;
        @Override public void sendChat(Player p, Component m) { chatCalls++; }
        @Override public void sendChatWithFallback(Player p, Component m, Locale l) { chatFallbackCalls++; lastFallbackLocale = l; }
        @Override public void sendActionBar(Player p, Component m) { actionBarCalls++; }
        @Override public void sendActionBarWithFallback(Player p, Component m, Locale l) { actionBarFallbackCalls++; lastFallbackLocale = l; }
        @Override public void sendTitle(Player p, Component t, Component s) { titleCalls++; }
        @Override public void sendTitleWithFallback(Player p, Component t, Component s, Locale l) { titleFallbackCalls++; lastFallbackLocale = l; }
        @Override public void broadcastWithFallback(Component m, Locale l) { broadcastFallbackCalls++; lastFallbackLocale = l; }
        void reset() { chatCalls=0; chatFallbackCalls=0; actionBarCalls=0; actionBarFallbackCalls=0; titleCalls=0; titleFallbackCalls=0; broadcastFallbackCalls=0; lastFallbackLocale=null; }
    }

    static class CountingParser implements ChunkLandMessagePipeline.MessageParser {
        int calls = 0;
        Component lastResult = null;
        @Override public Component parse(String template, Map<String, Object> vars) {
            calls++;
            net.kyori.adventure.text.minimessage.tag.resolver.TagResolver resolver;
            if (vars == null || vars.isEmpty()) resolver = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.empty();
            else {
                net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] rs = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[vars.size()];
                int i=0;
                for (var e : vars.entrySet()) rs[i++] = net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(e.getKey(), String.valueOf(e.getValue()));
                resolver = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(rs);
            }
            lastResult = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(template, resolver);
            return lastResult;
        }
    }
}

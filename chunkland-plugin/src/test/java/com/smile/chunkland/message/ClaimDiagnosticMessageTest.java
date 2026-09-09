package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Bilingual resources for claim diagnostic keys.
 *
 * <p>The claim handler and saga report failures as diagnostic keys carried in
 * the {@code reason} var ({@code claim.recovery_pending},
 * {@code claim.recovery_failed}, {@code pricing.unavailable},
 * {@code economy.unavailable}). Each of those keys must resolve to a natural
 * MiniMessage sentence in both locales so a player never sees the raw key.
 * Templates follow the same strict MiniMessage and placeholder allow-list
 * contract as the rest of the lang resources.
 */
class ClaimDiagnosticMessageTest {

    private static final String[] DIAGNOSTIC_KEYS = {
        "claim.recovery_pending",
        "claim.recovery_failed",
        "pricing.unavailable",
        "economy.unavailable",
    };

    private static final Map<String, String> OUTER_KEY = Map.of(
        "claim.recovery_pending", "command.land.claim.failed",
        "claim.recovery_failed", "command.land.claim.failed",
        "pricing.unavailable", "command.land.claim.rejected",
        "economy.unavailable", "command.land.claim.rejected");

    @Test
    void diagnosticKeysExistInBothLocales() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        for (String key : DIAGNOSTIC_KEYS) {
            assertNotNull(en.getString(key), "en_US missing " + key);
            assertNotNull(zh.getString(key), "zh_TW missing " + key);
            assertFalse(en.getString(key).isBlank(), "en_US blank " + key);
            assertFalse(zh.getString(key).isBlank(), "zh_TW blank " + key);
        }
    }

    @Test
    void diagnosticTemplatesPassStrictValidationAndAllowList() throws Exception {
        var allowList = allowedPlaceholders();
        for (String tag : new String[] {"en_US", "zh_TW"}) {
            YamlConfiguration cfg = load(tag);
            for (String key : DIAGNOSTIC_KEYS) {
                String template = cfg.getString(key);
                assertNotNull(template, tag + " missing " + key);
                validateStrict(key, template);
                // Same convention as LandCommandTest: strict validation above
                // already rejects unknown tags; here only allow-list placeholders
                // are collected (standard color tags are not placeholders).
                java.util.Set<String> found = new java.util.HashSet<>();
                java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("<([a-z_]+)>").matcher(template);
                while (m.find()) {
                    if (allowList.contains(m.group(1))) {
                        found.add(m.group(1));
                    }
                }
                for (String ph : found) {
                    assertTrue(allowList.contains(ph),
                        tag + " " + key + " uses placeholder <" + ph + "> not in allow-list");
                }
            }
        }
    }

    @Test
    void diagnosticMessagesRenderToNaturalLanguageNotRawKey() throws Exception {
        ChunkLandMessagePipeline enPipe = buildPipeline(Locale.US);
        ChunkLandMessagePipeline zhPipe = buildPipeline(Locale.forLanguageTag("zh-TW"));
        for (String key : DIAGNOSTIC_KEYS) {
            String enPlain = plain(enPipe.renderForBroadcast(key, Map.of(), Locale.US));
            assertFalse(enPlain.isBlank(), "en render blank for " + key);
            assertFalse(enPlain.contains(key), "en render leaks raw key for " + key);

            Locale zh = Locale.forLanguageTag("zh-TW");
            String zhPlain = plain(zhPipe.renderForBroadcast(key, Map.of(), zh));
            assertFalse(zhPlain.isBlank(), "zh render blank for " + key);
            assertFalse(zhPlain.contains(key), "zh render leaks raw key for " + key);
            assertNotEquals(enPlain, zhPlain, "zh render must differ from en for " + key);
        }
    }

    @Test
    void localizedReasonComposesWithoutRawKey() throws Exception {
        ChunkLandMessagePipeline enPipe = buildPipeline(Locale.US);
        Locale zh = Locale.forLanguageTag("zh-TW");
        ChunkLandMessagePipeline zhPipe = buildPipeline(zh);
        for (String key : DIAGNOSTIC_KEYS) {
            String outer = OUTER_KEY.get(key);
            String enReason = plain(enPipe.renderForBroadcast(key, Map.of(), Locale.US));
            String enComposed = plain(enPipe.renderForBroadcast(outer, Map.of("reason", enReason), Locale.US));
            assertTrue(enComposed.contains(enReason), "en composed must carry localized reason for " + key);
            assertFalse(enComposed.contains(key), "en composed leaks raw key for " + key);
            assertFalse(enComposed.contains(outer), "en composed leaks outer key for " + key);

            String zhReason = plain(zhPipe.renderForBroadcast(key, Map.of(), zh));
            String zhComposed = plain(zhPipe.renderForBroadcast(outer, Map.of("reason", zhReason), zh));
            assertTrue(zhComposed.contains(zhReason), "zh composed must carry localized reason for " + key);
            assertFalse(zhComposed.contains(key), "zh composed leaks raw key for " + key);
            assertFalse(zhComposed.contains(outer), "zh composed leaks outer key for " + key);
        }
    }

    private static YamlConfiguration load(String tag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new File("src/main/resources/lang/" + tag + ".yml"));
        return cfg;
    }

    private static void validateStrict(String key, String template) throws Exception {
        var m = ChunkLandMessagePipeline.class.getDeclaredMethod(
            "validateTemplateStrict", String.class, String.class);
        m.setAccessible(true);
        try {
            m.invoke(null, key, template);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<String> allowedPlaceholders() throws Exception {
        var f = ChunkLandMessagePipeline.class.getDeclaredField("ALLOWED_PLACEHOLDERS");
        f.setAccessible(true);
        return (java.util.Set<String>) f.get(null);
    }

    private static ChunkLandMessagePipeline buildPipeline(Locale def) throws Exception {
        Map<Locale, Map<String, String>> data = new HashMap<>();
        for (String tag : new String[] {"en_US", "zh_TW"}) {
            YamlConfiguration cfg = load(tag);
            Map<String, String> m = new HashMap<>();
            for (String k : cfg.getKeys(true)) {
                Object v = cfg.get(k);
                if (v instanceof String s) {
                    m.put(k, s);
                }
            }
            Locale loc = tag.equals("en_US") ? Locale.US : Locale.forLanguageTag("zh-TW");
            data.put(loc, m);
        }
        data.put(new Locale("en", "US"), data.get(Locale.US));
        ChunkLandMessagePipeline.LangProvider provider = (locale, key) -> {
            Map<String, String> mm = data.get(locale);
            if (mm != null && mm.containsKey(key)) {
                return Optional.of(mm.get(key));
            }
            Map<String, String> fallback = data.get(Locale.US);
            if (fallback != null && fallback.containsKey(key)) {
                return Optional.of(fallback.get(key));
            }
            return Optional.empty();
        };
        ChunkLandMessagePipeline.MessageParser parser = (template, vars) -> {
            net.kyori.adventure.text.minimessage.tag.resolver.TagResolver resolver;
            if (vars == null || vars.isEmpty()) {
                resolver = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.empty();
            } else {
                var resolvers = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[vars.size()];
                int i = 0;
                for (var e : vars.entrySet()) {
                    resolvers[i++] = net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                        e.getKey(), String.valueOf(e.getValue()));
                }
                resolver = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(resolvers);
            }
            return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(template, resolver);
        };
        var ctor = ChunkLandMessagePipeline.class.getDeclaredConstructor(
            ChunkLandMessagePipeline.PipelineSender.class,
            ChunkLandMessagePipeline.MessageParser.class,
            ChunkLandMessagePipeline.LangProvider.class,
            com.smile.acelib.bedrock.BedrockService.class,
            Locale.class);
        ctor.setAccessible(true);
        return (ChunkLandMessagePipeline) ctor.newInstance(
            new NoopSender(), parser, provider, null, def);
    }

    private static String plain(net.kyori.adventure.text.Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }

    static final class NoopSender implements ChunkLandMessagePipeline.PipelineSender {
        @Override
        public void sendChat(org.bukkit.entity.Player p, net.kyori.adventure.text.Component m) {
        }

        @Override
        public void sendChatWithFallback(org.bukkit.entity.Player p, net.kyori.adventure.text.Component m, Locale l) {
        }

        @Override
        public void sendActionBar(org.bukkit.entity.Player p, net.kyori.adventure.text.Component m) {
        }

        @Override
        public void sendActionBarWithFallback(org.bukkit.entity.Player p, net.kyori.adventure.text.Component m, Locale l) {
        }

        @Override
        public void sendTitle(org.bukkit.entity.Player p, net.kyori.adventure.text.Component t,
            net.kyori.adventure.text.Component s) {
        }

        @Override
        public void sendTitleWithFallback(org.bukkit.entity.Player p, net.kyori.adventure.text.Component t,
            net.kyori.adventure.text.Component s, Locale l) {
        }

        @Override
        public void broadcastWithFallback(net.kyori.adventure.text.Component m, Locale l) {
        }
    }
}

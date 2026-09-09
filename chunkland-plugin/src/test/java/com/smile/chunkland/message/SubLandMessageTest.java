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
 * Bilingual resources for the production {@code /land subland} replies.
 *
 * <p>The SubLand handler replies with ten keys under
 * {@code command.land.subland.*}. Every one of them must resolve in both
 * locales: an unknown key makes {@code resolveTemplate} throw, and the reply
 * sink swallows that failure, so the player would see nothing at all.
 * Templates follow the same strict MiniMessage and placeholder allow-list
 * contract as the rest of the lang resources.
 */
class SubLandMessageTest {

    private static final String[] SUBLAND_KEYS = {
        "command.land.subland.console",
        "command.land.subland.usage",
        "command.land.subland.no_selection",
        "command.land.subland.created",
        "command.land.subland.updated",
        "command.land.subland.deleted",
        "command.land.subland.stale",
        "command.land.subland.confirm_depth",
        "command.land.subland.failed",
        "command.land.subland.degraded",
    };

    @Test
    void sublandKeysExistInBothLocales() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        for (String key : SUBLAND_KEYS) {
            assertNotNull(en.getString(key), "en_US missing " + key);
            assertNotNull(zh.getString(key), "zh_TW missing " + key);
            assertFalse(en.getString(key).isBlank(), "en_US blank " + key);
            assertFalse(zh.getString(key).isBlank(), "zh_TW blank " + key);
        }
    }

    @Test
    void sublandTemplatesPassStrictValidationAndPlaceholderParity() throws Exception {
        var allowList = allowedPlaceholders();
        for (String tag : new String[] {"en_US", "zh_TW"}) {
            YamlConfiguration cfg = load(tag);
            for (String key : SUBLAND_KEYS) {
                String template = cfg.getString(key);
                assertNotNull(template, tag + " missing " + key);
                validateStrict(key, template);
            }
        }
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        for (String key : SUBLAND_KEYS) {
            assertEquals(
                    allowedPlaceholdersIn(en.getString(key), allowList),
                    allowedPlaceholdersIn(zh.getString(key), allowList),
                    "placeholder parity broken for " + key);
        }
    }

    @Test
    void sublandRepliesRenderWithoutRawKey() throws Exception {
        ChunkLandMessagePipeline enPipe = buildPipeline(Locale.US);
        Locale zhLocale = Locale.forLanguageTag("zh-TW");
        ChunkLandMessagePipeline zhPipe = buildPipeline(zhLocale);
        Map<String, Map<String, Object>> varsByKey = Map.of(
                "command.land.subland.console", Map.of(),
                "command.land.subland.usage", Map.of(),
                "command.land.subland.no_selection", Map.of(),
                "command.land.subland.created", Map.of("name", "Meadow"),
                "command.land.subland.updated", Map.of("name", "Meadow"),
                "command.land.subland.deleted", Map.of(),
                "command.land.subland.stale", Map.of(),
                "command.land.subland.confirm_depth",
                    Map.of("reason", "SubLand minY (40) is below the parent effective floor (60)"),
                "command.land.subland.failed", Map.of("reason", "overlap"),
                "command.land.subland.degraded", Map.of("reason", "runtime rebuild pending"));
        for (String key : SUBLAND_KEYS) {
            Map<String, Object> vars = varsByKey.get(key);
            String enPlain = plain(enPipe.renderForBroadcast(key, vars, Locale.US));
            assertFalse(enPlain.isBlank(), "en render blank for " + key);
            assertFalse(enPlain.contains(key), "en render leaks raw key for " + key);

            String zhPlain = plain(zhPipe.renderForBroadcast(key, vars, zhLocale));
            assertFalse(zhPlain.isBlank(), "zh render blank for " + key);
            assertFalse(zhPlain.contains(key), "zh render leaks raw key for " + key);
            assertNotEquals(enPlain, zhPlain, "zh render must differ from en for " + key);
        }
    }

    @Test
    void staleReplyRendersWithAndWithoutReason() throws Exception {
        ChunkLandMessagePipeline enPipe = buildPipeline(Locale.US);
        String bare = plain(enPipe.renderForBroadcast(
                "command.land.subland.stale", Map.of(), Locale.US));
        assertFalse(bare.isBlank());
        assertFalse(bare.contains("command.land.subland.stale"));
        String withReason = plain(enPipe.renderForBroadcast(
                "command.land.subland.stale", Map.of("reason", "confirm-rejected"), Locale.US));
        assertFalse(withReason.isBlank());
        assertFalse(withReason.contains("command.land.subland.stale"));
    }

    private static java.util.Set<String> allowedPlaceholdersIn(String template, java.util.Set<String> allowList) {
        java.util.Set<String> found = new java.util.HashSet<>();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("<([a-z_]+)>").matcher(template);
        while (m.find()) {
            if (allowList.contains(m.group(1))) {
                found.add(m.group(1));
            }
        }
        return found;
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

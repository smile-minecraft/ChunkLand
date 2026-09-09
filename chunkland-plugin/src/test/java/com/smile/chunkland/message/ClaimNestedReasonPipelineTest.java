package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.command.PipelineReplySink;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Production-path regression: claim diagnostic keys carried in the
 * {@code reason} var must render to natural bilingual copy through the
 * real {@link PipelineReplySink} data flow, never the raw key.
 */
class ClaimNestedReasonPipelineTest {

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
    void productionSinkRendersDiagnosticKeyWithoutRawKey() throws Exception {
        for (String diagnostic : DIAGNOSTIC_KEYS) {
            String outer = OUTER_KEY.get(diagnostic);
            // English path
            CapturingSender enSender = new CapturingSender();
            ChunkLandMessagePipeline enPipe = buildPipeline(Locale.US, enSender);
            Player enPlayer = playerWithLocale(UUID.randomUUID(), Locale.US);
            new PipelineReplySink(enPlayer, enPipe).reply(outer, Map.of("reason", diagnostic));
            assertNotNull(enSender.lastChat, "player path must send for " + diagnostic);
            String enPlain = plain(enSender.lastChat);
            assertFalse(enPlain.contains(diagnostic), "en output leaks raw key for " + diagnostic + ": " + enPlain);
            assertFalse(enPlain.contains(outer), "en output leaks outer key for " + diagnostic);
            String enReason = plain(buildPipeline(Locale.US, new CapturingSender())
                .renderForBroadcast(diagnostic, Map.of(), Locale.US));
            assertTrue(enPlain.contains(enReason),
                "en output must carry localized reason for " + diagnostic);

            // Traditional Chinese path
            Locale zh = Locale.forLanguageTag("zh-TW");
            CapturingSender zhSender = new CapturingSender();
            ChunkLandMessagePipeline zhPipe = buildPipeline(zh, zhSender);
            Player zhPlayer = playerWithLocale(UUID.randomUUID(), zh);
            new PipelineReplySink(zhPlayer, zhPipe).reply(outer, Map.of("reason", diagnostic), zh);
            assertNotNull(zhSender.lastChat, "player path must send for " + diagnostic + " (zh)");
            String zhPlain = plain(zhSender.lastChat);
            assertFalse(zhPlain.contains(diagnostic), "zh output leaks raw key for " + diagnostic + ": " + zhPlain);
            String zhReason = plain(buildPipeline(zh, new CapturingSender())
                .renderForBroadcast(diagnostic, Map.of(), zh));
            assertTrue(zhPlain.contains(zhReason),
                "zh output must carry localized reason for " + diagnostic);
            assertNotEquals(enPlain, zhPlain, "zh render must differ from en for " + diagnostic);
        }
    }

    @Test
    void unknownReasonKeyStaysLiteralAndNeverParsesAsTemplate() throws Exception {
        CapturingSender sender = new CapturingSender();
        ChunkLandMessagePipeline pipeline = buildPipeline(Locale.US, sender);
        Player player = playerWithLocale(UUID.randomUUID(), Locale.US);

        String evil = "<red>evil</red>";
        new PipelineReplySink(player, pipeline)
            .reply("command.land.claim.failed", Map.of("reason", evil));
        assertNotNull(sender.lastChat);
        String evilPlain = plain(sender.lastChat);
        assertTrue(evilPlain.contains(evil),
            "arbitrary reason must stay literal, not parsed as MiniMessage: " + evilPlain);

        CapturingSender sender2 = new CapturingSender();
        ChunkLandMessagePipeline pipeline2 = buildPipeline(Locale.US, sender2);
        String unknown = "claim.does_not_exist_xyz";
        new PipelineReplySink(player, pipeline2)
            .reply("command.land.claim.failed", Map.of("reason", unknown));
        assertNotNull(sender2.lastChat);
        String unknownPlain = plain(sender2.lastChat);
        // Fail-safe: unknown key is kept literal and never treated as a template.
        assertTrue(unknownPlain.contains(unknown),
            "unknown key must fall back to literal text: " + unknownPlain);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static Player playerWithLocale(UUID id, Locale locale) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("getUniqueId")) {
                    return id;
                }
                if (name.equals("locale")) {
                    return locale;
                }
                if (name.equals("isOnline")) {
                    return true;
                }
                if (name.equals("getName")) {
                    return "TestPlayer";
                }
                if (name.equals("hashCode")) {
                    return id.hashCode();
                }
                if (name.equals("equals") && args != null && args.length == 1) {
                    return proxy == args[0];
                }
                if (name.equals("toString")) {
                    return "FakePlayer:" + id;
                }
                Class<?> ret = method.getReturnType();
                if (ret == void.class) {
                    return null;
                }
                if (ret == boolean.class) {
                    return false;
                }
                if (ret == int.class) {
                    return 0;
                }
                if (ret == long.class) {
                    return 0L;
                }
                if (ret == double.class) {
                    return 0.0;
                }
                if (ret == float.class) {
                    return 0f;
                }
                return null;
            });
    }

    @SuppressWarnings("unused")
    private static CommandSender consoleSender(CopyOnWriteArrayList<Component> compOut) {
        return (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, args) -> {
                String n = method.getName();
                if (n.equals("sendMessage") && args != null) {
                    for (Object a : args) {
                        if (a instanceof Component c) {
                            compOut.add(c);
                        }
                    }
                    return null;
                }
                if (n.equals("hasPermission")) {
                    return true;
                }
                if (n.equals("getName")) {
                    return "Console";
                }
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class) {
                    return false;
                }
                if (rt == int.class) {
                    return 0;
                }
                return null;
            });
    }

    private static ChunkLandMessagePipeline buildPipeline(Locale def, CapturingSender sender) throws Exception {
        Map<Locale, Map<String, String>> data = new HashMap<>();
        for (String tag : new String[] {"en_US", "zh_TW"}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File("src/main/resources/lang/" + tag + ".yml"));
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
        return (ChunkLandMessagePipeline) ctor.newInstance(sender, parser, provider, null, def);
    }

    static final class CapturingSender implements ChunkLandMessagePipeline.PipelineSender {
        volatile Component lastChat;

        @Override
        public void sendChat(Player p, Component m) {
            lastChat = m;
        }

        @Override
        public void sendChatWithFallback(Player p, Component m, Locale l) {
            lastChat = m;
        }

        @Override
        public void sendActionBar(Player p, Component m) {
        }

        @Override
        public void sendActionBarWithFallback(Player p, Component m, Locale l) {
        }

        @Override
        public void sendTitle(Player p, Component t, Component s) {
        }

        @Override
        public void sendTitleWithFallback(Player p, Component t, Component s, Locale l) {
        }

        @Override
        public void broadcastWithFallback(Component m, Locale l) {
        }
    }
}

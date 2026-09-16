package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PlayerPreferredLocalePipelineTest {

    @Test
    void storedOverrideBeatsPlayerLocale() throws Exception {
        ChunkLandMessagePipeline pipeline = pipeline(Locale.US);
        UUID playerId = UUID.randomUUID();
        Player player = playerWithLocale(playerId, Locale.US);
        pipeline.setPreferredLocaleLookup(id -> Locale.forLanguageTag("zh-TW"));
        Locale effective = pipeline.resolveLocale(player, null);
        assertEquals(Locale.forLanguageTag("zh-TW"), effective);
    }

    @Test
    void explicitOverrideBeatsStored() throws Exception {
        ChunkLandMessagePipeline pipeline = pipeline(Locale.US);
        Player player = playerWithLocale(UUID.randomUUID(), Locale.US);
        pipeline.setPreferredLocaleLookup(id -> Locale.forLanguageTag("zh-TW"));
        Locale effective = pipeline.resolveLocale(player, Locale.FRANCE);
        assertEquals(Locale.FRANCE, effective);
    }

    @Test
    void noStoredKeepsPlayerLocaleChain() throws Exception {
        ChunkLandMessagePipeline pipeline = pipeline(Locale.US);
        Player player = playerWithLocale(UUID.randomUUID(), Locale.JAPAN);
        pipeline.setPreferredLocaleLookup(id -> null);
        assertEquals(Locale.JAPAN, pipeline.resolveLocale(player, null));
        pipeline.setPreferredLocaleLookup(null);
        assertEquals(Locale.JAPAN, pipeline.resolveLocale(player, null));
    }

    @Test
    void throwingLookupFailsClosedToPlayerLocale() throws Exception {
        ChunkLandMessagePipeline pipeline = pipeline(Locale.US);
        Player player = playerWithLocale(UUID.randomUUID(), Locale.JAPAN);
        pipeline.setPreferredLocaleLookup(id -> {
            throw new RuntimeException("cache broken");
        });
        assertEquals(Locale.JAPAN, pipeline.resolveLocale(player, null));
    }

    @Test
    void renderUsesStoredOverrideLocale() throws Exception {
        ChunkLandMessagePipeline pipeline = pipeline(Locale.US);
        UUID playerId = UUID.randomUUID();
        Player player = playerWithLocale(playerId, Locale.US);
        pipeline.setPreferredLocaleLookup(id -> Locale.forLanguageTag("zh-TW"));
        Component rendered = pipeline.render("m0.message.smoke", Map.of("value", "v"), null, player);
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);
        assertTrue(plain.startsWith("zh"), "render must use the stored override, got: " + plain);
    }

    @Test
    void renderWithoutStoredUsesPlayerLocale() throws Exception {
        ChunkLandMessagePipeline pipeline = pipeline(Locale.US);
        Player player = playerWithLocale(UUID.randomUUID(), Locale.US);
        pipeline.setPreferredLocaleLookup(null);
        Component rendered = pipeline.render("m0.message.smoke", Map.of("value", "v"), null, player);
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);
        assertTrue(plain.startsWith("en"), "render must keep the player locale, got: " + plain);
    }

    // Language-tagged provider: template text starts with the lookup
    // locale's language so the effective locale is observable without a
    // second locale map.
    private static ChunkLandMessagePipeline pipeline(Locale def) throws Exception {
        ChunkLandMessagePipeline.LangProvider provider =
                (locale, key) -> Optional.of(locale.getLanguage() + " says <value>");
        ChunkLandMessagePipeline.MessageParser parser = (template, vars) -> {
            var resolvers = vars == null || vars.isEmpty()
                    ? net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.empty()
                    : net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                            vars.entrySet().stream()
                                    .map(e -> net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
                                            .unparsed(e.getKey(), String.valueOf(e.getValue())))
                                    .toArray(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]::new));
            return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(template, resolvers);
        };
        var sender = new ChunkLandMessagePipelineTestHelperSender();
        var ctor = ChunkLandMessagePipeline.class.getDeclaredConstructor(
                ChunkLandMessagePipeline.PipelineSender.class,
                ChunkLandMessagePipeline.MessageParser.class,
                ChunkLandMessagePipeline.LangProvider.class,
                com.smile.acelib.bedrock.BedrockService.class,
                Locale.class);
        ctor.setAccessible(true);
        return (ChunkLandMessagePipeline) ctor.newInstance(sender, parser, provider, null, def);
    }

    static Player playerWithLocale(UUID id, Locale locale) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[] {Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "locale": return locale;
                        case "isOnline": return true;
                        case "getName": return "TestPlayer";
                        default: break;
                    }
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

    static final class ChunkLandMessagePipelineTestHelperSender
            implements ChunkLandMessagePipeline.PipelineSender {
        @Override public void sendChat(Player p, Component m) {}
        @Override public void sendChatWithFallback(Player p, Component m, Locale l) {}
        @Override public void sendActionBar(Player p, Component m) {}
        @Override public void sendActionBarWithFallback(Player p, Component m, Locale l) {}
        @Override public void sendTitle(Player p, Component t, Component s) {}
        @Override public void sendTitleWithFallback(Player p, Component t, Component s, Locale l) {}
        @Override public void broadcastWithFallback(Component m, Locale l) {}
    }
}

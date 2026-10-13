package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.rule.LandRuleView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The rule line shows translated words, never raw source tokens: the
 * source layer travels as the {@code layer} label var, so four display
 * names decide the rendering. Every rule name travels as {@code action}
 * and resolves the same way.
 */
class RuleLineLabelTest {

    private static final List<String> RAW_SOURCES = List.of("LAND", "WORLD", "GLOBAL", "BUILT_IN");

    private static final Map<String, String> EXPECTED_EN = Map.of(
            "LAND", "Land",
            "WORLD", "World",
            "GLOBAL", "Global",
            "BUILT_IN", "Built-in");

    private static final Map<String, String> EXPECTED_ZH = Map.of(
            "LAND", "每領地",
            "WORLD", "世界",
            "GLOBAL", "全域",
            "BUILT_IN", "內建");

    private static YamlConfiguration load(String localeTag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new File("src/main/resources/lang/" + localeTag + ".yml"));
        return cfg;
    }

    private static ChunkLandMessagePipeline pipeline(String localeTag) throws Exception {
        YamlConfiguration cfg = load(localeTag);
        ChunkLandMessagePipeline.LangProvider lang =
                (locale, key) -> Optional.ofNullable(cfg.getString(key));
        ChunkLandMessagePipeline.MessageParser parser = (template, vars) -> {
            List<TagResolver> resolvers = new ArrayList<>();
            if (vars != null) {
                for (Map.Entry<String, Object> entry : vars.entrySet()) {
                    resolvers.add(Placeholder.unparsed(entry.getKey(),
                            String.valueOf(entry.getValue())));
                }
            }
            return MiniMessage.miniMessage().deserialize(template,
                    TagResolver.resolver(resolvers));
        };
        ChunkLandMessagePipeline.PipelineSender sender =
                new ChunkLandMessagePipeline.PipelineSender() {
                    @Override public void sendChat(Player p, Component m) {}
                    @Override public void sendChatWithFallback(Player p, Component m, Locale l) {}
                    @Override public void sendActionBar(Player p, Component m) {}
                    @Override public void sendActionBarWithFallback(Player p, Component m, Locale l) {}
                    @Override public void sendTitle(Player p, Component t, Component s) {}
                    @Override public void sendTitleWithFallback(Player p, Component t, Component s, Locale l) {}
                    @Override public void broadcastWithFallback(Component m, Locale l) {}
                };
        return new ChunkLandMessagePipeline(sender, parser, lang, null, Locale.US);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static String renderLine(String localeTag, LandRuleType rule,
            String explicit, String effective, LandRuleView.Source source) throws Exception {
        return plain(pipeline(localeTag).renderForBroadcast("command.land.rule.line",
                Map.of("action", rule.name(), "state", explicit, "outcome", effective,
                        "layer", source.name()),
                "zh_TW".equals(localeTag) ? Locale.TRADITIONAL_CHINESE : Locale.US));
    }

    @ParameterizedTest
    @EnumSource(LandRuleView.Source.class)
    void ruleLineShowsTranslatedSourceInBothLocales(LandRuleView.Source source) throws Exception {
        if (source == LandRuleView.Source.UNAVAILABLE) {
            return;
        }
        String en = renderLine("en_US", LandRuleType.PVP, "INHERIT", "DENY", source);
        assertNotNull(en);
        for (String raw : RAW_SOURCES) {
            assertFalse(en.contains(raw),
                    "en rule line must not leak the raw source token " + raw + ", got: " + en);
        }
        assertTrue(en.contains(EXPECTED_EN.get(source.name())),
                "en rule line must name the source, got: " + en);

        String zh = renderLine("zh_TW", LandRuleType.PVP, "INHERIT", "DENY", source);
        assertNotNull(zh);
        for (String raw : RAW_SOURCES) {
            assertFalse(zh.contains(raw),
                    "zh rule line must not leak the raw source token " + raw + ", got: " + zh);
        }
        assertTrue(zh.contains(EXPECTED_ZH.get(source.name())),
                "zh rule line must name the source, got: " + zh);
    }

    @Test
    void ruleNamesRenderTranslatedInsteadOfRaw() throws Exception {
        String en = renderLine("en_US", LandRuleType.PISTON, "ALLOW", "ALLOW",
                LandRuleView.Source.LAND);
        assertFalse(en.contains("PISTON"), "en rule name must be translated, got: " + en);
        String zh = renderLine("zh_TW", LandRuleType.PVP, "INHERIT", "DENY",
                LandRuleView.Source.BUILT_IN);
        assertFalse(zh.contains("PVP"), "zh rule name must be translated, got: " + zh);
        assertTrue(zh.contains("玩家對戰"), "zh rule name must resolve, got: " + zh);
    }
}

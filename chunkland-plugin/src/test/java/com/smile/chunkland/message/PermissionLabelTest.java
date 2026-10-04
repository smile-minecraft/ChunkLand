package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.PermissionExplainLayer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Players never see a raw constant or a raw failure code: every permission
 * constant has a display name in both locales, the pipeline swaps it in, and
 * every failure code a handler can reply with resolves to a sentence.
 */
class PermissionLabelTest {

    private static final String[] LOCALES = {"en_US", "zh_TW"};

    private static final Pattern REASON_LITERAL =
            Pattern.compile("\"reason\",\\s*\"([a-z_]+\\.[a-z_.]+)\"");

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

    private static void assertLabel(YamlConfiguration cfg, String localeTag, String key) {
        String value = cfg.getString(key);
        assertTrue(value != null && !value.isBlank(), localeTag + " missing display name " + key);
        assertFalse(value.contains("<"), localeTag + " " + key + " must be plain text: " + value);
    }

    @Test
    void everyPermissionConstantHasADisplayNameInBothLocales() throws Exception {
        for (String localeTag : LOCALES) {
            YamlConfiguration cfg = load(localeTag);
            for (ProtectionActionType action : ProtectionActionType.values()) {
                assertLabel(cfg, localeTag, ChunkLandMessagePipeline.ACTION_LABEL_PREFIX
                        + action.name().toLowerCase(Locale.ROOT));
            }
            for (PermissionState state : PermissionState.values()) {
                assertLabel(cfg, localeTag, ChunkLandMessagePipeline.STATE_LABEL_PREFIX
                        + state.name().toLowerCase(Locale.ROOT));
            }
            for (PermissionExplainLayer layer : PermissionExplainLayer.values()) {
                assertLabel(cfg, localeTag, ChunkLandMessagePipeline.LAYER_LABEL_PREFIX
                        + layer.name().toLowerCase(Locale.ROOT));
            }
            for (DecisionSource source : DecisionSource.values()) {
                assertLabel(cfg, localeTag, ChunkLandMessagePipeline.SOURCE_LABEL_PREFIX
                        + source.name().toLowerCase(Locale.ROOT));
            }
            assertLabel(cfg, localeTag, ChunkLandMessagePipeline.FLAG_LABEL_PREFIX + "true");
            assertLabel(cfg, localeTag, ChunkLandMessagePipeline.FLAG_LABEL_PREFIX + "false");
        }
    }

    @Test
    void pipelineShowsDisplayNamesInsteadOfConstants() throws Exception {
        String en = plain(pipeline("en_US").renderForBroadcast("command.land.default.success",
                Map.of("action", "BLOCK_BREAK", "state", "DENY"), null));
        assertTrue(en.contains("Breaking blocks") && en.contains("Denied"), en);
        assertFalse(en.contains("BLOCK_BREAK") || en.contains("DENY"), en);

        String zh = plain(pipeline("zh_TW").renderForBroadcast("command.land.default.success",
                Map.of("action", ProtectionActionType.CONTAINER_OPEN,
                        "state", PermissionState.ALLOW), null));
        assertTrue(zh.contains("開啟容器") && zh.contains("允許"), zh);
        assertFalse(zh.contains("CONTAINER_OPEN") || zh.contains("ALLOW"), zh);
    }

    @Test
    void valuesWithoutADisplayNameRenderExactlyAsSupplied() throws Exception {
        ChunkLandMessagePipeline pipeline = pipeline("en_US");
        String preview = plain(pipeline.renderForBroadcast("command.land.subland.preview",
                Map.of("action", "create", "generation", 1, "revision", 2,
                        "land_name", "Home", "value", "/land subland create"), null));
        assertTrue(preview.contains("create"),
                "a free-form value under a label var must stay untouched: " + preview);
        assertEquals(Optional.empty(), pipeline.label("permission.action.not_a_thing", null));
        assertEquals(Optional.of("Breaking blocks"),
                pipeline.label("permission.action.block_break", Locale.US));
    }

    @Test
    void booleanFlagsReadAsWords() throws Exception {
        String zh = plain(pipeline("zh_TW").renderForBroadcast("command.land.explain.result",
                Map.of("action", "ENTRY", "outcome", "DENY", "source", "SUBJECT_PERMISSION",
                        "layer", "LAND_DEFAULT", "reason", "Land default: DENY -> DENY",
                        "coveringSubLandId", "none", "isOwner", false,
                        "adminBypass", false, "steward", true), null));
        assertTrue(zh.contains("進入領地") && zh.contains("拒絕") && zh.contains("領地預設")
                && zh.contains("玩家權限"), zh);
        assertTrue(zh.contains("是") && zh.contains("否"), zh);
        assertFalse(zh.contains("true") || zh.contains("false"), zh);
        assertFalse(zh.contains("LAND_DEFAULT") || zh.contains("SUBJECT_PERMISSION"), zh);
    }

    @Test
    void everyFailureCodeAHandlerRepliesWithResolvesToASentence() throws Exception {
        Set<String> codes = new TreeSet<>();
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = REASON_LITERAL.matcher(readSource(file));
                while (matcher.find()) {
                    codes.add(matcher.group(1));
                }
            }
        }
        assertTrue(codes.contains("default.unknown_action") && codes.contains("trust.unavailable"),
                "scanner must see the handler failure codes, found " + codes);
        for (String localeTag : LOCALES) {
            YamlConfiguration cfg = load(localeTag);
            Set<String> missing = new TreeSet<>();
            for (String code : codes) {
                String sentence = cfg.getString(code);
                if (sentence == null || sentence.isBlank()) {
                    missing.add(code);
                }
            }
            assertTrue(missing.isEmpty(),
                    localeTag + " shows these failure codes to players as raw text: " + missing);
        }
    }

    @Test
    void failureCodeIsReplacedByItsSentenceInTheReply() throws Exception {
        String zh = plain(pipeline("zh_TW").renderForBroadcast("command.land.default.failed",
                Map.of("reason", "default.unknown_action"), null));
        assertFalse(zh.contains("default.unknown_action"), zh);
        assertTrue(zh.contains("找不到這個權限項目"), zh);
    }

    private static String readSource(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new AssertionError("cannot read " + file, unreadable);
        }
    }
}

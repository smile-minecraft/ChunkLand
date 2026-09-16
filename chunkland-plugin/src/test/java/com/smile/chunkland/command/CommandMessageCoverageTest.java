package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Every complete {@code reply("...")} literal in production must resolve in
 * both locales, otherwise the fail-closed reply sink drops the message and
 * the player sees nothing. The expand command went silent exactly this way,
 * so this test pins the whole handler key surface instead of one section.
 *
 * <p>Dynamic keys built by string concatenation (for example
 * {@code "command.land." + base + ".console"} in the trust/ban entry
 * handlers, where {@code base} resolves at runtime to trust, untrust, ban
 * or unban) never appear as a complete literal. The scanner below only
 * collects {@code reply("...")} calls whose key ends with a closing quote
 * on the same call, and it skips the leftover {@code "command.land."}
 * prefix fragment because that prefix alone is never a real message key.
 */
class CommandMessageCoverageTest {

    private static final Set<String> EXPAND_KEYS = Set.of(
            "command.land.expand.console",
            "command.land.expand.no_selection",
            "command.land.expand.no_target",
            "command.land.expand.success",
            "command.land.expand.rejected",
            "command.land.expand.failed");

    /** Vars each expand reply carries in ExpandCommandHandler.replyOutcome/handle. */
    private static final Map<String, Map<String, String>> EXPAND_VARS = Map.of(
            "command.land.expand.console", Map.of(),
            "command.land.expand.no_selection", Map.of(),
            "command.land.expand.no_target", Map.of(),
            "command.land.expand.success", Map.of("chunk_count", "3"),
            "command.land.expand.rejected", Map.of("reason", "expand.overlap"),
            "command.land.expand.failed", Map.of("reason", "expand.failed"));

    @Test
    void everyReplyLiteralResolvesInBothLocales() throws Exception {
        Set<String> keys = scanReplyLiterals();
        assertTrue(keys.containsAll(EXPAND_KEYS),
                "scanner must see the expand keys, found " + keys.size() + " keys");
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        Set<String> missingEn = new TreeSet<>();
        Set<String> missingZh = new TreeSet<>();
        for (String key : keys) {
            if (en.getString(key) == null) {
                missingEn.add(key);
            }
            if (zh.getString(key) == null) {
                missingZh.add(key);
            }
        }
        assertTrue(missingEn.isEmpty(), "en_US missing reply keys: " + missingEn);
        assertTrue(missingZh.isEmpty(), "zh_TW missing reply keys: " + missingZh);
    }

    @Test
    void expandKeysMirrorWithHandlerVars() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        for (String key : EXPAND_KEYS) {
            assertNotNull(en.getString(key), "en_US missing " + key);
            assertNotNull(zh.getString(key), "zh_TW missing " + key);
        }
        assertEquals(placeholders(en.getString("command.land.expand.success")), Set.of("chunk_count"),
                "expand success must carry exactly the handler var <chunk_count>");
        assertEquals(placeholders(en.getString("command.land.expand.rejected")), Set.of("reason"),
                "expand rejected must carry exactly the handler var <reason>");
        assertEquals(placeholders(en.getString("command.land.expand.failed")), Set.of("reason"),
                "expand failed must carry exactly the handler var <reason>");
        for (String key : new String[] {
                "command.land.expand.console",
                "command.land.expand.no_selection",
                "command.land.expand.no_target"}) {
            assertTrue(placeholders(en.getString(key)).isEmpty(), key + " has no vars and must stay var-free");
        }
        for (String key : EXPAND_KEYS) {
            assertEquals(placeholders(en.getString(key)), placeholders(zh.getString(key)),
                    "placeholder parity broken for " + key);
        }
    }

    @Test
    void dynamicConcatenatedKeysAreSkipped() throws Exception {
        Set<String> keys = scanReplyLiterals();
        for (String key : keys) {
            assertFalse(key.endsWith("."), "scanner must skip dynamic prefix fragment: " + key);
        }
        assertFalse(keys.contains("command.land."),
                "dynamic \"command.land.\" + base sites must stay excluded, not reported as missing");
    }

    @Test
    void everyExpandOutcomeRendersNonEmptyInBothLocales() throws Exception {
        YamlConfiguration en = load("en_US");
        YamlConfiguration zh = load("zh_TW");
        for (String key : EXPAND_KEYS) {
            Map<String, String> vars = new HashMap<>(EXPAND_VARS.get(key));
            for (String tag : new String[] {"en_US", "zh_TW"}) {
                String template = (tag.equals("en_US") ? en : zh).getString(key);
                assertNotNull(template, tag + " missing " + key);
                assertFalse(template.isBlank(), tag + " blank " + key);
                validateStrict(key, template);
                var parsed = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize(template, toResolvers(vars));
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(parsed);
                assertFalse(plain.isBlank(), tag + " " + key + " must render a non-empty message");
                assertNotEquals(template, plain, tag + " " + key + " must render through MiniMessage");
            }
            assertNotEquals(en.getString(key), zh.getString(key), "zh render must differ from en for " + key);
        }
    }

    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] toResolvers(
            Map<String, String> vars) {
        return vars.entrySet().stream()
                .map(e -> net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
                        .unparsed(e.getKey(), e.getValue()))
                .toArray(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]::new);
    }

    /**
     * Collects complete {@code .reply("literal")} keys from production
     * sources. Concatenated keys such as {@code "command.land." + base}
     * leave a trailing-dot fragment that is skipped (see class javadoc).
     */
    static Set<String> scanReplyLiterals() throws Exception {
        File root = javaSourceRoot();
        Pattern call = Pattern.compile("\\.reply\\(\\s*\"([^\"]+)\"");
        Set<String> out = new HashSet<>();
        try (var stream = Files.walk(root.toPath())) {
            for (var path : (Iterable<java.nio.file.Path>) stream.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String text = Files.readString(path);
                Matcher m = call.matcher(text);
                while (m.find()) {
                    String key = m.group(1);
                    if (key.endsWith(".")) {
                        continue;
                    }
                    out.add(key);
                }
            }
        }
        return out;
    }

    private static File javaSourceRoot() {
        File direct = new File("src/main/java");
        if (direct.isDirectory()) {
            return direct;
        }
        File module = new File("chunkland-plugin/src/main/java");
        if (module.isDirectory()) {
            return module;
        }
        throw new IllegalStateException(
                "cannot locate production sources from working dir " + new File(".").getAbsolutePath());
    }

    private static YamlConfiguration load(String tag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        File direct = new File("src/main/resources/lang/" + tag + ".yml");
        File module = new File("chunkland-plugin/src/main/resources/lang/" + tag + ".yml");
        cfg.load(direct.isFile() ? direct : module);
        return cfg;
    }

    private static Set<String> placeholders(String template) {
        Set<String> found = new HashSet<>();
        Matcher matcher = Pattern.compile("<([a-z_]+)>").matcher(template);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        // MiniMessage color tags (<green>, <red>, ...) share the same
        // angle-bracket shape; only names the pipeline whitelist accepts
        // count as placeholders, so style tags never pollute var assertions.
        found.retainAll(allowedPlaceholders());
        return found;
    }

    private static Set<String> allowedPlaceholders() {
        try {
            var field = Class.forName("com.smile.chunkland.message.ChunkLandMessagePipeline")
                    .getDeclaredField("ALLOWED_PLACEHOLDERS");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> allowed = (Set<String>) field.get(null);
            return allowed;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read pipeline placeholder whitelist", e);
        }
    }

    private static void validateStrict(String key, String template) throws Exception {
        var method = Class.forName("com.smile.chunkland.message.ChunkLandMessagePipeline")
                .getDeclaredMethod("validateTemplateStrict", String.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(null, key, template);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        }
    }
}

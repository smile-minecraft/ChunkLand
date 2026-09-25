package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.message.AdventureClickPayload;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Help-UX shape and routing: {@code /land help} shows the prompt plus a
 * grouped, clickable grid, and {@code /land help <subcommand>} answers with
 * the detail triple. Unknown subcommands keep the existing replies.
 */
class LandHelpUxTest {

    private static String template(String tag, String key) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new File("src/main/resources/lang/" + tag + ".yml"));
        String value = cfg.getString(key);
        assertTrue(value != null && !value.isBlank(), tag + " missing " + key);
        return value;
    }

    /** Permission-node line shown between the detail and the usage. */
    static final String PERMISSION_KEY = "command.land.help_detail.permission";

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private record ClickEntry(String text, String clickValue, String hoverPlain) {
    }

    private static void collectClicks(Component node, List<ClickEntry> out) {
        ClickEvent click = node.clickEvent();
        if (click != null) {
            String hoverPlain = null;
            HoverEvent<?> hover = node.hoverEvent();
            if (hover != null) {
                Object value = hover.value();
                if (value instanceof Component hoverComponent) {
                    hoverPlain = plain(hoverComponent);
                } else if (value != null) {
                    hoverPlain = String.valueOf(value);
                }
            }
            out.add(new ClickEntry(plain(node), AdventureClickPayload.read(click), hoverPlain));
        }
        for (Component child : node.children()) {
            collectClicks(child, out);
        }
    }

    private static List<ClickEntry> helpClicks(String tag) throws Exception {
        Component parsed = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize(template(tag, "command.land.help"));
        List<ClickEntry> entries = new ArrayList<>();
        collectClicks(parsed, entries);
        return entries;
    }

    @Test
    void everySubcommandHasClickableSuggestEntryWithHoverShort() throws Exception {
        for (String tag : new String[] {"en_US", "zh_TW"}) {
            List<ClickEntry> entries = helpClicks(tag);
            for (String sub : LandCommand.SUBCOMMANDS) {
                String expectedClick = "/land " + sub;
                String expectedHover = plain(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize(template(tag, LandHelpKeys.shortKey(sub))));
                List<ClickEntry> matches = entries.stream()
                        .filter(e -> e.text().trim().equals(sub))
                        .toList();
                assertTrue(!matches.isEmpty(), tag + " help must list /land " + sub);
                for (ClickEntry match : matches) {
                    assertEquals(expectedClick, match.clickValue(),
                            tag + " help entry " + sub + " must suggest '" + expectedClick + "'");
                    assertEquals(expectedHover, match.hoverPlain(),
                            tag + " help entry " + sub + " must hover its short description");
                }
            }
        }
    }

    @Test
    void helpGridKeepsColumnStartsAndPadsTailRows() throws Exception {
        for (String tag : new String[] {"en_US", "zh_TW"}) {
            String rendered = plain(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(template(tag, "command.land.help")));
            String[] lines = rendered.split("\\R", -1);
            List<String> entryRows = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("Usage") || trimmed.startsWith("用法：")
                        || isGroupTitle(tag, trimmed)) {
                    continue;
                }
                entryRows.add(line);
            }
            assertTrue(entryRows.size() >= 7, tag + " help must keep at least 7 entry rows");
            int fullWidth = -1;
            for (String row : entryRows) {
                String[] names = row.trim().split("\\s+");
                int cursor = 0;
                for (String name : names) {
                    int start = row.indexOf(name, cursor);
                    assertTrue(start == 2 || start == 12 || start == 21 || start == 30,
                            tag + " help entry '" + name + "' must start at 2/12/21/30 but was " + start);
                    cursor = start + name.length();
                }
                if (names.length == 4) {
                    if (fullWidth == -1) {
                        fullWidth = row.length();
                    }
                    assertEquals(fullWidth, row.length(),
                            tag + " full help rows must share one width");
                }
            }
            assertTrue(fullWidth > 0, tag + " help must keep full rows");
            for (String row : entryRows) {
                assertEquals(fullWidth, row.length(),
                        tag + " tail help row must be padded to the full width: [" + row + "]");
            }
        }
    }

    private static boolean isGroupTitle(String tag, String trimmed) {
        if ("zh_TW".equals(tag)) {
            return trimmed.equals("領地操作") || trimmed.equals("權限管理")
                    || trimmed.equals("查詢") || trimmed.equals("管理員");
        }
        return trimmed.equals("Land actions") || trimmed.equals("Permissions")
                || trimmed.equals("Queries") || trimmed.equals("Administration");
    }

    private static CommandSender stubSender() {
        return (CommandSender) java.lang.reflect.Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class[] {CommandSender.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("hasPermission")) {
                        return true;
                    }
                    if (name.equals("isPermissionSet")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "Sender";
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "Sender-proxy";
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    if (rt == double.class) {
                        return 0d;
                    }
                    if (rt == float.class) {
                        return 0f;
                    }
                    return null;
                });
    }

    private record CapturedReply(String key, Map<String, Object> vars) {
    }

    private static LandCommand capturingCommand(List<CapturedReply> captured) {
        return new LandCommand(LandCommand.defaultStubHandlers(), (sender, pipeline) -> new ReplySink() {
            @Override
            public void reply(String key, Map<String, Object> vars) {
                captured.add(new CapturedReply(key, Map.copyOf(vars)));
            }

            @Override
            public void reply(String key, Map<String, Object> vars, Locale locale) {
                captured.add(new CapturedReply(key, Map.copyOf(vars)));
            }
        });
    }

    @Test
    void helpOverviewSendsPromptThenGroupedHelp() {
        for (String[] args : new String[][] {{}, {"help"}, {"?"}}) {
            List<CapturedReply> captured = new ArrayList<>();
            assertTrue(capturingCommand(captured).dispatch(stubSender(), args, null));
            List<String> keys = captured.stream().map(CapturedReply::key).toList();
            assertEquals(List.of(LandHelpKeys.PROMPT, "command.land.help"), keys,
                    "help overview must send the prompt line before the grouped entries");
        }
    }

    @Test
    void helpSubcommandRoutesToDetailPermissionUsage() {
        for (String sub : new String[] {"trust", "admin", "unclaim", "help"}) {
            List<CapturedReply> captured = new ArrayList<>();
            assertTrue(capturingCommand(captured).dispatch(stubSender(), new String[] {"help", sub}, null));
            List<String> keys = captured.stream().map(CapturedReply::key).toList();
            assertEquals(
                    List.of(LandHelpKeys.detailKey(sub), PERMISSION_KEY,
                            LandHelpKeys.usageKey(sub)),
                    keys,
                    "/land help " + sub + " must answer detail, permission node, then usage");
            assertEquals(LandPermissions.forSubcommand(sub), captured.get(1).vars().get("permission"),
                    "/land help " + sub + " must carry its permission node");
        }
    }

    @Test
    void helpSubcommandLookupIsCaseInsensitive() {
        List<CapturedReply> captured = new ArrayList<>();
        assertTrue(capturingCommand(captured).dispatch(stubSender(), new String[] {"help", "TRUST"}, null));
        List<String> keys = captured.stream().map(CapturedReply::key).toList();
        assertEquals(
                List.of(LandHelpKeys.detailKey("trust"), PERMISSION_KEY,
                        LandHelpKeys.usageKey("trust")),
                keys);
    }

    @Test
    void helpUnknownSubcommandKeepsHintReply() {
        List<CapturedReply> captured = new ArrayList<>();
        assertTrue(capturingCommand(captured).dispatch(stubSender(), new String[] {"help", "nope"}, null));
        List<String> keys = captured.stream().map(CapturedReply::key).toList();
        assertEquals(List.of(LandHelpKeys.UNKNOWN_HINT), keys,
                "/land help <unknown> must answer the unknown-subcommand hint");
        assertEquals("nope", captured.get(0).vars().get("subcommand"));
    }

    @Test
    void topLevelUnknownSubcommandIsUnchanged() {
        List<CapturedReply> captured = new ArrayList<>();
        assertTrue(capturingCommand(captured).dispatch(stubSender(), new String[] {"foobar"}, null));
        List<String> keys = captured.stream().map(CapturedReply::key).toList();
        assertEquals(List.of("command.land.unknown"), keys,
                "/land <unknown> must keep the existing unknown reply");
        assertEquals("foobar", captured.get(0).vars().get("subcommand"));
    }

    @Test
    void helpDetailCoversEverySubcommandInBothLocales() throws Exception {
        for (String sub : LandCommand.SUBCOMMANDS) {
            for (String tag : new String[] {"en_US", "zh_TW"}) {
                for (String key : LandHelpKeys.keysFor(sub)) {
                    String value = template(tag, key);
                    assertTrue(!value.isBlank(), tag + " " + key + " must stay non-blank");
                }
            }
            assertTrue(LandPermissions.forSubcommand(sub) != null && !LandPermissions.forSubcommand(sub).isBlank(),
                    "/land help " + sub + " must have a permission node to show");
        }
        Map<String, String> permissionCheck = new HashMap<>();
        permissionCheck.put("trust", LandPermissions.TRUST);
        permissionCheck.put("unclaim", LandPermissions.SHRINK);
        permissionCheck.put("admin", LandPermissions.ADMIN);
        permissionCheck.put("bypass", LandPermissions.BYPASS);
        permissionCheck.put("help", LandPermissions.HELP);
        for (Map.Entry<String, String> entry : permissionCheck.entrySet()) {
            assertEquals(entry.getValue(), LandPermissions.forSubcommand(entry.getKey()));
        }
    }
}

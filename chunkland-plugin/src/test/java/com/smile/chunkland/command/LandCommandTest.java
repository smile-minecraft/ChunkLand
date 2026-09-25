package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.message.ChunkLandMessagePipeline;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class LandCommandTest {

    // helpers to build bukkit senders
    private static CommandSender consoleSender(CopyOnWriteArrayList<String> out, CopyOnWriteArrayList<Component> compOut) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class[]{CommandSender.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("sendMessage")) {
                        if (args != null) {
                            for (Object a : args) {
                                if (a instanceof Component c) {
                                    compOut.add(c);
                                    out.add(plain(c));
                                } else if (a instanceof String s) {
                                    out.add(s);
                                } else if (a != null) {
                                    out.add(String.valueOf(a));
                                }
                            }
                        }
                        return null;
                    }
                    if (n.equals("hasPermission")) {
                        // console has all perms for most tests; individual tests override via wrapper
                        return true;
                    }
                    if (n.equals("isPermissionSet")) return true;
                    if (n.equals("getName")) return "Console";
                    if (n.equals("equals") || n.equals("hashCode") || n.equals("toString")) {
                        return switch (n) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "Console-proxy";
                        };
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0d;
                    if (rt == float.class) return 0f;
                    return null;
                });
    }

    private static String plain(Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static Player playerWithPerms(UUID id, Locale locale, Map<String, Boolean> perms, CopyOnWriteArrayList<String> out, CopyOnWriteArrayList<Component> compOut) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("sendMessage")) {
                        if (args != null) {
                            for (Object a : args) {
                                if (a instanceof Component c) {
                                    compOut.add(c);
                                    out.add(plain(c));
                                } else if (a instanceof String s) out.add(s);
                                else if (a != null) out.add(String.valueOf(a));
                            }
                        }
                        return null;
                    }
                    if (n.equals("hasPermission")) {
                        if (args != null && args.length == 1 && args[0] instanceof String p) {
                            return perms.getOrDefault(p, false);
                        }
                        return false;
                    }
                    if (n.equals("isPermissionSet")) return true;
                    if (n.equals("getUniqueId")) return id;
                    if (n.equals("locale")) return locale;
                    if (n.equals("isOnline")) return true;
                    if (n.equals("getName")) return "TestPlayer";
                    if (n.equals("equals") || n.equals("hashCode") || n.equals("toString")) {
                        return switch (n) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "Player-proxy:" + id;
                        };
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0d;
                    if (rt == float.class) return 0f;
                    return null;
                });
    }

    private static CommandSender permFilteredSender(Map<String, Boolean> perms, CopyOnWriteArrayList<String> out) {
        CopyOnWriteArrayList<Component> dummy = new CopyOnWriteArrayList<>();
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class[]{CommandSender.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("sendMessage")) {
                        if (args != null) for (Object a : args) if (a != null) out.add(String.valueOf(a));
                        return null;
                    }
                    if (n.equals("hasPermission")) {
                        if (args != null && args[0] instanceof String p) return perms.getOrDefault(p, false);
                        return false;
                    }
                    if (n.equals("isPermissionSet")) return true;
                    if (n.equals("getName")) return "Sender";
                    if (n.equals("equals") || n.equals("hashCode") || n.equals("toString")) {
                        return switch (n) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "Sender-proxy";
                        };
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0d;
                    if (rt == float.class) return 0f;
                    return null;
                });
    }

    // pipeline helpers similar to ChunkLandMessagePipelineTest - use reflection for package-private ctor
    private static ChunkLandMessagePipeline buildPipeline(boolean bedrock, CountingSender counting) throws Exception {
        ChunkLandMessagePipeline.LangProvider provider = buildLangProvider();
        FakeBedrock bedrockSvc = new FakeBedrock(bedrock);
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
        var ctor = ChunkLandMessagePipeline.class.getDeclaredConstructor(
                ChunkLandMessagePipeline.PipelineSender.class,
                ChunkLandMessagePipeline.MessageParser.class,
                ChunkLandMessagePipeline.LangProvider.class,
                com.smile.acelib.bedrock.BedrockService.class,
                Locale.class);
        ctor.setAccessible(true);
        return ctor.newInstance(counting, parser, provider, bedrockSvc, Locale.US);
    }

    private static void validateStrict(String key, String template) throws Exception {
        var m = ChunkLandMessagePipeline.class.getDeclaredMethod("validateTemplateStrict", String.class, String.class);
        m.setAccessible(true);
        try { m.invoke(null, key, template); } catch (java.lang.reflect.InvocationTargetException e) { throw (RuntimeException) e.getCause(); }
    }

    private static ChunkLandMessagePipeline.LangProvider buildLangProvider() throws Exception {
        Map<Locale, Map<String,String>> data = new HashMap<>();
        for (String tag : new String[]{"en_US","zh_TW"}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File("src/main/resources/lang/"+tag+".yml"));
            Map<String,String> m = new HashMap<>();
            for (String k : cfg.getKeys(true)) { Object v = cfg.get(k); if (v instanceof String s) m.put(k, s); }
            Locale loc = tag.equals("en_US") ? Locale.US : Locale.forLanguageTag("zh-TW");
            data.put(loc, m);
            data.put(new Locale("en","US"), data.get(Locale.US));
        }
        return (locale, key) -> {
            Map<String,String> mm = data.get(locale);
            if (mm != null && mm.containsKey(key)) return Optional.of(mm.get(key));
            Map<String,String> def = data.get(Locale.US);
            if (def != null && def.containsKey(key)) return Optional.of(def.get(key));
            return Optional.empty();
        };
    }

    static class CountingSender implements ChunkLandMessagePipeline.PipelineSender {
        int chatCalls=0, chatFallbackCalls=0, broadcastCalls=0;
        Component lastChat;
        Locale lastFallbackLocale;
        @Override public void sendChat(Player p, Component m){ chatCalls++; lastChat=m; }
        @Override public void sendChatWithFallback(Player p, Component m, Locale l){ chatFallbackCalls++; lastChat=m; lastFallbackLocale=l; }
        @Override public void sendActionBar(Player p, Component m){}
        @Override public void sendActionBarWithFallback(Player p, Component m, Locale l){}
        @Override public void sendTitle(Player p, Component t, Component s){}
        @Override public void sendTitleWithFallback(Player p, Component t, Component s, Locale l){}
        @Override public void broadcastWithFallback(Component m, Locale l){ broadcastCalls++; }
    }
    static class FakeBedrock implements com.smile.acelib.bedrock.BedrockService {
        private final boolean bedrock;
        FakeBedrock(boolean b){ this.bedrock=b; }
        @Override public boolean isBedrockPlayer(UUID u){ return bedrock; }
        @Override public Optional<com.smile.acelib.bedrock.BedrockPlayerInfo> getPlayerInfo(UUID u){ return Optional.empty(); }
        @Override public com.smile.acelib.form.FormService forms(){ return null; }
        @Override public String getModuleStatus(){ return ""; }
        @Override public void shutdown(){}
    }

    // ----- root / help / unknown / case -----
    @Test
    void rootEmptyArgsShowsHelp() throws Exception {
        CountingSender cs = new CountingSender();
        ChunkLandMessagePipeline pipeline = buildPipeline(false, cs);
        Map<String, Boolean> perms = Map.of("chunkland.command.land.help", true);
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Component> comp = new CopyOnWriteArrayList<>();
        // use console sender with pipeline to exercise help path via broadcast fallback -> component
        CommandSender sender = consoleSender(out, comp);
        // console hasPermission returns true for all; need land pipeline rendering for help key
        // We inject a ReplySink that captures key instead of pipeline to verify routing
        List<String> keys = new ArrayList<>();
        LandCommand cmd = new LandCommand(LandCommand.defaultStubHandlers(), (s,p) -> new ReplySink(){
            public void reply(String k, Map<String,Object> v){ keys.add(k); }
            public void reply(String k, Map<String,Object> v, Locale l){ keys.add(k); }
        });
        assertTrue(cmd.dispatch(sender, new String[]{}, pipeline));
        assertTrue(keys.contains("command.land.help"));
        // empty string arg variant
        keys.clear();
        assertTrue(cmd.dispatch(sender, new String[]{""}, pipeline));
        assertTrue(keys.contains("command.land.help"));
    }

    @Test
    void helpRendersGroupedLinesInBothLocales() throws Exception {
        for (String tag : new String[]{"en_US", "zh_TW"}) {
            String template = helpTemplate(tag);
            String rendered = plain(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(template));
            String[] lines = rendered.split("\\R", -1);
            assertTrue(lines.length >= 2, tag + " help must render on multiple lines");
            for (String title : helpGroupTitles(tag)) {
                assertTrue(rendered.contains(title), tag + " help must include group title " + title);
            }
            assertTrue(template.contains("<click:suggest_command:"),
                    tag + " help entries must be clickable suggest_command components");
            for (String subcommand : LandCommand.SUBCOMMANDS) {
                assertTrue(template.contains("'/land " + subcommand + "'"),
                        tag + " help must fill '/land " + subcommand + "' on click");
            }
        }
    }

    @Test
    void helpListsEverySubcommandInBothLocales() throws Exception {
        for (String tag : new String[]{"en_US", "zh_TW"}) {
            String rendered = plain(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(helpTemplate(tag)));
            for (String subcommand : LandCommand.SUBCOMMANDS) {
                assertTrue(rendered.contains(subcommand), tag + " help must list /land " + subcommand);
            }
        }
    }

    @Test
    void unknownSubcommandRoutesToUnknownKey() {
        List<String> keys = new ArrayList<>();
        Map<String,Map<String,Object>> varsCap = new HashMap<>();
        LandCommand cmd = new LandCommand(LandCommand.defaultStubHandlers(), (s,p) -> new ReplySink(){
            public void reply(String k, Map<String,Object> v){ keys.add(k); varsCap.put(k, Map.copyOf(v)); }
            public void reply(String k, Map<String,Object> v, Locale l){ keys.add(k); }
        });
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CommandSender sender = permFilteredSender(Map.of(), out);
        // need perm true for unknown? unknown does not check perm, it directly replies
        assertTrue(cmd.dispatch(sender, new String[]{"foobar"}, null));
        assertTrue(keys.contains("command.land.unknown"));
        assertEquals(Map.of("subcommand", "foobar"), varsCap.get("command.land.unknown"));
    }

    @Test
    void caseInsensitiveRouting() {
        List<String> keys = new ArrayList<>();
        Map<String, String> captured = new HashMap<>();
        Map<String, LandCommand.Handler> handlers = new HashMap<>();
        for (String sub : LandCommand.SUBCOMMANDS) {
            handlers.put(sub, (s,a,sink)-> captured.put("hit", sub));
        }
        // give all perms
        Map<String, Boolean> perms = new HashMap<>();
        for (String sub : LandCommand.SUBCOMMANDS) perms.put(LandPermissions.forSubcommand(sub), true);
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CommandSender sender = permFilteredSender(perms, out);
        LandCommand cmd = new LandCommand(handlers, (s,p)-> new ReplySink(){
            public void reply(String k, Map<String,Object> v){}
            public void reply(String k, Map<String,Object> v, Locale l){}
        });
        assertTrue(cmd.dispatch(sender, new String[]{"WAND"}, null));
        assertEquals("wand", captured.get("hit"));
        captured.clear();
        assertTrue(cmd.dispatch(sender, new String[]{"CoNfIrM"}, null));
        assertEquals("confirm", captured.get("hit"));
    }

    @Test
    void eachSubcommandAllowCallsHandlerDenyDoesNot() {
        for (String sub : LandCommand.SUBCOMMANDS) {
            AtomicBoolean called = new AtomicBoolean(false);
            Map<String, LandCommand.Handler> handlers = Map.of(sub, (s,a,sink)-> called.set(true));
            // deny case
            CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
            CommandSender denied = permFilteredSender(Map.of(LandPermissions.forSubcommand(sub), false), out);
            List<String> keys = new ArrayList<>();
            LandCommand cmdDeny = new LandCommand(handlers, (s,p)-> new ReplySink(){
                public void reply(String k, Map<String,Object> v){ keys.add(k); }
                public void reply(String k, Map<String,Object> v, Locale l){ keys.add(k); }
            });
            assertTrue(cmdDeny.dispatch(denied, new String[]{sub}, null));
            assertFalse(called.get(), sub+" deny must not call handler");
            if (sub.equals("explain")) {
                assertTrue(keys.contains("command.land.explain.denied"),
                        sub + " deny must reply the generic explain denial");
                assertFalse(keys.contains("command.land.denied"),
                        sub + " deny must not leak the shared denied key");
            } else if (sub.equals("inspect")) {
                assertTrue(keys.contains("command.land.inspect.denied"),
                        sub + " deny must reply the generic inspect denial");
                assertFalse(keys.contains("command.land.denied"),
                        sub + " deny must not leak the shared denied key");
            } else {
                assertTrue(keys.contains("command.land.denied"), sub+" deny must reply denied");
            }

            // allow case
            called.set(false);
            keys.clear();
            CommandSender allowed = permFilteredSender(Map.of(LandPermissions.forSubcommand(sub), true), out);
            LandCommand cmdAllow = new LandCommand(handlers, (s,p)-> new ReplySink(){
                public void reply(String k, Map<String,Object> v){ keys.add(k); }
                public void reply(String k, Map<String,Object> v, Locale l){ keys.add(k); }
            });
            assertTrue(cmdAllow.dispatch(allowed, new String[]{sub}, null));
            if (LandCommand.managementActionFor(sub).isPresent()) {
                // No domain authorizer is wired here, so management subcommands
                // fail closed even when the Bukkit node passes.
                assertFalse(called.get(), sub + " without a domain authorizer must fail closed");
                if (sub.equals("explain")) {
                    assertTrue(keys.contains("command.land.explain.denied"),
                            sub + " fail-closed must reply the generic explain denial");
                } else if (sub.equals("inspect")) {
                    assertTrue(keys.contains("command.land.inspect.denied"),
                            sub + " fail-closed must reply the generic inspect denial");
                } else {
                    assertTrue(keys.contains("command.land.denied"), sub + " fail-closed must reply denied");
                }
            } else {
                assertTrue(called.get(), sub + " allow must call handler");
            }
        }
    }

    @Test
    void tabCompletionPrefixFiltering() {
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CommandSender sender = permFilteredSender(Map.of(), out);
        List<String> all = LandCommand.tabComplete(sender, new String[]{""});
        assertTrue(all.containsAll(LandCommand.SUBCOMMANDS));
        List<String> c = LandCommand.tabComplete(sender, new String[]{"c"});
        assertTrue(c.contains("claim"));
        assertTrue(c.contains("confirm"));
        assertFalse(c.contains("wand"));
        List<String> tr = LandCommand.tabComplete(sender, new String[]{"tr"});
        assertEquals(List.of("trust"), tr);
        List<String> un = LandCommand.tabComplete(sender, new String[]{"un"});
        assertTrue(un.contains("untrust"));
        assertTrue(un.contains("unban"));
        assertTrue(un.contains("unclaim"));
        assertEquals(3, un.size());
        // case insensitive prefix
        List<String> up = LandCommand.tabComplete(sender, new String[]{"W"});
        assertTrue(up.contains("wand"));
        // second arg none
        assertEquals(List.of(), LandCommand.tabComplete(sender, new String[]{"claim","extra"}));
    }

    @Test
    void replySinkPlayerUsesPipelineChatPath() throws Exception {
        CountingSender cs = new CountingSender();
        ChunkLandMessagePipeline pipeline = buildPipeline(false, cs);
        UUID id = UUID.randomUUID();
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Component> comp = new CopyOnWriteArrayList<>();
        Player player = playerWithPerms(id, Locale.US, Map.of(), out, comp);
        PipelineReplySink sink = new PipelineReplySink(player, pipeline);
        sink.reply("command.land.help", Map.of());
        assertEquals(1, cs.chatCalls);
        assertEquals(0, cs.chatFallbackCalls);
        assertTrue(comp.isEmpty(), "player path goes via pipeline sender, not console sender");
    }

    @Test
    void replySinkConsoleUsesBroadcastSafePath() throws Exception {
        CountingSender cs = new CountingSender();
        ChunkLandMessagePipeline pipeline = buildPipeline(false, cs);
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Component> comp = new CopyOnWriteArrayList<>();
        CommandSender console = consoleSender(out, comp);
        PipelineReplySink sink = new PipelineReplySink(console, pipeline);
        sink.reply("command.land.help", Map.of());
        // console path uses renderForBroadcast then sender.sendMessage(Component)
        assertEquals(0, cs.chatCalls);
        assertEquals(0, cs.chatFallbackCalls);
        assertEquals(1, comp.size());
        assertFalse(plain(comp.get(0)).isBlank());
    }

    @Test
    void replySinkPreservesMessageKeyAndVarsForBothBranches() throws Exception {
        CountingSender cs = new CountingSender();
        ChunkLandMessagePipeline pipeline = buildPipeline(false, cs);
        // player
        UUID id = UUID.randomUUID();
        CopyOnWriteArrayList<String> outP = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Component> compP = new CopyOnWriteArrayList<>();
        Player player = playerWithPerms(id, Locale.US, Map.of(), outP, compP);
        PipelineReplySink sinkP = new PipelineReplySink(player, pipeline);
        sinkP.reply("command.land.not_yet", Map.of("subcommand","claim"));
        assertEquals(1, cs.chatCalls);
        assertTrue(plain(cs.lastChat).contains("claim"));

        // console with same key+vars renders same content
        cs = new CountingSender(); pipeline = buildPipeline(false, cs);
        CopyOnWriteArrayList<String> outC = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Component> compC = new CopyOnWriteArrayList<>();
        CommandSender console = consoleSender(outC, compC);
        PipelineReplySink sinkC = new PipelineReplySink(console, pipeline);
        sinkC.reply("command.land.not_yet", Map.of("subcommand","claim"));
        assertEquals(1, compC.size());
        assertTrue(plain(compC.get(0)).contains("claim"));
    }

    @Test
    void bilingualKeysExistAndStrictValidationPasses() throws Exception {
        java.util.Set<String> enKeys = commandLandKeys("en_US");
        java.util.Set<String> zhKeys = commandLandKeys("zh_TW");
        assertEquals(enKeys, zhKeys, "command.land.* key set must be identical between en_US and zh_TW");
        for (String tag : new String[]{"en_US","zh_TW"}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File("src/main/resources/lang/"+tag+".yml"));
            for (String k : enKeys) {
                String v = cfg.getString(k);
                assertNotNull(v, tag+" missing "+k);
                validateStrict(k, v);
            }
        }
        // strict validation already ensures only allowed placeholders survive, so reaching here is the check
        java.util.Set<String> allowedByReflection = allowedPlaceholders();
        for (String k : enKeys) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File("src/main/resources/lang/en_US.yml"));
            String tmpl = cfg.getString(k);
            java.util.Set<String> found = new java.util.HashSet<>();
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("<([a-z_]+)>").matcher(tmpl);
            while (mm.find()) {
                String ph = mm.group(1);
                if (allowedByReflection.contains(ph)) found.add(ph);
            }
            for (String ph : found) {
                assertTrue(allowedByReflection.contains(ph), k+" uses placeholder "+ph+" not in allow-list");
            }
        }
    }

    private static String helpTemplate(String tag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new File("src/main/resources/lang/" + tag + ".yml"));
        return cfg.getString("command.land.help");
    }

    private static String[] helpGroupTitles(String tag) {
        return "zh_TW".equals(tag)
                ? new String[]{"領地操作", "權限管理", "查詢", "管理員"}
                : new String[]{"Land actions", "Permissions", "Queries", "Administration"};
    }

    private static java.util.Set<String> commandLandKeys(String tag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new File("src/main/resources/lang/"+tag+".yml"));
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String k : cfg.getKeys(true)) {
            Object v = cfg.get(k);
            if (v instanceof String && k.startsWith("command.land.")) out.add(k);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<String> allowedPlaceholders() throws Exception {
        var f = ChunkLandMessagePipeline.class.getDeclaredField("ALLOWED_PLACEHOLDERS");
        f.setAccessible(true);
        return (java.util.Set<String>) f.get(null);
    }

    private static ChunkLandMessagePipeline newPipelineViaReflection(ChunkLandMessagePipeline.PipelineSender sender,
                                                                     ChunkLandMessagePipeline.MessageParser parser,
                                                                     ChunkLandMessagePipeline.LangProvider lang,
                                                                     com.smile.acelib.bedrock.BedrockService bedrock,
                                                                     Locale locale) throws Exception {
        var ctor = ChunkLandMessagePipeline.class.getDeclaredConstructor(
                ChunkLandMessagePipeline.PipelineSender.class,
                ChunkLandMessagePipeline.MessageParser.class,
                ChunkLandMessagePipeline.LangProvider.class,
                com.smile.acelib.bedrock.BedrockService.class,
                Locale.class);
        ctor.setAccessible(true);
        return ctor.newInstance(sender, parser, lang, bedrock, locale);
    }

    private static ChunkLandMessagePipeline.MessageParser pipelineGetParser(ChunkLandMessagePipeline p) throws Exception {
        var m = ChunkLandMessagePipeline.class.getDeclaredMethod("parser");
        m.setAccessible(true); return (ChunkLandMessagePipeline.MessageParser) m.invoke(p);
    }
    private static ChunkLandMessagePipeline.LangProvider pipelineGetLang(ChunkLandMessagePipeline p) throws Exception {
        var m = ChunkLandMessagePipeline.class.getDeclaredMethod("lang");
        m.setAccessible(true); return (ChunkLandMessagePipeline.LangProvider) m.invoke(p);
    }
    private static com.smile.acelib.bedrock.BedrockService pipelineGetBedrock(ChunkLandMessagePipeline p) throws Exception {
        var m = ChunkLandMessagePipeline.class.getDeclaredMethod("bedrock");
        m.setAccessible(true); return (com.smile.acelib.bedrock.BedrockService) m.invoke(p);
    }
    private static Locale pipelineGetDefaultLocale(ChunkLandMessagePipeline p) throws Exception {
        var m = ChunkLandMessagePipeline.class.getDeclaredMethod("defaultLocale");
        m.setAccessible(true); return (Locale) m.invoke(p);
    }

    // ----- fail-closed ReplySink -----
    @Test
    void replySinkNullPipelineIsFailClosedNoRawKey() {
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Component> comp = new CopyOnWriteArrayList<>();
        CommandSender console = consoleSender(out, comp);
        PipelineReplySink sink = new PipelineReplySink(console, null);
        sink.reply("command.land.help", Map.of());
        assertTrue(out.isEmpty(), "null pipeline must not send raw key");
        assertTrue(comp.isEmpty());
    }

    @Test
    void replySinkPlayerFailureDoesNotFallbackToBroadcast() throws Exception {
        CountingSender cs = new CountingSender();
        ChunkLandMessagePipeline pipeline = buildPipeline(false, cs);
        // wrap with throwing sendChat via reflection
        ChunkLandMessagePipeline throwing = newPipelineViaReflection(
                new ChunkLandMessagePipeline.PipelineSender() {
                    public void sendChat(Player p, Component m){ throw new RuntimeException("chat fail"); }
                    public void sendChatWithFallback(Player p, Component m, Locale l){ throw new RuntimeException("fallback fail"); }
                    public void sendActionBar(Player p, Component m){}
                    public void sendActionBarWithFallback(Player p, Component m, Locale l){}
                    public void sendTitle(Player p, Component t, Component s){}
                    public void sendTitleWithFallback(Player p, Component t, Component s, Locale l){}
                    public void broadcastWithFallback(Component m, Locale l){ cs.broadcastCalls++; }
                },
                pipelineGetParser(pipeline), pipelineGetLang(pipeline), pipelineGetBedrock(pipeline), pipelineGetDefaultLocale(pipeline));
        UUID id = UUID.randomUUID();
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Component> comp = new CopyOnWriteArrayList<>();
        Player player = playerWithPerms(id, Locale.US, Map.of(), out, comp);
        PipelineReplySink sink = new PipelineReplySink(player, throwing);
        sink.reply("command.land.help", Map.of());
        assertTrue(out.isEmpty(), "player failure must not fallback to raw key");
        assertTrue(comp.isEmpty(), "player failure must not fallback to broadcast component");
        assertEquals(0, cs.broadcastCalls, "must not call broadcast fallback on player failure");
        assertEquals(0, cs.chatCalls);
    }

    @Test
    void replySinkBedrockFallbackKeepsGroupedHelpWithoutClick() throws Exception {
        CountingSender cs = new CountingSender();
        ChunkLandMessagePipeline pipeline = buildPipeline(true, cs);
        UUID id = UUID.randomUUID();
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Component> comp = new CopyOnWriteArrayList<>();
        Player player = playerWithPerms(id, Locale.US, Map.of(), out, comp);
        PipelineReplySink sink = new PipelineReplySink(player, pipeline);

        sink.reply("command.land.help", Map.of());

        assertEquals(1, cs.chatFallbackCalls);
        String rendered = plain(cs.lastChat);
        assertTrue(rendered.contains("\n"), "Bedrock fallback must preserve grouped help lines");
        assertTrue(rendered.contains("subland"));
        assertTrue(rendered.contains("admin"));
        assertNull(cs.lastChat.clickEvent(), "help must not gain click actions");
    }

    @Test
    void replySinkBedrockFallbackFailureIsFailClosed() throws Exception {
        CountingSender throwingCS = new CountingSender() {
            @Override public void sendChatWithFallback(Player p, Component m, Locale l) {
                super.sendChatWithFallback(p, m, l);
                throw new RuntimeException("bedrock fallback fail");
            }
        };
        ChunkLandMessagePipeline base = buildPipeline(true, new CountingSender());
        assertTrue(pipelineGetBedrock(base).isBedrockPlayer(UUID.randomUUID()), "base must be bedrock seam");
        ChunkLandMessagePipeline pipeline = newPipelineViaReflection(
                throwingCS,
                pipelineGetParser(base), pipelineGetLang(base), pipelineGetBedrock(base), pipelineGetDefaultLocale(base));
        UUID id = UUID.randomUUID();
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Component> comp = new CopyOnWriteArrayList<>();
        Player player = playerWithPerms(id, Locale.US, Map.of(), out, comp);
        PipelineReplySink sink = new PipelineReplySink(player, pipeline);
        sink.reply("command.land.help", Map.of());
        assertEquals(1, throwingCS.chatFallbackCalls, "must trigger sendChatWithFallback exactly once");
        assertEquals(0, throwingCS.chatCalls, "must not call non-fallback sendChat");
        assertEquals(0, throwingCS.broadcastCalls, "must not fallback to broadcast on bedrock failure");
        assertTrue(out.isEmpty(), "bedrock failure must not leak raw key");
        assertTrue(comp.isEmpty(), "bedrock failure must not send Component/console output");
        for (String s : out) assertFalse(s.contains("command.land.help"));
    }

    @Test
    void replySinkRenderFailureIsFailClosed() throws Exception {
        ChunkLandMessagePipeline pipeline = buildPipeline(false, new CountingSender());
        ChunkLandMessagePipeline throwingRender = newPipelineViaReflection(
                new ChunkLandMessagePipeline.PipelineSender(){
                    public void sendChat(Player p, Component m){}
                    public void sendChatWithFallback(Player p, Component m, Locale l){}
                    public void sendActionBar(Player p, Component m){}
                    public void sendActionBarWithFallback(Player p, Component m, Locale l){}
                    public void sendTitle(Player p, Component t, Component s){}
                    public void sendTitleWithFallback(Player p, Component t, Component s, Locale l){}
                    public void broadcastWithFallback(Component m, Locale l){}
                },
                (tpl, vars) -> { throw new RuntimeException("parse fail"); },
                pipelineGetLang(pipeline), pipelineGetBedrock(pipeline), pipelineGetDefaultLocale(pipeline));
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Component> comp = new CopyOnWriteArrayList<>();
        CommandSender console = consoleSender(out, comp);
        PipelineReplySink sink = new PipelineReplySink(console, throwingRender);
        sink.reply("command.land.help", Map.of());
        assertTrue(out.isEmpty(), "render failure must not send raw key");
        assertTrue(comp.isEmpty());
    }

    @Test
    void rootEmptyArgsPermissionGate() {
        List<String> keys = new ArrayList<>();
        Map<String, Object> varsCap = new HashMap<>();
        LandCommand cmd = new LandCommand(LandCommand.defaultStubHandlers(), (s,p) -> new ReplySink(){
            public void reply(String k, Map<String,Object> v){ keys.add(k); varsCap.putAll(v); }
            public void reply(String k, Map<String,Object> v, Locale l){ keys.add(k); varsCap.putAll(v); }
        });
        // denied
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CommandSender denied = permFilteredSender(Map.of(LandPermissions.HELP, false), out);
        assertTrue(cmd.dispatch(denied, new String[]{}, null));
        assertTrue(keys.contains("command.land.denied"), "empty args without perm must deny");
        assertEquals(LandPermissions.HELP, varsCap.get("permission"));
        // blank arg also denied
        keys.clear(); varsCap.clear();
        assertTrue(cmd.dispatch(denied, new String[]{""}, null));
        assertTrue(keys.contains("command.land.denied"));
        assertTrue(cmd.dispatch(denied, new String[]{"   "}, null));
        assertTrue(keys.contains("command.land.denied"));
        // allowed
        keys.clear(); varsCap.clear();
        CommandSender allowed = permFilteredSender(Map.of(LandPermissions.HELP, true), out);
        assertTrue(cmd.dispatch(allowed, new String[]{}, null));
        assertTrue(keys.contains("command.land.help"));
        keys.clear();
        assertTrue(cmd.dispatch(allowed, new String[]{"help"}, null));
        assertTrue(keys.contains("command.land.help"));
    }

    @Test
    void landConfirmClickHasEquivalentCommandPath() throws Exception {
        YamlConfiguration en = new YamlConfiguration(); en.load(new File("src/main/resources/lang/en_US.yml"));
        String confirm = en.getString("land.claim.confirm");
        assertNotNull(confirm);
        assertTrue(confirm.contains("/land confirm <generation> <revision>"));
        // dispatch to confirm handler
        AtomicBoolean hit = new AtomicBoolean(false);
        Map<String, LandCommand.Handler> handlers = Map.of("confirm", (s,a,sink)-> { hit.set(true); assertTrue(a.length>=3); });
        Map<String, Boolean> perms = Map.of(LandPermissions.CONFIRM, true);
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CommandSender sender = permFilteredSender(perms, out);
        LandCommand cmd = new LandCommand(handlers, (s,p)-> new ReplySink(){
            public void reply(String k, Map<String,Object> v){}
            public void reply(String k, Map<String,Object> v, Locale l){}
        });
        assertTrue(cmd.dispatch(sender, new String[]{"confirm","0","42","Home"}, null));
        assertTrue(hit.get());
    }
}

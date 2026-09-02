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
    void unknownSubcommandRoutesToUnknownKey() {
        List<String> keys = new ArrayList<>();
        Map<String,List<String>> varsCap = new HashMap<>();
        LandCommand cmd = new LandCommand(LandCommand.defaultStubHandlers(), (s,p) -> new ReplySink(){
            public void reply(String k, Map<String,Object> v){ keys.add(k); varsCap.put(k, new ArrayList<>(v.keySet())); }
            public void reply(String k, Map<String,Object> v, Locale l){ keys.add(k); }
        });
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CommandSender sender = permFilteredSender(Map.of(), out);
        // need perm true for unknown? unknown does not check perm, it directly replies
        assertTrue(cmd.dispatch(sender, new String[]{"foobar"}, null));
        assertTrue(keys.contains("command.land.unknown"));
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
            assertTrue(keys.contains("command.land.denied"), sub+" deny must reply denied");

            // allow case
            called.set(false);
            keys.clear();
            CommandSender allowed = permFilteredSender(Map.of(LandPermissions.forSubcommand(sub), true), out);
            LandCommand cmdAllow = new LandCommand(handlers, (s,p)-> new ReplySink(){
                public void reply(String k, Map<String,Object> v){ keys.add(k); }
                public void reply(String k, Map<String,Object> v, Locale l){ keys.add(k); }
            });
            assertTrue(cmdAllow.dispatch(allowed, new String[]{sub}, null));
            assertTrue(called.get(), sub+" allow must call handler");
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
        assertEquals(2, un.size());
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
        for (String tag : new String[]{"en_US","zh_TW"}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File("src/main/resources/lang/"+tag+".yml"));
            for (String k : new String[]{"command.land.help","command.land.usage","command.land.unknown","command.land.denied","command.land.not_yet"}) {
                String v = cfg.getString(k);
                assertNotNull(v, tag+" missing "+k);
                validateStrict(k, v);
            }
        }
        // ensure same set
        YamlConfiguration en = new YamlConfiguration(); en.load(new File("src/main/resources/lang/en_US.yml"));
        YamlConfiguration zh = new YamlConfiguration(); zh.load(new File("src/main/resources/lang/zh_TW.yml"));
        for (String k : new String[]{"command.land.help","command.land.usage","command.land.unknown","command.land.denied","command.land.not_yet"}) {
            assertNotNull(en.getString(k));
            assertNotNull(zh.getString(k));
        }
    }

    @Test
    void landConfirmClickHasEquivalentCommandPath() throws Exception {
        YamlConfiguration en = new YamlConfiguration(); en.load(new File("src/main/resources/lang/en_US.yml"));
        String confirm = en.getString("land.claim.confirm");
        assertNotNull(confirm);
        assertTrue(confirm.contains("/land confirm <revision>"));
        // dispatch to confirm handler
        AtomicBoolean hit = new AtomicBoolean(false);
        Map<String, LandCommand.Handler> handlers = Map.of("confirm", (s,a,sink)-> { hit.set(true); assertTrue(a.length>=2); });
        Map<String, Boolean> perms = Map.of(LandPermissions.CONFIRM, true);
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CommandSender sender = permFilteredSender(perms, out);
        LandCommand cmd = new LandCommand(handlers, (s,p)-> new ReplySink(){
            public void reply(String k, Map<String,Object> v){}
            public void reply(String k, Map<String,Object> v, Locale l){}
        });
        assertTrue(cmd.dispatch(sender, new String[]{"confirm","42"}, null));
        assertTrue(hit.get());
    }
}

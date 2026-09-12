package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.message.ChunkLandMessagePipeline;
import com.smile.chunkland.wand.FakeItemStack;
import com.smile.chunkland.wand.TestWandHelpers;
import com.smile.chunkland.wand.WandGiveHandler;
import com.smile.chunkland.wand.WandKeys;
import com.smile.chunkland.wand.WandSafetyListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class ChunkLandPluginWandLifecycleTest {

    @SuppressWarnings("unchecked")
    private static ChunkLandPlugin allocatePlugin() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        ChunkLandPlugin p = (ChunkLandPlugin) unsafe.allocateInstance(ChunkLandPlugin.class);
        for (var f : ChunkLandPlugin.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            long offset = unsafe.objectFieldOffset(f);
            Object v = unsafe.getObject(p, offset);
            if (f.getType() == java.util.Optional.class && v == null) {
                unsafe.putObject(p, offset, java.util.Optional.empty());
            } else if (f.getType() == com.smile.chunkland.adapter.AceLibBridge.class && v == null) {
                unsafe.putObject(p, offset, new com.smile.chunkland.adapter.AceLibBridge());
            }
        }
        return p;
    }

    private static com.smile.acelib.AceLibApi readyApi() {
        return com.smile.acelib.AceLibApi.ready(
                "1.2.0",
                com.smile.acelib.platform.Platform.PAPER,
                () -> true,
                () -> {});
    }

    @Test
    void buildLandHandlersContainsWand() throws Exception {
        Method m = ChunkLandPlugin.class.getDeclaredMethod("buildLandHandlers");
        m.setAccessible(true);
        var handlers = (java.util.Map<String, ?>) m.invoke(null);
        assertTrue(handlers.containsKey("wand"));
        assertTrue(handlers.get("wand") instanceof WandGiveHandler);
    }

    @Test
    void onEnableShortCircuitsWhenAceLibNotReady() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        // ServicesManager that yields no AceLib provider -> enable returns false
        org.bukkit.plugin.ServicesManager sm = (org.bukkit.plugin.ServicesManager) Proxy.newProxyInstance(
                org.bukkit.plugin.ServicesManager.class.getClassLoader(),
                new Class[]{org.bukkit.plugin.ServicesManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getRegistration")) return null;
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        java.util.concurrent.atomic.AtomicBoolean disableCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        PluginManager pm = (PluginManager) Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class[]{PluginManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("disablePlugin")) { disableCalled.set(true); return null; }
                    if (method.getName().equals("registerEvents")) fail("must not register when AceLib not ready");
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class[]{Server.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("getServicesManager")) return sm;
                    if (n.equals("getPluginManager")) return pm;
                    if (n.equals("getLogger")) return java.util.logging.Logger.getLogger("Test");
                    if (n.equals("getDataFolder")) return new java.io.File(System.getProperty("java.io.tmpdir"));
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        Field serverField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(plugin, server);
        Field loggerField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        loggerField.set(plugin, java.util.logging.Logger.getLogger("Test"));
        // need dataFolder field
        try {
            Field df = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("dataFolder");
            df.setAccessible(true);
            df.set(plugin, new java.io.File(System.getProperty("java.io.tmpdir"), "chunkland-test-" + System.nanoTime()));
        } catch (NoSuchFieldException ignored) {}

        plugin.onEnable();

        assertTrue(disableCalled.get(), "enable false must trigger self-disable");
        // no downstream wiring after bootstrap failure
        Field fLandCommand = ChunkLandPlugin.class.getDeclaredField("landCommand");
        fLandCommand.setAccessible(true);
        assertNull(fLandCommand.get(plugin), "landCommand must not be wired when AceLib not ready");
        Field fWand = ChunkLandPlugin.class.getDeclaredField("wandSafetyListener");
        fWand.setAccessible(true);
        assertNull(fWand.get(plugin), "wand listener must not be registered when AceLib not ready");
        Field fPipeline = ChunkLandPlugin.class.getDeclaredField("landMessagePipeline");
        fPipeline.setAccessible(true);
        var opt = (java.util.Optional<?>) fPipeline.get(plugin);
        assertTrue(opt.isEmpty(), "land pipeline must remain empty when AceLib not ready");
    }

    @Test
    void wandListenerRegistrationFailureDisablesPlugin() throws Exception {
        // Prove onEnable registers and disables on failure with real onDisable cleanup
        java.util.concurrent.atomic.AtomicBoolean disabled = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean onDisableCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        ChunkLandPlugin plugin = allocatePlugin();
        // pre-set isEnabled true to mimic Bukkit's enabled state during onEnable
        Field isEnabledField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("isEnabled");
        isEnabledField.setAccessible(true);
        isEnabledField.set(plugin, true);
        PluginManager pm = (PluginManager) Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class[]{PluginManager.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("registerEvents")) throw new RuntimeException("register boom");
                    if (n.equals("disablePlugin")) {
                        disabled.set(true);
                        // model real Bukkit: set isEnabled false and invoke onDisable synchronously
                        try {
                            Field f = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("isEnabled");
                            f.setAccessible(true);
                            f.set(plugin, false);
                        } catch (Exception ignored) {}
                        plugin.onDisable();
                        onDisableCalled.set(true);
                        return null;
                    }
                    if (n.equals("isPluginEnabled")) return !disabled.get() ? true : false;
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        // Ready AceLib provider for enable to succeed
        com.smile.acelib.AceLibApi readyApi = readyApi();
        com.smile.acelib.AceLibApi.AceLibProvider provider = (com.smile.acelib.AceLibApi.AceLibProvider) Proxy.newProxyInstance(
                com.smile.acelib.AceLibApi.AceLibProvider.class.getClassLoader(),
                new Class[]{com.smile.acelib.AceLibApi.AceLibProvider.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("api")) return readyApi;
                    return null;
                });
        org.bukkit.plugin.ServicesManager sm = (org.bukkit.plugin.ServicesManager) Proxy.newProxyInstance(
                org.bukkit.plugin.ServicesManager.class.getClassLoader(),
                new Class[]{org.bukkit.plugin.ServicesManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getRegistration")) {
                        return new org.bukkit.plugin.RegisteredServiceProvider<>(
                                com.smile.acelib.AceLibApi.AceLibProvider.class, provider,
                                org.bukkit.plugin.ServicePriority.Normal, null);
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class[]{Server.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("getServicesManager")) return sm;
                    if (n.equals("getPluginManager")) return pm;
                    if (n.equals("getLogger")) return java.util.logging.Logger.getLogger("Test");
                    if (n.equals("getDataFolder")) return new java.io.File(System.getProperty("java.io.tmpdir"));
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        Field serverField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(plugin, server);
        Field loggerField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        loggerField.set(plugin, java.util.logging.Logger.getLogger("Test"));
        try {
            Field df = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("dataFolder");
            df.setAccessible(true);
            df.set(plugin, new java.io.File(System.getProperty("java.io.tmpdir"), "chunkland-test-" + System.nanoTime()));
        } catch (NoSuchFieldException ignored) {}

        plugin.onEnable();

        assertTrue(disabled.get(), "registration failure must trigger disable");
        assertTrue(onDisableCalled.get(), "successful disable must invoke onDisable for cleanup");
        Field fWand = ChunkLandPlugin.class.getDeclaredField("wandSafetyListener");
        fWand.setAccessible(true);
        assertNull(fWand.get(plugin), "listener must be cleared after registration failure");
        Field fCap = ChunkLandPlugin.class.getDeclaredField("capabilities");
        fCap.setAccessible(true);
        var capOpt = (java.util.Optional<?>) fCap.get(plugin);
        assertTrue(capOpt.isEmpty(), "M0 capabilities must be released on registration-failure shutdown");
        Field fLandCmd = ChunkLandPlugin.class.getDeclaredField("landCommand");
        fLandCmd.setAccessible(true);
        assertNull(fLandCmd.get(plugin), "landCommand must be cleared after failure");
        Field fPipeline = ChunkLandPlugin.class.getDeclaredField("landMessagePipeline");
        fPipeline.setAccessible(true);
        assertTrue(((java.util.Optional<?>) fPipeline.get(plugin)).isEmpty(), "land pipeline must be cleared");
        // full cleanup via onDisable: message/capability/config/bridge
        for (String field : new String[]{"messagePipeline", "capabilityProbe", "configService"}) {
            Field f = ChunkLandPlugin.class.getDeclaredField(field);
            f.setAccessible(true);
            var opt = (java.util.Optional<?>) f.get(plugin);
            assertTrue(opt.isEmpty(), field + " must be cleared by full cleanup");
        }
        assertNull(plugin.getBridge().getApi(), "bridge must be released");
        assertFalse(plugin.getBridge().isAcquired(), "bridge must not be acquired after cleanup");
    }

    @Test
    void listenerRegistrationFailureWithDisableNoOpPropagates() throws Exception {
        PluginManager pm = (PluginManager) Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class[]{PluginManager.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("registerEvents")) throw new RuntimeException("register boom");
                    if (n.equals("disablePlugin")) return null; // no-op, does not disable
                    if (n.equals("isPluginEnabled")) return true; // remains enabled
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        com.smile.acelib.AceLibApi readyApi = readyApi();
        com.smile.acelib.AceLibApi.AceLibProvider provider = (com.smile.acelib.AceLibApi.AceLibProvider) Proxy.newProxyInstance(
                com.smile.acelib.AceLibApi.AceLibProvider.class.getClassLoader(),
                new Class[]{com.smile.acelib.AceLibApi.AceLibProvider.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("api")) return readyApi;
                    return null;
                });
        org.bukkit.plugin.ServicesManager sm = (org.bukkit.plugin.ServicesManager) Proxy.newProxyInstance(
                org.bukkit.plugin.ServicesManager.class.getClassLoader(),
                new Class[]{org.bukkit.plugin.ServicesManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getRegistration")) {
                        return new org.bukkit.plugin.RegisteredServiceProvider<>(
                                com.smile.acelib.AceLibApi.AceLibProvider.class, provider,
                                org.bukkit.plugin.ServicePriority.Normal, null);
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class[]{Server.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("getServicesManager")) return sm;
                    if (n.equals("getPluginManager")) return pm;
                    if (n.equals("getLogger")) return java.util.logging.Logger.getLogger("Test");
                    if (n.equals("getDataFolder")) return new java.io.File(System.getProperty("java.io.tmpdir"));
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        ChunkLandPlugin plugin = allocatePlugin();
        Field serverField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(plugin, server);
        Field loggerField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        loggerField.set(plugin, java.util.logging.Logger.getLogger("Test"));
        try {
            Field df = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("dataFolder");
            df.setAccessible(true);
            df.set(plugin, new java.io.File(System.getProperty("java.io.tmpdir"), "chunkland-test-" + System.nanoTime()));
        } catch (NoSuchFieldException ignored) {}

        try {
            plugin.onEnable();
            fail("disable no-op must propagate as terminal");
        } catch (IllegalStateException expected) {
            // full cleanup must have run before terminal propagation
            for (String field : new String[]{"messagePipeline", "capabilityProbe", "landMessagePipeline", "capabilities", "configService"}) {
                Field f = ChunkLandPlugin.class.getDeclaredField(field);
                f.setAccessible(true);
                var opt = (java.util.Optional<?>) f.get(plugin);
                assertTrue(opt.isEmpty(), field + " must be cleared even when disable is no-op");
            }
            Field fWand = ChunkLandPlugin.class.getDeclaredField("wandSafetyListener");
            fWand.setAccessible(true);
            assertNull(fWand.get(plugin), "listener must be cleared even when disable is no-op");
            Field fCmd = ChunkLandPlugin.class.getDeclaredField("landCommand");
            fCmd.setAccessible(true);
            assertNull(fCmd.get(plugin), "landCommand must be cleared even when disable is no-op");
            assertNull(plugin.getBridge().getApi(), "bridge must be released even when disable is no-op");
        }
    }

    @Test
    void listenerRegistrationFailureWithDisableFailurePropagates() throws Exception {
        PluginManager pm = (PluginManager) Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class[]{PluginManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("registerEvents")) throw new RuntimeException("register boom");
                    if (method.getName().equals("disablePlugin")) throw new RuntimeException("disable boom");
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        com.smile.acelib.AceLibApi readyApi = readyApi();
        com.smile.acelib.AceLibApi.AceLibProvider provider = (com.smile.acelib.AceLibApi.AceLibProvider) Proxy.newProxyInstance(
                com.smile.acelib.AceLibApi.AceLibProvider.class.getClassLoader(),
                new Class[]{com.smile.acelib.AceLibApi.AceLibProvider.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("api")) return readyApi;
                    return null;
                });
        org.bukkit.plugin.ServicesManager sm = (org.bukkit.plugin.ServicesManager) Proxy.newProxyInstance(
                org.bukkit.plugin.ServicesManager.class.getClassLoader(),
                new Class[]{org.bukkit.plugin.ServicesManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getRegistration")) {
                        return new org.bukkit.plugin.RegisteredServiceProvider<>(
                                com.smile.acelib.AceLibApi.AceLibProvider.class, provider,
                                org.bukkit.plugin.ServicePriority.Normal, null);
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class[]{Server.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("getServicesManager")) return sm;
                    if (n.equals("getPluginManager")) return pm;
                    if (n.equals("getLogger")) return java.util.logging.Logger.getLogger("Test");
                    if (n.equals("getDataFolder")) return new java.io.File(System.getProperty("java.io.tmpdir"));
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        ChunkLandPlugin plugin = allocatePlugin();
        Field serverField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(plugin, server);
        Field loggerField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        loggerField.set(plugin, java.util.logging.Logger.getLogger("Test"));
        try {
            Field df = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("dataFolder");
            df.setAccessible(true);
            df.set(plugin, new java.io.File(System.getProperty("java.io.tmpdir"), "chunkland-test-" + System.nanoTime()));
        } catch (NoSuchFieldException ignored) {}

        try {
            plugin.onEnable();
            fail("disable failure must propagate as terminal");
        } catch (IllegalStateException expected) {
            for (String field : new String[]{"messagePipeline", "capabilityProbe", "landMessagePipeline", "capabilities", "configService"}) {
                Field f = ChunkLandPlugin.class.getDeclaredField(field);
                f.setAccessible(true);
                var opt = (java.util.Optional<?>) f.get(plugin);
                assertTrue(opt.isEmpty(), field + " must be cleared even when disable throws");
            }
            Field fWand = ChunkLandPlugin.class.getDeclaredField("wandSafetyListener");
            fWand.setAccessible(true);
            assertNull(fWand.get(plugin), "listener must be cleared even when disable throws");
            Field fCmd = ChunkLandPlugin.class.getDeclaredField("landCommand");
            fCmd.setAccessible(true);
            assertNull(fCmd.get(plugin), "landCommand must be cleared even when disable throws");
            assertNull(plugin.getBridge().getApi(), "bridge must be released even when disable throws");
        }
    }

    @Test
    void sendWandSelectionMessageForwardsWidthHeightVarsThroughThePipeline() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        java.util.List<net.kyori.adventure.text.Component> sent = new java.util.ArrayList<>();
        Map<String, Object> capturedVars = new java.util.concurrent.ConcurrentHashMap<>();
        ChunkLandMessagePipeline.PipelineSender sender = new ChunkLandMessagePipeline.PipelineSender() {
            @Override public void sendChat(org.bukkit.entity.Player p, net.kyori.adventure.text.Component m) {
                sent.add(m);
            }
            @Override public void sendChatWithFallback(org.bukkit.entity.Player p, net.kyori.adventure.text.Component m, java.util.Locale l) {
                sent.add(m);
            }
            @Override public void sendActionBar(org.bukkit.entity.Player p, net.kyori.adventure.text.Component m) { }
            @Override public void sendActionBarWithFallback(org.bukkit.entity.Player p, net.kyori.adventure.text.Component m, java.util.Locale l) { }
            @Override public void sendTitle(org.bukkit.entity.Player p, net.kyori.adventure.text.Component t, net.kyori.adventure.text.Component s) { }
            @Override public void sendTitleWithFallback(org.bukkit.entity.Player p, net.kyori.adventure.text.Component t, net.kyori.adventure.text.Component s, java.util.Locale l) { }
            @Override public void broadcastWithFallback(net.kyori.adventure.text.Component m, java.util.Locale l) { }
        };
        ChunkLandMessagePipeline.MessageParser parser = (template, vars) -> {
            capturedVars.putAll(vars);
            return net.kyori.adventure.text.Component.text("x");
        };
        ChunkLandMessagePipeline.LangProvider lang = (locale, key) -> java.util.Optional.of("size <width>×<height>");
        ChunkLandMessagePipeline pipeline = pipeline(sender, parser, lang);

        java.lang.reflect.Field fPipeline = ChunkLandPlugin.class.getDeclaredField("landMessagePipeline");
        fPipeline.setAccessible(true);
        fPipeline.set(plugin, java.util.Optional.of(pipeline));

        java.lang.reflect.Method send = ChunkLandPlugin.class.getDeclaredMethod(
                "sendWandSelectionMessage", org.bukkit.entity.Player.class, String.class, Map.class);
        send.setAccessible(true);
        send.invoke(plugin, fakePlayer(), "selection.wand.second_point", Map.of("width", 2, "height", 3));

        assertEquals(Map.of("width", 2, "height", 3), capturedVars,
                "the plugin helper must hand the width/height vars to the shared pipeline");
        assertEquals(1, sent.size(), "the prompt must actually leave through the pipeline sender");
    }

    @Test
    void sendWandSelectionMessageStaysSafeWithoutPipelineOrOnFailure() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        java.lang.reflect.Field fPipeline = ChunkLandPlugin.class.getDeclaredField("landMessagePipeline");
        fPipeline.setAccessible(true);
        fPipeline.set(plugin, java.util.Optional.empty());

        java.lang.reflect.Method send = ChunkLandPlugin.class.getDeclaredMethod(
                "sendWandSelectionMessage", org.bukkit.entity.Player.class, String.class, Map.class);
        send.setAccessible(true);
        // Missing pipeline: silent fail-closed, never throws onto the event path.
        send.invoke(plugin, fakePlayer(), "selection.wand.second_point", Map.of("width", 2, "height", 3));

        // Failing pipeline: the prompt failure must not propagate either.
        ChunkLandMessagePipeline pipeline = pipeline(
                new ChunkLandMessagePipeline.PipelineSender() {
                    @Override public void sendChat(org.bukkit.entity.Player p, net.kyori.adventure.text.Component m) {
                        throw new IllegalStateException("boom");
                    }
                    @Override public void sendChatWithFallback(org.bukkit.entity.Player p, net.kyori.adventure.text.Component m, java.util.Locale l) {
                        throw new IllegalStateException("boom");
                    }
                    @Override public void sendActionBar(org.bukkit.entity.Player p, net.kyori.adventure.text.Component m) { }
                    @Override public void sendActionBarWithFallback(org.bukkit.entity.Player p, net.kyori.adventure.text.Component m, java.util.Locale l) { }
                    @Override public void sendTitle(org.bukkit.entity.Player p, net.kyori.adventure.text.Component t, net.kyori.adventure.text.Component s) { }
                    @Override public void sendTitleWithFallback(org.bukkit.entity.Player p, net.kyori.adventure.text.Component t, net.kyori.adventure.text.Component s, java.util.Locale l) { }
                    @Override public void broadcastWithFallback(net.kyori.adventure.text.Component m, java.util.Locale l) { }
                },
                (template, vars) -> net.kyori.adventure.text.Component.text("x"),
                (locale, key) -> java.util.Optional.of("size <width>×<height>"));
        fPipeline.set(plugin, java.util.Optional.of(pipeline));
        send.invoke(plugin, fakePlayer(), "selection.wand.second_point", Map.of("width", 2, "height", 3));
    }

    /** Package-private pipeline constructor, reached reflectively from this test package. */
    private static ChunkLandMessagePipeline pipeline(
            ChunkLandMessagePipeline.PipelineSender sender,
            ChunkLandMessagePipeline.MessageParser parser,
            ChunkLandMessagePipeline.LangProvider lang) throws Exception {
        var ctor = ChunkLandMessagePipeline.class.getDeclaredConstructor(
                ChunkLandMessagePipeline.PipelineSender.class,
                ChunkLandMessagePipeline.MessageParser.class,
                ChunkLandMessagePipeline.LangProvider.class,
                com.smile.acelib.bedrock.BedrockService.class,
                java.util.Locale.class);
        ctor.setAccessible(true);
        return ctor.newInstance(sender, parser, lang, null, java.util.Locale.US);
    }

    private static org.bukkit.entity.Player fakePlayer() {
        return (org.bukkit.entity.Player) Proxy.newProxyInstance(
                org.bukkit.entity.Player.class.getClassLoader(),
                new Class[]{org.bukkit.entity.Player.class},
                (proxy, method, args) -> {
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
    }

    @Test
    void wandFactoryProducesIndependentInstancesWithGlintAndExactPdc() throws Exception {
        // Check WandFactory source uses STICK and Glint and exact PDC without needing server
        String src = java.nio.file.Files.readString(java.nio.file.Paths.get("src/main/java/com/smile/chunkland/wand/WandFactory.java"));
        assertTrue(src.contains("Material.STICK"), "Factory must create STICK");
        assertTrue(src.contains("withEnchantment") || src.contains("UNBREAKING"), "Factory must apply Glint via UNBREAKING");
        assertTrue(src.contains("WandKeys.ITEM_TYPE"), "Factory must write exact PDC item_type");
        assertTrue(src.contains("WandKeys.SCHEMA_VERSION"), "Factory must write exact schema_version");
        // Check configure produces independent instances
        var pdc1 = TestWandHelpers.fakePdc();
        var meta1 = TestWandHelpers.fakeMeta(pdc1);
        var pdc2 = TestWandHelpers.fakePdc();
        var meta2 = TestWandHelpers.fakeMeta(pdc2);
        // use WandFactory.configure directly (package-private via reflection)
        Method cfg = com.smile.chunkland.wand.WandFactory.class.getDeclaredMethod("configure", org.bukkit.inventory.meta.ItemMeta.class);
        cfg.setAccessible(true);
        cfg.invoke(null, meta1);
        cfg.invoke(null, meta2);
        assertTrue(com.smile.chunkland.wand.WandIdentity.isWand(meta1));
        assertTrue(com.smile.chunkland.wand.WandIdentity.isWand(meta2));
        pdc1.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, "tampered");
        assertFalse(com.smile.chunkland.wand.WandIdentity.isWand(meta1));
        assertTrue(com.smile.chunkland.wand.WandIdentity.isWand(meta2));
    }
}

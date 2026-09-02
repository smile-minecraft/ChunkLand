package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.LandPermissions;
import com.smile.chunkland.command.PipelineReplySink;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class ChunkLandPluginLandWiringTest {

    @SuppressWarnings("unchecked")
    private static ChunkLandPlugin allocatePluginWithoutConstructor() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        ChunkLandPlugin p = (ChunkLandPlugin) unsafe.allocateInstance(ChunkLandPlugin.class);
        for (var f : ChunkLandPlugin.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getType() == java.util.Optional.class && f.get(p) == null) {
                f.set(p, java.util.Optional.empty());
            }
        }
        return p;
    }

    private static CommandSender senderWithPerms(Map<String,Boolean> perms, CopyOnWriteArrayList<String> out) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class[]{CommandSender.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("sendMessage")) {
                        if (args != null) for (Object a: args) if (a!=null) out.add(String.valueOf(a));
                        return null;
                    }
                    if (n.equals("hasPermission")) {
                        if (args!=null && args[0] instanceof String p) return perms.getOrDefault(p,false);
                        return false;
                    }
                    if (n.equals("isPermissionSet")) return true;
                    if (n.equals("getName")) return "Sender";
                    if (n.equals("equals")||n.equals("hashCode")||n.equals("toString")) {
                        return switch(n){case "equals"->proxy==args[0];case "hashCode"->System.identityHashCode(proxy);default->"Sender-proxy";};
                    }
                    Class<?> rt=method.getReturnType();
                    if (rt==boolean.class) return false;
                    if (rt==int.class) return 0;
                    if (rt==long.class) return 0L;
                    if (rt==double.class) return 0d;
                    if (rt==float.class) return 0f;
                    return null;
                });
    }

    @Test
    void onCommandLandBranchIsWired() throws Exception {
        List<String> keys = new ArrayList<>();
        LandCommand cmd = new LandCommand(LandCommand.defaultStubHandlers(), (s,p)-> new ReplySink(){
            public void reply(String k, Map<String,Object> v){ keys.add(k); }
            public void reply(String k, Map<String,Object> v, java.util.Locale l){ keys.add(k); }
        });
        ChunkLandPlugin plugin = allocatePluginWithoutConstructor();
        plugin.setLandCommandForTest(cmd);
        plugin.setLandMessagePipelineForTest(null);

        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CommandSender allowed = senderWithPerms(Map.of(LandPermissions.HELP, true), out);
        Command land = ChunkLandPlugin.commandForTest("land");
        assertTrue(plugin.onCommand(allowed, land, "land", new String[]{}));
        assertTrue(keys.contains("command.land.help"));

        keys.clear();
        CommandSender denied = senderWithPerms(Map.of(LandPermissions.HELP, false), out);
        assertTrue(plugin.onCommand(denied, land, "land", new String[]{}));
        assertTrue(keys.contains("command.land.denied"));

        // unknown command returns false
        Command unknown = ChunkLandPlugin.commandForTest("unknowncmd");
        assertFalse(plugin.onCommand(allowed, unknown, "unknowncmd", new String[]{}));
    }

    @Test
    void onCommandLandUnknownSubcommandStillTrue() throws Exception {
        List<String> keys = new ArrayList<>();
        LandCommand cmd = new LandCommand(LandCommand.defaultStubHandlers(), (s,p)-> new ReplySink(){
            public void reply(String k, Map<String,Object> v){ keys.add(k); }
            public void reply(String k, Map<String,Object> v, java.util.Locale l){ keys.add(k); }
        });
        ChunkLandPlugin plugin = allocatePluginWithoutConstructor();
        plugin.setLandCommandForTest(cmd);
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CommandSender sender = senderWithPerms(Map.of(), out);
        Command land = ChunkLandPlugin.commandForTest("land");
        assertTrue(plugin.onCommand(sender, land, "land", new String[]{"foobar"}));
        assertTrue(keys.contains("command.land.unknown"));
    }

    @Test
    void existingChunklandM0StillFalseForUnknown() throws Exception {
        ChunkLandPlugin plugin = allocatePluginWithoutConstructor();
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CommandSender sender = senderWithPerms(Map.of(), out);
        Command chunkland = ChunkLandPlugin.commandForTest("chunkland");
        // unknown subcommand for /chunkland must return false
        assertFalse(plugin.onCommand(sender, chunkland, "chunkland", new String[]{"unknown"}));
        assertFalse(plugin.onCommand(sender, chunkland, "chunkland", new String[]{}));
    }

    @Test
    void onTabCompleteLandBranch() throws Exception {
        ChunkLandPlugin plugin = allocatePluginWithoutConstructor();
        plugin.setLandCommandForTest(new LandCommand(LandCommand.defaultStubHandlers(), null));
        CopyOnWriteArrayList<String> out = new CopyOnWriteArrayList<>();
        CommandSender sender = senderWithPerms(Map.of(), out);
        Command land = ChunkLandPlugin.commandForTest("land");
        List<String> all = plugin.onTabComplete(sender, land, "land", new String[]{""});
        assertTrue(all.containsAll(LandCommand.SUBCOMMANDS));
        List<String> c = plugin.onTabComplete(sender, land, "land", new String[]{"c"});
        assertTrue(c.contains("claim"));
        assertEquals(List.of(), plugin.onTabComplete(sender, land, "land", new String[]{"claim","extra"}));
    }
}

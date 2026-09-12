package com.smile.chunkland.wand;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.command.ReplySink;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

class WandGiveHandlerTest {

    private static class CaptureSink implements ReplySink {
        List<String> keys = new ArrayList<>();
        List<Map<String, Object>> vars = new ArrayList<>();
        @Override public void reply(String k, Map<String, Object> v) { keys.add(k); vars.add(v); }
        @Override public void reply(String k, Map<String, Object> v, java.util.Locale l) { keys.add(k); vars.add(v); }
    }

    private static Player playerWithInventory(PlayerInventory inv) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("getInventory")) return inv;
                    if (n.equals("hasPermission")) return true;
                    if (n.equals("getName")) return "TestPlayer";
                    if (n.equals("equals")) return proxy == args[0];
                    if (n.equals("hashCode")) return System.identityHashCode(proxy);
                    if (n.equals("toString")) return "Player-proxy";
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
    }

    private static PlayerInventory inventoryWithFirstEmpty(int firstEmpty, Map<Integer, ItemStack> addResult) {
        return (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(),
                new Class[]{PlayerInventory.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("firstEmpty")) return firstEmpty;
                    if (n.equals("addItem")) {
                        // return addResult (empty map means success)
                        return addResult;
                    }
                    if (n.equals("getItemInMainHand")) {
                        // not used in give handler
                        return null;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
    }

    private static CommandSender consoleSender() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class[]{CommandSender.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("hasPermission")) return true;
                    if (n.equals("getName")) return "Console";
                    if (n.equals("equals")) return proxy == args[0];
                    if (n.equals("hashCode")) return System.identityHashCode(proxy);
                    if (n.equals("toString")) return "Console-proxy";
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
    }

    @Test
    void consoleGetsConsoleMessageAndNoInventoryTouch() {
        CaptureSink sink = new CaptureSink();
        // track whether inventory was touched
        boolean[] touched = {false};
        PlayerInventory inv = (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(),
                new Class[]{PlayerInventory.class},
                (proxy, method, args) -> {
                    touched[0] = true;
                    return null;
                });
        // console is not a Player, so inventory should never be accessed
        WandGiveHandler handler = new WandGiveHandler() {
            @Override
            public void handle(CommandSender sender, String[] args, ReplySink s) {
                // use real handler but ensure no NPE when sender is console
                super.handle(sender, args, s);
            }
        };
        CommandSender console = consoleSender();
        handler.handle(console, new String[]{}, sink);
        assertTrue(sink.keys.contains("command.land.wand.console"));
        assertFalse(touched[0], "console must not touch inventory");
    }

    @Test
    void fullInventoryReportsFullAndDoesNotAdd() {
        CaptureSink sink = new CaptureSink();
        PlayerInventory inv = inventoryWithFirstEmpty(-1, Map.of());
        Player player = playerWithInventory(inv);
        WandGiveHandler handler = new WandGiveHandler();
        handler.handle(player, new String[]{}, sink);
        assertTrue(sink.keys.contains("command.land.wand.inventory_full"));
        assertFalse(sink.keys.contains("command.land.wand.given"));
    }

    @Test
    void successfulGiveRepliesGiven() {
        // Use creator seam to avoid Material registry; exercise production handle logic
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        // configure fake wand PDC via factory helper without needing Material
        WandFactory.configure(meta);
        FakeItemStack fakeWand = new FakeItemStack(meta);

        class SeamedHandler extends WandGiveHandler {
            @Override protected ItemStack createWand() { return fakeWand; }
        }

        CaptureSink sink = new CaptureSink();
        // capture addItem argument
        List<ItemStack> added = new ArrayList<>();
        PlayerInventory inv = (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(),
                new Class[]{PlayerInventory.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("firstEmpty")) return 0;
                    if (n.equals("addItem")) {
                        if (args != null) {
                            for (Object arg : args) {
                                if (arg instanceof ItemStack s) added.add(s);
                                else if (arg instanceof ItemStack[] arr) for (ItemStack s : arr) if (s != null) added.add(s);
                            }
                        }
                        return new HashMap<Integer, ItemStack>();
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        Player player = playerWithInventory(inv);
        SeamedHandler h = new SeamedHandler();
        h.handle(player, new String[]{}, sink);
        assertTrue(sink.keys.contains("command.land.wand.given"));
        assertEquals(1, added.size());
        assertTrue(WandIdentity.isWand(added.get(0).getItemMeta()));
    }

    private static PlayerInventory inventoryWithContents(ItemStack[] contents, List<ItemStack> added) {
        return (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(),
                new Class[]{PlayerInventory.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("getContents")) return contents;
                    if (n.equals("getStorageContents")) return contents;
                    if (n.equals("firstEmpty")) {
                        if (contents == null) return 0;
                        for (int i = 0; i < contents.length; i++) {
                            if (contents[i] == null) return i;
                        }
                        return -1;
                    }
                    if (n.equals("addItem")) {
                        if (args != null) {
                            for (Object arg : args) {
                                if (arg instanceof ItemStack s) added.add(s);
                                else if (arg instanceof ItemStack[] arr) for (ItemStack s : arr) if (s != null) added.add(s);
                            }
                        }
                        return new HashMap<Integer, ItemStack>();
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
    }

    private static FakeItemStack wandItem() {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        WandFactory.configure(meta);
        return new FakeItemStack(meta);
    }

    private static FakeItemStack plainStickItem() {
        return new FakeItemStack(TestWandHelpers.fakeMeta(TestWandHelpers.fakePdc()));
    }

    @Test
    void existingWandIsNotDuplicated() {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        WandFactory.configure(meta);
        FakeItemStack existing = new FakeItemStack(meta);
        List<ItemStack> added = new ArrayList<>();
        PlayerInventory inv = inventoryWithContents(new ItemStack[]{existing, null}, added);
        Player player = playerWithInventory(inv);

        CaptureSink sink = new CaptureSink();
        new WandGiveHandler().handle(player, new String[]{}, sink);

        assertTrue(sink.keys.contains("command.land.wand.already_have"));
        assertFalse(sink.keys.contains("command.land.wand.given"));
        assertTrue(added.isEmpty(), "existing wand must not be duplicated");
    }

    @Test
    void twoExistingWandsStillAddNothing() {
        List<ItemStack> added = new ArrayList<>();
        PlayerInventory inv = inventoryWithContents(new ItemStack[]{wandItem(), wandItem()}, added);
        Player player = playerWithInventory(inv);

        CaptureSink sink = new CaptureSink();
        new WandGiveHandler().handle(player, new String[]{}, sink);

        assertTrue(sink.keys.contains("command.land.wand.already_have"));
        assertTrue(added.isEmpty());
    }

    @Test
    void legacyTwoKeyWandCountsAsExisting() {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, org.bukkit.persistence.PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, org.bukkit.persistence.PersistentDataType.INTEGER,
                WandKeys.SCHEMA_VERSION_VALUE);
        List<ItemStack> added = new ArrayList<>();
        PlayerInventory inv = inventoryWithContents(new ItemStack[]{new FakeItemStack(meta), null}, added);
        Player player = playerWithInventory(inv);

        CaptureSink sink = new CaptureSink();
        new WandGiveHandler().handle(player, new String[]{}, sink);

        assertTrue(sink.keys.contains("command.land.wand.already_have"));
        assertTrue(added.isEmpty(), "legacy wand holders must not receive a second wand");
    }

    @Test
    void nonWandStickStillReceivesOneWand() {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        WandFactory.configure(meta);
        FakeItemStack fresh = new FakeItemStack(meta);
        class SeamedHandler extends WandGiveHandler {
            @Override protected ItemStack createWand() { return fresh; }
        }
        List<ItemStack> added = new ArrayList<>();
        PlayerInventory inv = inventoryWithContents(new ItemStack[]{plainStickItem(), null}, added);
        Player player = playerWithInventory(inv);

        CaptureSink sink = new CaptureSink();
        new SeamedHandler().handle(player, new String[]{}, sink);

        assertTrue(sink.keys.contains("command.land.wand.given"));
        assertFalse(sink.keys.contains("command.land.wand.already_have"));
        assertEquals(1, added.size());
    }

    @Test
    void factoryFailureMapsToError() {
        class FailingHandler extends WandGiveHandler {
            @Override protected ItemStack createWand() { throw new RuntimeException("factory boom"); }
        }
        CaptureSink sink = new CaptureSink();
        PlayerInventory inv = inventoryWithFirstEmpty(0, new HashMap<>());
        Player player = playerWithInventory(inv);
        FailingHandler h = new FailingHandler();
        h.handle(player, new String[]{}, sink);
        assertTrue(sink.keys.contains("command.land.wand.error"));
        assertFalse(sink.keys.contains("command.land.wand.given"));
        assertFalse(sink.keys.contains("command.land.wand.inventory_full"));
    }

    @Test
    void permissionGateIsViaLandCommand() {
        // Check that /land wand respects LandPermissions.WAND via LandCommand dispatch
        var permsDenied = Map.of(com.smile.chunkland.command.LandPermissions.WAND, false);
        var permsAllowed = Map.of(com.smile.chunkland.command.LandPermissions.WAND, true);
        List<String> keys = new ArrayList<>();
        com.smile.chunkland.command.LandCommand cmdDenied = new com.smile.chunkland.command.LandCommand(
                Map.of("wand", (s,a,sink)-> sink.reply("command.land.wand.given", Map.of())),
                (s,p)-> new ReplySink(){
                    public void reply(String k, Map<String,Object> v){ keys.add(k); }
                    public void reply(String k, Map<String,Object> v, java.util.Locale l){ keys.add(k); }
                });
        CommandSender denied = (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class[]{CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission") && args[0].equals(com.smile.chunkland.command.LandPermissions.WAND)) return false;
                    if (method.getName().equals("isPermissionSet")) return true;
                    if (method.getName().equals("getName")) return "Sender";
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        assertTrue(cmdDenied.dispatch(denied, new String[]{"wand"}, null));
        assertTrue(keys.contains("command.land.denied"));

        keys.clear();
        CommandSender allowed = (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class[]{CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission") && args[0].equals(com.smile.chunkland.command.LandPermissions.WAND)) return true;
                    if (method.getName().equals("isPermissionSet")) return true;
                    if (method.getName().equals("getName")) return "Sender";
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        com.smile.chunkland.command.LandCommand cmdAllowed = new com.smile.chunkland.command.LandCommand(
                Map.of("wand", (s,a,sink)-> sink.reply("command.land.wand.given", Map.of())),
                (s,p)-> new ReplySink(){
                    public void reply(String k, Map<String,Object> v){ keys.add(k); }
                    public void reply(String k, Map<String,Object> v, java.util.Locale l){ keys.add(k); }
                });
        assertTrue(cmdAllowed.dispatch(allowed, new String[]{"wand"}, null));
        assertTrue(keys.contains("command.land.wand.given"));
    }
}

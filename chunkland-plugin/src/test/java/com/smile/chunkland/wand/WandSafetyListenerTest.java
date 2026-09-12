package com.smile.chunkland.wand;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class WandSafetyListenerTest {

    private static Player playerWithMainHand(FakeItemStack stack) {
        PlayerInventory inv = (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(),
                new Class[]{PlayerInventory.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getItemInMainHand")) return stack;
                    if (method.getName().equals("getItemInOffHand")) return null;
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getInventory")) return inv;
                    if (method.getName().equals("getName")) return "TestPlayer";
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
    }

    private static Block fakeBlock() {
        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class[]{Block.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("toString")) return "FakeBlock";
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
    }

    private static BlockDamageEvent createDamageEvent(Player player, Block block, FakeItemStack itemInHand, boolean instaBreak, boolean cancelled) throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        BlockDamageEvent ev = (BlockDamageEvent) unsafe.allocateInstance(BlockDamageEvent.class);
        // set block (super)
        Field fBlock = org.bukkit.event.block.BlockEvent.class.getDeclaredField("block");
        fBlock.setAccessible(true);
        fBlock.set(ev, block);
        Field fPlayer = BlockDamageEvent.class.getDeclaredField("player");
        fPlayer.setAccessible(true);
        fPlayer.set(ev, player);
        Field fItem = BlockDamageEvent.class.getDeclaredField("itemInHand");
        fItem.setAccessible(true);
        fItem.set(ev, itemInHand);
        Field fInsta = BlockDamageEvent.class.getDeclaredField("instaBreak");
        fInsta.setAccessible(true);
        fInsta.set(ev, instaBreak);
        Field fCancelled = BlockDamageEvent.class.getDeclaredField("cancelled");
        fCancelled.setAccessible(true);
        fCancelled.set(ev, cancelled);
        // blockFace may be null
        try {
            Field fFace = BlockDamageEvent.class.getDeclaredField("blockFace");
            fFace.setAccessible(true);
            fFace.set(ev, BlockFace.NORTH);
        } catch (Exception ignored) {}
        return ev;
    }

    private static BlockBreakEvent createBreakEvent(Block block, Player player, boolean cancelled) throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        BlockBreakEvent ev = (BlockBreakEvent) unsafe.allocateInstance(BlockBreakEvent.class);
        Field fBlock = org.bukkit.event.block.BlockEvent.class.getDeclaredField("block");
        fBlock.setAccessible(true);
        fBlock.set(ev, block);
        Field fPlayer = BlockBreakEvent.class.getDeclaredField("player");
        fPlayer.setAccessible(true);
        fPlayer.set(ev, player);
        Field fCancelled = BlockBreakEvent.class.getDeclaredField("cancelled");
        fCancelled.setAccessible(true);
        fCancelled.set(ev, cancelled);
        return ev;
    }

    @Test
    void annotationHasHighestAndIgnoreCancelled() throws Exception {
        var m1 = WandSafetyListener.class.getDeclaredMethod("onBlockDamage", BlockDamageEvent.class);
        EventHandler h1 = m1.getAnnotation(EventHandler.class);
        assertNotNull(h1);
        assertEquals(EventPriority.HIGHEST, h1.priority());
        assertTrue(h1.ignoreCancelled());

        var m2 = WandSafetyListener.class.getDeclaredMethod("onBlockBreak", BlockBreakEvent.class);
        EventHandler h2 = m2.getAnnotation(EventHandler.class);
        assertNotNull(h2);
        assertEquals(EventPriority.HIGHEST, h2.priority());
        assertTrue(h2.ignoreCancelled());
    }

    @Test
    void nonWandDoesNotCancelDamage() throws Exception {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        // non-wand: no PDC
        FakeItemStack nonWand = new FakeItemStack(meta);
        Player player = playerWithMainHand(nonWand);
        Block block = fakeBlock();
        BlockDamageEvent ev = createDamageEvent(player, block, nonWand, false, false);
        WandSafetyListener listener = new WandSafetyListener();
        listener.onBlockDamage(ev);
        assertFalse(ev.isCancelled());
    }

    @Test
    void wandCancelsDamageAndBreak() throws Exception {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 1);
        FakeItemStack wand = new FakeItemStack(meta);
        Player player = playerWithMainHand(wand);
        Block block = fakeBlock();

        BlockDamageEvent dmg = createDamageEvent(player, block, wand, false, false);
        WandSafetyListener l = new WandSafetyListener();
        l.onBlockDamage(dmg);
        assertTrue(dmg.isCancelled(), "wand should cancel BlockDamageEvent");

        BlockBreakEvent brk = createBreakEvent(block, player, false);
        l.onBlockBreak(brk);
        assertTrue(brk.isCancelled(), "wand should cancel BlockBreakEvent");
    }

    @Test
    void creativeInstaBreakAlsoCancelled() throws Exception {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 1);
        FakeItemStack wand = new FakeItemStack(meta);
        Player player = playerWithMainHand(wand);
        Block block = fakeBlock();
        BlockDamageEvent ev = createDamageEvent(player, block, wand, true, false);
        WandSafetyListener l = new WandSafetyListener();
        l.onBlockDamage(ev);
        assertTrue(ev.isCancelled());
        assertTrue(ev.getInstaBreak(), "instaBreak flag preserved but event cancelled");
    }

    @Test
    void alreadyCancelledEventStaysCancelledAndNoClick() throws Exception {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 1);
        FakeItemStack wand = new FakeItemStack(meta);
        Player player = playerWithMainHand(wand);
        Block block = fakeBlock();
        int[] count = {0};
        WandSafetyListener l = new WandSafetyListener(ctx -> count[0]++);
        BlockDamageEvent ev = createDamageEvent(player, block, wand, false, true);
        l.onBlockDamage(ev);
        assertTrue(ev.isCancelled());
        assertEquals(0, count[0], "already cancelled should be ignored (ignoreCancelled)");

        BlockBreakEvent br = createBreakEvent(block, player, true);
        l.onBlockBreak(br);
        assertTrue(br.isCancelled());
        assertEquals(0, count[0]);
    }

    @Test
    void failClosedOnBackendException() throws Exception {
        Block block = fakeBlock();
        // backend failure via getItemMeta throwing (callback exception) must fail closed
        FakeItemStack throwingStack = new FakeItemStack(null) {
            @Override public org.bukkit.inventory.meta.ItemMeta getItemMeta() { throw new RuntimeException("boom"); }
        };
        Player p2 = playerWithMainHand(throwingStack);
        WandSafetyListener l = new WandSafetyListener();
        BlockDamageEvent ev2 = createDamageEvent(p2, block, throwingStack, false, false);
        l.onBlockDamage(ev2);
        assertTrue(ev2.isCancelled(), "backend exception must fail closed");

        BlockBreakEvent br2 = createBreakEvent(block, p2, false);
        l.onBlockBreak(br2);
        assertTrue(br2.isCancelled(), "backend exception must fail closed on break");

        // backend failure via PDC proxy throwing must also fail closed
        var badMeta = TestWandHelpers.fakeMetaWithException();
        FakeItemStack pdcFailStack = new FakeItemStack(badMeta);
        Player p3 = playerWithMainHand(pdcFailStack);
        BlockDamageEvent ev3 = createDamageEvent(p3, block, pdcFailStack, false, false);
        l.onBlockDamage(ev3);
        assertTrue(ev3.isCancelled(), "PDC backend exception must fail closed");

        BlockBreakEvent br3 = createBreakEvent(block, p3, false);
        l.onBlockBreak(br3);
        assertTrue(br3.isCancelled(), "PDC backend exception must fail closed on break");
    }

    @Test
    void pdcProxyExceptionPropagatesToListenerButMalformedIsFalse() throws Exception {
        // malformed wrong value must return false (not throw) and not cancel
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, "wrong");
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 1);
        assertFalse(WandIdentity.isWand(meta));
        FakeItemStack malformedStack = new FakeItemStack(meta);
        Player player = playerWithMainHand(malformedStack);
        Block block = fakeBlock();
        WandSafetyListener l = new WandSafetyListener();
        BlockDamageEvent ev = createDamageEvent(player, block, malformedStack, false, false);
        l.onBlockDamage(ev);
        assertFalse(ev.isCancelled(), "malformed should not cancel");

        // backend PDC exception must propagate and cause cancel (tested above) - verify identity throws
        var throwingMeta = TestWandHelpers.fakeMetaWithException();
        assertThrows(RuntimeException.class, () -> WandIdentity.isWand(throwingMeta));
    }

    @Test
    void clickSeamNoOpDoesNotCreateState() throws Exception {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 1);
        FakeItemStack wand = new FakeItemStack(meta);
        Player player = playerWithMainHand(wand);
        Block block = fakeBlock();
        BlockDamageEvent ev = createDamageEvent(player, block, wand, false, false);
        WandSafetyListener noop = new WandSafetyListener();
        noop.onBlockDamage(ev);
        assertTrue(ev.isCancelled());
        // no exception, no state created; just ensure handler didn't throw
        BlockBreakEvent br = createBreakEvent(block, player, false);
        noop.onBlockBreak(br);
        assertTrue(br.isCancelled());
    }

    @Test
    void damageAndBreakAreSafetyOnlyWithoutCallback() throws Exception {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 1);
        FakeItemStack wand = new FakeItemStack(meta);
        Player player = playerWithMainHand(wand);
        Block block = fakeBlock();
        int[] useCount = {0};
        int[] rightCount = {0};
        WandSafetyListener l = new WandSafetyListener(new WandClickHandler() {
            @Override public void onWandUse(Context context) { useCount[0]++; }
            @Override public void onWandRightClick(Player p, Block b, BlockFace face) { rightCount[0]++; }
        });
        BlockDamageEvent ev = createDamageEvent(player, block, wand, false, false);
        l.onBlockDamage(ev);
        assertTrue(ev.isCancelled(), "wand damage must stay cancelled");
        BlockBreakEvent br = createBreakEvent(block, player, false);
        l.onBlockBreak(br);
        assertTrue(br.isCancelled(), "wand break must stay cancelled");
        assertEquals(0, useCount[0], "damage/break must not call selection callback");
        assertEquals(0, rightCount[0]);
    }

    @Test
    void interactLeftAndRightEachCallOnce() {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 1);
        FakeItemStack wand = new FakeItemStack(meta);
        Player player = playerWithMainHand(wand);
        Block block = fakeBlock();
        int[] useCount = {0};
        int[] rightCount = {0};
        WandSafetyListener l = new WandSafetyListener(new WandClickHandler() {
            @Override public void onWandUse(Context context) { useCount[0]++; }
            @Override public void onWandRightClick(Player p, Block b, BlockFace face) { rightCount[0]++; }
        });

        PlayerInteractEvent left = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, wand, block, BlockFace.NORTH, EquipmentSlot.HAND);
        l.onPlayerInteract(left);
        assertTrue(left.isCancelled());
        assertEquals(1, useCount[0], "Creative LEFT interact must call selection once");
        assertEquals(0, rightCount[0]);

        PlayerInteractEvent right = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, wand, block, BlockFace.NORTH, EquipmentSlot.HAND);
        l.onPlayerInteract(right);
        assertTrue(right.isCancelled());
        assertEquals(1, useCount[0]);
        assertEquals(1, rightCount[0], "RIGHT interact must call selection once");
    }

    @Test
    void interactPlusDamageDoesNotDuplicateSelection() throws Exception {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 1);
        FakeItemStack wand = new FakeItemStack(meta);
        Player player = playerWithMainHand(wand);
        Block block = fakeBlock();
        int[] useCount = {0};
        int[] rightCount = {0};
        WandSafetyListener l = new WandSafetyListener(new WandClickHandler() {
            @Override public void onWandUse(Context context) { useCount[0]++; }
            @Override public void onWandRightClick(Player p, Block b, BlockFace face) { rightCount[0]++; }
        });

        // Survival order: interact LEFT then the follow-up damage for the same hit.
        PlayerInteractEvent left = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, wand, block, BlockFace.NORTH, EquipmentSlot.HAND);
        l.onPlayerInteract(left);
        BlockDamageEvent damage = createDamageEvent(player, block, wand, false, false);
        l.onBlockDamage(damage);
        assertTrue(left.isCancelled());
        assertTrue(damage.isCancelled());
        assertEquals(1, useCount[0], "one left hit must record a single corner");
        assertEquals(0, rightCount[0]);
    }

    @Test
    void interactIgnoresOffhandNonWandCancelledAndOtherActions() {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 1);
        FakeItemStack wand = new FakeItemStack(meta);
        FakeItemStack plain = new FakeItemStack(TestWandHelpers.fakeMeta(TestWandHelpers.fakePdc()));
        Player player = playerWithMainHand(wand);
        Block block = fakeBlock();
        int[] useCount = {0};
        int[] rightCount = {0};
        WandSafetyListener l = new WandSafetyListener(new WandClickHandler() {
            @Override public void onWandUse(Context context) { useCount[0]++; }
            @Override public void onWandRightClick(Player p, Block b, BlockFace face) { rightCount[0]++; }
        });

        PlayerInteractEvent offhandLeft = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, wand, block, BlockFace.NORTH, EquipmentSlot.OFF_HAND);
        l.onPlayerInteract(offhandLeft);
        assertFalse(offhandLeft.isCancelled());

        PlayerInteractEvent offhandRight = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, wand, block, BlockFace.NORTH, EquipmentSlot.OFF_HAND);
        l.onPlayerInteract(offhandRight);
        assertFalse(offhandRight.isCancelled());

        PlayerInteractEvent plainLeft = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, plain, block, BlockFace.NORTH, EquipmentSlot.HAND);
        l.onPlayerInteract(plainLeft);
        assertFalse(plainLeft.isCancelled());

        PlayerInteractEvent plainRight = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, plain, block, BlockFace.NORTH, EquipmentSlot.HAND);
        l.onPlayerInteract(plainRight);
        assertFalse(plainRight.isCancelled());

        for (Action action : new Action[]{Action.LEFT_CLICK_AIR, Action.RIGHT_CLICK_AIR, Action.PHYSICAL}) {
            PlayerInteractEvent other = new PlayerInteractEvent(
                    player, action, wand, block, BlockFace.NORTH, EquipmentSlot.HAND);
            l.onPlayerInteract(other);
            assertFalse(other.isCancelled(), "other action must be ignored: " + action);
        }

        PlayerInteractEvent cancelledLeft = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, wand, block, BlockFace.NORTH, EquipmentSlot.HAND);
        cancelledLeft.setCancelled(true);
        l.onPlayerInteract(cancelledLeft);

        PlayerInteractEvent cancelledRight = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, wand, block, BlockFace.NORTH, EquipmentSlot.HAND);
        cancelledRight.setCancelled(true);
        l.onPlayerInteract(cancelledRight);

        PlayerInteractEvent nullBlock = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, wand, null, BlockFace.NORTH, EquipmentSlot.HAND);
        l.onPlayerInteract(nullBlock);
        // Null block has no target: selection must stay untouched regardless of cancel flag.

        assertEquals(0, useCount[0], "ignored interacts must not call selection");
        assertEquals(0, rightCount[0]);
    }

    @Test
    void interactFailClosedOnBackendException() {
        Block block = fakeBlock();
        FakeItemStack throwingStack = new FakeItemStack(null) {
            @Override public org.bukkit.inventory.meta.ItemMeta getItemMeta() { throw new RuntimeException("boom"); }
        };
        Player player = playerWithMainHand(throwingStack);
        int[] useCount = {0};
        WandSafetyListener l = new WandSafetyListener(new WandClickHandler() {
            @Override public void onWandUse(Context context) { useCount[0]++; }
        });

        PlayerInteractEvent left = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, throwingStack, block, BlockFace.NORTH, EquipmentSlot.HAND);
        l.onPlayerInteract(left);
        assertTrue(left.isCancelled(), "interact backend exception must fail closed");

        PlayerInteractEvent right = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, throwingStack, block, BlockFace.NORTH, EquipmentSlot.HAND);
        l.onPlayerInteract(right);
        assertTrue(right.isCancelled(), "interact backend exception must fail closed");
        assertEquals(0, useCount[0]);
    }

    @Test
    void clickSeamExceptionStillCancelled() throws Exception {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 1);
        FakeItemStack wand = new FakeItemStack(meta);
        Player player = playerWithMainHand(wand);
        Block block = fakeBlock();
        WandSafetyListener l = new WandSafetyListener(ctx -> { throw new RuntimeException("click fail"); });
        BlockDamageEvent ev = createDamageEvent(player, block, wand, false, false);
        l.onBlockDamage(ev);
        assertTrue(ev.isCancelled());
        BlockBreakEvent br = createBreakEvent(block, player, false);
        l.onBlockBreak(br);
        assertTrue(br.isCancelled());
    }
}

package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.GuiClickListener;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

/**
 * Bridge contract for {@link GuiClickListener}: the Bukkit event is only
 * read (clicker UUID plus raw slot), the navigator's current generation is
 * the only session identity, an upstream-cancelled button click still routes
 * (the listener observes with {@code ignoreCancelled = false}), and nothing
 * else — untracked players, player-inventory slots, a missing navigator —
 * ever reaches a page action. The bridge never touches Bukkit state and
 * never throws onto the server thread.
 */
class GuiClickListenerTest {

    private static final UUID ALICE = UUID.randomUUID();

    /** Records every Bukkit proxy call by "type#method". */
    private static final class Calls {
        final List<String> names = new CopyOnWriteArrayList<>();
    }

    private static Player playerProxy(UUID uuid, Calls calls) {
        InvocationHandler handler = (proxy, method, args) -> {
            calls.names.add("player#" + method.getName());
            if (method.getName().equals("getUniqueId")) {
                return uuid;
            }
            return defaultValue(method.getReturnType());
        };
        return (Player) Proxy.newProxyInstance(GuiClickListenerTest.class.getClassLoader(),
            new Class<?>[] {Player.class}, handler);
    }

    private static InventoryView viewProxy(HumanEntity who, Calls calls) {
        InvocationHandler handler = (proxy, method, args) -> {
            calls.names.add("view#" + method.getName());
            if (method.getName().equals("getPlayer")) {
                return who;
            }
            return defaultValue(method.getReturnType());
        };
        return (InventoryView) Proxy.newProxyInstance(GuiClickListenerTest.class.getClassLoader(),
            new Class<?>[] {InventoryView.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        return 0;
    }

    private static InventoryClickEvent clickEvent(InventoryView view, int rawSlot) {
        return new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot,
            ClickType.LEFT, InventoryAction.PICKUP_ALL);
    }

    private static GuiNavigator openRoot(FakeGuiService gui, AtomicReference<GuiClickContext> seen) {
        ManagementGuiActions actions = new ManagementGuiActions() {
            @Override
            public void openDetails(GuiClickContext click) {
                seen.set(click);
            }

            @Override
            public void back(GuiClickContext click) {
            }

            @Override
            public void requestChange(GuiClickContext click,
                    com.smile.chunkland.api.permission.ProtectionActionType action) {
            }
        };
        GuiNavigator navigator = new GuiNavigator(gui);
        navigator.open(ALICE, ManagementGuiPages.rootPage(actions)).orElseThrow();
        return navigator;
    }

    @Test
    void handlerObservesCancelledEventsAtHighestPriority() throws Exception {
        Method handler = GuiClickListener.class.getMethod("onInventoryClick", InventoryClickEvent.class);
        EventHandler annotation = handler.getAnnotation(EventHandler.class);
        assertTrue(annotation != null, "click bridge must be a Bukkit event handler");
        assertFalse(annotation.ignoreCancelled(),
            "upstream cancels protected button clicks, so the bridge must still observe them");
        assertEquals(EventPriority.HIGHEST, annotation.priority());
    }

    @Test
    void cancelledButtonClickStillRoutesThroughNavigator() {
        Calls calls = new Calls();
        FakeGuiService gui = new FakeGuiService();
        AtomicReference<GuiClickContext> seen = new AtomicReference<>();
        GuiNavigator navigator = openRoot(gui, seen);
        long generation = navigator.currentGeneration(ALICE).orElseThrow();
        GuiClickListener bridge = new GuiClickListener(() -> navigator);

        Player player = playerProxy(ALICE, calls);
        InventoryClickEvent event = clickEvent(viewProxy(player, calls), ManagementGuiPages.ENTRY_SLOT);
        event.setCancelled(true);

        bridge.onInventoryClick(event);

        GuiClickContext routed = seen.get();
        assertTrue(routed != null, "cancelled entry click must still reach its page action");
        assertEquals(ALICE, routed.playerUuid());
        assertEquals(generation, routed.generation());
        assertEquals(ManagementGuiPages.ENTRY_SLOT, routed.slot());
        assertEquals(ManagementGuiPages.ROOT_PAGE_ID, routed.pageId());
        assertTrue(event.isCancelled(), "bridge must not change the event it only reads");
    }

    @Test
    void uncancelledButtonClickLeavesEventUntouched() {
        Calls calls = new Calls();
        FakeGuiService gui = new FakeGuiService();
        AtomicReference<GuiClickContext> seen = new AtomicReference<>();
        GuiNavigator navigator = openRoot(gui, seen);
        GuiClickListener bridge = new GuiClickListener(() -> navigator);

        Player player = playerProxy(ALICE, calls);
        InventoryClickEvent event = clickEvent(viewProxy(player, calls), ManagementGuiPages.ENTRY_SLOT);

        bridge.onInventoryClick(event);

        assertTrue(seen.get() != null);
        assertFalse(event.isCancelled(), "bridge must never cancel (or uncancel) the event");
    }

    @Test
    void playerInventoryRawSlotNeverDispatches() {
        Calls calls = new Calls();
        FakeGuiService gui = new FakeGuiService();
        AtomicReference<GuiClickContext> seen = new AtomicReference<>();
        GuiNavigator navigator = openRoot(gui, seen);
        GuiClickListener bridge = new GuiClickListener(() -> navigator);

        Player player = playerProxy(ALICE, calls);
        bridge.onInventoryClick(clickEvent(viewProxy(player, calls), 30));

        assertTrue(seen.get() == null, "player-inventory raw slot must not dispatch");
    }

    @Test
    void untrackedClosedAndMissingNavigatorFailClosed() {
        Calls calls = new Calls();
        FakeGuiService gui = new FakeGuiService();
        AtomicReference<GuiClickContext> seen = new AtomicReference<>();
        GuiNavigator navigator = openRoot(gui, seen);
        GuiClickListener bridge = new GuiClickListener(() -> navigator);

        Player stranger = playerProxy(UUID.randomUUID(), calls);
        bridge.onInventoryClick(
            clickEvent(viewProxy(stranger, calls), ManagementGuiPages.ENTRY_SLOT));
        assertTrue(seen.get() == null, "untracked player must not dispatch");

        navigator.close(ALICE);
        Player player = playerProxy(ALICE, calls);
        bridge.onInventoryClick(
            clickEvent(viewProxy(player, calls), ManagementGuiPages.ENTRY_SLOT));
        assertTrue(seen.get() == null, "click after close must not dispatch");

        GuiClickListener missing = new GuiClickListener(null);
        missing.onInventoryClick(
            clickEvent(viewProxy(player, calls), ManagementGuiPages.ENTRY_SLOT));
        GuiClickListener nullNavigator = new GuiClickListener(() -> null);
        nullNavigator.onInventoryClick(
            clickEvent(viewProxy(player, calls), ManagementGuiPages.ENTRY_SLOT));
        assertTrue(seen.get() == null, "missing navigator must not dispatch");
    }

    @Test
    void bridgeOnlyReadsUuidAndRawSlotFromBukkit() {
        Calls calls = new Calls();
        FakeGuiService gui = new FakeGuiService();
        AtomicReference<GuiClickContext> seen = new AtomicReference<>();
        GuiNavigator navigator = openRoot(gui, seen);
        GuiClickListener bridge = new GuiClickListener(() -> navigator);

        Player player = playerProxy(ALICE, calls);
        InventoryClickEvent event =
            clickEvent(viewProxy(player, calls), ManagementGuiPages.ENTRY_SLOT);
        // The Bukkit constructor itself resolves the view (convertSlot) while
        // building the event; only calls made by the bridge count below.
        calls.names.clear();

        bridge.onInventoryClick(event);

        assertTrue(seen.get() != null);
        for (String call : calls.names) {
            assertTrue(call.equals("view#getPlayer") || call.equals("player#getUniqueId"),
                "bridge must only read the clicker and the view owner, never touch Bukkit state: "
                    + call);
        }
    }

    @Test
    void nullsAndThrowingSeamsNeverThrow() {
        Supplier<GuiNavigator> exploding = () -> {
            throw new RuntimeException("navigator boom");
        };
        GuiClickListener bridge = new GuiClickListener(exploding);
        Calls calls = new Calls();
        Player player = playerProxy(ALICE, calls);

        bridge.onInventoryClick(null);
        bridge.onInventoryClick(clickEvent(viewProxy(player, calls), ManagementGuiPages.ENTRY_SLOT));
    }
}

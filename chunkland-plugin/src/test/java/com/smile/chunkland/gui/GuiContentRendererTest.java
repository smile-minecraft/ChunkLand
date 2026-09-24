package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.adapter.gui.GuiContentRenderer;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.PermissionExplain;
import com.smile.chunkland.protection.PermissionExplainLayer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class GuiContentRendererTest {

    private static final UUID PLAYER_ID = UUID.randomUUID();

    @Test
    void managementPagesExposeTheVisibleRenderItems() {
        GuiPage root = ManagementGuiPages.rootPage(ManagementGuiActions.noop());

        assertEquals(List.of(new com.smile.chunkland.gui.GuiPage.RenderItem(
                ManagementGuiPages.ENTRY_SLOT,
                "Open the permission detail view.",
                List.of(),
                "entry")), root.renderItems());

        GuiPage unavailable = ManagementGuiPages.unavailablePage(ManagementGuiActions.noop());
        assertEquals(List.of(26), unavailable.renderItems().stream()
                .map(com.smile.chunkland.gui.GuiPage.RenderItem::slot).toList());
        assertEquals(unavailable.lines(), unavailable.renderItems().get(0).lore());
    }

    @Test
    void detailsItemsUseRowOrderAndKeepBackInTheLastSlot() {
        ManagementGuiModel model = ManagementGuiModel.fromExplains(List.of(
                new PermissionExplain(ProtectionActionType.BLOCK_BREAK,
                        PermissionState.ALLOW, ProtectionActionType.BLOCK_BREAK.decisionSource(),
                        PermissionExplainLayer.LAND_DEFAULT, "allowed", null, false, false, false),
                new PermissionExplain(ProtectionActionType.CONTAINER_OPEN,
                        PermissionState.DENY, ProtectionActionType.CONTAINER_OPEN.decisionSource(),
                        PermissionExplainLayer.LAND_DEFAULT, "denied", null, false, false, false)),
                Map.of());

        GuiPage details = ManagementGuiPages.detailsPage(model, ManagementGuiActions.noop());

        assertEquals(List.of(0, 1, ManagementGuiPages.BACK_SLOT), details.renderItems().stream()
                .map(com.smile.chunkland.gui.GuiPage.RenderItem::slot).toList());
        assertTrue(details.renderItems().get(0).name().contains("DENY"));
        assertEquals("deny", details.renderItems().get(0).materialHint());
        assertEquals("allow", details.renderItems().get(1).materialHint());
        assertEquals("Back", details.renderItems().get(2).name());
    }

    @Test
    void asyncFlowUsesTheOpenedGenerationAndAppliesTheRenderer() {
        FakeGuiService service = new FakeGuiService();
        GuiPage root = ManagementGuiPages.rootPage(ManagementGuiActions.noop());
        GuiNavigator navigator = new GuiNavigator(service);
        long generation = navigator.open(PLAYER_ID, root).orElseThrow();
        List<SlotWrite> writes = new ArrayList<>();
        Inventory inventory = inventoryProxy(27, writes);
        Player player = playerProxy(inventory);
        GuiContentRenderer renderer = new GuiContentRenderer(service, ignored -> player,
                material -> new RecordingItemStack(material));

        assertTrue(renderer.render(PLAYER_ID, generation, root));
        assertEquals(1, service.beginCalls().size());
        FakeGuiService.BeginCall begin = service.beginCalls().get(0);
        assertEquals(PLAYER_ID, begin.playerUuid());
        assertEquals(generation, begin.sessionGeneration());
        assertEquals(0, begin.pageIndex());
        assertEquals(1, service.applyCalls().size());
        assertEquals(begin.pageIndex(), service.applyCalls().get(0).request().pageIndex());
        assertEquals(1, service.rendererCalls());
        assertEquals(ManagementGuiPages.ENTRY_SLOT, writes.get(0).slot());
        assertEquals(org.bukkit.Material.COMPASS, writes.get(0).item().getType());
    }

    @Test
    void failedBeginOrApplyIsSilentAndDoesNotCallTheRenderer() {
        FakeGuiService service = new FakeGuiService();
        GuiPage root = ManagementGuiPages.rootPage(ManagementGuiActions.noop());
        long generation = new GuiNavigator(service).open(PLAYER_ID, root).orElseThrow();
        Player player = playerProxy(inventoryProxy(27, new ArrayList<>()));
        GuiContentRenderer renderer = new GuiContentRenderer(service, ignored -> player,
                material -> new RecordingItemStack(material));

        service.rejectNextAsyncBegin();
        assertFalse(renderer.render(PLAYER_ID, generation, root));
        assertEquals(0, service.applyCalls().size());
        assertEquals(0, service.rendererCalls());

        service.rejectNextAsyncApply();
        assertFalse(renderer.render(PLAYER_ID, generation, root));
        assertEquals(1, service.applyCalls().size());
        assertEquals(0, service.rendererCalls());
    }

    @Test
    void closedViewAndOutOfRangeInventoryAreContained() {
        FakeGuiService service = new FakeGuiService();
        GuiPage root = ManagementGuiPages.rootPage(ManagementGuiActions.noop());
        long generation = new GuiNavigator(service).open(PLAYER_ID, root).orElseThrow();
        List<SlotWrite> writes = new ArrayList<>();
        Player closed = playerProxy(null);
        GuiContentRenderer renderer = new GuiContentRenderer(service, ignored -> closed,
                material -> new RecordingItemStack(material));

        assertDoesNotThrow(() -> renderer.render(PLAYER_ID, generation, root));
        assertEquals(1, service.rendererCalls());
        assertEquals(0, writes.size());

        List<SlotWrite> mismatchedWrites = new ArrayList<>();
        Player smallInventory = playerProxy(inventoryProxy(9, mismatchedWrites));
        GuiContentRenderer mismatch = new GuiContentRenderer(service, ignored -> smallInventory,
                material -> new RecordingItemStack(material));
        assertDoesNotThrow(() -> mismatch.render(PLAYER_ID, generation, root));
        assertEquals(0, mismatchedWrites.size());
    }

    @Test
    void repeatedApplyWritesTheSameSlotsWithoutAccumulatingItems() {
        FakeGuiService service = new FakeGuiService();
        GuiPage root = ManagementGuiPages.rootPage(ManagementGuiActions.noop());
        long generation = new GuiNavigator(service).open(PLAYER_ID, root).orElseThrow();
        List<SlotWrite> writes = new ArrayList<>();
        Player player = playerProxy(inventoryProxy(27, writes));
        GuiContentRenderer renderer = new GuiContentRenderer(service, ignored -> player,
                material -> new RecordingItemStack(material));

        assertTrue(renderer.render(PLAYER_ID, generation, root));
        assertTrue(renderer.render(PLAYER_ID, generation, root));

        assertEquals(2, writes.size());
        assertEquals(writes.get(0).slot(), writes.get(1).slot());
        assertEquals(writes.get(0).item().getType(), writes.get(1).item().getType());
    }

    private static final class RecordingItemStack extends ItemStack {
        private final org.bukkit.Material material;

        private RecordingItemStack(org.bukkit.Material material) {
            super();
            this.material = material;
        }

        @Override
        public org.bukkit.Material getType() {
            return material;
        }

        @Override
        public org.bukkit.inventory.meta.ItemMeta getItemMeta() {
            return null;
        }
    }

    private record SlotWrite(int slot, ItemStack item) {
    }

    private static Player playerProxy(Inventory inventory) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("getUniqueId")) {
                return PLAYER_ID;
            }
            if (method.getName().equals("getOpenInventory")) {
                return inventory == null ? null : viewProxy(inventory);
            }
            return defaultValue(method.getReturnType());
        };
        return (Player) Proxy.newProxyInstance(GuiContentRendererTest.class.getClassLoader(),
                new Class<?>[] {Player.class}, handler);
    }

    private static InventoryView viewProxy(Inventory inventory) {
        InvocationHandler handler = (proxy, method, args) -> method.getName().equals("getTopInventory")
                ? inventory : defaultValue(method.getReturnType());
        return (InventoryView) Proxy.newProxyInstance(GuiContentRendererTest.class.getClassLoader(),
                new Class<?>[] {InventoryView.class}, handler);
    }

    private static Inventory inventoryProxy(int size, List<SlotWrite> writes) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("getSize")) {
                return size;
            }
            if (method.getName().equals("setItem")) {
                writes.add(new SlotWrite((Integer) args[0], (ItemStack) args[1]));
                return null;
            }
            return defaultValue(method.getReturnType());
        };
        return (Inventory) Proxy.newProxyInstance(GuiContentRendererTest.class.getClassLoader(),
                new Class<?>[] {Inventory.class}, handler);
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
}

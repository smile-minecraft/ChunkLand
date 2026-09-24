package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.ChunkLandPlugin;
import com.smile.chunkland.adapter.gui.GuiContentRenderer;
import com.smile.chunkland.adapter.AceLibBridge;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.protection.PermissionDefaultsCache;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ManagementGuiWiringTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());

    @Test
    void rootDetailsAndBackEachRenderAfterSuccessfulNavigation() throws Exception {
        Fixture fixture = fixture();
        ChunkLandPlugin plugin = fixture.plugin();

        long rootGeneration = plugin.openManagementGui(PLAYER, LAND).orElseThrow();
        assertEquals(1, fixture.service().beginCalls().size());

        GuiNavigator navigator = plugin.guiNavigator().orElseThrow();
        navigator.handleClick(PLAYER, rootGeneration, ManagementGuiPages.ENTRY_SLOT);
        assertEquals(2, fixture.service().beginCalls().size());

        long detailsGeneration = navigator.currentGeneration(PLAYER).orElseThrow();
        navigator.handleClick(PLAYER, detailsGeneration, ManagementGuiPages.BACK_SLOT);
        assertEquals(3, fixture.service().beginCalls().size());
        assertEquals(3, fixture.service().applyCalls().size());
        assertEquals(3, fixture.service().rendererCalls());
    }

    @Test
    void failedRootPushAndBackDoNotRender() throws Exception {
        Fixture rootFailure = fixture();
        rootFailure.service().rejectNextOpen();
        assertTrue(rootFailure.plugin().openManagementGui(PLAYER, LAND).isEmpty());
        assertEquals(0, rootFailure.service().beginCalls().size());

        Fixture pushFailure = fixture();
        long rootGeneration = pushFailure.plugin().openManagementGui(PLAYER, LAND).orElseThrow();
        pushFailure.service().rejectNextOpen();
        pushFailure.plugin().guiNavigator().orElseThrow().handleClick(
                PLAYER, rootGeneration, ManagementGuiPages.ENTRY_SLOT);
        assertEquals(1, pushFailure.service().beginCalls().size());

        Fixture backFailure = fixture();
        long backRoot = backFailure.plugin().openManagementGui(PLAYER, LAND).orElseThrow();
        GuiNavigator backNavigator = backFailure.plugin().guiNavigator().orElseThrow();
        backNavigator.handleClick(PLAYER, backRoot, ManagementGuiPages.ENTRY_SLOT);
        long detailsGeneration = backNavigator.currentGeneration(PLAYER).orElseThrow();
        backFailure.service().rejectNextOpen();
        backNavigator.handleClick(PLAYER, detailsGeneration, ManagementGuiPages.BACK_SLOT);
        assertEquals(2, backFailure.service().beginCalls().size());
    }

    @Test
    void disableClearsTheContentRendererReference() throws Exception {
        Fixture fixture = fixture();
        fixture.plugin().openManagementGui(PLAYER, LAND).orElseThrow();

        fixture.plugin().onDisable();

        Field renderer = ChunkLandPlugin.class.getDeclaredField("guiContentRenderer");
        renderer.setAccessible(true);
        assertNull(renderer.get(fixture.plugin()));
    }

    private static Fixture fixture() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        FakeGuiService service = new FakeGuiService();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land())));
        PermissionDefaultsCache defaults = new PermissionDefaultsCache(
                ChunkLandConfig::defaults, ignored -> Optional.empty(), null);
        GuiContentRenderer renderer = new GuiContentRenderer(service, ignored -> player(),
                material -> null);
        setField(plugin, "guiNavigator", new GuiNavigator(service));
        setField(plugin, "guiContentRenderer", renderer);
        setField(plugin, "protectionStore", store);
        setField(plugin, "permissionDefaults", defaults);
        return new Fixture(plugin, service);
    }

    private static LandSnapshot land() {
        return new LandSnapshot(LAND, "Home", LandName.normalize("Home"),
                OwnerRef.player(PLAYER), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(ManagementGuiWiringTest.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) {
                        return PLAYER;
                    }
                    return defaultValue(method.getReturnType());
                });
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

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static ChunkLandPlugin allocatePlugin() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        ChunkLandPlugin plugin = (ChunkLandPlugin) unsafe.allocateInstance(ChunkLandPlugin.class);
        for (Field field : ChunkLandPlugin.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            long offset = unsafe.objectFieldOffset(field);
            Object value = unsafe.getObject(plugin, offset);
            if (field.getType() == Optional.class && value == null) {
                unsafe.putObject(plugin, offset, Optional.empty());
            } else if (field.getType() == AceLibBridge.class && value == null) {
                unsafe.putObject(plugin, offset, new AceLibBridge());
            }
        }
        Field logger = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("logger");
        logger.setAccessible(true);
        logger.set(plugin, Logger.getLogger(ManagementGuiWiringTest.class.getName()));
        return plugin;
    }

    private record Fixture(ChunkLandPlugin plugin, FakeGuiService service) {
    }
}

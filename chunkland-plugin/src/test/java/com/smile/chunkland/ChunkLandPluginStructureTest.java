package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.adapter.AceLibBridge;
import java.lang.reflect.Method;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class ChunkLandPluginStructureTest {

    @Test
    void nameConstantIsPreserved() {
        assertEquals("ChunkLand", ChunkLandPlugin.NAME);
    }

    @Test
    void extendsJavaPluginAndDeclaresLifecycle() throws Exception {
        Class<?> clazz = ChunkLandPlugin.class;
        assertTrue(JavaPlugin.class.isAssignableFrom(clazz), "ChunkLandPlugin must extend JavaPlugin");
        Method onEnable = clazz.getDeclaredMethod("onEnable");
        Method onDisable = clazz.getDeclaredMethod("onDisable");
        assertFalse(onEnable.isBridge());
        assertFalse(onDisable.isBridge());
    }

    @Test
    void exposesBridgeAccessor() throws Exception {
        Method getBridge = ChunkLandPlugin.class.getDeclaredMethod("getBridge");
        assertEquals(AceLibBridge.class, getBridge.getReturnType());
    }
}

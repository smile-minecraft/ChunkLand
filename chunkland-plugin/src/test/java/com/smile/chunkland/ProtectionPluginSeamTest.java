package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.ProtectionEngine;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProtectionPluginSeamTest {

    @Test
    void startupEngineCoversEveryAction() {
        ProtectionEngine engine = ChunkLandPlugin.buildProtectionEngine(new LandRegistryStore());
        assertNotNull(engine);
        for (ProtectionActionType action : ProtectionActionType.values()) {
            assertNotNull(engine.routeOf(action),
                    "startup engine must route every action: " + action);
        }
    }

    @Test
    void startupEngineLeavesWildernessAlone() {
        ProtectionEngine engine = ChunkLandPlugin.buildProtectionEngine(new LandRegistryStore());
        var decision = engine.decideAt(UUID.randomUUID(), UUID.randomUUID(), 0, 0,
                ProtectionActionType.BLOCK_BREAK);
        assertEquals(PermissionState.ALLOW, decision.outcome(),
                "empty registry means wilderness: vanilla, never DENY");
    }

    @Test
    void pluginDeclaresProtectionWiring() throws Exception {
        assertNotNull(ChunkLandPlugin.class.getDeclaredField("protectionStore"));
        assertNotNull(ChunkLandPlugin.class.getDeclaredField("protectionEngine"));
        assertNotNull(ChunkLandPlugin.class.getDeclaredField("protectionListener"));
        assertNotNull(ChunkLandPlugin.class.getDeclaredMethod("registerProtectionListener",
                com.smile.chunkland.protection.ProtectionListener.class));
    }
}

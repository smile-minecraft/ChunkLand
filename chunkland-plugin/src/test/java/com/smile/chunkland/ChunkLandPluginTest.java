package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChunkLandPluginTest {
    @Test
    void exposesThePluginName() {
        assertEquals("ChunkLand", ChunkLandPlugin.NAME);
    }
}

package com.smile.chunkland.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChunkLandApiTest {
    @Test
    void exposesAnApiTypeWithoutRuntimeDependencies() {
        assertTrue(ChunkLandApi.class.isInterface());
    }
}

package com.smile.chunkland.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ChunkLandApiSkeletonTest {

    private static final Set<String> EXPECTED_METHODS = Set.of(
            "getLandSnapshot", "getSubLandSnapshot", "getOwner", "can", "getRule", "getProtectionDepth");

    @Test
    void isAnInterface() {
        assertTrue(ChunkLandApi.class.isInterface());
    }

    @Test
    void exposesMinimalReadApiEntryPoints() {
        var actual = new HashSet<String>();
        for (var m : ChunkLandApi.class.getDeclaredMethods()) {
            actual.add(m.getName());
        }
        for (var name : EXPECTED_METHODS) {
            assertTrue(actual.contains(name), "missing read API method: " + name);
        }
    }
}

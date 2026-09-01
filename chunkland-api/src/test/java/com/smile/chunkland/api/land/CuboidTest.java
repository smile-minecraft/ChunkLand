package com.smile.chunkland.api.land;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CuboidTest {

    private static final UUID WORLD = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void rejectsReversedBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Cuboid(1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Cuboid(0, 1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Cuboid(0, 0, 1, 0, 0, 0));
    }

    @Test
    void usesInclusiveBlockBoundsForChunkProjection() {
        var cuboid = new Cuboid(0, 10, 0, 16, 20, 16);

        assertEquals(
                Set.of(
                        new ChunkKey(WORLD, 0, 0),
                        new ChunkKey(WORLD, 0, 1),
                        new ChunkKey(WORLD, 1, 0),
                        new ChunkKey(WORLD, 1, 1)),
                cuboid.coveredChunks(WORLD));
    }

    @Test
    void handlesNegativeCoordinatesAndCrossingZero() {
        var cuboid = new Cuboid(-17, 0, -1, 0, 5, 16);

        assertEquals(9, cuboid.coveredChunks(WORLD).size());
        assertTrue(cuboid.coveredChunks(WORLD).contains(new ChunkKey(WORLD, -2, -1)));
        assertTrue(cuboid.coveredChunks(WORLD).contains(new ChunkKey(WORLD, 0, 1)));
    }

    @Test
    void extremeBlockCoordinatesThatStayInOneChunkAreSafe() {
        var minimum = new Cuboid(Integer.MIN_VALUE, 0, Integer.MIN_VALUE, Integer.MIN_VALUE, 1, Integer.MIN_VALUE);
        var maximum = new Cuboid(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, 1, Integer.MAX_VALUE);

        assertEquals(Set.of(new ChunkKey(WORLD, -134217728, -134217728)), minimum.coveredChunks(WORLD));
        assertEquals(Set.of(new ChunkKey(WORLD, 134217727, 134217727)), maximum.coveredChunks(WORLD));
    }

    @Test
    void rejectsHugeChunkProjectionBeforeEnumeration() {
        var huge = new Cuboid(
                Integer.MIN_VALUE, 0, Integer.MIN_VALUE,
                Integer.MAX_VALUE, 0, Integer.MAX_VALUE);

        assertThrows(IllegalArgumentException.class, () -> huge.coveredChunks(WORLD));
    }

    @Test
    void partialChunksAreAllowedAndReturnedSetIsImmutable() {
        var cuboid = new Cuboid(3, -20, 4, 12, 30, 14);

        var chunks = cuboid.coveredChunks(WORLD);
        assertEquals(Set.of(new ChunkKey(WORLD, 0, 0)), chunks);
        assertThrows(UnsupportedOperationException.class, () -> chunks.clear());
        assertThrows(NullPointerException.class, () -> cuboid.coveredChunks(null));
    }

    @Test
    void intersectionUsesInclusiveBlockSemanticsAndIsNestedAware() {
        var base = new Cuboid(0, 0, 0, 10, 10, 10);

        assertTrue(base.intersects(new Cuboid(10, 10, 10, 20, 20, 20)));
        assertTrue(base.intersects(new Cuboid(2, 2, 2, 3, 3, 3)));
        assertFalse(base.intersects(new Cuboid(11, 0, 0, 20, 10, 10)));
        assertFalse(base.intersects(new Cuboid(0, 11, 0, 10, 20, 10)));
    }
}

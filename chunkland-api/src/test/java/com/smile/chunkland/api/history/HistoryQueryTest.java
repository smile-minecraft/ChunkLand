package com.smile.chunkland.api.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Bounded world-history query contract: the caller-thread capture clamps
 * radius, look-back window and result limit to documented maxima so a
 * background optional lookup can never run unbounded.
 */
class HistoryQueryTest {

    private static final UUID WORLD = UUID.randomUUID();

    @Test
    void inRangeValuesArePreserved() {
        HistoryQuery query = HistoryQuery.bounded(WORLD, "world", 10, 64, -20, 8, 3600, 5);
        assertEquals(WORLD, query.worldId());
        assertEquals("world", query.worldName());
        assertEquals(10, query.centerX());
        assertEquals(64, query.centerY());
        assertEquals(-20, query.centerZ());
        assertEquals(8, query.radiusBlocks());
        assertEquals(3600, query.secondsBack());
        assertEquals(5, query.maxResults());
    }

    @Test
    void overflowsClampToDocumentedMaxima() {
        HistoryQuery query = HistoryQuery.bounded(WORLD, "world", 0, 64, 0,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(HistoryQuery.MAX_RADIUS_BLOCKS, query.radiusBlocks());
        assertEquals(HistoryQuery.MAX_SECONDS_BACK, query.secondsBack());
        assertEquals(HistoryQuery.MAX_RESULTS, query.maxResults());
    }

    @Test
    void underflowsClampToOne() {
        HistoryQuery query = HistoryQuery.bounded(WORLD, "world", 0, 64, 0, 0, 0, 0);
        assertEquals(1, query.radiusBlocks());
        assertEquals(1, query.secondsBack());
        assertEquals(1, query.maxResults());
    }

    @Test
    void negativeInputsClampToOne() {
        HistoryQuery query = HistoryQuery.bounded(WORLD, "world", 0, 64, 0, -4, -9, -2);
        assertEquals(1, query.radiusBlocks());
        assertEquals(1, query.secondsBack());
        assertEquals(1, query.maxResults());
    }

    @Test
    void nullWorldIdIsRejected() {
        assertThrows(NullPointerException.class,
                () -> HistoryQuery.bounded(null, "world", 0, 64, 0, 8, 60, 5));
    }

    @Test
    void blankWorldNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> HistoryQuery.bounded(WORLD, "  ", 0, 64, 0, 8, 60, 5));
    }

    @Test
    void canonicalConstructorRejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new HistoryQuery(WORLD, "world", 0, 64, 0, 0, 60, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new HistoryQuery(WORLD, "world", 0, 64, 0, 8, 60,
                        HistoryQuery.MAX_RESULTS + 1));
    }
}

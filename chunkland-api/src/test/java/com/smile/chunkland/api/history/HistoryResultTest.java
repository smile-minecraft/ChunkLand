package com.smile.chunkland.api.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * History result contract: entries are defensively copied and immutable, and
 * the unavailable marker stays empty so fail-closed callers never see a
 * half-filled payload.
 */
class HistoryResultTest {

    private static HistoryEntry entry() {
        return new HistoryEntry(12, 64, -3, "placed", "STONE", 1_700_000_000L);
    }

    @Test
    void entriesAreDefensivelyCopied() {
        List<HistoryEntry> source = new ArrayList<>(List.of(entry()));
        HistoryResult result = HistoryResult.of(source, false);
        source.clear();
        assertEquals(1, result.entries().size());
    }

    @Test
    void entriesAreImmutable() {
        HistoryResult result = HistoryResult.of(List.of(entry()), false);
        assertThrows(UnsupportedOperationException.class,
                () -> result.entries().add(entry()));
    }

    @Test
    void ofMarksAvailable() {
        HistoryResult result = HistoryResult.of(List.of(entry()), true);
        assertTrue(result.available());
        assertTrue(result.truncated());
    }

    @Test
    void unavailableStaysEmpty() {
        HistoryResult result = HistoryResult.unavailable();
        assertFalse(result.available());
        assertFalse(result.truncated());
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void nullEntriesBecomeEmpty() {
        HistoryResult result = HistoryResult.of(null, false);
        assertTrue(result.available());
        assertTrue(result.entries().isEmpty());
    }
}

package com.smile.chunkland.api.history;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Provider contract: the empty provider is unavailable and answers every
 * query — including a null query — with an unavailable result instead of
 * throwing, so CoreProtect-absent servers stay fail-closed.
 */
class WorldHistoryProviderTest {

    @Test
    void emptyProviderIsUnavailable() {
        assertFalse(WorldHistoryProvider.empty().available());
    }

    @Test
    void emptyProviderAnswersUnavailable() throws Exception {
        HistoryQuery query = HistoryQuery.bounded(UUID.randomUUID(), "world",
                0, 64, 0, 8, 60, 5);
        HistoryResult result = WorldHistoryProvider.empty().query(query)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertFalse(result.available());
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void emptyProviderNeverThrowsOnNullQuery() throws Exception {
        HistoryResult result = WorldHistoryProvider.empty().query(null)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertFalse(result.available());
    }
}

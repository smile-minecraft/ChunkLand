package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditSearchTest {

    @TempDir Path tmp;

    private Path db() {
        return tmp.resolve("search.db");
    }

    private static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");

    private AuditEntry entry(Instant ts, UUID actor, String action, LandId land, UUID world) {
        return new AuditEntry(0, ts, actor, action, land, world, null, 1, null, null, null, List.of());
    }

    private AuditSearchQuery all() {
        return new AuditSearchQuery(null, null, null, null, null, 20, 0);
    }

    @Test
    void filterByActor() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            UUID actorA = UUID.randomUUID();
            UUID actorB = UUID.randomUUID();
            repo.insert(entry(BASE, actorA, "LAND_CREATE", null, null)).toCompletableFuture().join();
            repo.insert(entry(BASE.plusSeconds(1), actorB, "LAND_CREATE", null, null)).toCompletableFuture().join();
            List<AuditEntry> found = repo.search(
                    new AuditSearchQuery(actorA, null, null, null, null, 20, 0))
                    .toCompletableFuture().join();
            assertEquals(1, found.size());
            assertEquals(actorA, found.get(0).actor());
            assertThrows(UnsupportedOperationException.class, () -> found.add(found.get(0)));
        }
    }

    @Test
    void filterByAction() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            repo.insert(entry(BASE, null, "LAND_CREATE", null, null)).toCompletableFuture().join();
            repo.insert(entry(BASE.plusSeconds(1), null, "DEPTH_EXTEND", null, null)).toCompletableFuture().join();
            List<AuditEntry> found = repo.search(
                    new AuditSearchQuery(null, null, "DEPTH_EXTEND", null, null, 20, 0))
                    .toCompletableFuture().join();
            assertEquals(1, found.size());
            assertEquals("DEPTH_EXTEND", found.get(0).action());
        }
    }

    @Test
    void filterByLand() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            LandId landA = new LandId(UUID.randomUUID());
            LandId landB = new LandId(UUID.randomUUID());
            repo.insert(entry(BASE, null, "LAND_CREATE", landA, null)).toCompletableFuture().join();
            repo.insert(entry(BASE.plusSeconds(1), null, "LAND_CREATE", landB, null)).toCompletableFuture().join();
            List<AuditEntry> found = repo.search(
                    new AuditSearchQuery(null, null, null, landA, null, 20, 0))
                    .toCompletableFuture().join();
            assertEquals(1, found.size());
            assertEquals(landA, found.get(0).landId());
        }
    }

    @Test
    void filterByWorld() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            UUID worldA = UUID.randomUUID();
            UUID worldB = UUID.randomUUID();
            repo.insert(entry(BASE, null, "LAND_CREATE", null, worldA)).toCompletableFuture().join();
            repo.insert(entry(BASE.plusSeconds(1), null, "LAND_CREATE", null, worldB)).toCompletableFuture().join();
            List<AuditEntry> found = repo.search(
                    new AuditSearchQuery(null, null, null, null, worldA, 20, 0))
                    .toCompletableFuture().join();
            assertEquals(1, found.size());
            assertEquals(worldA, found.get(0).worldId());
        }
    }

    @Test
    void filterByTimeLowerBound() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            repo.insert(entry(BASE, null, "LAND_CREATE", null, null)).toCompletableFuture().join();
            repo.insert(entry(BASE.plusSeconds(3600), null, "LAND_CREATE", null, null)).toCompletableFuture().join();
            List<AuditEntry> found = repo.search(
                    new AuditSearchQuery(null, BASE.plusSeconds(1800), null, null, null, 20, 0))
                    .toCompletableFuture().join();
            assertEquals(1, found.size());
            assertEquals(BASE.plusSeconds(3600), found.get(0).timestamp());
        }
    }

    @Test
    void combinedFiltersNarrowToOne() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            UUID actor = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            UUID world = UUID.randomUUID();
            Instant match = BASE.plusSeconds(6);
            repo.insert(entry(match, actor, "LAND_CREATE", land, world)).toCompletableFuture().join();
            repo.insert(entry(BASE.plusSeconds(10), actor, "LAND_RENAME", land, world)).toCompletableFuture().join();
            repo.insert(entry(BASE.plusSeconds(20), UUID.randomUUID(), "LAND_CREATE", land, world))
                    .toCompletableFuture().join();
            repo.insert(entry(BASE.plusSeconds(30), actor, "LAND_CREATE",
                    new LandId(UUID.randomUUID()), world)).toCompletableFuture().join();
            List<AuditEntry> found = repo.search(
                    new AuditSearchQuery(actor, BASE.plusSeconds(5), "LAND_CREATE", land, world, 20, 0))
                    .toCompletableFuture().join();
            assertEquals(1, found.size());
            assertEquals(match, found.get(0).timestamp());
        }
    }

    @Test
    void resultsAreNewestFirstWithStableTieBreak() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            repo.insert(entry(BASE.plusSeconds(30), null, "LAND_CREATE", null, null)).toCompletableFuture().join();
            repo.insert(entry(BASE, null, "LAND_CREATE", null, null)).toCompletableFuture().join();
            repo.insert(entry(BASE.plusSeconds(10), null, "LAND_CREATE", null, null)).toCompletableFuture().join();
            List<AuditEntry> found = repo.search(all()).toCompletableFuture().join();
            assertEquals(3, found.size());
            assertTrue(!found.get(0).timestamp().isBefore(found.get(1).timestamp()));
            assertTrue(!found.get(1).timestamp().isBefore(found.get(2).timestamp()));
        }
    }

    @Test
    void paginationSlicesInStableOrder() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            for (int i = 0; i < 5; i++) {
                repo.insert(entry(BASE.plusSeconds(i), null, "LAND_CREATE", null, null))
                        .toCompletableFuture().join();
            }
            List<AuditEntry> page1 = repo.search(new AuditSearchQuery(null, null, null, null, null, 2, 0))
                    .toCompletableFuture().join();
            List<AuditEntry> page2 = repo.search(new AuditSearchQuery(null, null, null, null, null, 2, 2))
                    .toCompletableFuture().join();
            List<AuditEntry> page3 = repo.search(new AuditSearchQuery(null, null, null, null, null, 2, 4))
                    .toCompletableFuture().join();
            assertEquals(2, page1.size());
            assertEquals(2, page2.size());
            assertEquals(1, page3.size());
            assertEquals(BASE.plusSeconds(4), page1.get(0).timestamp());
            assertEquals(BASE.plusSeconds(3), page1.get(1).timestamp());
            assertEquals(BASE.plusSeconds(2), page2.get(0).timestamp());
            assertEquals(BASE, page3.get(0).timestamp());
        }
    }

    @Test
    void emptyResultIsImmutableAndEmpty() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            repo.insert(entry(BASE, null, "LAND_CREATE", null, null)).toCompletableFuture().join();
            List<AuditEntry> found = repo.search(
                    new AuditSearchQuery(null, null, "LEDGER_RESOLVE", null, null, 20, 0))
                    .toCompletableFuture().join();
            assertTrue(found.isEmpty());
            assertThrows(UnsupportedOperationException.class,
                    () -> found.add(entry(BASE, null, "LAND_CREATE", null, null)));
        }
    }

    @Test
    void invalidPagingIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditSearchQuery(null, null, null, null, null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditSearchQuery(null, null, null, null, null, 101, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditSearchQuery(null, null, null, null, null, 20, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditSearchQuery(null, null, "", null, null, 20, 0));
    }

    @Test
    void everyFilteredQueryUsesAnIndex() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            UUID actor = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            UUID world = UUID.randomUUID();
            AuditSearchQuery[] queries = {
                new AuditSearchQuery(actor, null, null, null, null, 20, 0),
                new AuditSearchQuery(null, BASE, null, null, null, 20, 0),
                new AuditSearchQuery(null, null, "LAND_CREATE", null, null, 20, 0),
                new AuditSearchQuery(null, null, null, land, null, 20, 0),
                new AuditSearchQuery(null, null, null, null, world, 20, 0),
                new AuditSearchQuery(actor, BASE, "LAND_CREATE", land, world, 20, 0),
            };
            for (AuditSearchQuery query : queries) {
                List<String> plan = repo.explainSearch(query).toCompletableFuture().join();
                assertFalse(plan.isEmpty(), "empty query plan for " + query);
                boolean usesIndex = plan.stream().anyMatch(
                        line -> line.contains("SEARCH") || line.contains("USING INDEX"));
                assertTrue(usesIndex, "query falls back to a full scan: " + plan);
                boolean fullScan = plan.stream().anyMatch(
                        line -> line.contains("SCAN") && !line.contains("USING INDEX"));
                assertFalse(fullScan, "full table scan detected: " + plan);
            }
        }
    }

    @Test
    void chunksRoundTripThroughSearch() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            UUID world = UUID.randomUUID();
            ChunkKey chunk = new ChunkKey(world, 1, 2);
            AuditEntry withChunks = new AuditEntry(0, BASE, null, "CHUNK_ADD", null, world,
                    null, 1, null, null, null, List.of(chunk));
            repo.insert(withChunks).toCompletableFuture().join();
            List<AuditEntry> found = repo.search(
                    new AuditSearchQuery(null, null, "CHUNK_ADD", null, null, 20, 0))
                    .toCompletableFuture().join();
            assertEquals(1, found.size());
            assertEquals(List.of(chunk), found.get(0).chunks());
        }
    }
}

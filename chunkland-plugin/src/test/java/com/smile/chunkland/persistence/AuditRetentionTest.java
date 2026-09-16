package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditRetentionTest {

    @TempDir Path tmp;

    private Path db() {
        return tmp.resolve("retention.db");
    }

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    @Test
    void zeroDaysMeansRetainForever() {
        assertTrue(AuditRetentionPolicy.retainsForever(0));
        assertEquals(Optional.empty(), AuditRetentionPolicy.cutoff(NOW, 0));
    }

    @Test
    void positiveDaysResolveToEventTimeCutoff() {
        assertFalse(AuditRetentionPolicy.retainsForever(180));
        assertEquals(Optional.of(NOW.minusSeconds(180L * 86400L)),
                AuditRetentionPolicy.cutoff(NOW, 180));
        assertEquals(Optional.of(NOW.minusSeconds(86400L)),
                AuditRetentionPolicy.cutoff(NOW, 1));
    }

    @Test
    void negativeDaysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AuditRetentionPolicy.cutoff(NOW, -1));
        assertThrows(IllegalArgumentException.class, () -> AuditRetentionPolicy.retainsForever(-1));
        assertThrows(NullPointerException.class, () -> AuditRetentionPolicy.cutoff(null, 10));
    }

    private AuditEntry entry(Instant ts, UUID world) {
        return new AuditEntry(0, ts, UUID.randomUUID(), "LAND_CREATE", null, world,
                null, 1, null, null, null, List.of());
    }

    @Test
    void purgeRemovesOnlyExpiredRowsAndTheirChunks() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            UUID world = UUID.randomUUID();
            long oldId = repo.insert(new AuditEntry(0, NOW.minusSeconds(200L * 86400L),
                    UUID.randomUUID(), "LAND_CREATE", null, world, null, 1, null, null, null,
                    List.of(new ChunkKey(world, 7, 7)))).toCompletableFuture().join();
            long freshId = repo.insert(entry(NOW.minusSeconds(10L * 86400L), world))
                    .toCompletableFuture().join();
            int removed = repo.purgeOlderThan(NOW.minusSeconds(180L * 86400L))
                    .toCompletableFuture().join();
            assertEquals(1, removed);
            assertTrue(repo.findById(oldId).toCompletableFuture().join().isEmpty());
            assertTrue(repo.findById(freshId).toCompletableFuture().join().isPresent());
            int orphanChunks = store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM audit_chunks WHERE audit_id = ?")) {
                    ps.setLong(1, oldId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getInt(1);
                    }
                }
            });
            assertEquals(0, orphanChunks);
            int freshChunks = store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM audit_chunks WHERE audit_id = ?")) {
                    ps.setLong(1, freshId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getInt(1);
                    }
                }
            });
            assertEquals(0, freshChunks);
        }
    }

    @Test
    void purgeKeepsRowsExactlyAtCutoff() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            Instant cutoff = NOW.minusSeconds(180L * 86400L);
            long boundaryId = repo.insert(entry(cutoff, UUID.randomUUID())).toCompletableFuture().join();
            int removed = repo.purgeOlderThan(cutoff).toCompletableFuture().join();
            assertEquals(0, removed);
            assertTrue(repo.findById(boundaryId).toCompletableFuture().join().isPresent());
        }
    }

    @Test
    void purgeTouchesNothingOutsideAuditHistory() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository landRepo = new SqliteLandRepository(store);
            SqliteAuditRepository auditRepo = new SqliteAuditRepository(store);
            UUID world = UUID.randomUUID();
            LandId landId = new LandId(UUID.randomUUID());
            landRepo.save(new com.smile.chunkland.api.land.LandSnapshot(landId, "Home", "home",
                    com.smile.chunkland.api.land.OwnerRef.player(UUID.randomUUID()), world,
                    java.util.Set.of(), List.of(), 0, 0, NOW, NOW)).toCompletableFuture().join();
            auditRepo.insert(entry(NOW.minusSeconds(300L * 86400L), world)).toCompletableFuture().join();
            int removed = auditRepo.purgeOlderThan(NOW.minusSeconds(180L * 86400L))
                    .toCompletableFuture().join();
            assertEquals(1, removed);
            assertTrue(landRepo.findById(landId).toCompletableFuture().join().isPresent());
            int lands = store.execute(conn -> {
                try (java.sql.Statement st = conn.createStatement();
                        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM lands")) {
                    rs.next();
                    return rs.getInt(1);
                }
            });
            assertEquals(1, lands);
        }
    }

    @Test
    void purgeWithNoExpiredRowsDeletesNothing() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            repo.insert(entry(NOW, UUID.randomUUID())).toCompletableFuture().join();
            int removed = repo.purgeOlderThan(NOW.minusSeconds(180L * 86400L))
                    .toCompletableFuture().join();
            assertEquals(0, removed);
        }
    }

    @Test
    void purgeRejectsNullCutoff() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteAuditRepository repo = new SqliteAuditRepository(store);
            assertThrows(NullPointerException.class,
                    () -> repo.purgeOlderThan(null));
        }
    }
}

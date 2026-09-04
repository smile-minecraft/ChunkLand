package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditMetadataVersionTest {

    @TempDir Path tmp;

    @Test
    void unknownMetadataSchemaVersionReadsBackWithoutRejection() {
        try (PersistenceStore store = PersistenceStore.open(tmp.resolve(UUID.randomUUID() + ".db"))) {
            store.execute(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO audit_log (timestamp, actor_uuid, action, land_id, world_uuid, "
                                + "single_chunk_packed, metadata_schema_version, before_json, after_json, metadata_json) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    statement.setLong(1, Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
                    statement.setBytes(2, null);
                    statement.setString(3, "LAND_CREATE");
                    statement.setBytes(4, null);
                    statement.setBytes(5, null);
                    statement.setObject(6, null);
                    statement.setInt(7, 999);
                    statement.setString(8, null);
                    statement.setString(9, null);
                    statement.setString(10, "{}");
                    statement.executeUpdate();
                }
                return null;
            });

            SqliteAuditRepository repository = new SqliteAuditRepository(store);
            List<AuditEntry> all = repository.findAll(10, 0).toCompletableFuture().join();
            assertEquals(1, all.size());
            assertEquals(999, all.get(0).metadataVersion());
            assertEquals(999, all.get(0).metadataSchemaVersion());
            assertEquals(all.get(0).metadataVersion(), all.get(0).metadataSchemaVersion());

            Optional<AuditEntry> byId = repository.findById(all.get(0).id()).toCompletableFuture().join();
            assertTrue(byId.isPresent());
            assertEquals(999, byId.get().metadataVersion());
        }
    }

    @Test
    void legacyZeroMetadataSchemaVersionReadsBack() {
        try (PersistenceStore store = PersistenceStore.open(tmp.resolve(UUID.randomUUID() + ".db"))) {
            SqliteAuditRepository repository = new SqliteAuditRepository(store);
            AuditEntry entry = new AuditEntry(0, Instant.parse("2026-01-01T00:00:00Z"), null,
                    "RULE_CHANGE", null, null, null, 0, null, null, "{}", List.of());
            long id = repository.insert(entry).toCompletableFuture().join();

            Optional<AuditEntry> got = repository.findById(id).toCompletableFuture().join();
            assertTrue(got.isPresent());
            assertEquals(0, got.get().metadataVersion());
            assertEquals(0, got.get().metadataSchemaVersion());
        }
    }
}

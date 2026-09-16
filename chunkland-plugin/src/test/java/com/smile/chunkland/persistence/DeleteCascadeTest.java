package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whole-land delete cascade contract: the atomic delete removes every
 * Land-owned row but the audit history, the subject groups and the permission
 * profiles survive and stay reusable.
 */
class DeleteCascadeTest {

    @TempDir java.nio.file.Path temp;

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    private PersistenceStore db() {
        return PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
    }

    private static LandSnapshot land(UUID world, LandId lid, OwnerRef owner, ChunkKey chunk) {
        return new LandSnapshot(lid, "Home", "home", owner, world, java.util.Set.of(chunk),
                List.of(), 4, 0, NOW, NOW);
    }

    private int count(PersistenceStore store, String sql, LandId land) {
        return store.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBytes(1, UuidBlob.encode(land.value()));
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getInt(1);
                }
            }
        });
    }

    private int countAll(PersistenceStore store, String table) {
        return store.execute(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rows.next();
                return rows.getInt(1);
            }
        });
    }

    private static boolean containsInChain(Throwable failure, String fragment) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(fragment)) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return false;
            }
            current = cause;
        }
        return false;
    }

    private static String chainMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (messages.length() > 0) {
                messages.append(" <- ");
            }
            messages.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return messages.toString();
    }

    @Test
    void deleteCommitCascadesOwnedRowsAndRetainsHistory() throws Exception {
        try (PersistenceStore store = db()) {
            OperationLedger ledger = new OperationLedger(store);
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            SqliteSubLandRepository subs = new SqliteSubLandRepository(store);
            SqliteAuditRepository audits = new SqliteAuditRepository(store);

            UUID world = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            LandId land = new LandId(UUID.randomUUID());
            ChunkKey key = new ChunkKey(world, 2, 5);
            lands.save(land(world, land, owner, key)).toCompletableFuture().join();
            UUID lot = UUID.randomUUID();
            chunks.addChunk(land, key, 64, lot, 250L).toCompletableFuture().join();

            SubLandId sub = new SubLandId(UUID.randomUUID());
            subs.save(new SubLandSnapshot(sub, land, "den",
                    new Cuboid(32, 60, 80, 47, 70, 95), world)).toCompletableFuture().join();

            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            UUID banned = UUID.randomUUID();
            store.execute(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO subject_groups (id, owner_key, name_key, display_name, created_at)"
                                + " VALUES (?, ?, ?, ?, ?)")) {
                    statement.setBytes(1, UuidBlob.encode(groupId));
                    statement.setString(2, owner.key());
                    statement.setString(3, "friends");
                    statement.setString(4, "Friends");
                    statement.setLong(5, NOW.toEpochMilli());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO permission_profiles (id, owner_key, name_key, display_name, created_at)"
                                + " VALUES (?, ?, ?, ?, ?)")) {
                    statement.setBytes(1, UuidBlob.encode(profileId));
                    statement.setString(2, owner.key());
                    statement.setString(3, "member");
                    statement.setString(4, "Member");
                    statement.setLong(5, NOW.toEpochMilli());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO land_bindings (land_id, subject_type, subject_id, profile_id)"
                                + " VALUES (?, ?, ?, ?)")) {
                    statement.setBytes(1, UuidBlob.encode(land.value()));
                    statement.setString(2, "GROUP");
                    statement.setBytes(3, UuidBlob.encode(groupId));
                    statement.setBytes(4, UuidBlob.encode(profileId));
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO subland_bindings (subland_id, subject_type, subject_id, profile_id)"
                                + " VALUES (?, ?, ?, ?)")) {
                    statement.setBytes(1, UuidBlob.encode(sub.value()));
                    statement.setString(2, "GROUP");
                    statement.setBytes(3, UuidBlob.encode(groupId));
                    statement.setBytes(4, UuidBlob.encode(profileId));
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO land_defaults (land_id, permission, state) VALUES (?, ?, ?)")) {
                    statement.setBytes(1, UuidBlob.encode(land.value()));
                    statement.setString(2, "BLOCK_BREAK");
                    statement.setString(3, "DENY");
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO subland_defaults (subland_id, permission, state)"
                                + " VALUES (?, ?, ?)")) {
                    statement.setBytes(1, UuidBlob.encode(sub.value()));
                    statement.setString(2, "BLOCK_BREAK");
                    statement.setString(3, "ALLOW");
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO land_rules (land_id, rule_type, state) VALUES (?, ?, ?)")) {
                    statement.setBytes(1, UuidBlob.encode(land.value()));
                    statement.setString(2, "FIRE_SPREAD");
                    statement.setString(3, "DENY");
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO subland_rules (subland_id, rule_type, state)"
                                + " VALUES (?, ?, ?)")) {
                    statement.setBytes(1, UuidBlob.encode(sub.value()));
                    statement.setString(2, "FIRE_SPREAD");
                    statement.setString(3, "ALLOW");
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO land_entry_bans (land_id, player_uuid, banned_at, banned_by)"
                                + " VALUES (?, ?, ?, ?)")) {
                    statement.setBytes(1, UuidBlob.encode(land.value()));
                    statement.setBytes(2, UuidBlob.encode(banned));
                    statement.setLong(3, NOW.toEpochMilli());
                    statement.setBytes(4, UuidBlob.encode(actor));
                    statement.executeUpdate();
                }
                return null;
            });

            // A pre-delete audit row proves history is not cascaded away.
            UUID operationId = UUID.randomUUID();
            OperationPayload payload = OperationPayload.delete(operationId, actor, world, land,
                    List.of(new OperationPayload.Chunk(key, 64, lot, 250L)), 250L,
                    "test-economy", NOW, "Home");
            ledger.create(payload).toCompletableFuture().join();
            AuditEntry audit = new AuditEntry(0, NOW, actor, "LAND_DELETE", land, world,
                    key.pack(), OperationPayload.CURRENT_SCHEMA_VERSION, null, payload.toJson(),
                    "{\"refundMinorUnits\":250,\"costBasisMinorUnits\":250,\"chunks\":1}",
                    new ArrayList<>(List.of(key)));
            ledger.commitDeleteAtomically(new DeleteCommit(operationId, land, world, owner, 4,
                    List.of(new OperationPayload.Chunk(key, 64, lot, 250L)), 250L, audit))
                    .toCompletableFuture().join();

            // Land-owned rows are gone.
            assertTrue(lands.findById(land).toCompletableFuture().join().isEmpty());
            assertEquals(0, count(store, "SELECT COUNT(*) FROM land_chunks WHERE land_id = ?", land));
            assertEquals(0, count(store, "SELECT COUNT(*) FROM sublands WHERE land_id = ?", land));
            assertEquals(0, count(store, "SELECT COUNT(*) FROM land_bindings WHERE land_id = ?", land));
            assertEquals(0, count(store, "SELECT COUNT(*) FROM land_defaults WHERE land_id = ?", land));
            assertEquals(0, count(store, "SELECT COUNT(*) FROM land_rules WHERE land_id = ?", land));
            assertEquals(0,
                    count(store, "SELECT COUNT(*) FROM land_entry_bans WHERE land_id = ?", land));
            assertEquals(0, countAll(store, "subland_bindings"));
            assertEquals(0, countAll(store, "subland_defaults"));
            assertEquals(0, countAll(store, "subland_rules"));
            assertEquals(LedgerState.DOMAIN_COMMITTED.name(),
                    ledger.find(operationId).toCompletableFuture().join().state());

            // The audit history keeps the deleted land id.
            List<AuditEntry> history = audits.findByLand(land, 10, 0).toCompletableFuture().join();
            assertEquals(1, history.size());
            assertEquals("LAND_DELETE", history.get(0).action());
            assertEquals(land, history.get(0).landId());

            // Group and profile survive and stay reusable.
            assertEquals(1, countAll(store, "subject_groups"));
            assertEquals(1, countAll(store, "permission_profiles"));
            store.execute(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM permission_profiles WHERE id = ?")) {
                    statement.setBytes(1, UuidBlob.encode(profileId));
                    statement.executeUpdate();
                    return null;
                }
            });
            assertEquals(0, countAll(store, "permission_profiles"));
        }
    }

    @Test
    void staleRevisionAbortsDeleteAtomically() throws Exception {
        try (PersistenceStore store = db()) {
            OperationLedger ledger = new OperationLedger(store);
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);

            UUID world = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            LandId land = new LandId(UUID.randomUUID());
            ChunkKey key = new ChunkKey(world, 0, 0);
            lands.save(land(world, land, owner, key)).toCompletableFuture().join();
            UUID lot = UUID.randomUUID();
            chunks.addChunk(land, key, 64, lot, 100L).toCompletableFuture().join();

            UUID operationId = UUID.randomUUID();
            OperationPayload payload = OperationPayload.delete(operationId, actor, world, land,
                    List.of(new OperationPayload.Chunk(key, 64, lot, 100L)), 100L,
                    "test-economy", NOW, "Home");
            ledger.create(payload).toCompletableFuture().join();
            AuditEntry audit = new AuditEntry(0, NOW, actor, "LAND_DELETE", land, world,
                    key.pack(), OperationPayload.CURRENT_SCHEMA_VERSION, null, payload.toJson(),
                    "{}", new ArrayList<>(List.of(key)));
            java.util.concurrent.CompletionException stale =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            java.util.concurrent.CompletionException.class,
                            () -> ledger.commitDeleteAtomically(new DeleteCommit(operationId, land,
                                    world, owner, 99,
                                    List.of(new OperationPayload.Chunk(key, 64, lot, 100L)), 100L,
                                    audit)).toCompletableFuture().join());
            assertTrue(containsInChain(stale, "stale land structure revision"),
                    "stale revision must abort the commit, got " + chainMessages(stale));
            // Nothing moved: land, chunk and ledger row are untouched.
            assertTrue(lands.findById(land).toCompletableFuture().join().isPresent());
            assertEquals(1, count(store, "SELECT COUNT(*) FROM land_chunks WHERE land_id = ?", land));
            assertEquals(LedgerState.CREATED.name(),
                    ledger.find(operationId).toCompletableFuture().join().state());
        }
    }
}

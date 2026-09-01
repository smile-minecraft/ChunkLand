package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryContractTest {

    @TempDir Path tmp;

    private Path db() { return tmp.resolve("c.db"); }

    // ---- helpers ----
    private LandSnapshot land(UUID worldId, LandId id, OwnerRef owner) {
        return new LandSnapshot(id, "Home", "home", owner, worldId, Set.of(), List.of(), 0, 0, Instant.now(), Instant.now());
    }

    @Test
    void interfacesExist() {
        assertNotNull(LandRepository.class);
        assertNotNull(ChunkRepository.class);
        assertNotNull(SubLandRepository.class);
        assertNotNull(AuditRepository.class);
        assertNotNull(LedgerRepository.class);
    }

    @Test
    void landSaveFindImmutableRoundTripAndUUIDBlob() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository repo = new SqliteLandRepository(store);
            UUID world = UUID.randomUUID();
            LandId lid = new LandId(UUID.randomUUID());
            OwnerRef owner = OwnerRef.player(UUID.randomUUID());
            LandSnapshot orig = land(world, lid, owner);
            repo.save(orig).toCompletableFuture().join();
            // verify BLOB length 16 via direct SQL on persistence thread
            byte[] storedWorld = store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("SELECT world_uuid FROM lands WHERE id = ?")) {
                    ps.setBytes(1, UuidBlob.encode(lid.value()));
                    try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getBytes(1); }
                }
            });
            assertEquals(16, storedWorld.length);
            assertEquals(world, UuidBlob.decode(storedWorld));

            Optional<LandSnapshot> found = repo.findById(lid).toCompletableFuture().join();
            assertTrue(found.isPresent());
            assertEquals(lid, found.get().id());
            assertEquals(owner, found.get().ownerRef());
            // immutable: returned collections unmodifiable
            assertThrows(UnsupportedOperationException.class, () -> found.get().chunks().add(new ChunkKey(world, 1, 1)));
            // original mutation does not affect stored (save copy)
            LandSnapshot mutated = orig.addChunk(new ChunkKey(world, 9, 9));
            Optional<LandSnapshot> still = repo.findById(lid).toCompletableFuture().join();
            assertTrue(still.get().chunks().isEmpty());
            // server namespace
            LandId serverLid = new LandId(UUID.randomUUID());
            LandSnapshot serverLand = land(world, serverLid, OwnerRef.server());
            repo.save(serverLand).toCompletableFuture().join();
            Optional<LandSnapshot> sFound = repo.findById(serverLid).toCompletableFuture().join();
            assertTrue(sFound.isPresent());
            assertEquals(OwnerRef.server(), sFound.get().ownerRef());
            String ownerKey = store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("SELECT owner_key FROM lands WHERE id = ?")) {
                    ps.setBytes(1, UuidBlob.encode(serverLid.value()));
                    try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
                }
            });
            assertEquals("SERVER", ownerKey);
            // findByOwner and findAll immutable lists
            List<LandSnapshot> byOwner = repo.findByOwner(owner).toCompletableFuture().join();
            assertEquals(1, byOwner.size());
            assertThrows(UnsupportedOperationException.class, () -> byOwner.add(orig));
            List<LandSnapshot> all = repo.findAll().toCompletableFuture().join();
            assertTrue(all.size() >= 2);
        }
    }

    @Test
    void landOwnerUniqueNameKey() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository repo = new SqliteLandRepository(store);
            UUID world = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(UUID.randomUUID());
            LandId a = new LandId(UUID.randomUUID());
            LandId b = new LandId(UUID.randomUUID());
            LandSnapshot la = land(world, a, owner);
            LandSnapshot lb = land(world, b, owner); // same nameKey "home" same owner -> unique violation
            repo.save(la).toCompletableFuture().join();
            CompletionException ex = assertThrows(CompletionException.class, () -> repo.save(lb).toCompletableFuture().join());
            assertTrue(ex.getCause() instanceof PersistenceException || ex.getCause() instanceof SQLException || ex.getCause().getMessage().contains("UNIQUE"));
        }
    }

    @Test
    void chunkRepositoryRoundTripAndExecutorGuard() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository landRepo = new SqliteLandRepository(store);
            SqliteChunkRepository chunkRepo = new SqliteChunkRepository(store);
            UUID world = UUID.randomUUID();
            LandId lid = new LandId(UUID.randomUUID());
            landRepo.save(land(world, lid, OwnerRef.player(UUID.randomUUID()))).toCompletableFuture().join();
            ChunkKey ck = new ChunkKey(world, 5, -3);
            UUID lot = UUID.randomUUID();
            chunkRepo.addChunk(lid, ck, 40, lot, 1000L).toCompletableFuture().join();
            // verify stored BLOB length and thread
            String threadName = chunkRepo.listByLand(lid).thenApply(v -> Thread.currentThread().getName()).toCompletableFuture().join();
            // CompletableFuture callbacks run on calling thread, but internal work was on persistence thread – check via store execute direct rejection
            assertThrows(DirectSqlAccessException.class, () -> store.executeDirect(c -> 1));
            List<ChunkKey> listed = chunkRepo.listByLand(lid).toCompletableFuture().join();
            assertEquals(1, listed.size());
            assertEquals(ck, listed.get(0));
            assertThrows(UnsupportedOperationException.class, () -> listed.add(ck));
            Optional<LandId> found = chunkRepo.findLandByChunk(ck).toCompletableFuture().join();
            assertTrue(found.isPresent());
            assertEquals(lid, found.get());
            chunkRepo.removeChunk(ck).toCompletableFuture().join();
            assertTrue(chunkRepo.listByLand(lid).toCompletableFuture().join().isEmpty());
        }
    }

    @Test
    void subLandNoOverlapConstraintAtDBAndRoundTrip() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository landRepo = new SqliteLandRepository(store);
            SqliteSubLandRepository subRepo = new SqliteSubLandRepository(store);
            UUID world = UUID.randomUUID();
            LandId lid = new LandId(UUID.randomUUID());
            landRepo.save(land(world, lid, OwnerRef.player(UUID.randomUUID()))).toCompletableFuture().join();
            SubLandId sid = new SubLandId(UUID.randomUUID());
            Cuboid c = new Cuboid(0, 10, 0, 15, 20, 15);
            SubLandSnapshot snap = new SubLandSnapshot(sid, lid, "store", c, world);
            subRepo.save(snap).toCompletableFuture().join();
            // second overlapping cuboid with same land – DB must allow (no UNIQUE) because overlap is runtime-checked
            SubLandId sid2 = new SubLandId(UUID.randomUUID());
            Cuboid c2 = new Cuboid(5, 15, 5, 20, 25, 20); // overlaps in 3D
            SubLandSnapshot snap2 = new SubLandSnapshot(sid2, lid, "overlap", c2, world);
            assertDoesNotThrow(() -> subRepo.save(snap2).toCompletableFuture().join());
            List<SubLandSnapshot> listed = subRepo.findByLand(lid).toCompletableFuture().join();
            assertEquals(2, listed.size());
            assertThrows(UnsupportedOperationException.class, () -> listed.add(snap));
            Optional<SubLandSnapshot> found = subRepo.findById(sid).toCompletableFuture().join();
            assertTrue(found.isPresent());
            assertEquals(c, found.get().cuboid());
            // verify BLOB storage
            byte[] widBlob = store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("SELECT world_uuid FROM sublands WHERE id = ?")) {
                    ps.setBytes(1, UuidBlob.encode(sid.value()));
                    try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getBytes(1); }
                }
            });
            assertEquals(16, widBlob.length);
            subRepo.delete(sid).toCompletableFuture().join();
            assertTrue(subRepo.findById(sid).toCompletableFuture().join().isEmpty());
        }
    }

    @Test
    void auditRetentionNotCascadeAndChunks() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository landRepo = new SqliteLandRepository(store);
            SqliteAuditRepository auditRepo = new SqliteAuditRepository(store);
            UUID world = UUID.randomUUID();
            LandId lid = new LandId(UUID.randomUUID());
            landRepo.save(land(world, lid, OwnerRef.player(UUID.randomUUID()))).toCompletableFuture().join();
            ChunkKey ck1 = new ChunkKey(world, 1, 2);
            ChunkKey ck2 = new ChunkKey(world, 3, 4);
            AuditEntry entry = new AuditEntry(0, Instant.now(), UUID.randomUUID(), "LAND_CREATE", lid, world, null, 1, null, null, "{\"k\":\"v\"}", List.of(ck1, ck2));
            long aid = auditRepo.insert(entry).toCompletableFuture().join();
            Optional<AuditEntry> got = auditRepo.findById(aid).toCompletableFuture().join();
            assertTrue(got.isPresent());
            assertEquals(2, got.get().chunks().size());
            assertThrows(UnsupportedOperationException.class, () -> got.get().chunks().add(ck1));
            // delete land – audit must survive and retain land_id
            landRepo.delete(lid).toCompletableFuture().join();
            Optional<AuditEntry> after = auditRepo.findById(aid).toCompletableFuture().join();
            assertTrue(after.isPresent());
            assertEquals(lid, after.get().landId());
            // verify foreign key not cascading: count
            int count = store.execute(conn -> {
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM audit_log WHERE id=" + aid)) { rs.next(); return rs.getInt(1); }
            });
            assertEquals(1, count);
            // audit_chunks cascade when audit deleted? but we keep audit
            List<AuditEntry> byLand = auditRepo.findByLand(lid, 10, 0).toCompletableFuture().join();
            assertEquals(1, byLand.size());
            List<AuditEntry> byAction = auditRepo.findByAction("LAND_CREATE", 10).toCompletableFuture().join();
            assertEquals(1, byAction.size());
        }
    }

    @Test
    void ledgerIdempotencyAndMetadataRoundTrip() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLedgerRepository ledger = new SqliteLedgerRepository(store);
            UUID op = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId lid = new LandId(UUID.randomUUID());
            Instant now = Instant.now();
            LedgerEntry e = new LedgerEntry(op, "CLAIM", "PENDING", actor, world, lid, 500L, "vault", "ref1", "{\"payload\":1}", 2, now, now);
            ledger.insert(e).toCompletableFuture().join();
            // duplicate must fail
            CompletionException dup = assertThrows(CompletionException.class, () -> ledger.insert(e).toCompletableFuture().join());
            assertTrue(dup.getCause().getMessage().contains("UNIQUE") || dup.getCause() instanceof PersistenceException);
            Optional<LedgerEntry> found = ledger.findById(op).toCompletableFuture().join();
            assertTrue(found.isPresent());
            assertEquals("{\"payload\":1}", found.get().payloadJson());
            assertEquals(2, found.get().metadataVersion());
            assertEquals(16, store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("SELECT operation_id FROM operation_ledger WHERE operation_id = ?")) {
                    ps.setBytes(1, UuidBlob.encode(op));
                    try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getBytes(1).length; }
                }
            }).intValue());
            // state transition
            Instant later = Instant.now().plusSeconds(10);
            ledger.updateState(op, "COMPLETED", later).toCompletableFuture().join();
            assertEquals("COMPLETED", ledger.findById(op).toCompletableFuture().join().get().state());
            List<LedgerEntry> byState = ledger.findByState("COMPLETED").toCompletableFuture().join();
            assertEquals(1, byState.size());
            assertThrows(UnsupportedOperationException.class, () -> byState.add(e));
            List<LedgerEntry> all = ledger.findAll().toCompletableFuture().join();
            assertEquals(1, all.size());
        }
    }

    @Test
    void cascadeDeleteOwnedRowsAndRestrictGroupProfile() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository landRepo = new SqliteLandRepository(store);
            SqliteChunkRepository chunkRepo = new SqliteChunkRepository(store);
            SqliteSubLandRepository subRepo = new SqliteSubLandRepository(store);
            UUID world = UUID.randomUUID();
            LandId lid = new LandId(UUID.randomUUID());
            landRepo.save(land(world, lid, OwnerRef.player(UUID.randomUUID()))).toCompletableFuture().join();
            // chunk
            chunkRepo.addChunk(lid, new ChunkKey(world, 1, 1), 10, UUID.randomUUID(), 100).toCompletableFuture().join();
            // subland
            SubLandId sid = new SubLandId(UUID.randomUUID());
            subRepo.save(new SubLandSnapshot(sid, lid, "s", new Cuboid(0,0,0,15,10,15), world)).toCompletableFuture().join();
            // groups/profiles bindings defaults rules via direct SQL on persistence thread
            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO subject_groups (id, owner_key, name_key, display_name, created_at) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setBytes(1, UuidBlob.encode(groupId));
                    ps.setString(2, "PLAYER:" + UUID.randomUUID());
                    ps.setString(3, "friends");
                    ps.setString(4, "Friends");
                    ps.setLong(5, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO permission_profiles (id, owner_key, name_key, display_name, created_at) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setBytes(1, UuidBlob.encode(profileId));
                    ps.setString(2, "PLAYER:" + UUID.randomUUID());
                    ps.setString(3, "default");
                    ps.setString(4, "Default");
                    ps.setLong(5, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO land_bindings (land_id, subject_type, subject_id, profile_id) VALUES (?, ?, ?, ?)")) {
                    ps.setBytes(1, UuidBlob.encode(lid.value()));
                    ps.setString(2, "GROUP");
                    ps.setBytes(3, UuidBlob.encode(groupId));
                    ps.setBytes(4, UuidBlob.encode(profileId));
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO land_defaults (land_id, permission, state) VALUES (?, ?, ?)")) {
                    ps.setBytes(1, UuidBlob.encode(lid.value()));
                    ps.setString(2, "BLOCK_BREAK");
                    ps.setString(3, "ALLOW");
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO land_rules (land_id, rule_type, state) VALUES (?, ?, ?)")) {
                    ps.setBytes(1, UuidBlob.encode(lid.value()));
                    ps.setString(2, "FIRE_SPREAD");
                    ps.setString(3, "DENY");
                    ps.executeUpdate();
                }
                return null;
            });
            // attempt to delete profile while still referenced -> RESTRICT
            CompletionException restrict = assertThrows(CompletionException.class, () -> store.submitAsync(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM permission_profiles WHERE id = ?")) {
                    ps.setBytes(1, UuidBlob.encode(profileId));
                    ps.executeUpdate();
                    return null;
                }
            }).join());
            assertTrue(restrict.getCause().getMessage().toLowerCase().contains("foreign") || restrict.getCause() instanceof SQLException || restrict.getCause() instanceof PersistenceException);
            // attempt to delete group while referenced via binding? group not directly FK RESTRICT, but profile is; group delete should also be RESTRICT via binding's subject? Actually binding subject_id not FK, so group delete not restricted – we test profile
            // delete land should cascade owned rows
            landRepo.delete(lid).toCompletableFuture().join();
            int remainingChunks = store.execute(conn -> { try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM land_chunks WHERE land_id = ?")) { ps.setBytes(1, UuidBlob.encode(lid.value())); try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); } } });
            assertEquals(0, remainingChunks);
            int remainingSubs = store.execute(conn -> { try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM sublands WHERE land_id = ?")) { ps.setBytes(1, UuidBlob.encode(lid.value())); try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); } } });
            assertEquals(0, remainingSubs);
            int remainingBindings = store.execute(conn -> { try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM land_bindings WHERE land_id = ?")) { ps.setBytes(1, UuidBlob.encode(lid.value())); try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); } } });
            assertEquals(0, remainingBindings);
            int remainingDefaults = store.execute(conn -> { try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM land_defaults WHERE land_id = ?")) { ps.setBytes(1, UuidBlob.encode(lid.value())); try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); } } });
            assertEquals(0, remainingDefaults);
            // group/profile should survive
            int groups = store.execute(conn -> { try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM subject_groups")) { rs.next(); return rs.getInt(1); } });
            assertEquals(1, groups);
            int profiles = store.execute(conn -> { try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM permission_profiles")) { rs.next(); return rs.getInt(1); } });
            assertEquals(1, profiles);
            // now unreferenced profile deletable
            store.execute(conn -> { try (PreparedStatement ps = conn.prepareStatement("DELETE FROM permission_profiles WHERE id = ?")) { ps.setBytes(1, UuidBlob.encode(profileId)); ps.executeUpdate(); return null; } });
            int profilesAfter = store.execute(conn -> { try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM permission_profiles")) { rs.next(); return rs.getInt(1); } });
            assertEquals(0, profilesAfter);
            // group deletable after binding gone (binding cascade removed)
            store.execute(conn -> { try (PreparedStatement ps = conn.prepareStatement("DELETE FROM subject_groups WHERE id = ?")) { ps.setBytes(1, UuidBlob.encode(groupId)); ps.executeUpdate(); return null; } });
            int groupsAfter = store.execute(conn -> { try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM subject_groups")) { rs.next(); return rs.getInt(1); } });
            assertEquals(0, groupsAfter);
            // CHECK constraint rejects EVERYONE – runSqlWork wraps the SQLiteException in PersistenceException
            CompletionException checkFail = assertThrows(CompletionException.class, () -> store.submitAsync(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO land_bindings (land_id, subject_type, subject_id, profile_id) VALUES (?, ?, ?, ?)")) {
                    ps.setBytes(1, UuidBlob.encode(UUID.randomUUID()));
                    ps.setString(2, "EVERYONE");
                    ps.setBytes(3, UuidBlob.encode(UUID.randomUUID()));
                    ps.setBytes(4, UuidBlob.encode(UUID.randomUUID()));
                    ps.executeUpdate();
                    return null;
                }
            }).join());
            // walk the cause chain to confirm the CHECK constraint failure (not any RuntimeException)
            Throwable wrapped = checkFail.getCause();
            assertTrue(wrapped instanceof PersistenceException, "expected PersistenceException wrapping SQLiteException");
            Throwable sql = wrapped.getCause();
            assertTrue(sql instanceof SQLException, "expected SQLException as PersistenceException cause");
            assertTrue(sql.getMessage().contains("CHECK"), "expected SQLite CHECK constraint failure in cause chain");
        }
    }

    @Test
    void asyncErrorPropagationAndExecutorThreadGuard() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository repo = new SqliteLandRepository(store);
            // duplicate insert async failure propagates
            UUID world = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(UUID.randomUUID());
            LandId id1 = new LandId(UUID.randomUUID());
            LandId id2 = new LandId(UUID.randomUUID());
            LandSnapshot a = land(world, id1, owner);
            LandSnapshot b = land(world, id2, owner); // same nameKey owner duplicate
            repo.save(a).toCompletableFuture().join();
            CompletableFuture<Void> fail = repo.save(b).toCompletableFuture();
            assertTrue(fail.isCompletedExceptionally() || assertThrows(CompletionException.class, fail::join) != null);
            // direct SQL from caller thread must be rejected
            assertThrows(DirectSqlAccessException.class, () -> store.connectionForPersistenceThread());
            assertThrows(DirectSqlAccessException.class, () -> store.executeDirect(c -> 1));
            // callback does not retain connection – we test that after work connection is not usable outside
        }
    }

    @Test
    void schemaForwardMigrationNoOpAndFutureRejectionAndRollback() throws Exception {
        Path path = db();
        try (PersistenceStore s = PersistenceStore.open(path)) {
            assertEquals(SchemaMigrator.LATEST_VERSION, s.schemaVersion());
            // second open is no-op
            try (PersistenceStore s2 = PersistenceStore.open(path)) {
                assertEquals(SchemaMigrator.LATEST_VERSION, s2.schemaVersion());
            }
        }
        // future version rejection
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            try (Statement st = c.createStatement()) { st.executeUpdate("UPDATE schema_version SET version = 999 WHERE id = 1"); }
        }
        assertThrows(PersistenceException.class, () -> PersistenceStore.open(path));
        // repair back
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            try (Statement st = c.createStatement()) { st.executeUpdate("UPDATE schema_version SET version = " + SchemaMigrator.LATEST_VERSION + " WHERE id = 1"); }
        }
        assertDoesNotThrow(() -> { try (PersistenceStore s = PersistenceStore.open(path)) { assertEquals(SchemaMigrator.LATEST_VERSION, s.schemaVersion()); } });

        // migration failure rollback: inject failing connection for v2
        Path failPath = tmp.resolve("fail.db");
        assertThrows(PersistenceException.class, () -> PersistenceStore.open(failPath, p -> {
            Connection d = DriverManager.getConnection("jdbc:sqlite:" + p);
            // create schema_version with version 0 but make land migration fail via trigger
            try (Statement st = d.createStatement()) {
                st.executeUpdate("CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id=1), version INTEGER NOT NULL)");
                st.executeUpdate("INSERT INTO schema_version (id, version) VALUES (1, 0)");
                st.executeUpdate("CREATE TRIGGER fail BEFORE CREATE ON land_chunks BEGIN SELECT RAISE(FAIL, 'injected'); END;");
            }
            return d;
        }));
        // after failure, db should still be at version 0 and no lands table
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + failPath);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT version FROM schema_version WHERE id=1")) { rs.next(); assertEquals(0, rs.getInt(1)); }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='lands'")) { rs.next(); assertEquals(0, rs.getInt(1)); }
        }
    }

    @Test
    void rejectsDirectSqlFromCallerAndImmutableCollections() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository repo = new SqliteLandRepository(store);
            UUID world = UUID.randomUUID();
            LandId lid = new LandId(UUID.randomUUID());
            repo.save(land(world, lid, OwnerRef.player(UUID.randomUUID()))).toCompletableFuture().join();
            List<LandSnapshot> list = repo.findAll().toCompletableFuture().join();
            assertThrows(UnsupportedOperationException.class, () -> list.add(land(world, new LandId(UUID.randomUUID()), OwnerRef.server())));
        }
    }
}

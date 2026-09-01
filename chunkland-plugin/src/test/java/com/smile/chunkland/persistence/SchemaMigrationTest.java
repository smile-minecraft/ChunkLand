package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaMigrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesFromV0ToV1OnOpen() {
        try (PersistenceStore store = PersistenceStore.open(databasePath())) {
            assertEquals(SchemaMigrator.LATEST_VERSION, store.schemaVersion());
            assertTrue(landChunksExists(store));
        }
    }

    @Test
    void landChunksEnforcesUniqueWorldChunk() {
        try (PersistenceStore store = PersistenceStore.open(databasePath())) {
            insertLandChunk(store, "world", "1,2", OwnerKey.server(), null, 1000L);

            PersistenceException duplicate = assertThrows(
                    PersistenceException.class,
                    () -> insertLandChunk(store, "world", "1,2", OwnerKey.server(), null, 2000L));

            assertTrue(duplicate.getMessage().contains("operation failed"));
            assertEquals(1, countLandChunks(store));
        }
    }

    @Test
    void ownerKeyDistinguishesPlayerAndServerNamespaces() {
        UUID playerId = UUID.randomUUID();
        try (PersistenceStore store = PersistenceStore.open(databasePath())) {
            insertLandChunk(store, "world", "3,4", OwnerKey.player(playerId), playerId, 1000L);
            insertLandChunk(store, "world", "5,6", OwnerKey.server(), null, 1000L);

            assertEquals(
                    "PLAYER:" + playerId,
                    ownerKeyFor(store, "3,4"));
            assertEquals("SERVER", ownerKeyFor(store, "5,6"));
        }

        OwnerKey parsedPlayer = OwnerKey.parse("PLAYER:" + playerId);
        assertTrue(parsedPlayer.isPlayer());
        assertEquals(playerId, parsedPlayer.playerUuid());
        assertTrue(OwnerKey.parse("SERVER").isServer());
        assertFalse(OwnerKey.parse("SERVER").isPlayer());
    }

    @Test
    void uuidStoredAsSixteenByteBlobRoundTrips() {
        UUID original = UUID.randomUUID();
        assertEquals(16, UuidBlob.encode(original).length);

        try (PersistenceStore store = PersistenceStore.open(databasePath())) {
            insertLandChunk(store, "world", "7,8", OwnerKey.player(original), original, 1000L);

            byte[] stored = store.execute(connection -> selectOwnerUuid(connection, "7,8"));
            assertEquals(16, stored.length);
            assertArrayEquals(UuidBlob.encode(original), stored);
            assertEquals(original, UuidBlob.decode(stored));
        }
    }

    @Test
    void migrationFailureRollsBackWithoutPartialSchema() throws SQLException {
        Path path = databasePath();
        assertThrows(
                PersistenceException.class,
                () -> PersistenceStore.open(path, databasePath -> failingLandChunksConnection(databasePath)));

        // The failed open must leave the database untouched: version still 0 and
        // land_chunks never created. A direct read inspects the rollback state
        // without triggering a fresh migration through the store.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                Statement statement = connection.createStatement()) {
            assertEquals(0, readVersion(statement));
            assertEquals(0, countTable(statement, "land_chunks"));
        }
    }

    @Test
    void writeVersionFailureRollsBackWhenUpdateAffectsNoRows() throws SQLException {
        Path path = databasePath();
        // A BEFORE UPDATE trigger that swallows the version UPDATE makes executeUpdate()
        // report 0 affected rows; the migration must fail closed and roll back rather than
        // commit land_chunks with a stale version.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), version INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO schema_version (id, version) VALUES (1, 0)");
            statement.executeUpdate(
                    "CREATE TRIGGER swallow_version_update BEFORE UPDATE ON schema_version "
                            + "BEGIN SELECT RAISE(IGNORE); END");
        }

        assertThrows(PersistenceException.class, () -> PersistenceStore.open(path));

        // Rollback must leave the database untouched: version still 0 and land_chunks absent.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                Statement statement = connection.createStatement()) {
            assertEquals(0, readVersion(statement));
            assertEquals(0, countTable(statement, "land_chunks"));
        }
    }

    private int readVersion(Statement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT version FROM schema_version WHERE id = 1")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int countTable(Statement statement, String name) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '" + name + "'")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    @Test
    void alreadyAtV1IsIdempotentAcrossRestarts() {
        UUID playerId = UUID.randomUUID();
        Path path = databasePath();

        try (PersistenceStore first = PersistenceStore.open(path)) {
            insertLandChunk(first, "world", "9,10", OwnerKey.player(playerId), playerId, 1000L);
        }

        try (PersistenceStore second = PersistenceStore.open(path)) {
            assertEquals(SchemaMigrator.LATEST_VERSION, second.schemaVersion());
            assertEquals(1, countLandChunks(second));
            assertEquals("PLAYER:" + playerId, ownerKeyFor(second, "9,10"));
        }
    }

    @Test
    void enablesForeignKeyEnforcement() {
        try (PersistenceStore store = PersistenceStore.open(databasePath())) {
            int foreignKeys = store.execute(connection -> {
                try (Statement statement = connection.createStatement();
                        ResultSet resultSet = statement.executeQuery("PRAGMA foreign_keys")) {
                    resultSet.next();
                    return resultSet.getInt(1);
                }
            });
            assertEquals(1, foreignKeys, "foreign_keys pragma must be enabled for future cascade FKs");
        }
    }

    // --- Blocker 1: OwnerKey.parse must enforce the namespace contract ---

    @Test
    void ownerKeyParseRejectsUnknownNamespace() {
        assertThrows(IllegalArgumentException.class, () -> OwnerKey.parse("ADMIN"));
        assertThrows(IllegalArgumentException.class, () -> OwnerKey.parse("PLAYER_X:abc"));
    }

    @Test
    void ownerKeyParseRejectsInvalidPlayerUuid() {
        assertThrows(IllegalArgumentException.class, () -> OwnerKey.parse("PLAYER:not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> OwnerKey.parse("PLAYER:12345"));
    }

    @Test
    void ownerKeyParseRejectsEmptyAndNull() {
        assertThrows(IllegalArgumentException.class, () -> OwnerKey.parse(""));
        assertThrows(NullPointerException.class, () -> OwnerKey.parse(null));
    }

    @Test
    void ownerKeyParseRejectsPlayerWithoutUuid() {
        assertThrows(IllegalArgumentException.class, () -> OwnerKey.parse("PLAYER:"));
    }

    @Test
    void ownerKeyParseRejectsNonCanonicalUuid() {
        // UUID.fromString tolerates short/non-canonical hyphenated forms; the contract requires
        // the exact 8-4-4-4-12 canonical shape, so these must be rejected.
        assertThrows(IllegalArgumentException.class, () -> OwnerKey.parse("PLAYER:1-1-1-1-1"));
        assertThrows(IllegalArgumentException.class, () -> OwnerKey.parse("PLAYER:0-0-0-0-0"));
    }

    @Test
    void ownerKeyParseAcceptsCanonicalUppercaseUuid() {
        UUID id = UUID.randomUUID();
        OwnerKey parsed = OwnerKey.parse("PLAYER:" + id.toString().toUpperCase());
        assertTrue(parsed.isPlayer());
        assertEquals(id, parsed.playerUuid());
    }

    // --- Blocker 2: migration cleanup must not mask the primary failure ---

    @Test
    void migrationFailurePreservesPrimaryAndSuppressesRollbackFailure() throws SQLException {
        Path path = databasePath();
        SQLException primary = new SQLException("injected migration commit failure");

        PersistenceException thrown = assertThrows(
                PersistenceException.class,
                () -> PersistenceStore.open(path, db -> failingMigrationConnection(db, primary, true, true, false)));

        Throwable cause = thrown.getCause();
        assertSame(primary, cause, "primary SQLException must be preserved, not masked by cleanup");
        assertEquals(1, cause.getSuppressed().length);
        assertTrue(cause.getSuppressed()[0].getMessage().contains("rollback"));
    }

    @Test
    void migrationFailurePreservesPrimaryAndSuppressesAutoCommitFailure() throws SQLException {
        Path path = databasePath();
        SQLException primary = new SQLException("injected migration commit failure");

        PersistenceException thrown = assertThrows(
                PersistenceException.class,
                () -> PersistenceStore.open(path, db -> failingMigrationConnection(db, primary, true, false, true)));

        Throwable cause = thrown.getCause();
        assertSame(primary, cause);
        assertEquals(1, cause.getSuppressed().length);
        assertTrue(cause.getSuppressed()[0].getMessage().contains("autocommit"));
    }

    @Test
    void migrationFailureWithRuntimeExceptionPrimaryPreservesAndSuppresses() throws SQLException {
        Path path = databasePath();
        RuntimeException primary = new RuntimeException("injected migration commit failure");

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> PersistenceStore.open(path, db -> failingMigrationConnection(db, primary, true, true, false)));

        assertSame(primary, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0].getMessage().contains("rollback"));
    }

    @Test
    void migrationFailureWithErrorPrimaryPreservesAndSuppresses() throws SQLException {
        Path path = databasePath();
        Error primary = new Error("injected migration commit failure");

        Error thrown = assertThrows(
                Error.class,
                () -> PersistenceStore.open(path, db -> failingMigrationConnection(db, primary, true, true, false)));

        assertSame(primary, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0].getMessage().contains("rollback"));
    }

    @Test
    void committedMigrationReportsAutoCommitRestoreFailure() throws SQLException {
        Path path = databasePath();
        // Commit succeeds (failCommit=false) but the post-commit setAutoCommit(true) restore
        // fails; that cleanup failure must be reported rather than swallowed.
        PersistenceException thrown = assertThrows(
                PersistenceException.class,
                () -> PersistenceStore.open(
                        path, db -> failingMigrationConnection(db, null, false, false, true)));

        Throwable cause = thrown.getCause();
        assertTrue(cause.getMessage().contains("autocommit"));
    }

    // --- Blocker 3: schema version validation must fail closed ---

    @Test
    void rejectsTextVersionOnOpen() throws SQLException {
        Path path = databasePath();
        seedSchemaVersion(path, "TEXT", "'1'");
        assertThrows(PersistenceException.class, () -> PersistenceStore.open(path));
    }

    @Test
    void rejectsRealVersionOnOpen() throws SQLException {
        Path path = databasePath();
        seedSchemaVersion(path, "INTEGER", "1.5");
        assertThrows(PersistenceException.class, () -> PersistenceStore.open(path));
    }

    @Test
    void rejectsNegativeVersionOnOpen() throws SQLException {
        Path path = databasePath();
        seedSchemaVersion(path, "INTEGER", "-1");
        assertThrows(PersistenceException.class, () -> PersistenceStore.open(path));
    }

    @Test
    void rejectsOverflowVersionOnOpen() throws SQLException {
        Path path = databasePath();
        seedSchemaVersion(path, "INTEGER", "9999999999");
        assertThrows(PersistenceException.class, () -> PersistenceStore.open(path));
    }

    @Test
    void rejectsExtraRowOnOpen() throws SQLException {
        Path path = databasePath();
        seedSchemaVersionWithExtraRow(path);
        assertThrows(PersistenceException.class, () -> PersistenceStore.open(path));
    }

    @Test
    void migrateFailsClosedOnMalformedVersion() throws SQLException {
        Path path = databasePath();
        seedSchemaVersion(path, "TEXT", "'abc'");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            SQLException failure = assertThrows(
                    SQLException.class, () -> SchemaMigrator.migrate(connection));
            assertTrue(failure.getMessage().contains("integer"));

            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                            "SELECT COUNT(*) FROM sqlite_master "
                                    + "WHERE type = 'table' AND name = 'land_chunks'")) {
                resultSet.next();
                assertEquals(0, resultSet.getInt(1), "migration must not run on a malformed version");
            }
        }
    }

    @Test
    void schemaVersionReaderRejectsMissingRow() throws SQLException {
        Path path = databasePath();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE schema_version ("
                            + "id INTEGER PRIMARY KEY CHECK (id = 1), version INTEGER NOT NULL)");
            assertThrows(SQLException.class, () -> SchemaVersion.readCurrent(connection));
        }
    }

    // --- Blocker 4: v1 -> v2 must backfill legacy land_chunks rows ---

    @Test
    void v1ToV2BackfillsLegacyRowsIntoReadableLands() throws SQLException {
        Path path = databasePath();
        UUID playerId = UUID.randomUUID();
        UUID uuidWorld = UUID.randomUUID();
        seedV1Database(
                path,
                new LegacyChunkRow(1L, "world", "1,2", "PLAYER:" + playerId, playerId, 1000L),
                new LegacyChunkRow(2L, "world", "3,4", "SERVER", null, 2000L),
                new LegacyChunkRow(3L, uuidWorld.toString(), "-5,6", "PLAYER:" + playerId, playerId, 3000L));

        try (PersistenceStore store = PersistenceStore.open(path)) {
            assertEquals(SchemaMigrator.LATEST_VERSION, store.schemaVersion());

            SqliteChunkRepository chunkRepo = new SqliteChunkRepository(store);
            SqliteLandRepository landRepo = new SqliteLandRepository(store);

            // deterministic synthetic lands; legacy id 1 -> landA, 2 -> landB, 3 -> landC
            LandId landA = legacySyntheticLandId(1L);
            LandId landB = legacySyntheticLandId(2L);
            LandId landC = legacySyntheticLandId(3L);

            // findLandByChunk round-trips through world_uuid/chunk_x/chunk_z
            UUID legacyWorldUuid = legacyWorldUuid("world");
            Optional<LandId> foundA = chunkRepo.findLandByChunk(new ChunkKey(legacyWorldUuid, 1, 2))
                    .toCompletableFuture().join();
            assertTrue(foundA.isPresent(), "legacy world 'world' + chunk '1,2' must be findable");
            assertEquals(landA, foundA.get());

            Optional<LandId> foundB = chunkRepo.findLandByChunk(new ChunkKey(legacyWorldUuid, 3, 4))
                    .toCompletableFuture().join();
            assertTrue(foundB.isPresent(), "legacy world 'world' + chunk '3,4' must be findable");
            assertEquals(landB, foundB.get());

            // UUID-shaped legacy world strings are preserved verbatim, including negative chunk coords
            Optional<LandId> foundC = chunkRepo.findLandByChunk(new ChunkKey(uuidWorld, -5, 6))
                    .toCompletableFuture().join();
            assertTrue(foundC.isPresent(), "UUID-string legacy world + chunk '-5,6' must be findable");
            assertEquals(landC, foundC.get());

            // listByLand returns each legacy chunk by its synthetic land id
            List<ChunkKey> listedA = chunkRepo.listByLand(landA).toCompletableFuture().join();
            assertEquals(List.of(new ChunkKey(legacyWorldUuid, 1, 2)), listedA);

            List<ChunkKey> listedB = chunkRepo.listByLand(landB).toCompletableFuture().join();
            assertEquals(List.of(new ChunkKey(legacyWorldUuid, 3, 4)), listedB);

            List<ChunkKey> listedC = chunkRepo.listByLand(landC).toCompletableFuture().join();
            assertEquals(List.of(new ChunkKey(uuidWorld, -5, 6)), listedC);

            // SqliteLandRepository.findById returns a fully readable LandSnapshot
            Optional<LandSnapshot> byIdA = landRepo.findById(landA).toCompletableFuture().join();
            assertTrue(byIdA.isPresent());
            LandSnapshot snapA = byIdA.get();
            assertEquals(landA, snapA.id());
            assertEquals(OwnerRef.player(playerId), snapA.ownerRef());
            assertEquals(legacyWorldUuid, snapA.worldId());
            assertEquals(List.of(new ChunkKey(legacyWorldUuid, 1, 2)), List.copyOf(snapA.chunks()));
            assertEquals(0L, snapA.structureRevision());
            assertEquals(0L, snapA.landPolicyRevision());

            Optional<LandSnapshot> byIdB = landRepo.findById(landB).toCompletableFuture().join();
            assertTrue(byIdB.isPresent());
            assertEquals(OwnerRef.server(), byIdB.get().ownerRef());
            assertEquals(legacyWorldUuid, byIdB.get().worldId());

            Optional<LandSnapshot> byIdC = landRepo.findById(landC).toCompletableFuture().join();
            assertTrue(byIdC.isPresent());
            assertEquals(uuidWorld, byIdC.get().worldId());

            // legacy land_chunks columns world/chunk/owner_key/owner_uuid/claimed_at preserved
            assertEquals("world", readLegacyChunkWorld(store, 1L));
            assertEquals("1,2", readLegacyChunkChunk(store, 1L));
            assertEquals("PLAYER:" + playerId, readLegacyChunkOwnerKey(store, 1L));
            assertEquals(1000L, readLegacyChunkClaimedAt(store, 1L));
        }
    }

    @Test
    void v1ToV2BackfillIsDeterministicAcrossRestart() throws SQLException {
        Path path = databasePath();
        UUID playerId = UUID.randomUUID();
        seedV1Database(
                path,
                new LegacyChunkRow(7L, "world", "11,12", "PLAYER:" + playerId, playerId, 4000L));

        LandId firstSynthetic;
        try (PersistenceStore first = PersistenceStore.open(path)) {
            SqliteLandRepository landRepo = new SqliteLandRepository(first);
            firstSynthetic = legacySyntheticLandId(7L);
            assertTrue(landRepo.findById(firstSynthetic).toCompletableFuture().join().isPresent());
        }
        try (PersistenceStore second = PersistenceStore.open(path)) {
            SqliteLandRepository landRepo = new SqliteLandRepository(second);
            Optional<LandSnapshot> again = landRepo.findById(firstSynthetic).toCompletableFuture().join();
            assertTrue(again.isPresent());
            assertEquals(firstSynthetic, again.get().id());
        }
    }

    @Test
    void v1ToV2BackfillRejectsMalformedChunkText() throws SQLException {
        Path path = databasePath();
        UUID playerId = UUID.randomUUID();
        seedV1Database(
                path,
                new LegacyChunkRow(1L, "world", "not-a-chunk", "PLAYER:" + playerId, playerId, 1000L));

        assertThrows(PersistenceException.class, () -> PersistenceStore.open(path));

        // rollback must leave version, schema, and legacy rows untouched: the v2
        // ALTER TABLE additions are gone, so the v1 land_chunks schema is what
        // we observe.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                Statement statement = connection.createStatement()) {
            assertEquals(1, readVersion(statement));
            assertEquals(1, countTable(statement, "land_chunks"));
            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) FROM land_chunks")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "legacy land_chunks row must survive rollback");
            }
            assertEquals(0, countTable(statement, "lands"));
            assertEquals(0, countTable(statement, "sublands"));
            assertEquals(0, countTable(statement, "subject_groups"));
            assertEquals(0, countTable(statement, "permission_profiles"));
            assertEquals(0, countTable(statement, "audit_log"));
            assertEquals(0, countTable(statement, "operation_ledger"));
        }
    }

    @Test
    void v1ToV2BackfillRejectsMalformedOwnerKey() throws SQLException {
        Path path = databasePath();
        seedV1Database(
                path,
                new LegacyChunkRow(1L, "world", "1,2", "GUILD:rogue", null, 1000L));

        assertThrows(PersistenceException.class, () -> PersistenceStore.open(path));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                Statement statement = connection.createStatement()) {
            assertEquals(1, readVersion(statement));
            assertEquals(1, countTable(statement, "land_chunks"));
            assertEquals(0, countTable(statement, "lands"));
            assertEquals(0, countTable(statement, "sublands"));
            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) FROM land_chunks WHERE owner_key = 'GUILD:rogue'")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "the malformed legacy row must survive rollback");
            }
        }
    }

    @Test
    void v1ToV2MidBackfillFailureRollsBackSchemaAndData() throws SQLException {
        // A mid-backfill failure (e.g. malformed chunk on the 2nd legacy row) must
        // roll back the entire migration transaction: version stays at 1, the v2
        // tables are absent, and legacy rows are unchanged.
        Path path = temporaryDirectory.resolve("mid-backfill.db");
        UUID playerId = UUID.randomUUID();
        seedV1Database(
                path,
                new LegacyChunkRow(1L, "world", "1,2", "PLAYER:" + playerId, playerId, 1000L),
                new LegacyChunkRow(2L, "world", "garbage", "SERVER", null, 2000L));

        assertThrows(PersistenceException.class, () -> PersistenceStore.open(path));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                Statement statement = connection.createStatement()) {
            assertEquals(1, readVersion(statement));
            assertEquals(1, countTable(statement, "land_chunks"));
            assertEquals(0, countTable(statement, "lands"));
            assertEquals(0, countTable(statement, "sublands"));
            assertEquals(0, countTable(statement, "subject_groups"));
            assertEquals(0, countTable(statement, "permission_profiles"));
            assertEquals(0, countTable(statement, "audit_log"));
            assertEquals(0, countTable(statement, "operation_ledger"));
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM land_chunks")) {
                rs.next();
                assertEquals(2, rs.getInt(1), "both legacy rows must survive rollback");
            }
        }
    }

    // --- Follow-up regression: legacy text normalization must not silently collide ---

    @Test
    void v1ToV2BackfillRejectsCanonicalCoordinateCollision() throws SQLException {
        // Two legacy rows pass the v1 UNIQUE(world, chunk) check because their raw
        // text differs, but they map to the same canonical (world_uuid, chunk_x,
        // chunk_z). The migration must fail closed: keeping one row and silently
        // losing the other would violate the no-loss upgrade contract because the
        // v2 repositories only read back one land per coordinate.
        Path path = databasePath();
        UUID playerId = UUID.randomUUID();
        UUID uuidWorld = UUID.randomUUID();
        seedV1Database(
                path,
                new LegacyChunkRow(1L, uuidWorld.toString(), "1,2", "PLAYER:" + playerId, playerId, 1000L),
                new LegacyChunkRow(2L, uuidWorld.toString(), "01,2", "SERVER", null, 2000L));

        PersistenceException thrown = assertThrows(
                PersistenceException.class, () -> PersistenceStore.open(path));
        Throwable cause = thrown.getCause();
        assertNotNull(cause);
        assertTrue(
                cause.getMessage().contains("collision"),
                "cause must describe the coordinate collision, got: " + cause.getMessage());

        // rollback must leave schema version, legacy rows, and v2 tables untouched
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                Statement statement = connection.createStatement()) {
            assertEquals(1, readVersion(statement));
            assertEquals(1, countTable(statement, "land_chunks"));
            assertEquals(0, countTable(statement, "lands"));
            assertEquals(0, countTable(statement, "sublands"));
            assertEquals(0, countTable(statement, "subject_groups"));
            assertEquals(0, countTable(statement, "permission_profiles"));
            assertEquals(0, countTable(statement, "audit_log"));
            assertEquals(0, countTable(statement, "operation_ledger"));
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM land_chunks")) {
                rs.next();
                assertEquals(2, rs.getInt(1), "both legacy rows must survive rollback");
            }
        }
    }

    @Test
    void v1ToV2BackfillDistinguishesCanonicalAndNonCanonicalUuidLikeWorldText() throws SQLException {
        // A non-canonical UUID-shaped legacy world ("1-1-1-1-1") and its canonical
        // equivalent ("00000001-0001-0001-0001-000000000001") must map to different
        // canonical world_uuids, otherwise the legacy row whose world was the
        // non-canonical form would alias the canonical row and become unreadable.
        // Both rows use distinct chunks so the backfill can complete.
        Path path = databasePath();
        UUID playerId = UUID.randomUUID();
        UUID canonicalWorld = UUID.fromString("00000001-0001-0001-0001-000000000001");
        seedV1Database(
                path,
                new LegacyChunkRow(
                        1L, "00000001-0001-0001-0001-000000000001", "1,2",
                        "PLAYER:" + playerId, playerId, 1000L),
                new LegacyChunkRow(
                        2L, "1-1-1-1-1", "3,4", "SERVER", null, 2000L));

        try (PersistenceStore store = PersistenceStore.open(path)) {
            SqliteChunkRepository chunkRepo = new SqliteChunkRepository(store);
            SqliteLandRepository landRepo = new SqliteLandRepository(store);

            // canonical UUID text is preserved verbatim
            Optional<LandId> foundCanonical = chunkRepo.findLandByChunk(new ChunkKey(canonicalWorld, 1, 2))
                    .toCompletableFuture().join();
            assertTrue(foundCanonical.isPresent(), "canonical UUID world text must be findable");
            assertEquals(legacySyntheticLandId(1L), foundCanonical.get());

            // non-canonical UUID-shaped text becomes a distinct namespaced UUID
            UUID nonCanonicalWorld = legacyWorldUuid("1-1-1-1-1");
            assertNotEquals(
                    canonicalWorld, nonCanonicalWorld,
                    "non-canonical UUID-like text must not alias the canonical UUID it parses to");
            Optional<LandId> foundNonCanonical = chunkRepo.findLandByChunk(new ChunkKey(nonCanonicalWorld, 3, 4))
                    .toCompletableFuture().join();
            assertTrue(foundNonCanonical.isPresent(), "non-canonical UUID-shaped text must be readable on its own mapping");
            assertEquals(legacySyntheticLandId(2L), foundNonCanonical.get());

            // the natural-key lookup on each row's coordinate returns only that row's land
            Optional<LandSnapshot> snapCanonical = landRepo.findById(legacySyntheticLandId(1L))
                    .toCompletableFuture().join();
            assertTrue(snapCanonical.isPresent());
            assertEquals(canonicalWorld, snapCanonical.get().worldId());

            Optional<LandSnapshot> snapNonCanonical = landRepo.findById(legacySyntheticLandId(2L))
                    .toCompletableFuture().join();
            assertTrue(snapNonCanonical.isPresent());
            assertEquals(nonCanonicalWorld, snapNonCanonical.get().worldId());
        }
    }

    private void seedV1Database(Path path, LegacyChunkRow... rows) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE schema_version ("
                            + "id INTEGER PRIMARY KEY CHECK (id = 1), version INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO schema_version (id, version) VALUES (1, 1)");
            statement.executeUpdate(
                    "CREATE TABLE land_chunks ("
                            + "id INTEGER PRIMARY KEY,"
                            + "world TEXT NOT NULL,"
                            + "chunk TEXT NOT NULL,"
                            + "owner_key TEXT NOT NULL,"
                            + "owner_uuid BLOB(16),"
                            + "claimed_at INTEGER NOT NULL,"
                            + "UNIQUE (world, chunk))");
            for (LegacyChunkRow row : rows) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO land_chunks (id, world, chunk, owner_key, owner_uuid, claimed_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setLong(1, row.id());
                    ps.setString(2, row.world);
                    ps.setString(3, row.chunk);
                    ps.setString(4, row.ownerKey);
                    if (row.ownerUuid == null) {
                        ps.setBytes(5, null);
                    } else {
                        ps.setBytes(5, UuidBlob.encode(row.ownerUuid));
                    }
                    ps.setLong(6, row.claimedAt);
                    ps.executeUpdate();
                }
            }
        }
    }

    private static LandId legacySyntheticLandId(long legacyId) {
        byte[] bytes = ("chunkland-legacy-land:" + legacyId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new LandId(UUID.nameUUIDFromBytes(bytes));
    }

    private static UUID legacyWorldUuid(String legacyWorld) {
        // Mirrors SchemaMigrator.legacyWorldUuid: only the canonical 8-4-4-4-12
        // UUID text is preserved; non-canonical UUID-like text and arbitrary
        // names map through a fixed namespace so two distinct legacy rows cannot
        // alias one another.
        try {
            UUID parsed = UUID.fromString(legacyWorld);
            if (parsed.toString().equalsIgnoreCase(legacyWorld)) {
                return parsed;
            }
        } catch (IllegalArgumentException notCanonicalUuid) {
            // fall through to namespace fallback
        }
        byte[] bytes = ("chunkland-legacy-world:" + legacyWorld).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(bytes);
    }

    private static String readLegacyChunkWorld(PersistenceStore store, long id) {
        return store.execute(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT world FROM land_chunks WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    private static String readLegacyChunkChunk(PersistenceStore store, long id) {
        return store.execute(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT chunk FROM land_chunks WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    private static String readLegacyChunkOwnerKey(PersistenceStore store, long id) {
        return store.execute(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT owner_key FROM land_chunks WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    private static long readLegacyChunkClaimedAt(PersistenceStore store, long id) {
        return store.execute(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT claimed_at FROM land_chunks WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private record LegacyChunkRow(long id, String world, String chunk, String ownerKey, UUID ownerUuid, long claimedAt) {
    }

    private Path databasePath() {
        return temporaryDirectory.resolve("chunkland.db");
    }

    private void insertLandChunk(
            PersistenceStore store,
            String world,
            String chunk,
            OwnerKey ownerKey,
            UUID ownerUuid,
            long claimedAt) {
        store.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO land_chunks (world, chunk, owner_key, owner_uuid, claimed_at) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, world);
                statement.setString(2, chunk);
                statement.setString(3, ownerKey.asString());
                if (ownerUuid == null) {
                    statement.setBytes(4, null);
                } else {
                    statement.setBytes(4, UuidBlob.encode(ownerUuid));
                }
                statement.setLong(5, claimedAt);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private boolean landChunksExists(PersistenceStore store) {
        return store.execute(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                            "SELECT COUNT(*) FROM sqlite_master "
                                    + "WHERE type = 'table' AND name = 'land_chunks'")) {
                resultSet.next();
                return resultSet.getInt(1) == 1;
            }
        });
    }

    private int countLandChunks(PersistenceStore store) {
        return store.execute(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM land_chunks")) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        });
    }

    private String ownerKeyFor(PersistenceStore store, String chunk) {
        return store.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT owner_key FROM land_chunks WHERE chunk = ?")) {
                statement.setString(1, chunk);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getString(1);
                }
            }
        });
    }

    private byte[] selectOwnerUuid(Connection connection, String chunk) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_uuid FROM land_chunks WHERE chunk = ?")) {
            statement.setString(1, chunk);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBytes(1);
            }
        }
    }

    private static Connection failingLandChunksConnection(Path databasePath) throws SQLException {
        Connection delegate = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        AtomicInteger commitCount = new AtomicInteger();
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("commit")
                                    && commitCount.incrementAndGet() >= 2) {
                                throw new SQLException("injected migration commit failure");
                            }
                            try {
                                return method.invoke(
                                        delegate, arguments == null ? new Object[0] : arguments);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }

    /**
     * Connection used to inject deterministic migration failures. The 2nd {@code commit()}
     * throws {@code primaryOnCommit} only when {@code failCommit} is set (otherwise the
     * commit succeeds, exercising the committed path). {@code rollback()} and the 2nd
     * {@code setAutoCommit(true)} can also be made to fail so cleanup-failure handling is
     * observable.
     */
    private static Connection failingMigrationConnection(
            Path databasePath,
            Throwable primaryOnCommit,
            boolean failCommit,
            boolean failRollback,
            boolean failAutoCommit) throws SQLException {
        Connection delegate = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        AtomicInteger commitCount = new AtomicInteger();
        AtomicInteger autoCommitTrueCount = new AtomicInteger();
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, arguments) -> {
                            String name = method.getName();
                            if (name.equals("commit") && commitCount.incrementAndGet() >= 2) {
                                if (failCommit) {
                                    if (primaryOnCommit != null) {
                                        throw primaryOnCommit;
                                    }
                                    throw new SQLException("injected migration commit failure");
                                }
                                return method.invoke(
                                        delegate, arguments == null ? new Object[0] : arguments);
                            }
                            if (failRollback && name.equals("rollback")) {
                                throw new SQLException("injected rollback failure");
                            }
                            if (failAutoCommit && name.equals("setAutoCommit")
                                    && arguments != null && arguments.length > 0
                                    && Boolean.TRUE.equals(arguments[0])
                                    && autoCommitTrueCount.incrementAndGet() >= 2) {
                                throw new SQLException("injected autocommit failure");
                            }
                            try {
                                return method.invoke(
                                        delegate, arguments == null ? new Object[0] : arguments);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }

    private void seedSchemaVersion(Path path, String versionColumnType, String versionLiteral)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE schema_version ("
                            + "id INTEGER PRIMARY KEY CHECK (id = 1), version "
                            + versionColumnType + " NOT NULL)");
            statement.executeUpdate(
                    "INSERT INTO schema_version (id, version) VALUES (1, " + versionLiteral + ")");
        }
    }

    /**
     * Seeds a legacy-shaped {@code schema_version} without the id=1 CHECK, with two rows so the
     * full-table validation must reject it (a {@code WHERE id = 1} query alone would hide the
     * extra row).
     */
    private void seedSchemaVersionWithExtraRow(Path path) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE schema_version (id INTEGER, version INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO schema_version (id, version) VALUES (1, 1)");
            statement.executeUpdate("INSERT INTO schema_version (id, version) VALUES (2, 1)");
        }
    }
}

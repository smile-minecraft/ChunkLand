package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaMigrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesFromV0ToV1OnOpen() {
        try (PersistenceStore store = PersistenceStore.open(databasePath())) {
            assertEquals(1, store.schemaVersion());
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
            assertEquals(1, second.schemaVersion());
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

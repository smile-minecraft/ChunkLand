package com.smile.chunkland.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Applies schema migrations to the single persistence connection.
 *
 * <p>Migrations run inside one transaction on the persistence thread. If any
 * statement or the final version write fails, the whole batch is rolled back
 * and the {@code schema_version} row is left untouched, so a failed upgrade
 * never leaves a partial schema behind. Reaching the latest version is a
 * no-op, which makes repeated starts idempotent.
 *
 * <p>Future schema work (audit log, ledger, ACL, cascade rules from the later
 * milestones) is expected to land as additional entries here, each in its own
 * versioned block. Child tables that must not be silently removed when a land
 * row is deleted will reference {@code land_chunks} with an explicit
 * {@code ON DELETE RESTRICT} (or {@code NO ACTION}) rather than a cascade, so
 * the historical rows survive a land deletion.
 */
final class SchemaMigrator {

    static final int LATEST_VERSION = 1;

    /**
     * Ordered migration SQL, indexed by (version - 1). Entry 0 is the v0 -> v1
     * upgrade. Add new entries at the tail and bump {@link #LATEST_VERSION};
     * never reorder or edit a shipped entry.
     */
    private static final List<String> MIGRATIONS = List.of(
            // v1: domain storage foundation for claimed land chunks.
            """
            CREATE TABLE land_chunks (
                id INTEGER PRIMARY KEY,
                world TEXT NOT NULL,
                chunk TEXT NOT NULL,
                owner_key TEXT NOT NULL,
                owner_uuid BLOB(16),
                claimed_at INTEGER NOT NULL,
                UNIQUE (world, chunk)
            )
            """);

    private SchemaMigrator() {
    }

    static void migrate(Connection connection) throws SQLException {
        int current = SchemaVersion.readCurrent(connection);
        if (current == LATEST_VERSION) {
            return;
        }
        if (current > LATEST_VERSION) {
            throw new SQLException(
                    "Database schema version " + current
                            + " is newer than the supported version " + LATEST_VERSION);
        }

        boolean committed = false;
        connection.setAutoCommit(false);
        Throwable primary = null;
        try {
            for (int version = current + 1; version <= LATEST_VERSION; version++) {
                applyMigration(connection, version);
            }
            writeVersion(connection, LATEST_VERSION);
            connection.commit();
            committed = true;
        } catch (Throwable failure) {
            primary = failure;
        } finally {
            if (!committed && primary != null) {
                try {
                    connection.rollback();
                } catch (Throwable rollbackFailure) {
                    primary.addSuppressed(rollbackFailure);
                }
            }
            try {
                connection.setAutoCommit(true);
            } catch (Throwable autoCommitFailure) {
                if (primary != null) {
                    primary.addSuppressed(autoCommitFailure);
                } else {
                    primary = autoCommitFailure;
                }
            }
        }
        if (primary != null) {
            if (primary instanceof SQLException sql) {
                throw sql;
            }
            if (primary instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (primary instanceof Error error) {
                throw error;
            }
            throw new AssertionError("unexpected migration failure", primary);
        }
    }

    private static void applyMigration(Connection connection, int version) throws SQLException {
        String sql = MIGRATIONS.get(version - 1);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void writeVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE schema_version SET version = ? WHERE id = 1")) {
            statement.setInt(1, version);
            int updatedRows = statement.executeUpdate();
            if (updatedRows != 1) {
                throw new SQLException(
                        "schema_version update affected " + updatedRows
                                + " rows, expected exactly 1");
            }
        }
    }
}

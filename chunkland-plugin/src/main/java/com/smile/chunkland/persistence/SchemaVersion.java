package com.smile.chunkland.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Strict reader for the single-row {@code schema_version} contract.
 *
 * <p>The row must exist with {@code id = 1}, the {@code version} column must be
 * stored as a SQLite integer (never text or real), and its value must be a
 * non-negative Java {@code int}. Any deviation fails closed so a malformed or
 * tampered database cannot silently drive migrations or be misread through a
 * lenient {@code getInt} conversion.
 */
final class SchemaVersion {

    private SchemaVersion() {
    }

    static int readCurrent(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT id, typeof(version), version FROM schema_version")) {
            if (!resultSet.next()) {
                throw new SQLException("schema_version has no row");
            }
            int id = resultSet.getInt(1);
            if (id != 1) {
                throw new SQLException("schema_version must have exactly one row with id = 1, found id: " + id);
            }
            String storageType = resultSet.getString(2);
            if (!"integer".equals(storageType)) {
                throw new SQLException(
                        "schema_version.version must be stored as an integer, got storage type: "
                                + storageType);
            }
            long value = resultSet.getLong(3);
            if (value < 0 || value > Integer.MAX_VALUE) {
                throw new SQLException("schema_version.version is out of int range: " + value);
            }
            if (resultSet.next()) {
                throw new SQLException("schema_version has more than one row");
            }
            return (int) value;
        }
    }
}

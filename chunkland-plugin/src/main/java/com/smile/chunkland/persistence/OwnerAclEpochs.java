package com.smile.chunkland.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable per-owner ACL epoch for stale-cache detection.
 *
 * <p>One row per owner namespace ({@code PLAYER:<uuid>}); the epoch starts at
 * zero when the row is first created and increments by exactly one per
 * committed ACL mutation in the same database transaction. Readers (a future
 * cache generation) compare the stored epoch against the epoch captured with
 * their snapshot to detect that group, profile, membership or binding rows
 * changed underneath them. Every increment runs inside the caller's
 * transaction on the persistence thread, so a rolled-back mutation never
 * moves the epoch.
 *
 * <p>No collaborator callback runs here: this is straight JDBC on the given
 * connection.
 */
public final class OwnerAclEpochs {

    private OwnerAclEpochs() {
    }

    /**
     * Owner key of one player's ACL namespace.
     */
    public static String ownerKey(UUID owner) {
        return OwnerKey.player(Objects.requireNonNull(owner, "owner")).asString();
    }

    /**
     * Read the durable epoch for one owner key. An owner that never mutated
     * any ACL row reads as zero, which matches the migration seed.
     */
    public static long read(Connection conn, String ownerKey) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(ownerKey, "ownerKey");
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT epoch FROM owner_acl_epochs WHERE owner_key = ? LIMIT 1")) {
            query.setString(1, ownerKey);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return 0L;
                }
                return rows.getLong(1);
            }
        }
    }

    /**
     * Atomically create-or-increment the epoch for one owner key inside the
     * caller's transaction. The first committed mutation seeds the epoch at
     * one; every later committed mutation adds exactly one.
     *
     * @return the epoch after the increment
     */
    public static long increment(Connection conn, String ownerKey) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(ownerKey, "ownerKey");
        try (PreparedStatement upsert = conn.prepareStatement(
                "INSERT INTO owner_acl_epochs (owner_key, epoch) VALUES (?, 1)"
                        + " ON CONFLICT(owner_key) DO UPDATE SET epoch = epoch + 1")) {
            upsert.setString(1, ownerKey);
            upsert.executeUpdate();
        }
        return read(conn, ownerKey);
    }
}

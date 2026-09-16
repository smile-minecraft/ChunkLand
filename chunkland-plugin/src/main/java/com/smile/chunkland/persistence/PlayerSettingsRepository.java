package com.smile.chunkland.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Typed read/write seam for {@code player_settings.preferred_locale} and
 * {@code player_settings.enter_leave_message}.
 *
 * <p>Only these two columns are managed here; the remaining {@code NOT NULL}
 * columns keep their bootstrap defaults on insert so no schema change is
 * needed. A missing locale row, a SQL {@code NULL} or a blank value all read
 * back as {@link Optional#empty()} (no override). A missing or unknown
 * enter-leave value reads back as {@link Optional#empty()} and callers fall
 * back to enabled (the column default is {@code 1}).</p>
 */
public final class PlayerSettingsRepository {

    private final PersistenceStore store;

    public PlayerSettingsRepository(PersistenceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Reads the raw stored tag. Empty when the row is missing or the column
     * is null/blank. Runs on the single persistence executor; never call
     * from the render path.
     */
    public CompletionStage<Optional<String>> findPreferredLocale(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return store.submitAsync(conn -> findInternal(conn, playerId));
    }

    /**
     * Reads the stored enter-leave switch. Empty when the row is missing or
     * the value is unknown; {@code 1} reads as enabled, {@code 0} as
     * disabled. Runs on the single persistence executor; never call from
     * the movement path.
     */
    public CompletionStage<Optional<Boolean>> findEnterLeaveEnabled(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return store.submitAsync(conn -> findEnterLeaveInternal(conn, playerId));
    }

    /**
     * Persists the raw tag; {@code null} or blank clears the override
     * (stored as SQL {@code NULL}). Runs on the single persistence
     * executor; never call from the render path.
     */
    public CompletionStage<Void> savePreferredLocale(UUID playerId, String preferredLocaleOrNull) {
        Objects.requireNonNull(playerId, "playerId");
        String stored = (preferredLocaleOrNull == null || preferredLocaleOrNull.isBlank())
                ? null
                : preferredLocaleOrNull.trim();
        return store.submitAsync(conn -> {
            saveInternal(conn, playerId, stored);
            return null;
        });
    }

    /**
     * Persists the enter-leave switch ({@code true} as {@code 1},
     * {@code false} as {@code 0}). Update-only when the row already exists
     * so the sibling locale column keeps its value; the insert carries the
     * bootstrap defaults for the untouched columns. Runs on the single
     * persistence executor; never call from the movement path.
     */
    public CompletionStage<Void> saveEnterLeaveEnabled(UUID playerId, boolean enabled) {
        Objects.requireNonNull(playerId, "playerId");
        int stored = enabled ? 1 : 0;
        return store.submitAsync(conn -> {
            saveEnterLeaveInternal(conn, playerId, stored);
            return null;
        });
    }

    private static Optional<String> findInternal(Connection conn, UUID playerId) throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT preferred_locale FROM player_settings WHERE player_uuid = ?")) {
            query.setBytes(1, UuidBlob.encode(playerId));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                String raw = rows.getString(1);
                if (raw == null || raw.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(raw);
            }
        }
    }

    private static Optional<Boolean> findEnterLeaveInternal(Connection conn, UUID playerId)
            throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT enter_leave_message FROM player_settings WHERE player_uuid = ?")) {
            query.setBytes(1, UuidBlob.encode(playerId));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                int raw = rows.getInt(1);
                if (rows.wasNull()) {
                    return Optional.empty();
                }
                if (raw == 1) {
                    return Optional.of(true);
                }
                if (raw == 0) {
                    return Optional.of(false);
                }
                return Optional.empty();
            }
        }
    }

    private static void saveEnterLeaveInternal(Connection conn, UUID playerId, int stored)
            throws SQLException {
        // Update-only when the row already exists so the sibling locale
        // column keeps whatever value it stored. The single persistence
        // thread serializes these two statements with every other write,
        // so no check-then-act race is possible here.
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE player_settings SET enter_leave_message = ? WHERE player_uuid = ?")) {
            update.setInt(1, stored);
            update.setBytes(2, UuidBlob.encode(playerId));
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO player_settings"
                        + " (player_uuid, enter_leave_message, preferred_ui,"
                        + " particle_preference, preferred_locale)"
                        + " VALUES (?, ?, 'auto', 'all', NULL)"
                        + " ON CONFLICT(player_uuid) DO UPDATE"
                        + " SET enter_leave_message = excluded.enter_leave_message")) {
            insert.setBytes(1, UuidBlob.encode(playerId));
            insert.setInt(2, stored);
            insert.executeUpdate();
        }
    }

    private static void saveInternal(Connection conn, UUID playerId, String stored) throws SQLException {
        // Update-only when the row already exists so the other NOT NULL
        // columns keep whatever values sibling features stored. The single
        // persistence thread serializes these two statements with every
        // other write, so no check-then-act race is possible here.
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE player_settings SET preferred_locale = ? WHERE player_uuid = ?")) {
            update.setString(1, stored);
            update.setBytes(2, UuidBlob.encode(playerId));
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO player_settings"
                        + " (player_uuid, enter_leave_message, preferred_ui,"
                        + " particle_preference, preferred_locale)"
                        + " VALUES (?, 1, 'auto', 'all', ?)"
                        + " ON CONFLICT(player_uuid) DO UPDATE"
                        + " SET preferred_locale = excluded.preferred_locale")) {
            insert.setBytes(1, UuidBlob.encode(playerId));
            insert.setString(2, stored);
            insert.executeUpdate();
        }
    }
}

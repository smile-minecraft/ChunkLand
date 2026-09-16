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
 * Typed read/write seam for {@code player_settings.preferred_locale}.
 *
 * <p>Only the locale column is managed here; the remaining
 * {@code NOT NULL} columns keep their bootstrap defaults on insert so no
 * schema change is needed. A missing row, a SQL {@code NULL} or a blank
 * value all read back as {@link Optional#empty()} (no override).</p>
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

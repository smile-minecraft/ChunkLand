package com.smile.chunkland.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Applies schema migrations to the single persistence connection.
 *
 * <p>Migrations run inside one transaction on the persistence thread. If any
 * statement, the v1 legacy backfill, or the final version write fails, the
 * whole batch is rolled back and the {@code schema_version} row is left
 * untouched, so a failed upgrade never leaves a partial schema behind.
 * Reaching the latest version is a no-op, which makes repeated starts
 * idempotent.
 *
 * <p>{@code operation_ledger.state} intentionally remains a permissive
 * {@code TEXT NOT NULL} column. Older databases can contain unknown or blank
 * values, and the recovery scanner must retain and classify those rows rather
 * than lose them during migration. All typed writes validate {@link LedgerState}
 * and all typed transitions use compare-and-set; the permissive storage shape
 * is therefore a compatibility boundary, not a state-machine bypass.
 *
 * <p>Future schema work (audit log, ledger, ACL, additional indexes) is
 * expected to land as additional versioned entries, each in its own
 * append-only block. The v2 schema cascades land lifecycle to its dependent
 * rows (sublands and the {@code land_chunks.land_id} link) because those rows
 * belong to the land, while bindings to a {@code permission_profiles} row
 * declare {@code ON DELETE RESTRICT} so deleting a profile requires an
 * explicit operator decision rather than silently stripping rights from
 * active bindings.
 */
final class SchemaMigrator {

    static final int LATEST_VERSION = 3;

    /**
     * Namespace prefix used by {@link #legacyWorldUuid(String)} when a legacy
     * {@code world} column is not already a UUID string. Fixed so the mapping
     * is stable across retries of a rolled-back migration.
     */
    private static final String LEGACY_WORLD_NAMESPACE = "chunkland-legacy-world:";

    /**
     * Namespace prefix used by {@link #legacySyntheticLandId(long)} to derive
     * a deterministic synthetic land id from the legacy row's primary key.
     * Fixed so the mapping is stable across retries of a rolled-back migration.
     */
    private static final String LEGACY_LAND_NAMESPACE = "chunkland-legacy-land:";

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
            """,
            // v2: lands, sublands, ACL, ledger and audit tables required by repositories.
            // append-only forward migration; preserves v1. SubLand 3D overlap is
            // intentionally not enforced by a DB UNIQUE constraint (see §60) and
            // is validated at runtime by SubLandTopologyValidator.
            """
            CREATE TABLE lands (
                id BLOB(16) PRIMARY KEY,
                owner_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                name_key TEXT NOT NULL,
                world_uuid BLOB(16) NOT NULL,
                structure_revision INTEGER NOT NULL,
                land_policy_revision INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE(owner_key, name_key)
            );
            CREATE INDEX idx_lands_owner ON lands(owner_key);
            CREATE INDEX idx_lands_world ON lands(world_uuid);
            CREATE TABLE sublands (
                id BLOB(16) PRIMARY KEY,
                land_id BLOB(16) NOT NULL,
                name TEXT,
                min_x INTEGER NOT NULL,
                min_y INTEGER NOT NULL,
                min_z INTEGER NOT NULL,
                max_x INTEGER NOT NULL,
                max_y INTEGER NOT NULL,
                max_z INTEGER NOT NULL,
                world_uuid BLOB(16) NOT NULL,
                FOREIGN KEY (land_id) REFERENCES lands(id) ON DELETE CASCADE
            );
            CREATE INDEX idx_sublands_land ON sublands(land_id);
            CREATE TABLE subject_groups (
                id BLOB(16) PRIMARY KEY,
                owner_key TEXT NOT NULL,
                name_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(owner_key, name_key)
            );
            CREATE TABLE subject_group_members (
                group_id BLOB(16) NOT NULL,
                member_uuid BLOB(16) NOT NULL,
                PRIMARY KEY (group_id, member_uuid),
                FOREIGN KEY (group_id) REFERENCES subject_groups(id) ON DELETE CASCADE
            );
            CREATE TABLE permission_profiles (
                id BLOB(16) PRIMARY KEY,
                owner_key TEXT NOT NULL,
                name_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(owner_key, name_key)
            );
            CREATE TABLE permission_profile_entries (
                profile_id BLOB(16) NOT NULL,
                permission TEXT NOT NULL,
                state TEXT NOT NULL,
                PRIMARY KEY (profile_id, permission),
                FOREIGN KEY (profile_id) REFERENCES permission_profiles(id) ON DELETE CASCADE
            );
            CREATE TABLE land_bindings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                land_id BLOB(16) NOT NULL,
                subject_type TEXT NOT NULL CHECK (subject_type IN ('PLAYER','GROUP')),
                subject_id BLOB(16) NOT NULL,
                profile_id BLOB(16) NOT NULL,
                FOREIGN KEY (land_id) REFERENCES lands(id) ON DELETE CASCADE,
                FOREIGN KEY (profile_id) REFERENCES permission_profiles(id) ON DELETE RESTRICT
            );
            CREATE INDEX idx_land_bindings_land ON land_bindings(land_id);
            CREATE INDEX idx_land_bindings_profile ON land_bindings(profile_id);
            CREATE TABLE subland_bindings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                subland_id BLOB(16) NOT NULL,
                subject_type TEXT NOT NULL CHECK (subject_type IN ('PLAYER','GROUP')),
                subject_id BLOB(16) NOT NULL,
                profile_id BLOB(16) NOT NULL,
                FOREIGN KEY (subland_id) REFERENCES sublands(id) ON DELETE CASCADE,
                FOREIGN KEY (profile_id) REFERENCES permission_profiles(id) ON DELETE RESTRICT
            );
            CREATE INDEX idx_subland_bindings_subland ON subland_bindings(subland_id);
            CREATE INDEX idx_subland_bindings_profile ON subland_bindings(profile_id);
            CREATE TABLE land_defaults (
                land_id BLOB(16) NOT NULL,
                permission TEXT NOT NULL,
                state TEXT NOT NULL,
                PRIMARY KEY (land_id, permission),
                FOREIGN KEY (land_id) REFERENCES lands(id) ON DELETE CASCADE
            );
            CREATE TABLE subland_defaults (
                subland_id BLOB(16) NOT NULL,
                permission TEXT NOT NULL,
                state TEXT NOT NULL,
                PRIMARY KEY (subland_id, permission),
                FOREIGN KEY (subland_id) REFERENCES sublands(id) ON DELETE CASCADE
            );
            CREATE TABLE land_rules (
                land_id BLOB(16) NOT NULL,
                rule_type TEXT NOT NULL,
                state TEXT NOT NULL,
                PRIMARY KEY (land_id, rule_type),
                FOREIGN KEY (land_id) REFERENCES lands(id) ON DELETE CASCADE
            );
            CREATE TABLE subland_rules (
                subland_id BLOB(16) NOT NULL,
                rule_type TEXT NOT NULL,
                state TEXT NOT NULL,
                PRIMARY KEY (subland_id, rule_type),
                FOREIGN KEY (subland_id) REFERENCES sublands(id) ON DELETE CASCADE
            );
            CREATE TABLE operation_ledger (
                operation_id BLOB(16) PRIMARY KEY,
                operation_type TEXT NOT NULL,
                state TEXT NOT NULL,
                actor_uuid BLOB(16),
                world_uuid BLOB(16),
                target_land_id BLOB(16),
                price_minor_units INTEGER,
                economy_provider_id TEXT,
                economy_transaction_ref TEXT,
                payload_json TEXT,
                metadata_schema_version INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );
            CREATE INDEX idx_ledger_state ON operation_ledger(state);
            CREATE TABLE audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                actor_uuid BLOB(16),
                action TEXT NOT NULL,
                land_id BLOB(16),
                world_uuid BLOB(16),
                single_chunk_packed INTEGER,
                metadata_schema_version INTEGER NOT NULL,
                before_json TEXT,
                after_json TEXT,
                metadata_json TEXT
            );
            CREATE INDEX idx_audit_timestamp ON audit_log(timestamp);
            CREATE INDEX idx_audit_actor_ts ON audit_log(actor_uuid, timestamp);
            CREATE INDEX idx_audit_land_ts ON audit_log(land_id, timestamp);
            CREATE INDEX idx_audit_action_ts ON audit_log(action, timestamp);
            CREATE TABLE audit_chunks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                audit_id INTEGER NOT NULL,
                world_uuid BLOB(16) NOT NULL,
                chunk_x INTEGER NOT NULL,
                chunk_z INTEGER NOT NULL,
                FOREIGN KEY (audit_id) REFERENCES audit_log(id) ON DELETE CASCADE
            );
            CREATE INDEX idx_audit_chunks_audit ON audit_chunks(audit_id);
            CREATE TABLE player_settings (
                player_uuid BLOB(16) PRIMARY KEY,
                enter_leave_message INTEGER NOT NULL,
                preferred_ui TEXT NOT NULL,
                particle_preference TEXT NOT NULL,
                preferred_locale TEXT
            );
            CREATE TABLE economy_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                operation_id BLOB(16) NOT NULL,
                provider_id TEXT NOT NULL,
                transaction_ref TEXT NOT NULL,
                amount_minor_units INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (operation_id) REFERENCES operation_ledger(operation_id) ON DELETE CASCADE,
                UNIQUE(provider_id, transaction_ref)
            );
            ALTER TABLE land_chunks ADD COLUMN land_id BLOB(16) REFERENCES lands(id) ON DELETE CASCADE;
            ALTER TABLE land_chunks ADD COLUMN world_uuid BLOB(16);
            ALTER TABLE land_chunks ADD COLUMN chunk_x INTEGER;
            ALTER TABLE land_chunks ADD COLUMN chunk_z INTEGER;
            ALTER TABLE land_chunks ADD COLUMN stored_min_protected_y INTEGER;
            ALTER TABLE land_chunks ADD COLUMN claim_lot_id BLOB(16);
            ALTER TABLE land_chunks ADD COLUMN cost_basis_minor_units INTEGER;
            CREATE INDEX idx_land_chunks_land ON land_chunks(land_id);
            CREATE INDEX idx_land_chunks_world_chunk ON land_chunks(world_uuid, chunk_x, chunk_z);
            """,
            // v3: durable compensation retry count for crash recovery.
            """
            ALTER TABLE operation_ledger ADD COLUMN compensation_attempts INTEGER NOT NULL DEFAULT 0;
            """ );

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
                if (version == 2) {
                    backfillV1LandChunks(connection);
                }
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
            for (String part : sql.split(";")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                statement.executeUpdate(trimmed);
            }
        }
    }

    /**
     * Backfill legacy v1 {@code land_chunks} rows so they are readable through
     * the v2 repositories. v1 had no canonical land identity; for each legacy
     * row we synthesize one deterministic {@code lands} row and populate the
     * {@code land_id}, {@code world_uuid}, {@code chunk_x}, and
     * {@code chunk_z} columns that v2 repositories require.
     *
     * <p>Runs inside the same migration transaction as the v2 schema so a
     * failure (e.g. malformed {@code chunk} text, malformed {@code owner_key},
     * or two legacy rows that map to the same canonical coordinate) rolls
     * back the entire upgrade rather than leaving partial v2 objects or
     * silently losing one of the colliding rows.
     *
     * <p>Synthetic identities are derived from the legacy row's primary key
     * with fixed namespace prefixes, so retrying after a rolled-back migration
     * produces the same {@code land_id} / {@code world_uuid} / {@code name_key}
     * values; the canonical coordinate collision set is rebuilt each run, so
     * the same collision is detected deterministically on retry.
     */
    static void backfillV1LandChunks(Connection connection) throws SQLException {
        Set<String> claimedCoordinates = new HashSet<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, world, chunk, owner_key, owner_uuid, claimed_at "
                        + "FROM land_chunks WHERE land_id IS NULL ORDER BY id ASC");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                long legacyId = rows.getLong(1);
                String legacyWorld = rows.getString(2);
                String legacyChunk = rows.getString(3);
                String legacyOwnerKey = rows.getString(4);
                long claimedAt = rows.getLong(6);

                // Strict validation: malformed legacy data must fail and roll back
                // rather than be silently skipped (spec on no-loss upgrades).
                try {
                    OwnerKey.parse(legacyOwnerKey);
                } catch (IllegalArgumentException invalidOwnerKey) {
                    throw new SQLException(
                            "malformed legacy land_chunks.owner_key '" + legacyOwnerKey
                                    + "' on row id=" + legacyId + ": "
                                    + invalidOwnerKey.getMessage(),
                            invalidOwnerKey);
                }
                int[] parsedChunk = parseLegacyChunk(legacyChunk);

                UUID legacyWorldUuid = legacyWorldUuid(legacyWorld);

                // Canonical coordinate collision check: two distinct legacy rows must
                // not collapse onto the same (world_uuid, chunk_x, chunk_z). The v1
                // UNIQUE(world, chunk) constraint only compared raw text and let
                // canonical UUID parsing + lenient chunk parsing alias rows together,
                // which the v2 repositories cannot disambiguate. Failing closed here
                // rolls back the entire migration so no land is silently lost.
                String coordinateKey = legacyWorldUuid + ":" + parsedChunk[0] + ":" + parsedChunk[1];
                if (!claimedCoordinates.add(coordinateKey)) {
                    throw new SQLException(
                            "v1->v2 backfill collision on legacy coordinate (world='"
                                    + legacyWorld + "', chunk='" + legacyChunk + "') for legacy "
                                    + "row id=" + legacyId + ": another legacy row already "
                                    + "owns canonical world_uuid=" + legacyWorldUuid
                                    + " chunk_x=" + parsedChunk[0]
                                    + " chunk_z=" + parsedChunk[1]
                                    + ". Resolve the duplicate before retrying the migration.");
                }

                UUID syntheticLandId = legacySyntheticLandId(legacyId);
                String syntheticNameKey = "legacy-land-" + legacyId;

                try (PreparedStatement insertLand = connection.prepareStatement(
                        "INSERT INTO lands (id, owner_key, display_name, name_key, "
                                + "world_uuid, structure_revision, land_policy_revision, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?)")) {
                    insertLand.setBytes(1, UuidBlob.encode(syntheticLandId));
                    insertLand.setString(2, legacyOwnerKey);
                    insertLand.setString(3, syntheticNameKey);
                    insertLand.setString(4, syntheticNameKey);
                    insertLand.setBytes(5, UuidBlob.encode(legacyWorldUuid));
                    insertLand.setLong(6, claimedAt);
                    insertLand.setLong(7, claimedAt);
                    insertLand.executeUpdate();
                }
                try (PreparedStatement updateChunk = connection.prepareStatement(
                        "UPDATE land_chunks SET land_id = ?, world_uuid = ?, "
                                + "chunk_x = ?, chunk_z = ? WHERE id = ?")) {
                    updateChunk.setBytes(1, UuidBlob.encode(syntheticLandId));
                    updateChunk.setBytes(2, UuidBlob.encode(legacyWorldUuid));
                    updateChunk.setInt(3, parsedChunk[0]);
                    updateChunk.setInt(4, parsedChunk[1]);
                    updateChunk.setLong(5, legacyId);
                    int updated = updateChunk.executeUpdate();
                    if (updated != 1) {
                        throw new SQLException(
                                "v1->v2 backfill expected to update 1 land_chunks row for id="
                                        + legacyId + ", updated " + updated);
                    }
                }
            }
        }
    }

    private static int[] parseLegacyChunk(String chunk) throws SQLException {
        int comma = chunk.indexOf(',');
        if (comma <= 0 || comma == chunk.length() - 1) {
            throw new SQLException(
                    "malformed legacy land_chunks.chunk value '"
                            + chunk + "', expected 'x,z'");
        }
        int x;
        int z;
        try {
            x = Integer.parseInt(chunk.substring(0, comma).trim());
            z = Integer.parseInt(chunk.substring(comma + 1).trim());
        } catch (NumberFormatException invalid) {
            throw new SQLException(
                    "malformed legacy land_chunks.chunk value '"
                            + chunk + "', expected 'x,z'",
                    invalid);
        }
        return new int[] {x, z};
    }

    /**
     * Map a legacy {@code world} string to a UUID. Only the canonical
     * 8-4-4-4-12 hexadecimal form parses back to the same string when
     * re-rendered by {@link UUID#toString()} (case-insensitive); that form is
     * preserved verbatim so canonical UUID values keep their own UUID identity.
     * Anything else &mdash; non-canonical short hyphenated UUID-shaped text,
     * uppercase differences, or arbitrary world names &mdash; is mapped
     * deterministically through a fixed namespace prefix and
     * {@link UUID#nameUUIDFromBytes(byte[])}, so two legacy rows whose text
     * differs only in canonicalization map to distinct world_uuids and cannot
     * alias one another's v2 coordinate.
     */
    static UUID legacyWorldUuid(String legacyWorld) {
        try {
            UUID parsed = UUID.fromString(legacyWorld);
            if (parsed.toString().equalsIgnoreCase(legacyWorld)) {
                return parsed;
            }
        } catch (IllegalArgumentException notCanonicalUuid) {
            // not even a UUID-shaped string; fall through to namespace mapping.
        }
        byte[] bytes = (LEGACY_WORLD_NAMESPACE + legacyWorld)
                .getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(bytes);
    }

    /**
     * Derive a deterministic synthetic land id from the legacy row's primary
     * key. Stable across retries of a rolled-back migration because the legacy
     * primary key is immutable.
     */
    static UUID legacySyntheticLandId(long legacyId) {
        byte[] bytes = (LEGACY_LAND_NAMESPACE + legacyId)
                .getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(bytes);
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

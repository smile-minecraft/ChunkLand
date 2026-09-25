package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.rename.RenameRejectedException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Single-transaction land rename against the authoritative rows.
 *
 * <p>One call re-reads the land row, re-checks the actor against the durable
 * owner, enforces the owner-scoped name key, rewrites the display name and
 * key, leaves the authorisation generation untouched, and writes the
 * rename audit — all in one transaction on the persistence thread. Any
 * failure rolls back every row, so a rejected or failed rename leaves no
 * partial state behind.
 *
 * <p>Authorisation at mutation time: a Server Land row requires the steward
 * flag; a player Land row requires the actor to equal the durable owner.
 * The flag is ignored on player rows and there is no bypass input, so a
 * stale snapshot or a forged flag can never grant a rename the precheck
 * refused. Uniqueness is enforced twice: an explicit same-owner lookup for
 * a clear duplicate diagnostic, and the {@code UNIQUE(owner_key, name_key)}
 * row constraint as the final backstop for races that interleave between
 * the lookup and the rewrite.
 *
 * <p>A rename whose key equals the stored key is a display-case change only
 * and always succeeds for an authorised actor; different owners may hold
 * the same key. The structure revision, chunks, sublands, costs and depths
 * are never touched, and no collaborator callback runs inside the
 * transaction: the only out-of-line call is the test-only failure injector.
 */
public final class LandRenameRepository {

    /** Injection points for atomicity tests. */
    public enum Step {
        AFTER_UPDATE,
        AFTER_AUDIT
    }

    /** Audit action written by the atomic rename transaction. */
    public static final String RENAME_AUDIT_ACTION = "LAND_RENAME";

    /** Metadata schema version for the audit rows written here. */
    public static final int AUDIT_METADATA_VERSION = 1;

    /**
     * Durable values observed by one committed rename: the names before and
     * after plus the policy revision on both sides, which a rename never
     * moves because names carry no authorisation meaning.
     */
    public record Outcome(
            LandId landId,
            UUID worldId,
            String oldDisplayName,
            String oldNameKey,
            String newDisplayName,
            String newNameKey,
            long oldPolicyRevision,
            long newPolicyRevision) {
        public Outcome {
            Objects.requireNonNull(landId, "landId");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(oldDisplayName, "oldDisplayName");
            Objects.requireNonNull(oldNameKey, "oldNameKey");
            Objects.requireNonNull(newDisplayName, "newDisplayName");
            Objects.requireNonNull(newNameKey, "newNameKey");
        }
    }

    private final PersistenceStore store;
    private final Consumer<Step> failureInjector;

    public LandRenameRepository(PersistenceStore store) {
        this(store, ignored -> {
        });
    }

    public LandRenameRepository(PersistenceStore store, Consumer<Step> failureInjector) {
        this.store = Objects.requireNonNull(store, "store");
        this.failureInjector = Objects.requireNonNull(failureInjector, "failureInjector");
    }

    /**
     * Rename one land in a single transaction.
     *
     * <p>Names carry no authorisation meaning, so the authorisation
     * generation is left untouched and the outcome reports the unchanged
     * revision on both sides; previously pinned writes keep committing.
     *
     * @param landId target land; unknown rows reject without side effects
     * @param actor renaming player; must equal the durable player owner, and
     *              is ignored on Server Land rows beyond the steward flag
     * @param serverLandSteward whether the actor holds the server-land grant;
     *                          required on Server Land rows, ignored elsewhere
     * @param displayName new display name; blank or control-bearing input
     *                    rejects before any SQL runs
     * @param timestamp commit and audit instant
     * @return the committed before/after values
     * @throws RenameRejectedException with {@code rename.invalid},
     *         {@code rename.unknown_land}, {@code rename.not_allowed} or
     *         {@code rename.duplicate} when the rename must not happen
     */
    public CompletionStage<Outcome> rename(LandId landId, UUID actor,
            boolean serverLandSteward, String displayName, Instant timestamp) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(timestamp, "timestamp");
        final String canonical;
        final String key;
        try {
            LandName named = displayName == null
                    ? LandName.of("")
                    : LandName.of(displayName);
            canonical = named.displayName();
            key = named.nameKey();
        } catch (RuntimeException invalid) {
            throw new RenameRejectedException("rename.invalid", invalid);
        }
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            DurableRow row = readRow(conn, landId);
            requireAuthorised(row, landId, actor, serverLandSteward);
            if (!key.equals(row.nameKey)) {
                requireNameAvailable(conn, landId, row.ownerKey, key);
            }
            long now = timestamp.toEpochMilli();
            try {
                updateNames(conn, landId, canonical, key, now);
            } catch (SQLException duplicate) {
                if (isUniqueViolation(duplicate)) {
                    throw new RenameRejectedException("rename.duplicate", duplicate);
                }
                throw duplicate;
            }
            failureInjector.accept(Step.AFTER_UPDATE);
            insertAudit(conn, renameAudit(actor, landId, row, canonical, key, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            long nextPolicy = readPolicyRevision(conn, landId);
            return new Outcome(landId, row.worldId, row.displayName, row.nameKey,
                    canonical, key, row.policyRevision, nextPolicy);
        }));
    }

    private record DurableRow(
            String ownerKey, String displayName, String nameKey, UUID worldId, long policyRevision) {
    }

    private static DurableRow readRow(Connection conn, LandId landId) throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT owner_key, display_name, name_key, world_uuid, land_policy_revision"
                        + " FROM lands WHERE id = ?")) {
            query.setBytes(1, UuidBlob.encode(landId.value()));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new RenameRejectedException("rename.unknown_land");
                }
                byte[] worldBytes = rows.getBytes(4);
                if (worldBytes == null) {
                    throw new SQLException("land row has no world: " + landId);
                }
                return new DurableRow(rows.getString(1), rows.getString(2), rows.getString(3),
                        UuidBlob.decode(worldBytes), rows.getLong(5));
            }
        }
    }

    private static void requireAuthorised(DurableRow row, LandId landId, UUID actor,
            boolean serverLandSteward) throws SQLException {
        if (OwnerKey.SERVER_VALUE.equals(row.ownerKey)) {
            if (!serverLandSteward) {
                throw new RenameRejectedException("rename.not_allowed");
            }
            return;
        }
        if (row.ownerKey != null && row.ownerKey.startsWith(OwnerKey.PLAYER_PREFIX)) {
            UUID durableOwner;
            try {
                durableOwner = UUID.fromString(
                        row.ownerKey.substring(OwnerKey.PLAYER_PREFIX.length()));
            } catch (IllegalArgumentException corrupt) {
                throw new SQLException("land row has a corrupt owner: " + landId, corrupt);
            }
            if (!durableOwner.equals(actor)) {
                throw new RenameRejectedException("rename.not_allowed");
            }
            return;
        }
        throw new SQLException("land row has an unknown owner: " + landId);
    }

    private static void requireNameAvailable(Connection conn, LandId landId,
            String ownerKey, String nameKey) throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT 1 FROM lands WHERE owner_key = ? AND name_key = ? AND id <> ? LIMIT 1")) {
            query.setString(1, ownerKey);
            query.setString(2, nameKey);
            query.setBytes(3, UuidBlob.encode(landId.value()));
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) {
                    throw new RenameRejectedException("rename.duplicate");
                }
            }
        }
    }

    private static void updateNames(Connection conn, LandId landId,
            String displayName, String nameKey, long now) throws SQLException {
        // Names only: a rename changes no authorisation state, so the
        // authorisation generation stays put. Bumping it here would strand
        // every revision pinned before the rename and refuse later writes
        // until an unrelated authorisation write happens to refresh them.
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE lands SET display_name = ?, name_key = ?,"
                        + " updated_at = ? WHERE id = ?")) {
            update.setString(1, displayName);
            update.setString(2, nameKey);
            update.setLong(3, now);
            update.setBytes(4, UuidBlob.encode(landId.value()));
            if (update.executeUpdate() != 1) {
                throw new SQLException("land disappeared during rename: " + landId);
            }
        }
    }

    private static long readPolicyRevision(Connection conn, LandId landId) throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT land_policy_revision FROM lands WHERE id = ?")) {
            query.setBytes(1, UuidBlob.encode(landId.value()));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("land disappeared during rename: " + landId);
                }
                return rows.getLong(1);
            }
        }
    }

    private static boolean isUniqueViolation(SQLException failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("UNIQUE constraint failed")) {
                return true;
            }
        }
        return false;
    }

    private static AuditEntry renameAudit(UUID actor, LandId landId, DurableRow before,
            String newDisplayName, String newNameKey, Instant timestamp) {
        String beforeJson = "{\"displayName\":\"" + escape(before.displayName)
                + "\",\"nameKey\":\"" + escape(before.nameKey)
                + "\",\"policyRevision\":" + before.policyRevision + "}";
        String afterJson = "{\"displayName\":\"" + escape(newDisplayName)
                + "\",\"nameKey\":\"" + escape(newNameKey)
                + "\",\"policyRevision\":" + before.policyRevision + "}";
        return new AuditEntry(0L, timestamp, actor, RENAME_AUDIT_ACTION, landId,
                before.worldId, null, AUDIT_METADATA_VERSION,
                beforeJson, afterJson, "{\"kind\":\"land-rename\"}", List.of());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void insertAudit(Connection conn, AuditEntry audit) throws SQLException {
        try (PreparedStatement write = conn.prepareStatement(
                "INSERT INTO audit_log (timestamp, actor_uuid, action, land_id, world_uuid,"
                        + " single_chunk_packed, metadata_schema_version, before_json, after_json,"
                        + " metadata_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            write.setLong(1, audit.timestamp().toEpochMilli());
            setUuid(write, 2, audit.actor());
            write.setString(3, audit.action());
            setUuid(write, 4, audit.landId() == null ? null : audit.landId().value());
            setUuid(write, 5, audit.worldId());
            write.setObject(6, null);
            write.setInt(7, audit.metadataVersion());
            write.setString(8, audit.beforeJson());
            write.setString(9, audit.afterJson());
            write.setString(10, audit.metadataJson());
            if (write.executeUpdate() != 1) {
                throw new SQLException("audit insert affected no rows");
            }
            try (ResultSet keys = write.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("audit insert did not return an id");
                }
            }
        }
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value)
            throws SQLException {
        if (value == null) {
            statement.setBytes(index, null);
        } else {
            statement.setBytes(index, UuidBlob.encode(value));
        }
    }
}

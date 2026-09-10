package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * All-or-nothing durable writes for direct trust and land defaults.
 *
 * <p>One call writes the profile rows, the binding or default row, the audit
 * row and the land policy-revision bump inside a single transaction on the
 * persistence thread, so a failure (including an audit failure) rolls back
 * every row instead of leaving partial state behind. Trust reuses one
 * implicit direct profile per player across lands; untrust deletes only the
 * binding row and never the profile. A {@code INHERIT} default deletes its
 * row instead of storing a value.
 *
 * <p>No collaborator callback runs inside the transaction: the only
 * out-of-line call is the test-only failure injector.
 */
public final class LandAuthorisationRepository {

    /** Injection points for atomicity tests. */
    public enum Step {
        AFTER_ENTRIES,
        AFTER_BINDING,
        AFTER_DEFAULT,
        AFTER_AUDIT
    }

    /** Name key of the implicit per-player direct profile. Fixed contract. */
    public static final String DIRECT_PROFILE_NAME_KEY = "direct";

    /** Metadata schema version for the audit rows written here. */
    public static final int AUDIT_METADATA_VERSION = 1;

    private static final Logger LOG =
            Logger.getLogger(LandAuthorisationRepository.class.getName());

    private final PersistenceStore store;
    private final Consumer<Step> failureInjector;

    public LandAuthorisationRepository(PersistenceStore store) {
        this(store, ignored -> {
        });
    }

    public LandAuthorisationRepository(PersistenceStore store, Consumer<Step> failureInjector) {
        this.store = Objects.requireNonNull(store, "store");
        this.failureInjector = Objects.requireNonNull(failureInjector, "failureInjector");
    }

    /** Owner key of the implicit direct profile for one player. */
    public static String profileOwnerKey(UUID playerUuid) {
        return "PLAYER:" + Objects.requireNonNull(playerUuid, "playerUuid");
    }

    /**
     * Trust one player on one land: upsert their implicit direct profile
     * with the full whitelist, replace their player binding, audit, and bump
     * the land policy revision — all in one transaction.
     *
     * <p>The audit before value is read from the durable binding row inside
     * the same transaction, never from the volatile cache, so consecutive
     * mutations record the true transition even when the cache refresh races.
     *
     * @return the reused (or newly created) implicit profile id
     */
    public CompletionStage<UUID> trust(LandId landId, UUID targetPlayer, UUID actor,
            Instant timestamp) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(targetPlayer, "targetPlayer");
        Objects.requireNonNull(timestamp, "timestamp");
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            requireKnownLand(conn, landId);
            boolean boundBefore = hasBinding(conn, landId, targetPlayer);
            long now = timestamp.toEpochMilli();
            UUID profileId = findOrCreateProfile(conn, targetPlayer, now);
            upsertWhitelistEntries(conn, profileId);
            pruneNonWhitelistEntries(conn, profileId);
            failureInjector.accept(Step.AFTER_ENTRIES);
            replaceBinding(conn, landId, targetPlayer, profileId);
            failureInjector.accept(Step.AFTER_BINDING);
            insertAudit(conn, trustAudit(actor, landId, targetPlayer, boundBefore, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            bumpPolicyRevision(conn, landId, now);
            return profileId;
        }));
    }

    /**
     * Untrust one player on one land: delete only their player binding row,
     * audit, and bump the land policy revision — all in one transaction.
     * Resending without a binding still succeeds and audits.
     *
     * <p>The audit before value is read from the durable binding row inside
     * the same transaction, so a resend without a binding records no binding
     * before it instead of repeating a stale grant.
     */
    public CompletionStage<Void> untrust(LandId landId, UUID targetPlayer, UUID actor,
            Instant timestamp) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(targetPlayer, "targetPlayer");
        Objects.requireNonNull(timestamp, "timestamp");
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            requireKnownLand(conn, landId);
            boolean boundBefore = hasBinding(conn, landId, targetPlayer);
            try (PreparedStatement delete = conn.prepareStatement(
                    "DELETE FROM land_bindings WHERE land_id = ? AND subject_type = 'PLAYER' AND subject_id = ?")) {
                delete.setBytes(1, UuidBlob.encode(landId.value()));
                delete.setBytes(2, UuidBlob.encode(targetPlayer));
                delete.executeUpdate();
            }
            failureInjector.accept(Step.AFTER_BINDING);
            insertAudit(conn, untrustAudit(actor, landId, targetPlayer, boundBefore, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            bumpPolicyRevision(conn, landId, timestamp.toEpochMilli());
            return null;
        }));
    }

    /**
     * Persist one land default. {@code ALLOW} and {@code DENY} upsert the row;
     * {@code INHERIT} deletes it. Only whitelisted subject actions are
     * accepted; management and rule actions fail closed before any SQL.
     *
     * <p>The audit before value is read from the durable default row inside
     * the same transaction, never from the volatile cache.
     */
    public CompletionStage<Void> setDefault(LandId landId, ProtectionActionType action,
            PermissionState state, UUID actor, Instant timestamp) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(timestamp, "timestamp");
        requireWhitelisted(action);
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            requireKnownLand(conn, landId);
            PermissionState durableBefore = readDefaultState(conn, landId, action);
            if (state == PermissionState.INHERIT) {
                try (PreparedStatement delete = conn.prepareStatement(
                        "DELETE FROM land_defaults WHERE land_id = ? AND permission = ?")) {
                    delete.setBytes(1, UuidBlob.encode(landId.value()));
                    delete.setString(2, action.name());
                    delete.executeUpdate();
                }
            } else {
                try (PreparedStatement upsert = conn.prepareStatement(
                        "INSERT INTO land_defaults (land_id, permission, state) VALUES (?, ?, ?)"
                                + " ON CONFLICT(land_id, permission) DO UPDATE SET state=excluded.state")) {
                    upsert.setBytes(1, UuidBlob.encode(landId.value()));
                    upsert.setString(2, action.name());
                    upsert.setString(3, state.name());
                    upsert.executeUpdate();
                }
            }
            failureInjector.accept(Step.AFTER_DEFAULT);
            insertAudit(conn, defaultAudit(actor, landId, action, durableBefore, state, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            bumpPolicyRevision(conn, landId, timestamp.toEpochMilli());
            return null;
        }));
    }

    /**
     * Read every durable binding, profile entry and land default into plain
     * maps for one runtime snapshot publish. Unknown permission or state
     * text is skipped row-wise so one corrupt row can never break the load.
     *
     * <p>Each binding is accepted only when its profile is the subject's own
     * implicit direct profile ({@code name_key='direct'} and {@code
     * owner_key='PLAYER:&lt;subject&gt;'}), checked on the persistence thread
     * from the joined profile row. Anything else is skipped with a warning so
     * a foreign profile can never publish another player's entries after a
     * restart.
     */
    public CompletionStage<SnapshotData> loadSnapshotData() {
        return store.submitAsync(connection -> {
            Map<LandId, Map<UUID, Set<ProtectionActionType>>> direct = new HashMap<>();
            Map<LandId, Map<ProtectionActionType, PermissionState>> defaults = new HashMap<>();
            try (PreparedStatement bindings = connection.prepareStatement(
                    "SELECT b.land_id, b.subject_id, b.profile_id, p.owner_key, p.name_key"
                            + " FROM land_bindings b LEFT JOIN permission_profiles p"
                            + " ON p.id = b.profile_id WHERE b.subject_type = 'PLAYER'")) {
                try (ResultSet rows = bindings.executeQuery()) {
                    while (rows.next()) {
                        LandId landId = new LandId(UuidBlob.decode(rows.getBytes(1)));
                        UUID subject = UuidBlob.decode(rows.getBytes(2));
                        UUID profileId = UuidBlob.decode(rows.getBytes(3));
                        String ownerKey = rows.getString(4);
                        String nameKey = rows.getString(5);
                        if (profileId == null || !DIRECT_PROFILE_NAME_KEY.equals(nameKey)
                                || !profileOwnerKey(subject).equals(ownerKey)) {
                            LOG.warning("Skipping land binding with unexpected profile"
                                    + " land=" + landId.value()
                                    + " subject=" + subject
                                    + " profile=" + profileId);
                            continue;
                        }
                        Set<ProtectionActionType> allows = loadProfileAllows(connection, profileId);
                        direct.computeIfAbsent(landId, ignored -> new HashMap<>())
                                .merge(subject, allows, LandAuthorisationRepository::union);
                    }
                }
            }
            try (PreparedStatement landDefaults = connection.prepareStatement(
                    "SELECT land_id, permission, state FROM land_defaults")) {
                try (ResultSet rows = landDefaults.executeQuery()) {
                    while (rows.next()) {
                        ProtectionActionType action = parseAction(rows.getString(2));
                        PermissionState state = parseState(rows.getString(3));
                        if (action == null || state == null || state == PermissionState.INHERIT) {
                            continue;
                        }
                        LandId landId = new LandId(UuidBlob.decode(rows.getBytes(1)));
                        defaults.computeIfAbsent(landId,
                                        ignored -> new EnumMap<>(ProtectionActionType.class))
                                .put(action, state);
                    }
                }
            }
            return new SnapshotData(direct, defaults);
        });
    }

    /**
     * Plain durable maps for one runtime snapshot publish. The maps are
     * already defensively copied and unmodifiable.
     */
    public record SnapshotData(
            Map<LandId, Map<UUID, Set<ProtectionActionType>>> directAllows,
            Map<LandId, Map<ProtectionActionType, PermissionState>> landDefaults) {
        public SnapshotData {
            directAllows = copyDirect(directAllows);
            landDefaults = copyDefaults(landDefaults);
        }

        private static Map<LandId, Map<UUID, Set<ProtectionActionType>>> copyDirect(
                Map<LandId, Map<UUID, Set<ProtectionActionType>>> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<LandId, Map<UUID, Set<ProtectionActionType>>> copy = new HashMap<>(source.size());
            for (Map.Entry<LandId, Map<UUID, Set<ProtectionActionType>>> entry : source.entrySet()) {
                Map<UUID, Set<ProtectionActionType>> byPlayer = new HashMap<>();
                for (Map.Entry<UUID, Set<ProtectionActionType>> player : entry.getValue().entrySet()) {
                    byPlayer.put(player.getKey(), Set.copyOf(player.getValue()));
                }
                copy.put(entry.getKey(), Map.copyOf(byPlayer));
            }
            return Map.copyOf(copy);
        }

        private static Map<LandId, Map<ProtectionActionType, PermissionState>> copyDefaults(
                Map<LandId, Map<ProtectionActionType, PermissionState>> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<LandId, Map<ProtectionActionType, PermissionState>> copy = new HashMap<>(source.size());
            for (Map.Entry<LandId, Map<ProtectionActionType, PermissionState>> entry
                    : source.entrySet()) {
                copy.put(entry.getKey(),
                        Map.copyOf(new EnumMap<>(entry.getValue())));
            }
            return Map.copyOf(copy);
        }
    }

    private static <T> Set<T> union(Set<T> first, Set<T> second) {
        Set<T> merged = new HashSet<>(first);
        merged.addAll(second);
        return Set.copyOf(merged);
    }

    private void requireWhitelisted(ProtectionActionType action) {
        if (!isWhitelisted(action)) {
            throw new IllegalArgumentException(
                    "action is not a durable land default: " + action);
        }
    }

    private static boolean isWhitelisted(ProtectionActionType action) {
        return switch (action) {
            case BLOCK_BREAK, BLOCK_PLACE, CONTAINER_OPEN, WORKSTATION_USE, DOOR_USE,
                    BUTTON_USE, LEVER_USE, REDSTONE_USE, BUCKET_USE, ENTRY, VEHICLE_USE,
                    ENTITY_INTERACT, ENTITY_DAMAGE, ITEM_FRAME, ARMOR_STAND, HANGING_ENTITY,
                    FARMLAND_TRAMPLE ->
                true;
            case PLAYER_DAMAGE_PLAYER, PISTON_MOVE, FLUID_FLOW, HOPPER_TRANSFER, FIRE_SPREAD,
                    FIRE_BURN, EXPLOSION_TERRAIN, EXPLOSION_ENTITY, MOB_GRIEFING,
                    HOSTILE_MOB_SPAWN, PASSIVE_MOB_SPAWN, BLOCK_MOVE_IN, BLOCK_MOVE_OUT,
                    FLUID_ENTER, FLUID_EXIT, ITEM_TRANSFER_IN, ITEM_TRANSFER_OUT,
                    DISPENSER_CROSS_BOUNDARY, MANAGE_MEMBER, MANAGE_PERMISSION,
                    MANAGE_SUBLAND, EXPAND_LAND, DELETE_LAND ->
                false;
        };
    }

    private static void requireKnownLand(Connection conn, LandId landId) throws SQLException {
        try (PreparedStatement query =
                     conn.prepareStatement("SELECT 1 FROM lands WHERE id = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(landId.value()));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("unknown land " + landId);
                }
            }
        }
    }

    private static UUID findOrCreateProfile(Connection conn, UUID target, long now)
            throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT id FROM permission_profiles WHERE owner_key = ? AND name_key = ? LIMIT 1")) {
            query.setString(1, profileOwnerKey(target));
            query.setString(2, DIRECT_PROFILE_NAME_KEY);
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) {
                    return UuidBlob.decode(rows.getBytes(1));
                }
            }
        }
        UUID profileId = UUID.randomUUID();
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO permission_profiles (id, owner_key, name_key, display_name, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)")) {
            insert.setBytes(1, UuidBlob.encode(profileId));
            insert.setString(2, profileOwnerKey(target));
            insert.setString(3, DIRECT_PROFILE_NAME_KEY);
            insert.setString(4, "Direct");
            insert.setLong(5, now);
            insert.executeUpdate();
        }
        return profileId;
    }

    private static final List<String> WHITELIST_PERMISSIONS = List.of(
            "BLOCK_BREAK", "BLOCK_PLACE", "CONTAINER_OPEN", "WORKSTATION_USE", "DOOR_USE",
            "BUTTON_USE", "LEVER_USE", "REDSTONE_USE", "BUCKET_USE", "ENTRY", "VEHICLE_USE",
            "ENTITY_INTERACT", "ENTITY_DAMAGE", "ITEM_FRAME", "ARMOR_STAND", "HANGING_ENTITY",
            "FARMLAND_TRAMPLE");

    private static void upsertWhitelistEntries(Connection conn, UUID profileId) throws SQLException {
        try (PreparedStatement upsert = conn.prepareStatement(
                "INSERT INTO permission_profile_entries (profile_id, permission, state)"
                        + " VALUES (?, ?, 'ALLOW')"
                        + " ON CONFLICT(profile_id, permission) DO UPDATE SET state='ALLOW'")) {
            for (String permission : WHITELIST_PERMISSIONS) {
                upsert.setBytes(1, UuidBlob.encode(profileId));
                upsert.setString(2, permission);
                upsert.addBatch();
            }
            upsert.executeBatch();
        }
    }

    /**
     * Remove durable entries outside the explicit whitelist for one direct
     * profile. Scoped by {@code profile_id} so group and other profiles keep
     * their rows; the runtime read path already filters, this keeps the
     * database consistent with it.
     */
    private static void pruneNonWhitelistEntries(Connection conn, UUID profileId)
            throws SQLException {
        StringBuilder sql = new StringBuilder(
                "DELETE FROM permission_profile_entries WHERE profile_id = ? AND permission NOT IN (");
        for (int i = 0; i < WHITELIST_PERMISSIONS.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(')');
        try (PreparedStatement delete = conn.prepareStatement(sql.toString())) {
            delete.setBytes(1, UuidBlob.encode(profileId));
            for (int i = 0; i < WHITELIST_PERMISSIONS.size(); i++) {
                delete.setString(i + 2, WHITELIST_PERMISSIONS.get(i));
            }
            delete.executeUpdate();
        }
    }

    private static boolean hasBinding(Connection conn, LandId landId, UUID target)
            throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT 1 FROM land_bindings WHERE land_id = ?"
                        + " AND subject_type = 'PLAYER' AND subject_id = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(landId.value()));
            query.setBytes(2, UuidBlob.encode(target));
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static PermissionState readDefaultState(Connection conn, LandId landId,
            ProtectionActionType action) throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT state FROM land_defaults WHERE land_id = ? AND permission = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(landId.value()));
            query.setString(2, action.name());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return PermissionState.INHERIT;
                }
                PermissionState stored = parseState(rows.getString(1));
                return stored == null ? PermissionState.INHERIT : stored;
            }
        }
    }

    private static String bindingJson(UUID target, boolean bound) {
        if (!bound) {
            return null;
        }
        return "{\"target\":\"" + target + "\",\"bound\":true}";
    }

    private static AuditEntry trustAudit(UUID actor, LandId landId, UUID target,
            boolean boundBefore, Instant timestamp) {
        return new AuditEntry(0L, timestamp, actor, "DIRECT_BINDING_CHANGE", landId, null, null,
                AUDIT_METADATA_VERSION, bindingJson(target, boundBefore),
                bindingJson(target, true), "{\"kind\":\"trust\"}", List.of());
    }

    private static AuditEntry untrustAudit(UUID actor, LandId landId, UUID target,
            boolean boundBefore, Instant timestamp) {
        return new AuditEntry(0L, timestamp, actor, "DIRECT_BINDING_CHANGE", landId, null, null,
                AUDIT_METADATA_VERSION, bindingJson(target, boundBefore), null,
                "{\"kind\":\"untrust\"}", List.of());
    }

    private static AuditEntry defaultAudit(UUID actor, LandId landId, ProtectionActionType action,
            PermissionState before, PermissionState state, Instant timestamp) {
        return new AuditEntry(0L, timestamp, actor, "DEFAULT_CHANGE", landId, null, null,
                AUDIT_METADATA_VERSION,
                "{\"action\":\"" + action.name() + "\",\"state\":\"" + before.name() + "\"}",
                "{\"action\":\"" + action.name() + "\",\"state\":\"" + state.name() + "\"}",
                "{\"kind\":\"land-default\"}", List.of());
    }

    private static void replaceBinding(Connection conn, LandId landId, UUID target, UUID profileId)
            throws SQLException {
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM land_bindings WHERE land_id = ? AND subject_type = 'PLAYER' AND subject_id = ?")) {
            delete.setBytes(1, UuidBlob.encode(landId.value()));
            delete.setBytes(2, UuidBlob.encode(target));
            delete.executeUpdate();
        }
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO land_bindings (land_id, subject_type, subject_id, profile_id)"
                        + " VALUES (?, 'PLAYER', ?, ?)")) {
            insert.setBytes(1, UuidBlob.encode(landId.value()));
            insert.setBytes(2, UuidBlob.encode(target));
            insert.setBytes(3, UuidBlob.encode(profileId));
            if (insert.executeUpdate() != 1) {
                throw new SQLException("binding insert affected no rows for land " + landId);
            }
        }
    }

    private static void bumpPolicyRevision(Connection conn, LandId landId, long now)
            throws SQLException {
        try (PreparedStatement bump = conn.prepareStatement(
                "UPDATE lands SET land_policy_revision = land_policy_revision + 1,"
                        + " updated_at = ? WHERE id = ?")) {
            bump.setLong(1, now);
            bump.setBytes(2, UuidBlob.encode(landId.value()));
            if (bump.executeUpdate() != 1) {
                throw new SQLException("land disappeared during commit: " + landId);
            }
        }
    }

    private static Set<ProtectionActionType> loadProfileAllows(Connection conn, UUID profileId)
            throws SQLException {
        Set<ProtectionActionType> allows = new HashSet<>();
        try (PreparedStatement entries = conn.prepareStatement(
                "SELECT permission FROM permission_profile_entries"
                        + " WHERE profile_id = ? AND state = 'ALLOW'")) {
            entries.setBytes(1, UuidBlob.encode(profileId));
            try (ResultSet rows = entries.executeQuery()) {
                while (rows.next()) {
                    ProtectionActionType action = parseAction(rows.getString(1));
                    if (action != null && isWhitelisted(action)) {
                        allows.add(action);
                    }
                }
            }
        }
        return Set.copyOf(allows);
    }

    private static ProtectionActionType parseAction(String name) {
        if (name == null) {
            return null;
        }
        try {
            return ProtectionActionType.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static PermissionState parseState(String name) {
        if (name == null) {
            return null;
        }
        try {
            return PermissionState.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private void insertAudit(Connection conn, AuditEntry audit) throws SQLException {
        long auditId;
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
            if (audit.singleChunkPacked() == null) {
                write.setObject(6, null);
            } else {
                write.setLong(6, audit.singleChunkPacked());
            }
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
                auditId = keys.getLong(1);
            }
        }
        if (audit.chunks().isEmpty()) {
            return;
        }
        try (PreparedStatement chunks = conn.prepareStatement(
                "INSERT INTO audit_chunks (audit_id, world_uuid, chunk_x, chunk_z) VALUES (?, ?, ?, ?)")) {
            for (var chunk : audit.chunks()) {
                chunks.setLong(1, auditId);
                chunks.setBytes(2, UuidBlob.encode(chunk.worldId()));
                chunks.setInt(3, chunk.chunkX());
                chunks.setInt(4, chunk.chunkZ());
                chunks.addBatch();
            }
            chunks.executeBatch();
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

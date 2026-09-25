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
        AFTER_BAN,
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
     * <p>Player-namespace only: Server Land rows fail closed before any
     * write (like bans), so a {@code SERVER} owner can never gain a
     * player-namespace binding.
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
            requirePlayerLand(conn, landId);
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
     * <p>Player-namespace only: Server Land rows fail closed before any
     * write (like bans).
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
            requirePlayerLand(conn, landId);
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
     * Ban one player from one land: insert their per-land ENTRY ban row,
     * audit, and bump the land policy revision — all in one transaction.
     * Resending while already banned still succeeds and audits. The land
     * owner and Server Land can never be banned: both fail closed before any
     * write, so the Owner Guarantee and steward contracts stay intact.
     *
     * <p>The audit before value is read from the durable ban row inside the
     * same transaction, never from the volatile cache.
     */
    public CompletionStage<Void> ban(LandId landId, UUID targetPlayer, UUID actor,
            Instant timestamp) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(targetPlayer, "targetPlayer");
        Objects.requireNonNull(timestamp, "timestamp");
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            String ownerKey = requireKnownLandOwner(conn, landId);
            if (OwnerKey.SERVER_VALUE.equals(ownerKey)) {
                throw new IllegalArgumentException(
                        "cannot ban on Server Land " + landId);
            }
            if (ownerKey.equals(OwnerKey.PLAYER_PREFIX + targetPlayer)) {
                throw new IllegalArgumentException(
                        "cannot ban the land owner " + targetPlayer + " on land " + landId);
            }
            boolean bannedBefore = hasBan(conn, landId, targetPlayer);
            long now = timestamp.toEpochMilli();
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO land_entry_bans (land_id, player_uuid, banned_at, banned_by)"
                            + " VALUES (?, ?, ?, ?)"
                            + " ON CONFLICT(land_id, player_uuid) DO UPDATE"
                            + " SET banned_at=excluded.banned_at, banned_by=excluded.banned_by")) {
                insert.setBytes(1, UuidBlob.encode(landId.value()));
                insert.setBytes(2, UuidBlob.encode(targetPlayer));
                insert.setLong(3, now);
                setUuid(insert, 4, actor);
                insert.executeUpdate();
            }
            failureInjector.accept(Step.AFTER_BAN);
            insertAudit(conn, banAudit(actor, landId, targetPlayer, bannedBefore, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            bumpPolicyRevision(conn, landId, now);
            return null;
        }));
    }

    /**
     * Unban one player on one land: delete only their per-land ENTRY ban row,
     * audit, and bump the land policy revision — all in one transaction.
     * Resending without a ban still succeeds and audits. Trust bindings and
     * land defaults are never touched.
     *
     * <p>The audit before value is read from the durable ban row inside the
     * same transaction, so a resend without a ban records no ban before it
     * instead of repeating a stale one.
     *
     * <p>Player-namespace only: Server Land rows fail closed before any
     * write (bans can never exist there).
     */
    public CompletionStage<Void> unban(LandId landId, UUID targetPlayer, UUID actor,
            Instant timestamp) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(targetPlayer, "targetPlayer");
        Objects.requireNonNull(timestamp, "timestamp");
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            requirePlayerLand(conn, landId);
            boolean bannedBefore = hasBan(conn, landId, targetPlayer);
            try (PreparedStatement delete = conn.prepareStatement(
                    "DELETE FROM land_entry_bans WHERE land_id = ? AND player_uuid = ?")) {
                delete.setBytes(1, UuidBlob.encode(landId.value()));
                delete.setBytes(2, UuidBlob.encode(targetPlayer));
                delete.executeUpdate();
            }
            failureInjector.accept(Step.AFTER_BAN);
            insertAudit(conn, unbanAudit(actor, landId, targetPlayer, bannedBefore, timestamp));
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
     * <p>Player-namespace only: Server Land rows fail closed before any
     * write (like bans).
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
            requirePlayerLand(conn, landId);
            PermissionState durableBefore = readDefaultState(conn, landId, action);
            writeDefaultRow(conn, landId, action, state);
            failureInjector.accept(Step.AFTER_DEFAULT);
            insertAudit(conn, defaultAudit(actor, landId, action, durableBefore, state, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            bumpPolicyRevision(conn, landId, timestamp.toEpochMilli());
            return null;
        }));
    }

    /**
     * Persist one land default only when the durable current still equals
     * {@code expected} (a missing row reads as {@code INHERIT}) and the
     * durable authorisation generation still equals
     * {@code expectedRevision}. A stale value fails with
     * {@link LandDefaultConflictException}; a lapsed authorisation fails
     * with {@link StaleAuthorisationException} — both before any row or
     * audit change, so a confirm page built from an older read can never
     * silently overwrite a newer change or a revoked grant. The
     * generation check, the value check, the write, the audit and the
     * revision bump stay in one transaction.
     *
     * <p>Boundary: only durable authorisation state participates here.
     * Config file defaults, admin bypass and the server-land steward
     * flag are memory-only inputs the persistence thread cannot
     * observe; their revocation is caught solely by re-checking the
     * management gate before submitting, never by this pin.
     *
     * <p>Unconditional writers keep using {@link #setDefault}: this entry
     * exists only for callers that observed the current and the
     * authorisation generation first (the management GUI confirm path
     * and {@code /land default}).
     */
    public CompletionStage<Void> setDefaultIfCurrent(LandId landId, ProtectionActionType action,
            PermissionState expected, long expectedRevision, PermissionState state, UUID actor,
            Instant timestamp) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(timestamp, "timestamp");
        requireWhitelisted(action);
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            requirePlayerLand(conn, landId);
            long durableRevision = readPolicyRevision(conn, landId);
            if (durableRevision != expectedRevision) {
                throw new StaleAuthorisationException("authorisation for " + landId
                        + " lapsed: expected revision " + expectedRevision
                        + ", durable " + durableRevision);
            }
            PermissionState durableBefore = readDefaultState(conn, landId, action);
            if (durableBefore != expected) {
                throw new LandDefaultConflictException("land default for " + action
                        + " on " + landId + " changed: expected " + expected
                        + ", durable " + durableBefore);
            }
            writeDefaultRow(conn, landId, action, state);
            failureInjector.accept(Step.AFTER_DEFAULT);
            insertAudit(conn, defaultAudit(actor, landId, action, durableBefore, state, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            bumpPolicyRevision(conn, landId, timestamp.toEpochMilli());
            return null;
        }));
    }

    /**
     * Durable authorisation generation for one land. A missing row reads
     * as a lapsed authorisation (fail-closed) rather than zero, so a
     * deleted land can never pass the pin on a default revision.
     */
    private static long readPolicyRevision(Connection conn, LandId landId)
            throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT land_policy_revision FROM lands WHERE id = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(landId.value()));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new StaleAuthorisationException(
                            "authorisation for " + landId + " lapsed: land is gone");
                }
                return rows.getLong(1);
            }
        }
    }

    private static void writeDefaultRow(Connection conn, LandId landId,
            ProtectionActionType action, PermissionState state) throws SQLException {
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
    }

    /**
     * Read every durable binding, profile entry, land default and ENTRY ban
     * into plain maps for one runtime snapshot publish. Unknown permission
     * or state text is skipped row-wise so one corrupt row can never break
     * the load.
     *
     * <p>Each binding is accepted only when its profile is the subject's own
     * implicit direct profile ({@code name_key='direct'} and {@code
     * owner_key='PLAYER:&lt;subject&gt;'}), checked on the persistence thread
     * from the joined profile row. Anything else is skipped with a warning so
     * a foreign profile can never publish another player's entries after a
     * restart. Malformed ban rows are skipped the same way, so one corrupt
     * row can never hide or forge a ban.
     */
    public CompletionStage<SnapshotData> loadSnapshotData() {
        return store.submitAsync(connection -> {
            Map<LandId, Map<UUID, Set<ProtectionActionType>>> direct = new HashMap<>();
            Map<LandId, Map<ProtectionActionType, PermissionState>> defaults = new HashMap<>();
            Map<LandId, Set<UUID>> bans = new HashMap<>();
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
            try (PreparedStatement entryBans = connection.prepareStatement(
                    "SELECT land_id, player_uuid FROM land_entry_bans")) {
                try (ResultSet rows = entryBans.executeQuery()) {
                    while (rows.next()) {
                        LandId landId;
                        UUID player;
                        try {
                            landId = new LandId(UuidBlob.decode(rows.getBytes(1)));
                            player = UuidBlob.decode(rows.getBytes(2));
                        } catch (RuntimeException corrupt) {
                            LOG.warning("Skipping land entry ban with malformed ids");
                            continue;
                        }
                        if (landId == null || player == null) {
                            LOG.warning("Skipping land entry ban with malformed ids");
                            continue;
                        }
                        bans.computeIfAbsent(landId, ignored -> new HashSet<>()).add(player);
                    }
                }
            }
            return new SnapshotData(direct, defaults, bans, readPolicyRevisions(connection));
        });
    }

    /**
     * Durable authorisation generation per land for one snapshot publish.
     * Malformed ids are skipped row-wise like every other load here, so
     * one corrupt row can never break the publish; a skipped land simply
     * carries no pin and its writers fail closed until the next load.
     */
    private static Map<LandId, Long> readPolicyRevisions(Connection conn) throws SQLException {
        Map<LandId, Long> revisions = new HashMap<>();
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT id, land_policy_revision FROM lands")) {
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    LandId landId;
                    try {
                        landId = new LandId(UuidBlob.decode(rows.getBytes(1)));
                    } catch (RuntimeException corrupt) {
                        LOG.warning("Skipping land revision with malformed id");
                        continue;
                    }
                    if (landId != null) {
                        revisions.put(landId, rows.getLong(2));
                    }
                }
            }
        }
        return revisions;
    }

    /**
     * Plain durable maps for one runtime snapshot publish. The maps are
     * already defensively copied and unmodifiable.
     */
    public record SnapshotData(
            Map<LandId, Map<UUID, Set<ProtectionActionType>>> directAllows,
            Map<LandId, Map<ProtectionActionType, PermissionState>> landDefaults,
            Map<LandId, Set<UUID>> entryBans,
            Map<LandId, Long> landPolicyRevisions) {
        public SnapshotData {
            directAllows = copyDirect(directAllows);
            landDefaults = copyDefaults(landDefaults);
            entryBans = copyBans(entryBans);
            landPolicyRevisions = copyRevisions(landPolicyRevisions);
        }

        /** Compatibility: no bans loaded yet reads as nobody banned. */
        public SnapshotData(
                Map<LandId, Map<UUID, Set<ProtectionActionType>>> directAllows,
                Map<LandId, Map<ProtectionActionType, PermissionState>> landDefaults) {
            this(directAllows, landDefaults, Map.of());
        }

        /** Compatibility: loads that predate revision publishing carry none. */
        public SnapshotData(
                Map<LandId, Map<UUID, Set<ProtectionActionType>>> directAllows,
                Map<LandId, Map<ProtectionActionType, PermissionState>> landDefaults,
                Map<LandId, Set<UUID>> entryBans) {
            this(directAllows, landDefaults, entryBans, Map.of());
        }

        private static Map<LandId, Long> copyRevisions(Map<LandId, Long> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<LandId, Long> copy = new HashMap<>(source.size());
            for (Map.Entry<LandId, Long> entry : source.entrySet()) {
                copy.put(Objects.requireNonNull(entry.getKey(), "land key"),
                        Objects.requireNonNull(entry.getValue(), "policy revision"));
            }
            return Map.copyOf(copy);
        }

        private static Map<LandId, Set<UUID>> copyBans(Map<LandId, Set<UUID>> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<LandId, Set<UUID>> copy = new HashMap<>(source.size());
            for (Map.Entry<LandId, Set<UUID>> entry : source.entrySet()) {
                copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            return Map.copyOf(copy);
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

    private static String requireKnownLandOwner(Connection conn, LandId landId)
            throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT owner_key FROM lands WHERE id = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(landId.value()));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("unknown land " + landId);
                }
                return rows.getString(1);
            }
        }
    }

    /**
     * Require a known player-owned land: unknown rows fail with SQL like
     * {@link #requireKnownLand}, while {@code SERVER} rows fail closed with
     * an illegal argument so the player-namespace mutations (trust, untrust,
     * defaults, unbans) can never touch the Server namespace.
     */
    private static void requirePlayerLand(Connection conn, LandId landId) throws SQLException {
        String ownerKey = requireKnownLandOwner(conn, landId);
        if (ownerKey == null || !ownerKey.startsWith(OwnerKey.PLAYER_PREFIX)) {
            throw new IllegalArgumentException("cannot mutate player-namespace state on non-player Land "
                    + landId);
        }
    }

    private static boolean hasBan(Connection conn, LandId landId, UUID target)
            throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT 1 FROM land_entry_bans WHERE land_id = ? AND player_uuid = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(landId.value()));
            query.setBytes(2, UuidBlob.encode(target));
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static String banJson(UUID target, boolean banned) {
        return "{\"target\":\"" + target + "\",\"banned\":" + banned + "}";
    }

    private static AuditEntry banAudit(UUID actor, LandId landId, UUID target,
            boolean bannedBefore, Instant timestamp) {
        return new AuditEntry(0L, timestamp, actor, "ENTRY_BAN", landId, null, null,
                AUDIT_METADATA_VERSION, banJson(target, bannedBefore),
                banJson(target, true), "{\"kind\":\"entry-ban\"}", List.of());
    }

    private static AuditEntry unbanAudit(UUID actor, LandId landId, UUID target,
            boolean bannedBefore, Instant timestamp) {
        return new AuditEntry(0L, timestamp, actor, "ENTRY_UNBAN", landId, null, null,
                AUDIT_METADATA_VERSION, banJson(target, bannedBefore),
                banJson(target, false), "{\"kind\":\"entry-unban\"}", List.of());
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

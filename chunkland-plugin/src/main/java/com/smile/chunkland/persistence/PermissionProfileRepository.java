package com.smile.chunkland.persistence;

import com.smile.chunkland.api.permission.DecisionSource;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * All-or-nothing durable writes for player-owned Permission Profiles.
 *
 * <p>One call writes the profile or entry rows plus the audit row inside a
 * single transaction on the persistence thread, so a failure (including an
 * audit failure) rolls back every row instead of leaving partial state
 * behind. Profiles are keyed by a random UUID; the human name is only an
 * owner-scoped lookup key ({@code UNIQUE(owner_key, name_key)}), so one
 * profile stays reusable across many lands, sublands and future bindings and
 * never disappears with a land delete.
 *
 * <p>Entries are sparse: only {@link PermissionState#ALLOW} and
 * {@link PermissionState#DENY} rows are stored, and only for
 * {@link DecisionSource#SUBJECT_PERMISSION} actions. {@link PermissionState#INHERIT}
 * deletes the row instead of storing a value. Land-rule actions are rejected
 * before any write.
 *
 * <p>The implicit per-player {@code direct} profile (see
 * {@link LandAuthorisationRepository#DIRECT_PROFILE_NAME_KEY}) is a fixed
 * internal contract: the generic path refuses to create, modify or delete it
 * with {@code profile.reserved}, so direct trust bindings can never be
 * polluted from here.
 *
 * <p>Every method takes the caller's player UUID as the owner namespace. The
 * type itself only admits player owners, so a {@code SERVER} namespace can
 * never be constructed here; foreign profiles read as absent and mutate as
 * unknown, so one owner can neither read nor guess another owner's profiles.
 * No collaborator callback runs inside the transaction: the only out-of-line
 * call is the test-only failure injector.
 */
public final class PermissionProfileRepository {

    /** Injection points for atomicity tests. */
    public enum Step {
        AFTER_PROFILE,
        AFTER_ENTRY,
        AFTER_BINDING_DELETE,
        AFTER_AUDIT
    }

    /** Audit actions written by the atomic profile transactions. */
    public static final String CREATE_AUDIT_ACTION = "PROFILE_CREATE";
    public static final String UPDATE_AUDIT_ACTION = "PROFILE_UPDATE";
    public static final String DELETE_AUDIT_ACTION = "PROFILE_DELETE";

    /** Metadata schema version for the audit rows written here. */
    public static final int AUDIT_METADATA_VERSION = 1;

    /**
     * Name key of the implicit per-player direct profile. Reserved: only the
     * internal trust path may create or touch it, never this repository.
     */
    public static final String DIRECT_PROFILE_NAME_KEY = "direct";

    /**
     * Immutable view of one profile with its sparse entries. The map holds
     * only {@code ALLOW}/{@code DENY} values and is unmodifiable; later
     * milestones resolve bindings against this shape without touching the
     * runtime decision path.
     */
    public record ProfileView(
            UUID id,
            UUID owner,
            String displayName,
            String nameKey,
            long createdAtMillis,
            Map<ProtectionActionType, PermissionState> entries) {
        public ProfileView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(nameKey, "nameKey");
            Objects.requireNonNull(entries, "entries");
            entries = entries.isEmpty()
                    ? Map.of()
                    : Map.copyOf(new EnumMap<>(entries));
        }
    }

    /**
     * One land or subland binding row that references a profile.
     * {@code scope} is {@code LAND} or {@code SUBLAND}; {@code sublandId} is
     * {@code null} on land scope, and {@code landId} on subland scope names
     * the parent land (possibly {@code null} when the parent row is already
     * gone). {@code subjectType} is {@code PLAYER} or {@code GROUP}.
     */
    public record AffectedBinding(
            String scope,
            UUID landId,
            UUID sublandId,
            String subjectType,
            UUID subjectId) {
        public AffectedBinding {
            Objects.requireNonNull(scope, "scope");
        }
    }

    /**
     * Outcome of one entry write: the stored value before and after.
     * {@code INHERIT} on either side means "no row". Immutable so later
     * milestones can hand it to the binding resolver without re-reading.
     */
    public record EntryOutcome(
            UUID profileId,
            ProtectionActionType action,
            PermissionState before,
            PermissionState after) {
        public EntryOutcome {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
        }
    }

    /**
     * Outcome of one force delete: the removed profile identity plus the
     * binding references removed with it in the same transaction.
     */
    public record ForceDeleteOutcome(
            UUID profileId,
            String displayName,
            String nameKey,
            List<AffectedBinding> affected) {
        public ForceDeleteOutcome {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(nameKey, "nameKey");
            Objects.requireNonNull(affected, "affected");
            affected = List.copyOf(affected);
        }
    }

    private final PersistenceStore store;
    private final Consumer<Step> failureInjector;

    public PermissionProfileRepository(PersistenceStore store) {
        this(store, ignored -> {
        });
    }

    public PermissionProfileRepository(PersistenceStore store, Consumer<Step> failureInjector) {
        this.store = Objects.requireNonNull(store, "store");
        this.failureInjector = Objects.requireNonNull(failureInjector, "failureInjector");
    }

    /**
     * Normalizes a raw profile name into its stable owner-scoped key: stripped
     * of surrounding whitespace and lower-cased with {@link Locale#ROOT}.
     *
     * @throws ProfileRejectedException with {@code profile.invalid} on blank,
     *         control-bearing or reserved ({@code EVERYONE}, {@code *}) input,
     *         or with {@code profile.reserved} on the fixed {@code direct}
     *         internal name
     */
    public static String normalizeNameKey(String raw) {
        Objects.requireNonNull(raw, "raw");
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isISOControl(raw.charAt(i))) {
                throw new ProfileRejectedException("profile.invalid");
            }
        }
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            throw new ProfileRejectedException("profile.invalid");
        }
        if (stripped.equalsIgnoreCase("EVERYONE") || stripped.equals("*")) {
            throw new ProfileRejectedException("profile.invalid");
        }
        String key = stripped.toLowerCase(Locale.ROOT);
        if (DIRECT_PROFILE_NAME_KEY.equals(key)) {
            throw new ProfileRejectedException("profile.reserved");
        }
        return key;
    }

    /**
     * Lookup form of a raw name: like {@link #normalizeNameKey(String)} but
     * the fixed {@code direct} name passes through, so resolving it still
     * finds the row and the mutation path can refuse it with
     * {@code profile.reserved} instead of leaking nothing or guessing.
     * Invalid names still throw {@code profile.invalid}.
     */
    static String lookupNameKey(String raw) {
        Objects.requireNonNull(raw, "raw");
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isISOControl(raw.charAt(i))) {
                throw new ProfileRejectedException("profile.invalid");
            }
        }
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            throw new ProfileRejectedException("profile.invalid");
        }
        if (stripped.equalsIgnoreCase("EVERYONE") || stripped.equals("*")) {
            throw new ProfileRejectedException("profile.invalid");
        }
        return stripped.toLowerCase(Locale.ROOT);
    }

    /** Display form of a raw profile name: stripped, case preserved. */
    static String displayNameOf(String raw) {
        return Objects.requireNonNull(raw, "raw").strip();
    }

    /**
     * Create one profile in the caller's namespace with a fresh UUID identity
     * and no entries.
     *
     * @throws ProfileRejectedException with {@code profile.invalid},
     *         {@code profile.reserved} or {@code profile.duplicate} before any
     *         write on invalid, reserved or already-used names
     */
    public CompletionStage<ProfileView> create(UUID owner, String displayName, UUID actor,
            Instant timestamp) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(timestamp, "timestamp");
        String nameKey = normalizeNameKey(Objects.requireNonNull(displayName, "displayName"));
        String display = displayNameOf(displayName);
        String ownerKey = OwnerKey.player(owner).asString();
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            requireNameAvailable(conn, ownerKey, nameKey);
            UUID profileId = UUID.randomUUID();
            long now = timestamp.toEpochMilli();
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO permission_profiles (id, owner_key, name_key, display_name, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)")) {
                insert.setBytes(1, UuidBlob.encode(profileId));
                insert.setString(2, ownerKey);
                insert.setString(3, nameKey);
                insert.setString(4, display);
                insert.setLong(5, now);
                if (insert.executeUpdate() != 1) {
                    throw new SQLException("profile insert affected no rows");
                }
            } catch (SQLException duplicate) {
                if (isUniqueViolation(duplicate)) {
                    throw new ProfileRejectedException("profile.duplicate", duplicate);
                }
                throw duplicate;
            }
            failureInjector.accept(Step.AFTER_PROFILE);
            insertAudit(conn, createAudit(actor, profileId, ownerKey, display, nameKey, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            return new ProfileView(profileId, owner, display, nameKey, now, Map.of());
        }));
    }

    /**
     * List every profile in the caller's namespace, oldest first, each with
     * its sparse entries. Foreign namespaces never appear.
     */
    public CompletionStage<List<ProfileView>> list(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        String ownerKey = OwnerKey.player(owner).asString();
        return store.submitAsync(connection -> {
            List<ProfileView> out = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id, name_key, display_name, created_at FROM permission_profiles"
                            + " WHERE owner_key = ? ORDER BY created_at, rowid")) {
                query.setString(1, ownerKey);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        UUID profileId = UuidBlob.decode(rows.getBytes(1));
                        out.add(new ProfileView(profileId, owner, rows.getString(3),
                                rows.getString(2), rows.getLong(4),
                                readEntries(connection, profileId)));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    /**
     * Find one profile by id within the caller's namespace. A foreign or
     * missing id reads as empty so existence never leaks across owners.
     */
    public CompletionStage<Optional<ProfileView>> findById(UUID owner, UUID profileId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(profileId, "profileId");
        String ownerKey = OwnerKey.player(owner).asString();
        return store.submitAsync(connection -> readProfile(connection, owner, ownerKey, profileId));
    }

    /**
     * Find one profile by name within the caller's namespace. Invalid names
     * read as empty rather than throwing, so guessing stays fail-closed.
     */
    public CompletionStage<Optional<ProfileView>> findByName(UUID owner, String displayName) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(displayName, "displayName");
        String ownerKey = OwnerKey.player(owner).asString();
        String nameKey;
        try {
            nameKey = lookupNameKey(displayName);
        } catch (ProfileRejectedException invalid) {
            return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
        }
        return store.submitAsync(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id FROM permission_profiles"
                            + " WHERE owner_key = ? AND name_key = ? LIMIT 1")) {
                query.setString(1, ownerKey);
                query.setString(2, nameKey);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    return readProfile(connection, owner, ownerKey,
                            UuidBlob.decode(rows.getBytes(1)));
                }
            }
        });
    }

    /**
     * Persist one sparse entry on a profile in the caller's namespace:
     * {@code ALLOW} and {@code DENY} upsert the row, {@code INHERIT} deletes
     * it. Only subject-permission actions are accepted; land-rule actions
     * fail closed before any SQL.
     *
     * @throws ProfileRejectedException with {@code profile.unknown} when the
     *         profile is missing or owned by someone else,
     *         {@code profile.reserved} on the fixed {@code direct} profile, or
     *         {@code profile.invalid_permission} on a land-rule action
     */
    public CompletionStage<EntryOutcome> setEntry(UUID owner, UUID profileId,
            ProtectionActionType action, PermissionState state, UUID actor, Instant timestamp) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(timestamp, "timestamp");
        requireSubjectPermission(action);
        String ownerKey = OwnerKey.player(owner).asString();
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            ProfileView before = requireOwnedWritable(conn, owner, ownerKey, profileId);
            PermissionState storedBefore = before.entries().getOrDefault(action, PermissionState.INHERIT);
            if (state == PermissionState.INHERIT) {
                try (PreparedStatement delete = conn.prepareStatement(
                        "DELETE FROM permission_profile_entries"
                                + " WHERE profile_id = ? AND permission = ?")) {
                    delete.setBytes(1, UuidBlob.encode(profileId));
                    delete.setString(2, action.name());
                    delete.executeUpdate();
                }
            } else {
                try (PreparedStatement upsert = conn.prepareStatement(
                        "INSERT INTO permission_profile_entries (profile_id, permission, state)"
                                + " VALUES (?, ?, ?)"
                                + " ON CONFLICT(profile_id, permission) DO UPDATE"
                                + " SET state=excluded.state")) {
                    upsert.setBytes(1, UuidBlob.encode(profileId));
                    upsert.setString(2, action.name());
                    upsert.setString(3, state.name());
                    upsert.executeUpdate();
                }
            }
            failureInjector.accept(Step.AFTER_ENTRY);
            insertAudit(conn, entryAudit(actor, before, action, storedBefore, state, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            return new EntryOutcome(profileId, action, storedBefore, state);
        }));
    }

    /**
     * Delete one profile in the caller's namespace.
     *
     * <p>When any land or subland binding still references the profile the
     * delete is refused with {@link ProfileDeleteRestrictedException} and no
     * row is touched. Entry rows go with the profile; other profiles and
     * bindings that point at them are never touched.
     *
     * @throws ProfileRejectedException with {@code profile.unknown} when the
     *         profile is missing or owned by someone else, or
     *         {@code profile.reserved} on the fixed {@code direct} profile
     */
    public CompletionStage<Void> delete(UUID owner, UUID profileId, UUID actor,
            Instant timestamp) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(timestamp, "timestamp");
        String ownerKey = OwnerKey.player(owner).asString();
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            ProfileView before = requireOwnedWritable(conn, owner, ownerKey, profileId);
            List<AffectedBinding> affected = collectBindings(conn, profileId);
            if (!affected.isEmpty()) {
                throw new ProfileDeleteRestrictedException(affected);
            }
            deleteEntries(conn, profileId);
            deleteProfile(conn, profileId);
            failureInjector.accept(Step.AFTER_PROFILE);
            insertAudit(conn, deleteAudit(actor, before, false, List.of(), timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            return null;
        }));
    }

    /**
     * Force delete one profile in the caller's namespace: the referencing
     * binding rows, the entry rows and the profile row are removed together
     * with the delete audit in one transaction, and the removed references
     * are returned so the caller can name them.
     *
     * <p>Only bindings that point at this profile are removed; other
     * profiles, their entries and their bindings are never touched.
     *
     * @throws ProfileRejectedException with {@code profile.unknown} when the
     *         profile is missing or owned by someone else, or
     *         {@code profile.reserved} on the fixed {@code direct} profile
     */
    public CompletionStage<ForceDeleteOutcome> forceDelete(UUID owner, UUID profileId, UUID actor,
            Instant timestamp) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(timestamp, "timestamp");
        String ownerKey = OwnerKey.player(owner).asString();
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            ProfileView before = requireOwnedWritable(conn, owner, ownerKey, profileId);
            List<AffectedBinding> affected = collectBindings(conn, profileId);
            deleteProfileBindings(conn, profileId);
            failureInjector.accept(Step.AFTER_BINDING_DELETE);
            deleteEntries(conn, profileId);
            deleteProfile(conn, profileId);
            failureInjector.accept(Step.AFTER_PROFILE);
            insertAudit(conn, deleteAudit(actor, before, true, affected, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            return new ForceDeleteOutcome(profileId, before.displayName(),
                    before.nameKey(), affected);
        }));
    }

    // ---- durable reads ----

    private Optional<ProfileView> readProfile(Connection conn, UUID owner, String ownerKey,
            UUID profileId) throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT name_key, display_name, created_at FROM permission_profiles"
                        + " WHERE id = ? AND owner_key = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(profileId));
            query.setString(2, ownerKey);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ProfileView(profileId, owner, rows.getString(2),
                        rows.getString(1), rows.getLong(3), readEntries(conn, profileId)));
            }
        }
    }

    private ProfileView requireOwned(Connection conn, UUID owner, String ownerKey,
            UUID profileId) throws SQLException {
        Optional<ProfileView> found = readProfile(conn, owner, ownerKey, profileId);
        if (found.isEmpty() || found.get() == null) {
            throw new ProfileRejectedException("profile.unknown");
        }
        return found.get();
    }

    private ProfileView requireOwnedWritable(Connection conn, UUID owner, String ownerKey,
            UUID profileId) throws SQLException {
        ProfileView found = requireOwned(conn, owner, ownerKey, profileId);
        if (DIRECT_PROFILE_NAME_KEY.equals(found.nameKey())) {
            throw new ProfileRejectedException("profile.reserved");
        }
        return found;
    }

    private static Map<ProtectionActionType, PermissionState> readEntries(Connection conn,
            UUID profileId) throws SQLException {
        Map<ProtectionActionType, PermissionState> entries = new EnumMap<>(ProtectionActionType.class);
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT permission, state FROM permission_profile_entries WHERE profile_id = ?")) {
            query.setBytes(1, UuidBlob.encode(profileId));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    ProtectionActionType action = parseAction(rows.getString(1));
                    PermissionState state = parseState(rows.getString(2));
                    if (action == null || state == null || state == PermissionState.INHERIT) {
                        continue;
                    }
                    entries.put(action, state);
                }
            }
        }
        return entries;
    }

    private static void requireNameAvailable(Connection conn, String ownerKey,
            String nameKey) throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT 1 FROM permission_profiles"
                        + " WHERE owner_key = ? AND name_key = ? LIMIT 1")) {
            query.setString(1, ownerKey);
            query.setString(2, nameKey);
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) {
                    throw new ProfileRejectedException("profile.duplicate");
                }
            }
        }
    }

    private static void requireSubjectPermission(ProtectionActionType action) {
        if (action.decisionSource() != DecisionSource.SUBJECT_PERMISSION) {
            throw new ProfileRejectedException("profile.invalid_permission");
        }
    }

    private static List<AffectedBinding> collectBindings(Connection conn, UUID profileId)
            throws SQLException {
        List<AffectedBinding> affected = new ArrayList<>();
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT land_id, subject_type, subject_id FROM land_bindings"
                        + " WHERE profile_id = ?")) {
            query.setBytes(1, UuidBlob.encode(profileId));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    affected.add(new AffectedBinding("LAND",
                            UuidBlob.decode(rows.getBytes(1)), null,
                            rows.getString(2), UuidBlob.decode(rows.getBytes(3))));
                }
            }
        }
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT b.subland_id, s.land_id, b.subject_type, b.subject_id"
                        + " FROM subland_bindings b"
                        + " LEFT JOIN sublands s ON s.id = b.subland_id"
                        + " WHERE b.profile_id = ?")) {
            query.setBytes(1, UuidBlob.encode(profileId));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    byte[] parent = rows.getBytes(2);
                    affected.add(new AffectedBinding("SUBLAND",
                            parent == null ? null : UuidBlob.decode(parent),
                            UuidBlob.decode(rows.getBytes(1)),
                            rows.getString(3), UuidBlob.decode(rows.getBytes(4))));
                }
            }
        }
        return List.copyOf(affected);
    }

    // ---- durable writes (inside the caller's transaction) ----

    private static void deleteProfileBindings(Connection conn, UUID profileId) throws SQLException {
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM land_bindings WHERE profile_id = ?")) {
            delete.setBytes(1, UuidBlob.encode(profileId));
            delete.executeUpdate();
        }
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM subland_bindings WHERE profile_id = ?")) {
            delete.setBytes(1, UuidBlob.encode(profileId));
            delete.executeUpdate();
        }
    }

    private static void deleteEntries(Connection conn, UUID profileId) throws SQLException {
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM permission_profile_entries WHERE profile_id = ?")) {
            delete.setBytes(1, UuidBlob.encode(profileId));
            delete.executeUpdate();
        }
    }

    private static void deleteProfile(Connection conn, UUID profileId) throws SQLException {
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM permission_profiles WHERE id = ?")) {
            delete.setBytes(1, UuidBlob.encode(profileId));
            if (delete.executeUpdate() != 1) {
                throw new SQLException("profile disappeared during delete: " + profileId);
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

    // ---- audit rows (inside the caller's transaction) ----

    private static AuditEntry createAudit(UUID actor, UUID profileId, String ownerKey,
            String display, String nameKey, Instant timestamp) {
        return new AuditEntry(0L, timestamp, actor, CREATE_AUDIT_ACTION, null, null, null,
                AUDIT_METADATA_VERSION, null,
                "{\"profile\":\"" + profileId + "\",\"nameKey\":\"" + escape(nameKey)
                        + "\",\"displayName\":\"" + escape(display) + "\"}",
                "{\"kind\":\"profile-create\",\"owner\":\"" + escape(ownerKey) + "\"}", List.of());
    }

    private static AuditEntry entryAudit(UUID actor, ProfileView profile,
            ProtectionActionType action, PermissionState before, PermissionState after,
            Instant timestamp) {
        return new AuditEntry(0L, timestamp, actor, UPDATE_AUDIT_ACTION, null, null, null,
                AUDIT_METADATA_VERSION,
                "{\"profile\":\"" + profile.id() + "\",\"action\":\"" + action.name()
                        + "\",\"state\":\"" + before.name() + "\"}",
                "{\"profile\":\"" + profile.id() + "\",\"action\":\"" + action.name()
                        + "\",\"state\":\"" + after.name() + "\"}",
                "{\"kind\":\"profile-set\",\"owner\":\"" + escape(profile.owner().toString())
                        + "\",\"nameKey\":\"" + escape(profile.nameKey()) + "\"}", List.of());
    }

    private static AuditEntry deleteAudit(UUID actor, ProfileView before, boolean force,
            List<AffectedBinding> affected, Instant timestamp) {
        StringBuilder affectedJson = new StringBuilder("[");
        for (int i = 0; i < affected.size(); i++) {
            AffectedBinding binding = affected.get(i);
            if (i > 0) {
                affectedJson.append(',');
            }
            affectedJson.append("{\"scope\":\"").append(binding.scope()).append('"');
            if (binding.landId() != null) {
                affectedJson.append(",\"land\":\"").append(binding.landId()).append('"');
            }
            if (binding.sublandId() != null) {
                affectedJson.append(",\"subland\":\"").append(binding.sublandId()).append('"');
            }
            if (binding.subjectType() != null) {
                affectedJson.append(",\"subjectType\":\"").append(binding.subjectType()).append('"');
            }
            if (binding.subjectId() != null) {
                affectedJson.append(",\"subject\":\"").append(binding.subjectId()).append('"');
            }
            affectedJson.append('}');
        }
        affectedJson.append(']');
        return new AuditEntry(0L, timestamp, actor, DELETE_AUDIT_ACTION, null, null, null,
                AUDIT_METADATA_VERSION,
                "{\"profile\":\"" + before.id() + "\",\"nameKey\":\""
                        + escape(before.nameKey()) + "\",\"displayName\":\""
                        + escape(before.displayName()) + "\",\"entries\":" + before.entries().size()
                        + "}",
                null,
                "{\"kind\":\"profile-delete\",\"force\":" + force
                        + ",\"affected\":" + affectedJson + "}", List.of());
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
            write.setObject(4, null);
            write.setObject(5, null);
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

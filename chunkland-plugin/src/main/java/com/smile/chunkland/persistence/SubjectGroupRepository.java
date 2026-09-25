package com.smile.chunkland.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * All-or-nothing durable writes for Global Groups in one player namespace.
 *
 * <p>One call writes the group or member rows plus the audit row inside a
 * single transaction on the persistence thread, so a failure (including an
 * audit failure) rolls back every row instead of leaving partial state
 * behind. Groups are keyed by a random UUID; the human name is only an
 * owner-scoped lookup key ({@code UNIQUE(owner_key, name_key)}), so one group
 * stays reusable across many lands and never disappears with a land delete
 * (no foreign key points from lands or bindings back to the group row).
 *
 * <p>Every method takes the caller's player UUID as the owner namespace. The
 * type itself only admits player owners, so a {@code SERVER} namespace can
 * never be constructed here; foreign groups read as absent and mutate as
 * unknown, so one owner can neither read nor guess another owner's groups.
 * No collaborator callback runs inside the transaction: the only out-of-line
 * call is the test-only failure injector.
 */
public final class SubjectGroupRepository {

    /** Injection points for atomicity tests. */
    public enum Step {
        AFTER_GROUP,
        AFTER_MEMBER,
        AFTER_BINDING_DELETE,
        AFTER_AUDIT
    }

    /** Audit actions written by the atomic group transactions. */
    public static final String CREATE_AUDIT_ACTION = "GROUP_CREATE";
    public static final String MEMBER_AUDIT_ACTION = "GROUP_MEMBER_CHANGE";
    public static final String DELETE_AUDIT_ACTION = "GROUP_DELETE";

    /** Metadata schema version for the audit rows written here. */
    public static final int AUDIT_METADATA_VERSION = 1;

    /**
     * Immutable view of one group with its member ids. Collections are
     * unmodifiable; later milestones resolve bindings against this shape
     * without touching the runtime decision path.
     */
    public record GroupView(
            UUID id,
            UUID owner,
            String displayName,
            String nameKey,
            long createdAtMillis,
            Set<UUID> members) {
        public GroupView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(nameKey, "nameKey");
            Objects.requireNonNull(members, "members");
            members = Set.copyOf(members);
        }
    }

    /**
     * One {@code GROUP} binding row that references a group. {@code scope} is
     * {@code LAND} or {@code SUBLAND}; {@code sublandId} is {@code null} on
     * land scope, and {@code landId} on subland scope names the parent land
     * (possibly {@code null} when the parent row is already gone).
     */
    public record AffectedBinding(
            String scope,
            UUID landId,
            UUID sublandId,
            UUID profileId) {
        public AffectedBinding {
            Objects.requireNonNull(scope, "scope");
        }
    }

    /**
     * Outcome of one membership write: whether the member was present before
     * and after. Resends succeed and audit with equal before/after values.
     */
    public record MembershipOutcome(
            UUID groupId,
            UUID member,
            boolean presentBefore,
            boolean presentAfter) {
        public MembershipOutcome {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(member, "member");
        }
    }

    /**
     * Outcome of one force delete: the removed group identity plus the
     * binding references removed with it in the same transaction.
     */
    public record ForceDeleteOutcome(
            UUID groupId,
            String displayName,
            String nameKey,
            List<AffectedBinding> affected) {
        public ForceDeleteOutcome {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(nameKey, "nameKey");
            Objects.requireNonNull(affected, "affected");
            affected = List.copyOf(affected);
        }
    }

    private final PersistenceStore store;
    private final Consumer<Step> failureInjector;

    public SubjectGroupRepository(PersistenceStore store) {
        this(store, ignored -> {
        });
    }

    public SubjectGroupRepository(PersistenceStore store, Consumer<Step> failureInjector) {
        this.store = Objects.requireNonNull(store, "store");
        this.failureInjector = Objects.requireNonNull(failureInjector, "failureInjector");
    }

    /**
     * Normalizes a raw group name into its stable owner-scoped key: stripped
     * of surrounding whitespace and lower-cased with {@link Locale#ROOT}.
     *
     * @throws GroupRejectedException with {@code group.invalid} on blank,
     *         control-bearing or reserved ({@code EVERYONE}, {@code *}) input
     */
    public static String normalizeNameKey(String raw) {
        Objects.requireNonNull(raw, "raw");
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isISOControl(raw.charAt(i))) {
                throw new GroupRejectedException("group.invalid");
            }
        }
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            throw new GroupRejectedException("group.invalid");
        }
        if (stripped.equalsIgnoreCase("EVERYONE") || stripped.equals("*")) {
            throw new GroupRejectedException("group.invalid");
        }
        return stripped.toLowerCase(Locale.ROOT);
    }

    /** Display form of a raw group name: stripped, case preserved. */
    static String displayNameOf(String raw) {
        return Objects.requireNonNull(raw, "raw").strip();
    }

    /**
     * Create one group in the caller's namespace with a fresh UUID identity.
     *
     * @throws GroupRejectedException with {@code group.invalid} or
     *         {@code group.duplicate} before any write on invalid or
     *         already-used names
     */
    public CompletionStage<GroupView> create(UUID owner, String displayName, UUID actor,
            Instant timestamp) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(timestamp, "timestamp");
        String nameKey = normalizeNameKey(Objects.requireNonNull(displayName, "displayName"));
        String display = displayNameOf(displayName);
        String ownerKey = OwnerKey.player(owner).asString();
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            requireNameAvailable(conn, ownerKey, nameKey);
            UUID groupId = UUID.randomUUID();
            long now = timestamp.toEpochMilli();
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO subject_groups (id, owner_key, name_key, display_name, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)")) {
                insert.setBytes(1, UuidBlob.encode(groupId));
                insert.setString(2, ownerKey);
                insert.setString(3, nameKey);
                insert.setString(4, display);
                insert.setLong(5, now);
                if (insert.executeUpdate() != 1) {
                    throw new SQLException("group insert affected no rows");
                }
            } catch (SQLException duplicate) {
                if (isUniqueViolation(duplicate)) {
                    throw new GroupRejectedException("group.duplicate", duplicate);
                }
                throw duplicate;
            }
            failureInjector.accept(Step.AFTER_GROUP);
            OwnerAclEpochs.increment(conn, ownerKey);
            insertAudit(conn, createAudit(actor, groupId, ownerKey, display, nameKey, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            return new GroupView(groupId, owner, display, nameKey, now, Set.of());
        }));
    }

    /**
     * List every group in the caller's namespace, oldest first, each with its
     * member ids. Foreign namespaces never appear.
     */
    public CompletionStage<List<GroupView>> list(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        String ownerKey = OwnerKey.player(owner).asString();
        return store.submitAsync(connection -> {
            List<GroupView> out = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id, name_key, display_name, created_at FROM subject_groups"
                            + " WHERE owner_key = ? ORDER BY created_at, rowid")) {
                query.setString(1, ownerKey);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        UUID groupId = UuidBlob.decode(rows.getBytes(1));
                        out.add(new GroupView(groupId, owner, rows.getString(3),
                                rows.getString(2), rows.getLong(4),
                                readMembers(connection, groupId)));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    /**
     * Find one group by id within the caller's namespace. A foreign or
     * missing id reads as empty so existence never leaks across owners.
     */
    public CompletionStage<Optional<GroupView>> findById(UUID owner, UUID groupId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(groupId, "groupId");
        String ownerKey = OwnerKey.player(owner).asString();
        return store.submitAsync(connection -> readGroup(connection, owner, ownerKey, groupId));
    }

    /**
     * Find one group by name within the caller's namespace. Invalid names
     * read as empty rather than throwing, so guessing stays fail-closed.
     */
    public CompletionStage<Optional<GroupView>> findByName(UUID owner, String displayName) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(displayName, "displayName");
        String ownerKey = OwnerKey.player(owner).asString();
        String nameKey;
        try {
            nameKey = normalizeNameKey(displayName);
        } catch (GroupRejectedException invalid) {
            return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
        }
        return store.submitAsync(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id FROM subject_groups WHERE owner_key = ? AND name_key = ? LIMIT 1")) {
                query.setString(1, ownerKey);
                query.setString(2, nameKey);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    return readGroup(connection, owner, ownerKey,
                            UuidBlob.decode(rows.getBytes(1)));
                }
            }
        });
    }

    /**
     * Add one member to a group in the caller's namespace. Resending for a
     * member that is already present still succeeds and audits.
     *
     * @throws GroupRejectedException with {@code group.unknown} when the
     *         group is missing or owned by someone else
     */
    public CompletionStage<MembershipOutcome> addMember(UUID owner, UUID groupId, UUID member,
            UUID actor, Instant timestamp) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(timestamp, "timestamp");
        String ownerKey = OwnerKey.player(owner).asString();
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            GroupView before = requireOwned(conn, owner, ownerKey, groupId);
            boolean presentBefore = before.members().contains(member);
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO subject_group_members (group_id, member_uuid)"
                            + " VALUES (?, ?) ON CONFLICT(group_id, member_uuid) DO NOTHING")) {
                insert.setBytes(1, UuidBlob.encode(groupId));
                insert.setBytes(2, UuidBlob.encode(member));
                insert.executeUpdate();
            }
            failureInjector.accept(Step.AFTER_MEMBER);
            OwnerAclEpochs.increment(conn, ownerKey);
            bumpReferencingLands(conn, groupId, timestamp.toEpochMilli());
            insertAudit(conn, memberAudit(actor, groupId, before, member,
                    presentBefore, true, "group-member-add", timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            return new MembershipOutcome(groupId, member, presentBefore, true);
        }));
    }

    /**
     * Remove one member from a group in the caller's namespace. Resending
     * without a membership still succeeds and audits; the group itself is
     * kept even when it becomes empty.
     *
     * @throws GroupRejectedException with {@code group.unknown} when the
     *         group is missing or owned by someone else
     */
    public CompletionStage<MembershipOutcome> removeMember(UUID owner, UUID groupId, UUID member,
            UUID actor, Instant timestamp) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(timestamp, "timestamp");
        String ownerKey = OwnerKey.player(owner).asString();
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            GroupView before = requireOwned(conn, owner, ownerKey, groupId);
            boolean presentBefore = before.members().contains(member);
            try (PreparedStatement delete = conn.prepareStatement(
                    "DELETE FROM subject_group_members WHERE group_id = ? AND member_uuid = ?")) {
                delete.setBytes(1, UuidBlob.encode(groupId));
                delete.setBytes(2, UuidBlob.encode(member));
                delete.executeUpdate();
            }
            failureInjector.accept(Step.AFTER_MEMBER);
            OwnerAclEpochs.increment(conn, ownerKey);
            bumpReferencingLands(conn, groupId, timestamp.toEpochMilli());
            insertAudit(conn, memberAudit(actor, groupId, before, member,
                    presentBefore, false, "group-member-remove", timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            return new MembershipOutcome(groupId, member, presentBefore, false);
        }));
    }

    /**
     * Delete one group in the caller's namespace.
     *
     * <p>When any land or subland binding still references the group the
     * delete is refused with {@link GroupDeleteRestrictedException} and no
     * row is touched. Member rows go with the group; profile rows and
     * non-{@code GROUP} bindings are never touched.
     *
     * @throws GroupRejectedException with {@code group.unknown} when the
     *         group is missing or owned by someone else
     */
    public CompletionStage<Void> delete(UUID owner, UUID groupId, UUID actor,
            Instant timestamp) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(timestamp, "timestamp");
        String ownerKey = OwnerKey.player(owner).asString();
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            GroupView before = requireOwned(conn, owner, ownerKey, groupId);
            List<AffectedBinding> affected = collectBindings(conn, groupId);
            if (!affected.isEmpty()) {
                throw new GroupDeleteRestrictedException(affected);
            }
            deleteMembers(conn, groupId);
            deleteGroup(conn, groupId);
            failureInjector.accept(Step.AFTER_GROUP);
            OwnerAclEpochs.increment(conn, ownerKey);
            insertAudit(conn, deleteAudit(actor, before, false, List.of(), timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            return null;
        }));
    }

    /**
     * Force delete one group in the caller's namespace: the referencing
     * {@code GROUP} binding rows, the member rows and the group row are
     * removed together with the delete audit in one transaction, and the
     * removed references are returned so the caller can name them.
     *
     * <p>Profile rows and non-{@code GROUP} bindings are never touched.
     *
     * @throws GroupRejectedException with {@code group.unknown} when the
     *         group is missing or owned by someone else
     */
    public CompletionStage<ForceDeleteOutcome> forceDelete(UUID owner, UUID groupId, UUID actor,
            Instant timestamp) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(timestamp, "timestamp");
        String ownerKey = OwnerKey.player(owner).asString();
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            GroupView before = requireOwned(conn, owner, ownerKey, groupId);
            List<AffectedBinding> affected = collectBindings(conn, groupId);
            deleteGroupBindings(conn, groupId);
            failureInjector.accept(Step.AFTER_BINDING_DELETE);
            bumpAffectedLands(conn, affected, timestamp.toEpochMilli());
            deleteMembers(conn, groupId);
            deleteGroup(conn, groupId);
            failureInjector.accept(Step.AFTER_GROUP);
            OwnerAclEpochs.increment(conn, ownerKey);
            insertAudit(conn, deleteAudit(actor, before, true, affected, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            return new ForceDeleteOutcome(groupId, before.displayName(),
                    before.nameKey(), affected);
        }));
    }

    // ---- durable reads ----

    private Optional<GroupView> readGroup(Connection conn, UUID owner, String ownerKey,
            UUID groupId) throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT name_key, display_name, created_at FROM subject_groups"
                        + " WHERE id = ? AND owner_key = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(groupId));
            query.setString(2, ownerKey);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new GroupView(groupId, owner, rows.getString(2),
                        rows.getString(1), rows.getLong(3), readMembers(conn, groupId)));
            }
        }
    }

    private GroupView requireOwned(Connection conn, UUID owner, String ownerKey,
            UUID groupId) throws SQLException {
        Optional<GroupView> found = readGroup(conn, owner, ownerKey, groupId);
        if (found.isEmpty() || found.get() == null) {
            throw new GroupRejectedException("group.unknown");
        }
        return found.get();
    }

    private static Set<UUID> readMembers(Connection conn, UUID groupId) throws SQLException {
        Set<UUID> members = new HashSet<>();
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT member_uuid FROM subject_group_members WHERE group_id = ?")) {
            query.setBytes(1, UuidBlob.encode(groupId));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    members.add(UuidBlob.decode(rows.getBytes(1)));
                }
            }
        }
        return Set.copyOf(members);
    }

    private static void requireNameAvailable(Connection conn, String ownerKey,
            String nameKey) throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT 1 FROM subject_groups WHERE owner_key = ? AND name_key = ? LIMIT 1")) {
            query.setString(1, ownerKey);
            query.setString(2, nameKey);
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) {
                    throw new GroupRejectedException("group.duplicate");
                }
            }
        }
    }

    private static List<AffectedBinding> collectBindings(Connection conn, UUID groupId)
            throws SQLException {
        List<AffectedBinding> affected = new ArrayList<>();
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT land_id, profile_id FROM land_bindings"
                        + " WHERE subject_type = 'GROUP' AND subject_id = ?")) {
            query.setBytes(1, UuidBlob.encode(groupId));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    affected.add(new AffectedBinding("LAND",
                            UuidBlob.decode(rows.getBytes(1)), null,
                            UuidBlob.decode(rows.getBytes(2))));
                }
            }
        }
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT b.subland_id, s.land_id, b.profile_id FROM subland_bindings b"
                        + " LEFT JOIN sublands s ON s.id = b.subland_id"
                        + " WHERE b.subject_type = 'GROUP' AND b.subject_id = ?")) {
            query.setBytes(1, UuidBlob.encode(groupId));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    byte[] parent = rows.getBytes(2);
                    affected.add(new AffectedBinding("SUBLAND",
                            parent == null ? null : UuidBlob.decode(parent),
                            UuidBlob.decode(rows.getBytes(1)),
                            UuidBlob.decode(rows.getBytes(3))));
                }
            }
        }
        return List.copyOf(affected);
    }

    // ---- durable writes (inside the caller's transaction) ----

    /**
     * Move the authorisation generation of every land bound to one group.
     * Membership changes alter binding visibility without touching the
     * binding rows, so without this bump a revision pinned at gate time
     * would miss the revocation.
     */
    private static void bumpReferencingLands(Connection conn, UUID groupId, long now)
            throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT DISTINCT land_id FROM land_bindings"
                        + " WHERE subject_type = 'GROUP' AND subject_id = ?")) {
            query.setBytes(1, UuidBlob.encode(groupId));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    bumpPolicyRevision(conn, UuidBlob.decode(rows.getBytes(1)), now);
                }
            }
        }
    }

    /**
     * Move the authorisation generation of every land-scope land in a
     * collected affected-binding list. Subland scopes name no land-level
     * decision input, so only {@code LAND} entries bump.
     */
    private static void bumpAffectedLands(Connection conn, List<AffectedBinding> affected,
            long now) throws SQLException {
        for (AffectedBinding binding : affected) {
            if (binding != null && "LAND".equals(binding.scope())
                    && binding.landId() != null) {
                bumpPolicyRevision(conn, binding.landId(), now);
            }
        }
    }

    private static void bumpPolicyRevision(Connection conn, UUID landId, long now)
            throws SQLException {
        try (PreparedStatement bump = conn.prepareStatement(
                "UPDATE lands SET land_policy_revision = land_policy_revision + 1,"
                        + " updated_at = ? WHERE id = ?")) {
            bump.setLong(1, now);
            bump.setBytes(2, UuidBlob.encode(landId));
            if (bump.executeUpdate() != 1) {
                throw new SQLException("land disappeared during commit: " + landId);
            }
        }
    }

    private static void deleteGroupBindings(Connection conn, UUID groupId) throws SQLException {
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM land_bindings WHERE subject_type = 'GROUP' AND subject_id = ?")) {
            delete.setBytes(1, UuidBlob.encode(groupId));
            delete.executeUpdate();
        }
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM subland_bindings WHERE subject_type = 'GROUP' AND subject_id = ?")) {
            delete.setBytes(1, UuidBlob.encode(groupId));
            delete.executeUpdate();
        }
    }

    private static void deleteMembers(Connection conn, UUID groupId) throws SQLException {
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM subject_group_members WHERE group_id = ?")) {
            delete.setBytes(1, UuidBlob.encode(groupId));
            delete.executeUpdate();
        }
    }

    private static void deleteGroup(Connection conn, UUID groupId) throws SQLException {
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM subject_groups WHERE id = ?")) {
            delete.setBytes(1, UuidBlob.encode(groupId));
            if (delete.executeUpdate() != 1) {
                throw new SQLException("group disappeared during delete: " + groupId);
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

    // ---- audit rows (inside the caller's transaction) ----

    private static AuditEntry createAudit(UUID actor, UUID groupId, String ownerKey,
            String display, String nameKey, Instant timestamp) {
        return new AuditEntry(0L, timestamp, actor, CREATE_AUDIT_ACTION, null, null, null,
                AUDIT_METADATA_VERSION, null,
                "{\"group\":\"" + groupId + "\",\"nameKey\":\"" + escape(nameKey)
                        + "\",\"displayName\":\"" + escape(display) + "\"}",
                "{\"kind\":\"group-create\",\"owner\":\"" + escape(ownerKey) + "\"}", List.of());
    }

    private static AuditEntry memberAudit(UUID actor, UUID groupId, GroupView group, UUID member,
            boolean presentBefore, boolean presentAfter, String kind, Instant timestamp) {
        return new AuditEntry(0L, timestamp, actor, MEMBER_AUDIT_ACTION, null, null, null,
                AUDIT_METADATA_VERSION,
                "{\"group\":\"" + groupId + "\",\"member\":\"" + member
                        + "\",\"present\":" + presentBefore + "}",
                "{\"group\":\"" + groupId + "\",\"member\":\"" + member
                        + "\",\"present\":" + presentAfter + "}",
                "{\"kind\":\"" + kind + "\",\"owner\":\"" + escape(group.owner().toString())
                        + "\",\"nameKey\":\"" + escape(group.nameKey()) + "\"}", List.of());
    }

    private static AuditEntry deleteAudit(UUID actor, GroupView before, boolean force,
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
            if (binding.profileId() != null) {
                affectedJson.append(",\"profile\":\"").append(binding.profileId()).append('"');
            }
            affectedJson.append('}');
        }
        affectedJson.append(']');
        return new AuditEntry(0L, timestamp, actor, DELETE_AUDIT_ACTION, null, null, null,
                AUDIT_METADATA_VERSION,
                "{\"group\":\"" + before.id() + "\",\"nameKey\":\""
                        + escape(before.nameKey()) + "\",\"displayName\":\""
                        + escape(before.displayName()) + "\",\"members\":" + before.members().size()
                        + "}",
                null,
                "{\"kind\":\"group-delete\",\"force\":" + force
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

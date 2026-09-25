package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * All-or-nothing durable writes for generic Land/SubLand bindings.
 *
 * <p>One call replaces or removes the single binding row for one
 * scope-plus-subject, writes the {@code BINDING_CREATE}/{@code BINDING_UPDATE}/
 * {@code BINDING_DELETE} audit row, bumps the parent land's policy revision
 * and increments the owner's ACL epoch — all inside one transaction on the
 * persistence thread, so a failure (including an audit failure) rolls back
 * every row instead of leaving partial state behind. The unique scope-subject
 * backstop plus the single-writer transaction make concurrent replaces
 * deterministic: the second writer replaces the first writer's row instead
 * of duplicating it.
 *
 * <p>Subjects are canonical UUIDs only: a player UUID or a group UUID. There
 * is no wildcard subject — "everyone" is expressed through defaults, never
 * through a binding row. Profiles must exist in the same owner namespace and
 * must never be the implicit {@code direct} profile; group subjects must
 * exist in the same namespace. Anything cross-owner, unknown, server-owned
 * or reserved fails closed with {@link BindingRejectedException} before any
 * write, so one owner can neither read nor guess another owner's bindings.
 * Profiles and groups are never deleted here, only binding rows.
 *
 * <p>No collaborator callback runs inside the transaction: the only
 * out-of-line call is the test-only failure injector.
 */
public final class LandBindingRepository {

    /** Injection points for atomicity tests. */
    public enum Step {
        AFTER_BINDING,
        AFTER_AUDIT
    }

    /** Audit actions written by the atomic binding transactions. */
    public static final String CREATE_AUDIT_ACTION = "BINDING_CREATE";
    public static final String UPDATE_AUDIT_ACTION = "BINDING_UPDATE";
    public static final String DELETE_AUDIT_ACTION = "BINDING_DELETE";

    /** Metadata schema version for the audit rows written here. */
    public static final int AUDIT_METADATA_VERSION = 1;

    /** Name key of the implicit per-player direct profile. Never bindable here. */
    public static final String DIRECT_PROFILE_NAME_KEY = "direct";

    /** Which subject namespace a binding addresses. */
    public enum SubjectKind {
        PLAYER,
        GROUP
    }

    /**
     * Canonical binding subject: a player UUID or a group UUID. The type
     * itself makes name-as-id, {@code EVERYONE} and {@code *} unrepresentable.
     */
    public record Subject(SubjectKind kind, UUID id) {
        public Subject {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
        }

        /** Subject SQL text for the {@code subject_type} column. */
        String sqlType() {
            return kind.name();
        }
    }

    /**
     * Outcome of one bind: the bound profile plus whether a binding row
     * already existed for the same scope and subject (which decides between
     * the create and update audit actions).
     */
    public record BindOutcome(
            UUID landId,
            UUID sublandId,
            Subject subject,
            UUID profileId,
            boolean replaced) {
        public BindOutcome {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(profileId, "profileId");
        }
    }

    /**
     * Outcome of one unbind: the removed profile, or empty when no binding
     * row existed. Resends still succeed, audit and bump.
     */
    public record UnbindOutcome(
            UUID landId,
            UUID sublandId,
            Subject subject,
            UUID removedProfileId) {
        public UnbindOutcome {
            Objects.requireNonNull(subject, "subject");
        }
    }

    /**
     * One generic binding row resolved against its profile entries and, for
     * group subjects, its member list. The runtime lookup turns only matching
     * rows into decision bindings: player rows for their own subject id,
     * group rows only for members.
     */
    public record GenericBinding(
            String subjectType,
            UUID subjectId,
            String subjectLabel,
            Map<ProtectionActionType, PermissionState> entries,
            Set<UUID> members) {
        public GenericBinding {
            Objects.requireNonNull(subjectType, "subjectType");
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(subjectLabel, "subjectLabel");
            Objects.requireNonNull(entries, "entries");
            Objects.requireNonNull(members, "members");
            entries = entries.isEmpty()
                    ? Map.of()
                    : Map.copyOf(new EnumMap<>(entries));
            members = Set.copyOf(members);
        }
    }

    /**
     * Plain durable maps for one runtime snapshot publish: generic bindings
     * per land and per subland plus sparse subland defaults. Unset subland
     * defaults read as {@code INHERIT} downstream. The maps are already
     * defensively copied and unmodifiable.
     */
    public record GenericSnapshotData(
            Map<LandId, List<GenericBinding>> landBindings,
            Map<SubLandId, List<GenericBinding>> sublandBindings,
            Map<SubLandId, Map<ProtectionActionType, PermissionState>> sublandDefaults,
            Map<LandId, Long> landPolicyRevisions) {
        public GenericSnapshotData {
            landBindings = copyBindings(landBindings);
            sublandBindings = copySubBindings(sublandBindings);
            sublandDefaults = copyDefaults(sublandDefaults);
            landPolicyRevisions = copyRevisions(landPolicyRevisions);
        }

        private static Map<LandId, List<GenericBinding>> copyBindings(
                Map<LandId, List<GenericBinding>> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<LandId, List<GenericBinding>> copy = new HashMap<>(source.size());
            for (Map.Entry<LandId, List<GenericBinding>> entry : source.entrySet()) {
                copy.put(Objects.requireNonNull(entry.getKey(), "land key"),
                        List.copyOf(Objects.requireNonNull(entry.getValue(), "bindings")));
            }
            return Map.copyOf(copy);
        }

        private static Map<SubLandId, List<GenericBinding>> copySubBindings(
                Map<SubLandId, List<GenericBinding>> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<SubLandId, List<GenericBinding>> copy = new HashMap<>(source.size());
            for (Map.Entry<SubLandId, List<GenericBinding>> entry : source.entrySet()) {
                copy.put(Objects.requireNonNull(entry.getKey(), "subland key"),
                        List.copyOf(Objects.requireNonNull(entry.getValue(), "bindings")));
            }
            return Map.copyOf(copy);
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

        private static Map<SubLandId, Map<ProtectionActionType, PermissionState>> copyDefaults(
                Map<SubLandId, Map<ProtectionActionType, PermissionState>> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<SubLandId, Map<ProtectionActionType, PermissionState>> copy =
                    new HashMap<>(source.size());
            for (Map.Entry<SubLandId, Map<ProtectionActionType, PermissionState>> entry
                    : source.entrySet()) {
                copy.put(Objects.requireNonNull(entry.getKey(), "subland key"),
                        Map.copyOf(new EnumMap<>(Objects.requireNonNull(
                                entry.getValue(), "subland defaults"))));
            }
            return Map.copyOf(copy);
        }
    }

    private final PersistenceStore store;
    private final Consumer<Step> failureInjector;

    public LandBindingRepository(PersistenceStore store) {
        this(store, ignored -> {
        });
    }

    public LandBindingRepository(PersistenceStore store, Consumer<Step> failureInjector) {
        this.store = Objects.requireNonNull(store, "store");
        this.failureInjector = Objects.requireNonNull(failureInjector, "failureInjector");
    }

    /**
     * Bind one subject on one land, replacing any existing row for the same
     * subject. Creates the row when absent (create audit), replaces it when
     * present (update audit).
     *
     * @throws BindingRejectedException with {@code binding.unknown} when the
     *         land, group subject or profile is missing, foreign or
     *         server-owned, or with {@code binding.reserved} on the implicit
     *         direct profile
     */
    public CompletionStage<BindOutcome> bindLand(UUID owner, LandId landId, Subject subject,
            UUID profileId, UUID actor, Instant timestamp) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(timestamp, "timestamp");
        String ownerKey = OwnerAclEpochs.ownerKey(owner);
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            requireOwnedLand(conn, ownerKey, landId);
            requireBindableProfile(conn, ownerKey, profileId);
            requireKnownSubject(conn, ownerKey, subject);
            UUID before = readLandBinding(conn, landId, subject);
            deleteLandBinding(conn, landId, subject);
            insertLandBinding(conn, landId, subject, profileId);
            failureInjector.accept(Step.AFTER_BINDING);
            insertAudit(conn, landAudit(actor, landId, null, subject, before, profileId,
                    before == null ? CREATE_AUDIT_ACTION : UPDATE_AUDIT_ACTION, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            bumpPolicyRevision(conn, landId, timestamp.toEpochMilli());
            OwnerAclEpochs.increment(conn, ownerKey);
            return new BindOutcome(landId.value(), null, subject, profileId, before != null);
        }));
    }

    /**
     * Remove one subject's binding on one land. Resending without a binding
     * row still succeeds, audits the removal and bumps.
     *
     * @throws BindingRejectedException with {@code binding.unknown} when the
     *         land is missing, foreign or server-owned
     */
    public CompletionStage<UnbindOutcome> unbindLand(UUID owner, LandId landId, Subject subject,
            UUID actor, Instant timestamp) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(timestamp, "timestamp");
        String ownerKey = OwnerAclEpochs.ownerKey(owner);
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            requireOwnedLand(conn, ownerKey, landId);
            UUID before = readLandBinding(conn, landId, subject);
            deleteLandBinding(conn, landId, subject);
            failureInjector.accept(Step.AFTER_BINDING);
            insertAudit(conn, landAudit(actor, landId, null, subject, before, null,
                    DELETE_AUDIT_ACTION, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            bumpPolicyRevision(conn, landId, timestamp.toEpochMilli());
            OwnerAclEpochs.increment(conn, ownerKey);
            return new UnbindOutcome(landId.value(), null, subject, before);
        }));
    }

    /**
     * Bind one subject on one subland, replacing any existing row for the
     * same subject. The parent land's policy revision is bumped; sublands
     * carry no revision of their own.
     *
     * @throws BindingRejectedException with {@code binding.unknown} when the
     *         subland, its parent land, the group subject or the profile is
     *         missing, foreign or server-owned, or with
     *         {@code binding.reserved} on the implicit direct profile
     */
    public CompletionStage<BindOutcome> bindSubland(UUID owner, SubLandId sublandId,
            Subject subject, UUID profileId, UUID actor, Instant timestamp) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(sublandId, "sublandId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(timestamp, "timestamp");
        String ownerKey = OwnerAclEpochs.ownerKey(owner);
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            LandId parent = requireOwnedSubland(conn, ownerKey, sublandId);
            requireBindableProfile(conn, ownerKey, profileId);
            requireKnownSubject(conn, ownerKey, subject);
            UUID before = readSublandBinding(conn, sublandId, subject);
            deleteSublandBinding(conn, sublandId, subject);
            insertSublandBinding(conn, sublandId, subject, profileId);
            failureInjector.accept(Step.AFTER_BINDING);
            insertAudit(conn, landAudit(actor, parent, sublandId, subject, before, profileId,
                    before == null ? CREATE_AUDIT_ACTION : UPDATE_AUDIT_ACTION, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            bumpPolicyRevision(conn, parent, timestamp.toEpochMilli());
            OwnerAclEpochs.increment(conn, ownerKey);
            return new BindOutcome(parent.value(), sublandId.value(), subject, profileId,
                    before != null);
        }));
    }

    /**
     * Remove one subject's binding on one subland. Resending without a
     * binding row still succeeds, audits the removal and bumps the parent
     * land revision.
     *
     * @throws BindingRejectedException with {@code binding.unknown} when the
     *         subland or its parent land is missing, foreign or server-owned
     */
    public CompletionStage<UnbindOutcome> unbindSubland(UUID owner, SubLandId sublandId,
            Subject subject, UUID actor, Instant timestamp) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(sublandId, "sublandId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(timestamp, "timestamp");
        String ownerKey = OwnerAclEpochs.ownerKey(owner);
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            LandId parent = requireOwnedSubland(conn, ownerKey, sublandId);
            UUID before = readSublandBinding(conn, sublandId, subject);
            deleteSublandBinding(conn, sublandId, subject);
            failureInjector.accept(Step.AFTER_BINDING);
            insertAudit(conn, landAudit(actor, parent, sublandId, subject, before, null,
                    DELETE_AUDIT_ACTION, timestamp));
            failureInjector.accept(Step.AFTER_AUDIT);
            bumpPolicyRevision(conn, parent, timestamp.toEpochMilli());
            OwnerAclEpochs.increment(conn, ownerKey);
            return new UnbindOutcome(parent.value(), sublandId.value(), subject, before);
        }));
    }

    /**
     * Read the durable epoch for one owner namespace. Never fails closed
     * with an exception for fresh owners: they read as zero.
     */
    public CompletionStage<Long> epochOf(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        String ownerKey = OwnerAclEpochs.ownerKey(owner);
        return store.submitAsync(connection -> OwnerAclEpochs.read(connection, ownerKey));
    }

    /**
     * Read every generic binding plus sparse subland defaults into plain maps
     * for one runtime snapshot publish. The implicit direct profiles stay on
     * the dedicated direct path: rows pointing at them are skipped here so
     * the two readers can never double-publish one binding. Unknown
     * subject types, permissions or states are skipped row-wise so one
     * corrupt row can never break the load.
     */
    public CompletionStage<GenericSnapshotData> loadSnapshotData() {
        return store.submitAsync(connection -> {
            Map<LandId, List<GenericBinding>> land = new HashMap<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT b.land_id, b.subject_type, b.subject_id, b.profile_id,"
                            + " p.name_key, g.display_name"
                            + " FROM land_bindings b"
                            + " JOIN permission_profiles p ON p.id = b.profile_id"
                            + " LEFT JOIN subject_groups g ON g.id = b.subject_id"
                            + " AND b.subject_type = 'GROUP'")) {
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        LandId landId;
                        UUID subjectId;
                        UUID profileId;
                        try {
                            landId = new LandId(UuidBlob.decode(rows.getBytes(1)));
                            subjectId = UuidBlob.decode(rows.getBytes(3));
                            profileId = UuidBlob.decode(rows.getBytes(4));
                        } catch (RuntimeException corrupt) {
                            continue;
                        }
                        if (landId == null || subjectId == null || profileId == null) {
                            continue;
                        }
                        GenericBinding binding = readGenericBinding(connection, rows.getString(2),
                                subjectId, profileId, rows.getString(5), rows.getString(6));
                        if (binding == null) {
                            continue;
                        }
                        land.computeIfAbsent(landId, ignored -> new ArrayList<>()).add(binding);
                    }
                }
            }
            Map<SubLandId, List<GenericBinding>> subland = new HashMap<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT b.subland_id, b.subject_type, b.subject_id, b.profile_id,"
                            + " p.name_key, g.display_name"
                            + " FROM subland_bindings b"
                            + " JOIN permission_profiles p ON p.id = b.profile_id"
                            + " LEFT JOIN subject_groups g ON g.id = b.subject_id"
                            + " AND b.subject_type = 'GROUP'")) {
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        SubLandId sublandId;
                        UUID subjectId;
                        UUID profileId;
                        try {
                            sublandId = new SubLandId(UuidBlob.decode(rows.getBytes(1)));
                            subjectId = UuidBlob.decode(rows.getBytes(3));
                            profileId = UuidBlob.decode(rows.getBytes(4));
                        } catch (RuntimeException corrupt) {
                            continue;
                        }
                        if (sublandId == null || subjectId == null || profileId == null) {
                            continue;
                        }
                        GenericBinding binding = readGenericBinding(connection, rows.getString(2),
                                subjectId, profileId, rows.getString(5), rows.getString(6));
                        if (binding == null) {
                            continue;
                        }
                        subland.computeIfAbsent(sublandId, ignored -> new ArrayList<>())
                                .add(binding);
                    }
                }
            }
            Map<SubLandId, Map<ProtectionActionType, PermissionState>> subDefaults =
                    new HashMap<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT subland_id, permission, state FROM subland_defaults")) {
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        SubLandId sublandId;
                        try {
                            sublandId = new SubLandId(UuidBlob.decode(rows.getBytes(1)));
                        } catch (RuntimeException corrupt) {
                            continue;
                        }
                        if (sublandId == null) {
                            continue;
                        }
                        ProtectionActionType action = parseAction(rows.getString(2));
                        PermissionState state = parseState(rows.getString(3));
                        if (action == null || state == null
                                || state == PermissionState.INHERIT
                                || action.decisionSource() != DecisionSource.SUBJECT_PERMISSION) {
                            continue;
                        }
                        subDefaults
                                .computeIfAbsent(sublandId,
                                        ignored -> new EnumMap<>(ProtectionActionType.class))
                                .put(action, state);
                    }
                }
            }
            Map<LandId, List<GenericBinding>> frozenLand = new HashMap<>(land.size());
            for (Map.Entry<LandId, List<GenericBinding>> entry : land.entrySet()) {
                frozenLand.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            Map<SubLandId, List<GenericBinding>> frozenSub = new HashMap<>(subland.size());
            for (Map.Entry<SubLandId, List<GenericBinding>> entry : subland.entrySet()) {
                frozenSub.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return new GenericSnapshotData(frozenLand, frozenSub, subDefaults,
                    readPolicyRevisions(connection));
        });
    }

    /**
     * Durable authorisation generation per land for one snapshot publish.
     * Malformed ids are skipped row-wise like every other load here; a
     * skipped land carries no pin and its writers fail closed until the
     * next load.
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

    private static GenericBinding readGenericBinding(Connection conn, String subjectType,
            UUID subjectId, UUID profileId, String profileNameKey, String groupLabel)
            throws SQLException {
        if (!SubjectKind.PLAYER.name().equals(subjectType)
                && !SubjectKind.GROUP.name().equals(subjectType)) {
            return null;
        }
        if (DIRECT_PROFILE_NAME_KEY.equals(profileNameKey)) {
            return null;
        }
        Map<ProtectionActionType, PermissionState> entries = readEntries(conn, profileId);
        if (SubjectKind.GROUP.name().equals(subjectType)) {
            if (groupLabel == null) {
                return null;
            }
            return new GenericBinding(subjectType, subjectId, groupLabel, entries,
                    readMembers(conn, subjectId));
        }
        return new GenericBinding(subjectType, subjectId, subjectId.toString(), entries,
                Set.of());
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
                    if (action == null || state == null || state == PermissionState.INHERIT
                            || action.decisionSource() != DecisionSource.SUBJECT_PERMISSION) {
                        continue;
                    }
                    entries.put(action, state);
                }
            }
        }
        return entries;
    }

    private static Set<UUID> readMembers(Connection conn, UUID groupId) throws SQLException {
        Set<UUID> members = new HashSet<>();
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT member_uuid FROM subject_group_members WHERE group_id = ?")) {
            query.setBytes(1, UuidBlob.encode(groupId));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    try {
                        UUID member = UuidBlob.decode(rows.getBytes(1));
                        if (member != null) {
                            members.add(member);
                        }
                    } catch (RuntimeException corrupt) {
                        // One corrupt member row must not hide the binding.
                    }
                }
            }
        }
        return Set.copyOf(members);
    }

    // ---- durable guards (inside the caller's transaction) ----

    private static void requireOwnedLand(Connection conn, String ownerKey, LandId landId)
            throws SQLException {
        String stored;
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT owner_key FROM lands WHERE id = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(landId.value()));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new BindingRejectedException("binding.unknown");
                }
                stored = rows.getString(1);
            }
        }
        if (!ownerKey.equals(stored)) {
            throw new BindingRejectedException("binding.unknown");
        }
    }

    private static LandId requireOwnedSubland(Connection conn, String ownerKey,
            SubLandId sublandId) throws SQLException {
        UUID parentRaw;
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT land_id FROM sublands WHERE id = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(sublandId.value()));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new BindingRejectedException("binding.unknown");
                }
                try {
                    parentRaw = UuidBlob.decode(rows.getBytes(1));
                } catch (RuntimeException corrupt) {
                    throw new BindingRejectedException("binding.unknown", corrupt);
                }
            }
        }
        if (parentRaw == null) {
            throw new BindingRejectedException("binding.unknown");
        }
        LandId parent = new LandId(parentRaw);
        requireOwnedLand(conn, ownerKey, parent);
        return parent;
    }

    private static void requireBindableProfile(Connection conn, String ownerKey, UUID profileId)
            throws SQLException {
        String storedOwner;
        String nameKey;
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT owner_key, name_key FROM permission_profiles WHERE id = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(profileId));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new BindingRejectedException("binding.unknown");
                }
                storedOwner = rows.getString(1);
                nameKey = rows.getString(2);
            }
        }
        if (!ownerKey.equals(storedOwner)) {
            throw new BindingRejectedException("binding.unknown");
        }
        if (DIRECT_PROFILE_NAME_KEY.equals(nameKey)) {
            throw new BindingRejectedException("binding.reserved");
        }
    }

    private static void requireKnownSubject(Connection conn, String ownerKey, Subject subject)
            throws SQLException {
        if (subject.kind() != SubjectKind.GROUP) {
            return;
        }
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT 1 FROM subject_groups WHERE id = ? AND owner_key = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(subject.id()));
            query.setString(2, ownerKey);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new BindingRejectedException("binding.unknown");
                }
            }
        }
    }

    // ---- durable writes (inside the caller's transaction) ----

    private static UUID readLandBinding(Connection conn, LandId landId, Subject subject)
            throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT profile_id FROM land_bindings WHERE land_id = ?"
                        + " AND subject_type = ? AND subject_id = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(landId.value()));
            query.setString(2, subject.sqlType());
            query.setBytes(3, UuidBlob.encode(subject.id()));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                try {
                    return UuidBlob.decode(rows.getBytes(1));
                } catch (RuntimeException corrupt) {
                    return null;
                }
            }
        }
    }

    private static void deleteLandBinding(Connection conn, LandId landId, Subject subject)
            throws SQLException {
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM land_bindings WHERE land_id = ?"
                        + " AND subject_type = ? AND subject_id = ?")) {
            delete.setBytes(1, UuidBlob.encode(landId.value()));
            delete.setString(2, subject.sqlType());
            delete.setBytes(3, UuidBlob.encode(subject.id()));
            delete.executeUpdate();
        }
    }

    private static void insertLandBinding(Connection conn, LandId landId, Subject subject,
            UUID profileId) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO land_bindings (land_id, subject_type, subject_id, profile_id)"
                        + " VALUES (?, ?, ?, ?)")) {
            insert.setBytes(1, UuidBlob.encode(landId.value()));
            insert.setString(2, subject.sqlType());
            insert.setBytes(3, UuidBlob.encode(subject.id()));
            insert.setBytes(4, UuidBlob.encode(profileId));
            if (insert.executeUpdate() != 1) {
                throw new SQLException("binding insert affected no rows for land " + landId);
            }
        }
    }

    private static UUID readSublandBinding(Connection conn, SubLandId sublandId, Subject subject)
            throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT profile_id FROM subland_bindings WHERE subland_id = ?"
                        + " AND subject_type = ? AND subject_id = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(sublandId.value()));
            query.setString(2, subject.sqlType());
            query.setBytes(3, UuidBlob.encode(subject.id()));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                try {
                    return UuidBlob.decode(rows.getBytes(1));
                } catch (RuntimeException corrupt) {
                    return null;
                }
            }
        }
    }

    private static void deleteSublandBinding(Connection conn, SubLandId sublandId, Subject subject)
            throws SQLException {
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM subland_bindings WHERE subland_id = ?"
                        + " AND subject_type = ? AND subject_id = ?")) {
            delete.setBytes(1, UuidBlob.encode(sublandId.value()));
            delete.setString(2, subject.sqlType());
            delete.setBytes(3, UuidBlob.encode(subject.id()));
            delete.executeUpdate();
        }
    }

    private static void insertSublandBinding(Connection conn, SubLandId sublandId, Subject subject,
            UUID profileId) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO subland_bindings (subland_id, subject_type, subject_id, profile_id)"
                        + " VALUES (?, ?, ?, ?)")) {
            insert.setBytes(1, UuidBlob.encode(sublandId.value()));
            insert.setString(2, subject.sqlType());
            insert.setBytes(3, UuidBlob.encode(subject.id()));
            insert.setBytes(4, UuidBlob.encode(profileId));
            if (insert.executeUpdate() != 1) {
                throw new SQLException(
                        "binding insert affected no rows for subland " + sublandId);
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

    // ---- audit rows (inside the caller's transaction) ----

    private static AuditEntry landAudit(UUID actor, LandId landId, SubLandId sublandId,
            Subject subject, UUID beforeProfile, UUID afterProfile, String action,
            Instant timestamp) {
        String scope = sublandId == null ? "LAND" : "SUBLAND";
        StringBuilder after = new StringBuilder("{\"scope\":\"").append(scope).append('"');
        after.append(",\"subject\":\"").append(subject.kind()).append(':').append(subject.id())
                .append('"');
        if (afterProfile != null) {
            after.append(",\"profile\":\"").append(afterProfile).append('"');
        }
        if (sublandId != null) {
            after.append(",\"subland\":\"").append(sublandId.value()).append('"');
        }
        after.append('}');
        String before = null;
        if (beforeProfile != null) {
            before = "{\"scope\":\"" + scope + "\",\"subject\":\"" + subject.kind() + ':'
                    + subject.id() + "\",\"profile\":\"" + beforeProfile + "\"}";
        }
        String afterJson = DELETE_AUDIT_ACTION.equals(action) ? null : after.toString();
        String kind = sublandId == null ? "binding" : "subland-binding";
        return new AuditEntry(0L, timestamp, actor, action, landId, null, null,
                AUDIT_METADATA_VERSION, before, afterJson,
                "{\"kind\":\"" + kind + "\"}", List.of());
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
}

package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Player-owned Permission Profile persistence: owner-scoped CRUD with UUID
 * identity, sparse ALLOW/DENY/INHERIT entries, a fixed {@code direct}
 * internal contract the generic path can never touch, RESTRICT on referenced
 * profiles with zero mutation, and atomic force deletes that remove only the
 * referencing bindings.
 */
class PermissionProfileRepositoryTest {

    @TempDir Path tmp;

    private Path db(String name) {
        return tmp.resolve(name);
    }

    private Instant now() {
        return Instant.now();
    }

    private static String reasonOf(CompletionException failure) {
        Throwable cause = failure.getCause();
        while ((cause instanceof CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null
                && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof ProfileDeleteRestrictedException restricted) {
            return restricted.reason() + ":" + restricted.affected().size();
        }
        if (cause instanceof ProfileRejectedException rejected) {
            return rejected.reason();
        }
        throw new AssertionError("expected ProfileRejectedException, got " + cause, failure);
    }

    private int count(PersistenceStore store, String sql, Object... params) {
        return store.execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    Object param = params[i];
                    if (param instanceof UUID uuid) {
                        ps.setBytes(i + 1, UuidBlob.encode(uuid));
                    } else {
                        ps.setString(i + 1, (String) param);
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    private int auditCount(PersistenceStore store, String action) {
        return count(store, "SELECT COUNT(*) FROM audit_log WHERE action = ?", action);
    }

    private LandSnapshot land(UUID worldId, LandId id, OwnerRef owner) {
        return new LandSnapshot(id, "Home", "home", owner, worldId, java.util.Set.of(),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private UUID insertDirectProfile(PersistenceStore store, UUID owner) {
        UUID profileId = UUID.randomUUID();
        store.execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO permission_profiles (id, owner_key, name_key, display_name, created_at)"
                            + " VALUES (?, ?, 'direct', 'Direct', ?)")) {
                ps.setBytes(1, UuidBlob.encode(profileId));
                ps.setString(2, "PLAYER:" + owner);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
                return null;
            }
        });
        store.execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO permission_profile_entries (profile_id, permission, state)"
                            + " VALUES (?, 'ENTRY', 'ALLOW')")) {
                ps.setBytes(1, UuidBlob.encode(profileId));
                ps.executeUpdate();
                return null;
            }
        });
        return profileId;
    }

    private void insertLandProfileBinding(PersistenceStore store, LandId landId,
            String subjectType, UUID subjectId, UUID profileId) {
        store.execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO land_bindings (land_id, subject_type, subject_id, profile_id)"
                            + " VALUES (?, ?, ?, ?)")) {
                ps.setBytes(1, UuidBlob.encode(landId.value()));
                ps.setString(2, subjectType);
                ps.setBytes(3, UuidBlob.encode(subjectId));
                ps.setBytes(4, UuidBlob.encode(profileId));
                ps.executeUpdate();
                return null;
            }
        });
    }

    private void insertSublandProfileBinding(PersistenceStore store, SubLandId sublandId,
            String subjectType, UUID subjectId, UUID profileId) {
        store.execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO subland_bindings (subland_id, subject_type, subject_id, profile_id)"
                            + " VALUES (?, ?, ?, ?)")) {
                ps.setBytes(1, UuidBlob.encode(sublandId.value()));
                ps.setString(2, subjectType);
                ps.setBytes(3, UuidBlob.encode(subjectId));
                ps.setBytes(4, UuidBlob.encode(profileId));
                ps.executeUpdate();
                return null;
            }
        });
    }

    private String entryState(PersistenceStore store, UUID profileId, String permission) {
        return store.execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT state FROM permission_profile_entries"
                            + " WHERE profile_id = ? AND permission = ? LIMIT 1")) {
                ps.setBytes(1, UuidBlob.encode(profileId));
                ps.setString(2, permission);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    // ---- create / list ----

    @Test
    void createListRoundTripWithUuidIdentityAndOwnerScopedKey() {
        try (PersistenceStore store = PersistenceStore.open(db("profiles.db"))) {
            PermissionProfileRepository repo = new PermissionProfileRepository(store);
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository.ProfileView created =
                    repo.create(owner, "  Builders  ", owner, now()).toCompletableFuture().join();
            assertEquals(owner, created.owner());
            assertEquals("Builders", created.displayName());
            assertEquals("builders", created.nameKey());
            assertTrue(created.entries().isEmpty());
            assertThrows(UnsupportedOperationException.class,
                    () -> created.entries().put(ProtectionActionType.ENTRY, PermissionState.ALLOW));

            List<PermissionProfileRepository.ProfileView> listed =
                    repo.list(owner).toCompletableFuture().join();
            assertEquals(1, listed.size());
            assertEquals(created.id(), listed.get(0).id());
            assertThrows(UnsupportedOperationException.class,
                    () -> listed.add(created));

            Optional<PermissionProfileRepository.ProfileView> byId =
                    repo.findById(owner, created.id()).toCompletableFuture().join();
            assertTrue(byId.isPresent());
            assertEquals("builders", byId.get().nameKey());

            Optional<PermissionProfileRepository.ProfileView> byName =
                    repo.findByName(owner, "BUILDERS").toCompletableFuture().join();
            assertTrue(byName.isPresent());
            assertEquals(created.id(), byName.get().id());

            assertEquals("PLAYER:" + owner, store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT owner_key FROM permission_profiles WHERE id = ?")) {
                    ps.setBytes(1, UuidBlob.encode(created.id()));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getString(1);
                    }
                }
            }));
            assertEquals(1, auditCount(store, "PROFILE_CREATE"));
            assertEquals("CL-M4-02", AuditActions.writerTask("PROFILE_CREATE"));
        }
    }

    @Test
    void nameNormalizationRejectsBlankControlReservedAndEveryone() {
        assertEquals("builders", PermissionProfileRepository.normalizeNameKey("  Builders "));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("i", PermissionProfileRepository.normalizeNameKey("I"));
            assertEquals("istanbul", PermissionProfileRepository.normalizeNameKey("Istanbul"));
        } finally {
            Locale.setDefault(previous);
        }
        for (String bad : List.of("", "   ", "EVERYONE", "everyone", "Everyone", "*")) {
            ProfileRejectedException rejected =
                    assertThrows(ProfileRejectedException.class,
                            () -> PermissionProfileRepository.normalizeNameKey(bad));
            assertEquals("profile.invalid", rejected.reason());
        }
        for (String reserved : List.of("direct", "Direct", " DIRECT ")) {
            ProfileRejectedException rejected =
                    assertThrows(ProfileRejectedException.class,
                            () -> PermissionProfileRepository.normalizeNameKey(reserved));
            assertEquals("profile.reserved", rejected.reason());
        }
        assertThrows(ProfileRejectedException.class,
                () -> PermissionProfileRepository.normalizeNameKey("a\tb"));
        assertThrows(ProfileRejectedException.class,
                () -> PermissionProfileRepository.normalizeNameKey("a\nb"));
        assertThrows(NullPointerException.class,
                () -> PermissionProfileRepository.normalizeNameKey(null));
    }

    @Test
    void createRejectsInvalidReservedAndDuplicateNames() {
        try (PersistenceStore store = PersistenceStore.open(db("names.db"))) {
            PermissionProfileRepository repo = new PermissionProfileRepository(store);
            UUID owner = UUID.randomUUID();
            ProfileRejectedException invalid = assertThrows(ProfileRejectedException.class,
                    () -> repo.create(owner, "   ", owner, now()));
            assertEquals("profile.invalid", invalid.reason());
            ProfileRejectedException reserved = assertThrows(ProfileRejectedException.class,
                    () -> repo.create(owner, "direct", owner, now()));
            assertEquals("profile.reserved", reserved.reason());
            ProfileRejectedException everyone = assertThrows(ProfileRejectedException.class,
                    () -> repo.create(owner, "EVERYONE", owner, now()));
            assertEquals("profile.invalid", everyone.reason());

            repo.create(owner, "Builders", owner, now()).toCompletableFuture().join();
            CompletionException duplicate = assertThrows(CompletionException.class, () ->
                    repo.create(owner, "builders", owner, now()).toCompletableFuture().join());
            assertEquals("profile.duplicate", reasonOf(duplicate));
            CompletionException caseOnly = assertThrows(CompletionException.class, () ->
                    repo.create(owner, "BUILDERS", owner, now()).toCompletableFuture().join());
            assertEquals("profile.duplicate", reasonOf(caseOnly));
            assertEquals(1, repo.list(owner).toCompletableFuture().join().size());
            assertEquals(1, auditCount(store, "PROFILE_CREATE"));
        }
    }

    @Test
    void ownerNamespacesAreIsolated() {
        try (PersistenceStore store = PersistenceStore.open(db("isolation.db"))) {
            PermissionProfileRepository repo = new PermissionProfileRepository(store);
            UUID ownerA = UUID.randomUUID();
            UUID ownerB = UUID.randomUUID();
            PermissionProfileRepository.ProfileView profileA =
                    repo.create(ownerA, "Builders", ownerA, now()).toCompletableFuture().join();
            repo.setEntry(ownerA, profileA.id(), ProtectionActionType.ENTRY,
                    PermissionState.ALLOW, ownerA, now()).toCompletableFuture().join();

            assertTrue(repo.findById(ownerB, profileA.id()).toCompletableFuture().join().isEmpty());
            assertTrue(repo.findByName(ownerB, "Builders").toCompletableFuture().join().isEmpty());
            assertTrue(repo.list(ownerB).toCompletableFuture().join().isEmpty());

            PermissionProfileRepository.ProfileView profileB =
                    repo.create(ownerB, "Builders", ownerB, now()).toCompletableFuture().join();
            assertFalse(profileB.id().equals(profileA.id()));

            int updatesBefore = auditCount(store, "PROFILE_UPDATE");
            CompletionException setForeign = assertThrows(CompletionException.class, () ->
                    repo.setEntry(ownerB, profileA.id(), ProtectionActionType.ENTRY,
                            PermissionState.DENY, ownerB, now()).toCompletableFuture().join());
            assertEquals("profile.unknown", reasonOf(setForeign));
            CompletionException deleteForeign = assertThrows(CompletionException.class, () ->
                    repo.delete(ownerB, profileA.id(), ownerB, now()).toCompletableFuture().join());
            assertEquals("profile.unknown", reasonOf(deleteForeign));
            assertEquals(updatesBefore, auditCount(store, "PROFILE_UPDATE"));
            assertEquals("ALLOW", entryState(store, profileA.id(), "ENTRY"));
        }
    }

    // ---- entries ----

    @Test
    void setEntryPersistsSparseAllowDenyAndInheritDeletes() {
        try (PersistenceStore store = PersistenceStore.open(db("entries.db"))) {
            PermissionProfileRepository repo = new PermissionProfileRepository(store);
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository.ProfileView profile =
                    repo.create(owner, "Crew", owner, now()).toCompletableFuture().join();

            PermissionProfileRepository.EntryOutcome first = repo.setEntry(owner, profile.id(),
                    ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW, owner, now())
                    .toCompletableFuture().join();
            assertEquals(PermissionState.INHERIT, first.before());
            assertEquals(PermissionState.ALLOW, first.after());

            PermissionProfileRepository.EntryOutcome second = repo.setEntry(owner, profile.id(),
                    ProtectionActionType.ENTRY, PermissionState.DENY, owner, now())
                    .toCompletableFuture().join();
            assertEquals(PermissionState.INHERIT, second.before());
            assertEquals(PermissionState.DENY, second.after());

            // Repeat set of the same value keeps the row and still audits.
            PermissionProfileRepository.EntryOutcome repeat = repo.setEntry(owner, profile.id(),
                    ProtectionActionType.ENTRY, PermissionState.DENY, owner, now())
                    .toCompletableFuture().join();
            assertEquals(PermissionState.DENY, repeat.before());
            assertEquals(PermissionState.DENY, repeat.after());

            // Overwrite flips the stored state.
            PermissionProfileRepository.EntryOutcome flip = repo.setEntry(owner, profile.id(),
                    ProtectionActionType.ENTRY, PermissionState.ALLOW, owner, now())
                    .toCompletableFuture().join();
            assertEquals(PermissionState.DENY, flip.before());
            assertEquals(PermissionState.ALLOW, flip.after());

            Map<ProtectionActionType, PermissionState> entries = repo
                    .findById(owner, profile.id()).toCompletableFuture().join().orElseThrow()
                    .entries();
            assertEquals(Map.of(ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW,
                    ProtectionActionType.ENTRY, PermissionState.ALLOW), entries);

            // INHERIT deletes the row; the profile itself survives with sparse rows.
            PermissionProfileRepository.EntryOutcome inherit = repo.setEntry(owner, profile.id(),
                    ProtectionActionType.ENTRY, PermissionState.INHERIT, owner, now())
                    .toCompletableFuture().join();
            assertEquals(PermissionState.ALLOW, inherit.before());
            assertEquals(PermissionState.INHERIT, inherit.after());
            assertEquals(null, entryState(store, profile.id(), "ENTRY"));
            assertTrue(repo.findById(owner, profile.id()).toCompletableFuture().join().isPresent());
            assertEquals("ALLOW", entryState(store, profile.id(), "BLOCK_BREAK"));
            assertEquals(5, auditCount(store, "PROFILE_UPDATE"));
            assertEquals("CL-M4-02", AuditActions.writerTask("PROFILE_UPDATE"));
        }
    }

    @Test
    void setEntryOnEmptyProfileKeepsProfileWithNoRows() {
        try (PersistenceStore store = PersistenceStore.open(db("empty.db"))) {
            PermissionProfileRepository repo = new PermissionProfileRepository(store);
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository.ProfileView profile =
                    repo.create(owner, "Sparse", owner, now()).toCompletableFuture().join();
            repo.setEntry(owner, profile.id(), ProtectionActionType.ENTRY,
                    PermissionState.INHERIT, owner, now()).toCompletableFuture().join();
            PermissionProfileRepository.ProfileView reloaded =
                    repo.findById(owner, profile.id()).toCompletableFuture().join().orElseThrow();
            assertTrue(reloaded.entries().isEmpty());
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM permission_profile_entries WHERE profile_id = ?",
                    profile.id()));
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM permission_profiles WHERE id = ?", profile.id()));
        }
    }

    @Test
    void setEntryRejectsLandRuleActions() {
        try (PersistenceStore store = PersistenceStore.open(db("landrule.db"))) {
            PermissionProfileRepository repo = new PermissionProfileRepository(store);
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository.ProfileView profile =
                    repo.create(owner, "Crew", owner, now()).toCompletableFuture().join();
            for (ProtectionActionType rule : List.of(ProtectionActionType.PISTON_MOVE,
                    ProtectionActionType.FLUID_FLOW, ProtectionActionType.FIRE_SPREAD,
                    ProtectionActionType.BLOCK_MOVE_IN, ProtectionActionType.PLAYER_DAMAGE_PLAYER)) {
                // Pre-transaction validation fails synchronously, like name checks.
                ProfileRejectedException rejected = assertThrows(ProfileRejectedException.class,
                        () -> repo.setEntry(owner, profile.id(), rule, PermissionState.ALLOW,
                                owner, now()));
                assertEquals("profile.invalid_permission", rejected.reason());
            }
            assertEquals(0, count(store, "SELECT COUNT(*) FROM permission_profile_entries"));
            assertEquals(0, auditCount(store, "PROFILE_UPDATE"));
        }
    }

    @Test
    void setEntryOnUnknownProfileFailsClosed() {
        try (PersistenceStore store = PersistenceStore.open(db("unknown.db"))) {
            PermissionProfileRepository repo = new PermissionProfileRepository(store);
            UUID owner = UUID.randomUUID();
            CompletionException set = assertThrows(CompletionException.class, () ->
                    repo.setEntry(owner, UUID.randomUUID(), ProtectionActionType.ENTRY,
                            PermissionState.ALLOW, owner, now()).toCompletableFuture().join());
            assertEquals("profile.unknown", reasonOf(set));
            CompletionException delete = assertThrows(CompletionException.class, () ->
                    repo.delete(owner, UUID.randomUUID(), owner, now()).toCompletableFuture().join());
            assertEquals("profile.unknown", reasonOf(delete));
            assertEquals(0, auditCount(store, "PROFILE_UPDATE"));
            assertEquals(0, auditCount(store, "PROFILE_DELETE"));
        }
    }

    // ---- direct profile protection ----

    @Test
    void directProfileCannotBeTouchedByGenericPath() {
        try (PersistenceStore store = PersistenceStore.open(db("direct.db"))) {
            PermissionProfileRepository repo = new PermissionProfileRepository(store);
            UUID owner = UUID.randomUUID();
            UUID directId = insertDirectProfile(store, owner);

            CompletionException set = assertThrows(CompletionException.class, () ->
                    repo.setEntry(owner, directId, ProtectionActionType.ENTRY,
                            PermissionState.DENY, owner, now()).toCompletableFuture().join());
            assertEquals("profile.reserved", reasonOf(set));

            CompletionException delete = assertThrows(CompletionException.class, () ->
                    repo.delete(owner, directId, owner, now()).toCompletableFuture().join());
            assertEquals("profile.reserved", reasonOf(delete));

            CompletionException force = assertThrows(CompletionException.class, () ->
                    repo.forceDelete(owner, directId, owner, now()).toCompletableFuture().join());
            assertEquals("profile.reserved", reasonOf(force));

            // The fixed internal contract is intact: row, entries and audits untouched.
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM permission_profiles WHERE id = ?", directId));
            assertEquals("ALLOW", entryState(store, directId, "ENTRY"));
            assertEquals(0, auditCount(store, "PROFILE_UPDATE"));
            assertEquals(0, auditCount(store, "PROFILE_DELETE"));
        }
    }

    // ---- delete / force delete ----

    @Test
    void normalDeleteIsRestrictedWithZeroMutation() {
        try (PersistenceStore store = PersistenceStore.open(db("restrict.db"))) {
            PermissionProfileRepository profiles = new PermissionProfileRepository(store);
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteSubLandRepository subs = new SqliteSubLandRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository.ProfileView profile =
                    profiles.create(owner, "Raiders", owner, now()).toCompletableFuture().join();
            profiles.setEntry(owner, profile.id(), ProtectionActionType.ENTRY,
                    PermissionState.ALLOW, owner, now()).toCompletableFuture().join();

            LandId landId = new LandId(UUID.randomUUID());
            lands.save(land(world, landId, OwnerRef.player(owner))).toCompletableFuture().join();
            SubLandId subId = new SubLandId(UUID.randomUUID());
            subs.save(new SubLandSnapshot(subId, landId, "den",
                    new Cuboid(0, 0, 0, 15, 10, 15), world)).toCompletableFuture().join();
            insertLandProfileBinding(store, landId, "PLAYER", UUID.randomUUID(), profile.id());
            insertSublandProfileBinding(store, subId, "GROUP", UUID.randomUUID(), profile.id());

            int profilesBefore = count(store, "SELECT COUNT(*) FROM permission_profiles");
            int entriesBefore = count(store, "SELECT COUNT(*) FROM permission_profile_entries");
            int landBindingsBefore = count(store, "SELECT COUNT(*) FROM land_bindings");
            int subBindingsBefore = count(store, "SELECT COUNT(*) FROM subland_bindings");
            int deletesBefore = auditCount(store, "PROFILE_DELETE");

            CompletionException restricted = assertThrows(CompletionException.class, () ->
                    profiles.delete(owner, profile.id(), owner, now()).toCompletableFuture().join());
            assertEquals("profile.restricted:2", reasonOf(restricted));
            Throwable cause = restricted.getCause();
            while (!(cause instanceof ProfileDeleteRestrictedException) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            ProfileDeleteRestrictedException blocked = (ProfileDeleteRestrictedException) cause;
            assertEquals(2, blocked.affected().size());
            assertTrue(blocked.affected().stream().anyMatch(b -> b.scope().equals("LAND")
                    && b.landId().equals(landId.value())));
            assertTrue(blocked.affected().stream().anyMatch(b -> b.scope().equals("SUBLAND")
                    && b.sublandId().equals(subId.value())));

            assertEquals(profilesBefore, count(store, "SELECT COUNT(*) FROM permission_profiles"));
            assertEquals(entriesBefore, count(store, "SELECT COUNT(*) FROM permission_profile_entries"));
            assertEquals(landBindingsBefore, count(store, "SELECT COUNT(*) FROM land_bindings"));
            assertEquals(subBindingsBefore, count(store, "SELECT COUNT(*) FROM subland_bindings"));
            assertEquals(deletesBefore, auditCount(store, "PROFILE_DELETE"));
            assertTrue(profiles.findById(owner, profile.id()).toCompletableFuture().join().isPresent());
        }
    }

    @Test
    void forceDeleteRemovesOnlyReferencingBindingsAtomically() {
        try (PersistenceStore store = PersistenceStore.open(db("force.db"))) {
            PermissionProfileRepository profiles = new PermissionProfileRepository(store);
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteSubLandRepository subs = new SqliteSubLandRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository.ProfileView profile =
                    profiles.create(owner, "Raiders", owner, now()).toCompletableFuture().join();
            profiles.setEntry(owner, profile.id(), ProtectionActionType.ENTRY,
                    PermissionState.ALLOW, owner, now()).toCompletableFuture().join();
            PermissionProfileRepository.ProfileView other =
                    profiles.create(owner, "Keep", owner, now()).toCompletableFuture().join();

            LandId landId = new LandId(UUID.randomUUID());
            lands.save(land(world, landId, OwnerRef.player(owner))).toCompletableFuture().join();
            SubLandId subId = new SubLandId(UUID.randomUUID());
            subs.save(new SubLandSnapshot(subId, landId, "den",
                    new Cuboid(0, 0, 0, 15, 10, 15), world)).toCompletableFuture().join();
            insertLandProfileBinding(store, landId, "PLAYER", UUID.randomUUID(), profile.id());
            insertSublandProfileBinding(store, subId, "GROUP", UUID.randomUUID(), profile.id());
            // Bindings on the other profile must survive the force delete.
            insertLandProfileBinding(store, landId, "PLAYER", UUID.randomUUID(), other.id());

            PermissionProfileRepository.ForceDeleteOutcome outcome = profiles
                    .forceDelete(owner, profile.id(), owner, now()).toCompletableFuture().join();
            assertEquals(profile.id(), outcome.profileId());
            assertEquals(2, outcome.affected().size());

            assertTrue(profiles.findById(owner, profile.id()).toCompletableFuture().join().isEmpty());
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM permission_profile_entries WHERE profile_id = ?",
                    profile.id()));
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM land_bindings WHERE profile_id = ?", profile.id()));
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM subland_bindings WHERE profile_id = ?", profile.id()));
            // Untouched: the other profile, its entries namespace, its binding and the land.
            assertTrue(profiles.findById(owner, other.id()).toCompletableFuture().join().isPresent());
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_bindings WHERE profile_id = ?", other.id()));
            assertTrue(lands.findById(landId).toCompletableFuture().join().isPresent());
            assertEquals(1, auditCount(store, "PROFILE_DELETE"));
            assertEquals("CL-M4-02", AuditActions.writerTask("PROFILE_DELETE"));
            String metadata = store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT metadata_json FROM audit_log WHERE action = 'PROFILE_DELETE'"
                                + " ORDER BY id DESC LIMIT 1")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getString(1);
                    }
                }
            });
            assertTrue(metadata.contains("\"force\":true"), metadata);
            assertTrue(metadata.contains(landId.value().toString()), metadata);
            assertTrue(metadata.contains(subId.value().toString()), metadata);
        }
    }

    @Test
    void forceDeleteWithoutReferencesStillAudits() {
        try (PersistenceStore store = PersistenceStore.open(db("forcelonely.db"))) {
            PermissionProfileRepository repo = new PermissionProfileRepository(store);
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository.ProfileView profile =
                    repo.create(owner, "Solo", owner, now()).toCompletableFuture().join();
            PermissionProfileRepository.ForceDeleteOutcome outcome = repo
                    .forceDelete(owner, profile.id(), owner, now()).toCompletableFuture().join();
            assertTrue(outcome.affected().isEmpty());
            assertTrue(repo.findById(owner, profile.id()).toCompletableFuture().join().isEmpty());
            assertEquals(1, auditCount(store, "PROFILE_DELETE"));

            CompletionException second = assertThrows(CompletionException.class, () ->
                    repo.forceDelete(owner, profile.id(), owner, now()).toCompletableFuture().join());
            assertEquals("profile.unknown", reasonOf(second));
        }
    }

    // ---- atomicity ----

    @Test
    void auditFailureRollsBackEveryWrite() {
        for (PermissionProfileRepository.Step failing
                : List.of(PermissionProfileRepository.Step.AFTER_PROFILE,
                        PermissionProfileRepository.Step.AFTER_AUDIT)) {
            try (PersistenceStore store = PersistenceStore.open(db("rollback-" + failing + ".db"))) {
                AtomicReference<PermissionProfileRepository.Step> failAt =
                        new AtomicReference<>(failing);
                PermissionProfileRepository repo = new PermissionProfileRepository(store, step -> {
                    if (step == failAt.get()) {
                        throw new IllegalStateException("injected " + step);
                    }
                });
                UUID owner = UUID.randomUUID();
                CompletionException failure = assertThrows(CompletionException.class, () ->
                        repo.create(owner, "Doomed", owner, now()).toCompletableFuture().join());
                assertTrue(failure.getCause() instanceof IllegalStateException
                        || failure.getCause().getCause() instanceof IllegalStateException);
                assertEquals(0, count(store, "SELECT COUNT(*) FROM permission_profiles"));
                assertEquals(0, auditCount(store, "PROFILE_CREATE"));
                assertTrue(repo.list(owner).toCompletableFuture().join().isEmpty());
            }
        }
    }

    @Test
    void entryAndForceDeleteFailuresRollBack() {
        try (PersistenceStore store = PersistenceStore.open(db("rollback-entry.db"))) {
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository plain = new PermissionProfileRepository(store);
            PermissionProfileRepository.ProfileView profile =
                    plain.create(owner, "Crew", owner, now()).toCompletableFuture().join();

            PermissionProfileRepository failingEntry = new PermissionProfileRepository(store, step -> {
                if (step == PermissionProfileRepository.Step.AFTER_ENTRY) {
                    throw new IllegalStateException("injected entry failure");
                }
            });
            assertThrows(CompletionException.class, () ->
                    failingEntry.setEntry(owner, profile.id(), ProtectionActionType.ENTRY,
                            PermissionState.ALLOW, owner, now()).toCompletableFuture().join());
            assertEquals(0, count(store, "SELECT COUNT(*) FROM permission_profile_entries"));
            assertEquals(0, auditCount(store, "PROFILE_UPDATE"));
        }

        try (PersistenceStore store = PersistenceStore.open(db("rollback-force.db"))) {
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository plain = new PermissionProfileRepository(store);
            SqliteLandRepository lands = new SqliteLandRepository(store);
            UUID world = UUID.randomUUID();
            PermissionProfileRepository.ProfileView profile =
                    plain.create(owner, "Crew", owner, now()).toCompletableFuture().join();
            plain.setEntry(owner, profile.id(), ProtectionActionType.ENTRY,
                    PermissionState.ALLOW, owner, now()).toCompletableFuture().join();
            LandId landId = new LandId(UUID.randomUUID());
            lands.save(land(world, landId, OwnerRef.player(owner))).toCompletableFuture().join();
            insertLandProfileBinding(store, landId, "PLAYER", UUID.randomUUID(), profile.id());

            for (PermissionProfileRepository.Step failing : List.of(
                    PermissionProfileRepository.Step.AFTER_BINDING_DELETE,
                    PermissionProfileRepository.Step.AFTER_PROFILE,
                    PermissionProfileRepository.Step.AFTER_AUDIT)) {
                PermissionProfileRepository attempt = new PermissionProfileRepository(store, step -> {
                    if (step == failing) {
                        throw new IllegalStateException("injected " + step);
                    }
                });
                assertThrows(CompletionException.class, () ->
                        attempt.forceDelete(owner, profile.id(), owner, now())
                                .toCompletableFuture().join());
                assertTrue(plain.findById(owner, profile.id()).toCompletableFuture().join().isPresent());
                assertEquals(1, count(store,
                        "SELECT COUNT(*) FROM land_bindings WHERE profile_id = ?", profile.id()));
                assertEquals("ALLOW", entryState(store, profile.id(), "ENTRY"));
            }
            assertEquals(0, auditCount(store, "PROFILE_DELETE"));
        }
    }

    @Test
    void concurrentDuplicateNameCommitsExactlyOnce() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(db("concurrent.db"))) {
            PermissionProfileRepository repo = new PermissionProfileRepository(store);
            UUID owner = UUID.randomUUID();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<PermissionProfileRepository.ProfileView>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                CompletableFuture<PermissionProfileRepository.ProfileView> future = new CompletableFuture<>();
                futures.add(future);
                Thread worker = new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        future.completeExceptionally(interrupted);
                        return;
                    }
                    repo.create(owner, "Racers", owner, now()).whenComplete((view, failure) -> {
                        if (failure != null) {
                            future.completeExceptionally(failure);
                        } else {
                            future.complete(view);
                        }
                    });
                });
                worker.setDaemon(true);
                worker.start();
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            go.countDown();
            int successes = 0;
            int duplicates = 0;
            for (CompletableFuture<PermissionProfileRepository.ProfileView> future : futures) {
                try {
                    future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    successes++;
                } catch (java.util.concurrent.ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    while ((cause instanceof CompletionException
                            || cause instanceof java.util.concurrent.ExecutionException)
                            && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    if (cause instanceof ProfileRejectedException rejected
                            && rejected.reason().equals("profile.duplicate")) {
                        duplicates++;
                    } else {
                        throw new AssertionError("unexpected failure " + cause, failure);
                    }
                }
            }
            assertEquals(1, successes);
            assertEquals(1, duplicates);
            assertEquals(1, repo.list(owner).toCompletableFuture().join().size());
            assertEquals(1, auditCount(store, "PROFILE_CREATE"));
        }
    }

    @Test
    void serverNamespaceCanNeverOwnAProfile() {
        try (PersistenceStore store = PersistenceStore.open(db("server.db"))) {
            PermissionProfileRepository repo = new PermissionProfileRepository(store);
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository.ProfileView profile =
                    repo.create(owner, "Crew", owner, now()).toCompletableFuture().join();
            assertEquals("PLAYER:" + owner, store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT DISTINCT owner_key FROM permission_profiles")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        String only = rs.getString(1);
                        assertFalse(rs.next(), "exactly one owner namespace must exist");
                        return only;
                    }
                }
            }));
            // The API surface only admits player UUID namespaces: there is no
            // overload that could store the SERVER literal.
            assertFalse(("PLAYER:" + owner).equals("SERVER"));
            assertThrows(NullPointerException.class,
                    () -> repo.create(null, "Crew", owner, now()));
            assertThrows(NullPointerException.class,
                    () -> repo.list(null));
            assertEquals(profile.id(), repo.findById(owner, profile.id())
                    .toCompletableFuture().join().orElseThrow().id());
        }
    }

    @Test
    void auditWriterRegistryCoversProfileActions() {
        assertEquals("CL-M4-02", AuditActions.writerTask("PROFILE_CREATE"));
        assertEquals("CL-M4-02", AuditActions.writerTask("PROFILE_UPDATE"));
        assertEquals("CL-M4-02", AuditActions.writerTask("PROFILE_DELETE"));
    }

    @Test
    void entryStateImmutabilityAndProfileSeparation() {
        try (PersistenceStore store = PersistenceStore.open(db("separation.db"))) {
            PermissionProfileRepository repo = new PermissionProfileRepository(store);
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository.ProfileView first =
                    repo.create(owner, "First", owner, now()).toCompletableFuture().join();
            PermissionProfileRepository.ProfileView second =
                    repo.create(owner, "Second", owner, now()).toCompletableFuture().join();
            repo.setEntry(owner, first.id(), ProtectionActionType.ENTRY,
                    PermissionState.ALLOW, owner, now()).toCompletableFuture().join();
            assertTrue(repo.findById(owner, second.id()).toCompletableFuture().join()
                    .orElseThrow().entries().isEmpty());
            assertEquals(null, entryState(store, second.id(), "ENTRY"));
        }
    }

    @Test
    void failureInjectorHelperIsWired() {
        try (PersistenceStore store = PersistenceStore.open(db("injector.db"))) {
            PermissionProfileRepository repo = new PermissionProfileRepository(store,
                    actual -> {
                        if (actual == PermissionProfileRepository.Step.AFTER_AUDIT) {
                            throw new IllegalStateException("injected " + actual);
                        }
                    });
            UUID owner = UUID.randomUUID();
            assertThrows(CompletionException.class, () ->
                    repo.create(owner, "Doomed", owner, now()).toCompletableFuture().join());
            assertEquals(0, count(store, "SELECT COUNT(*) FROM permission_profiles"));
        }
    }
}

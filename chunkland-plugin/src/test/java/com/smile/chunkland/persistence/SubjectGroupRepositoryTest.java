package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.land.Cuboid;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Global Group persistence: owner-scoped CRUD with UUID identity, idempotent
 * membership, audits on every write, survival across land deletes, RESTRICT
 * on referenced groups with zero mutation, and atomic force deletes that
 * remove only the {@code GROUP} references.
 */
class SubjectGroupRepositoryTest {

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
        if (cause instanceof GroupDeleteRestrictedException restricted) {
            return restricted.reason() + ":" + restricted.affected().size();
        }
        if (cause instanceof GroupRejectedException rejected) {
            return rejected.reason();
        }
        throw new AssertionError("expected GroupRejectedException, got " + cause, failure);
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
        return new LandSnapshot(id, "Home", "home", owner, worldId, Set.of(), List.of(),
                0, 0, Instant.now(), Instant.now());
    }

    private UUID insertProfile(PersistenceStore store, String ownerKey) {
        UUID profileId = UUID.randomUUID();
        store.execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO permission_profiles (id, owner_key, name_key, display_name, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)")) {
                ps.setBytes(1, UuidBlob.encode(profileId));
                ps.setString(2, ownerKey);
                ps.setString(3, "crew");
                ps.setString(4, "Crew");
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
                return null;
            }
        });
        return profileId;
    }

    private void insertLandGroupBinding(PersistenceStore store, LandId landId, UUID groupId,
            UUID profileId) {
        store.execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO land_bindings (land_id, subject_type, subject_id, profile_id)"
                            + " VALUES (?, 'GROUP', ?, ?)")) {
                ps.setBytes(1, UuidBlob.encode(landId.value()));
                ps.setBytes(2, UuidBlob.encode(groupId));
                ps.setBytes(3, UuidBlob.encode(profileId));
                ps.executeUpdate();
                return null;
            }
        });
    }

    private void insertSublandGroupBinding(PersistenceStore store, SubLandId sublandId,
            UUID groupId, UUID profileId) {
        store.execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO subland_bindings (subland_id, subject_type, subject_id, profile_id)"
                            + " VALUES (?, 'GROUP', ?, ?)")) {
                ps.setBytes(1, UuidBlob.encode(sublandId.value()));
                ps.setBytes(2, UuidBlob.encode(groupId));
                ps.setBytes(3, UuidBlob.encode(profileId));
                ps.executeUpdate();
                return null;
            }
        });
    }

    // ---- create / list ----

    @Test
    void createListRoundTripWithUuidIdentityAndOwnerScopedKey() {
        try (PersistenceStore store = PersistenceStore.open(db("groups.db"))) {
            SubjectGroupRepository repo = new SubjectGroupRepository(store);
            UUID owner = UUID.randomUUID();
            SubjectGroupRepository.GroupView created =
                    repo.create(owner, "  Friends  ", owner, now()).toCompletableFuture().join();
            assertEquals(owner, created.owner());
            assertEquals("Friends", created.displayName());
            assertEquals("friends", created.nameKey());
            assertTrue(created.members().isEmpty());
            assertThrows(UnsupportedOperationException.class,
                    () -> created.members().add(UUID.randomUUID()));

            List<SubjectGroupRepository.GroupView> listed =
                    repo.list(owner).toCompletableFuture().join();
            assertEquals(1, listed.size());
            assertEquals(created.id(), listed.get(0).id());
            assertThrows(UnsupportedOperationException.class,
                    () -> listed.add(created));

            Optional<SubjectGroupRepository.GroupView> byId =
                    repo.findById(owner, created.id()).toCompletableFuture().join();
            assertTrue(byId.isPresent());
            assertEquals("friends", byId.get().nameKey());

            Optional<SubjectGroupRepository.GroupView> byName =
                    repo.findByName(owner, "FRIENDS").toCompletableFuture().join();
            assertTrue(byName.isPresent());
            assertEquals(created.id(), byName.get().id());

            assertEquals("PLAYER:" + owner, store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT owner_key FROM subject_groups WHERE id = ?")) {
                    ps.setBytes(1, UuidBlob.encode(created.id()));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getString(1);
                    }
                }
            }));
            assertEquals(1, auditCount(store, "GROUP_CREATE"));
            assertEquals("CL-M4-01", AuditActions.writerTask("GROUP_CREATE"));
        }
    }

    @Test
    void nameNormalizationRejectsBlankControlAndReserved() {
        assertEquals("friends", SubjectGroupRepository.normalizeNameKey("  Friends "));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("i", SubjectGroupRepository.normalizeNameKey("I"));
            assertEquals("istanbul", SubjectGroupRepository.normalizeNameKey("Istanbul"));
        } finally {
            Locale.setDefault(previous);
        }
        for (String bad : List.of("", "   ", "EVERYONE", "everyone", "Everyone", "*")) {
            GroupRejectedException rejected =
                    assertThrows(GroupRejectedException.class,
                            () -> SubjectGroupRepository.normalizeNameKey(bad));
            assertEquals("group.invalid", rejected.reason());
        }
        assertThrows(GroupRejectedException.class,
                () -> SubjectGroupRepository.normalizeNameKey("a\tb"));
        assertThrows(GroupRejectedException.class,
                () -> SubjectGroupRepository.normalizeNameKey("a\nb"));
        assertThrows(NullPointerException.class,
                () -> SubjectGroupRepository.normalizeNameKey(null));
    }

    @Test
    void createRejectsInvalidAndDuplicateNames() {
        try (PersistenceStore store = PersistenceStore.open(db("names.db"))) {
            SubjectGroupRepository repo = new SubjectGroupRepository(store);
            UUID owner = UUID.randomUUID();
            GroupRejectedException invalid = assertThrows(GroupRejectedException.class,
                    () -> repo.create(owner, "   ", owner, now()));
            assertEquals("group.invalid", invalid.reason());
            GroupRejectedException reserved = assertThrows(GroupRejectedException.class,
                    () -> repo.create(owner, "EVERYONE", owner, now()));
            assertEquals("group.invalid", reserved.reason());

            repo.create(owner, "Friends", owner, now()).toCompletableFuture().join();
            CompletionException duplicate = assertThrows(CompletionException.class, () ->
                    repo.create(owner, "friends", owner, now()).toCompletableFuture().join());
            assertEquals("group.duplicate", reasonOf(duplicate));
            CompletionException caseOnly = assertThrows(CompletionException.class, () ->
                    repo.create(owner, "FRIENDS", owner, now()).toCompletableFuture().join());
            assertEquals("group.duplicate", reasonOf(caseOnly));
            assertEquals(1, repo.list(owner).toCompletableFuture().join().size());
            assertEquals(1, auditCount(store, "GROUP_CREATE"));
        }
    }

    @Test
    void ownerNamespacesAreIsolated() {
        try (PersistenceStore store = PersistenceStore.open(db("isolation.db"))) {
            SubjectGroupRepository repo = new SubjectGroupRepository(store);
            UUID ownerA = UUID.randomUUID();
            UUID ownerB = UUID.randomUUID();
            SubjectGroupRepository.GroupView groupA =
                    repo.create(ownerA, "Friends", ownerA, now()).toCompletableFuture().join();
            UUID member = UUID.randomUUID();
            repo.addMember(ownerA, groupA.id(), member, ownerA, now())
                    .toCompletableFuture().join();

            // Owner B sees nothing of A's group: same name and same id read as absent.
            assertTrue(repo.findById(ownerB, groupA.id()).toCompletableFuture().join().isEmpty());
            assertTrue(repo.findByName(ownerB, "Friends").toCompletableFuture().join().isEmpty());
            assertTrue(repo.list(ownerB).toCompletableFuture().join().isEmpty());

            // Owner B may reuse the same name in their own namespace.
            SubjectGroupRepository.GroupView groupB =
                    repo.create(ownerB, "Friends", ownerB, now()).toCompletableFuture().join();
            assertFalse(groupB.id().equals(groupA.id()));

            // Owner B cannot mutate A's group, and the failure writes nothing.
            int auditsBefore = auditCount(store, "GROUP_MEMBER_CHANGE");
            CompletionException addForeign = assertThrows(CompletionException.class, () ->
                    repo.addMember(ownerB, groupA.id(), UUID.randomUUID(), ownerB, now())
                            .toCompletableFuture().join());
            assertEquals("group.unknown", reasonOf(addForeign));
            CompletionException removeForeign = assertThrows(CompletionException.class, () ->
                    repo.removeMember(ownerB, groupA.id(), member, ownerB, now())
                            .toCompletableFuture().join());
            assertEquals("group.unknown", reasonOf(removeForeign));
            CompletionException deleteForeign = assertThrows(CompletionException.class, () ->
                    repo.delete(ownerB, groupA.id(), ownerB, now()).toCompletableFuture().join());
            assertEquals("group.unknown", reasonOf(deleteForeign));
            assertEquals(auditsBefore, auditCount(store, "GROUP_MEMBER_CHANGE"));
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM subject_group_members WHERE group_id = ?", groupA.id()));
        }
    }

    // ---- membership ----

    @Test
    void membershipAddRemoveIsIdempotentAndAudited() {
        try (PersistenceStore store = PersistenceStore.open(db("members.db"))) {
            SubjectGroupRepository repo = new SubjectGroupRepository(store);
            UUID owner = UUID.randomUUID();
            SubjectGroupRepository.GroupView group =
                    repo.create(owner, "Crew", owner, now()).toCompletableFuture().join();
            UUID member = UUID.randomUUID();

            SubjectGroupRepository.MembershipOutcome added =
                    repo.addMember(owner, group.id(), member, owner, now())
                            .toCompletableFuture().join();
            assertFalse(added.presentBefore());
            assertTrue(added.presentAfter());

            SubjectGroupRepository.MembershipOutcome resent =
                    repo.addMember(owner, group.id(), member, owner, now())
                            .toCompletableFuture().join();
            assertTrue(resent.presentBefore());
            assertTrue(resent.presentAfter());

            assertTrue(repo.findById(owner, group.id()).toCompletableFuture().join()
                    .orElseThrow().members().contains(member));

            SubjectGroupRepository.MembershipOutcome removed =
                    repo.removeMember(owner, group.id(), member, owner, now())
                            .toCompletableFuture().join();
            assertTrue(removed.presentBefore());
            assertFalse(removed.presentAfter());

            SubjectGroupRepository.MembershipOutcome removedAgain =
                    repo.removeMember(owner, group.id(), member, owner, now())
                            .toCompletableFuture().join();
            assertFalse(removedAgain.presentBefore());
            assertFalse(removedAgain.presentAfter());

            assertTrue(repo.findById(owner, group.id()).toCompletableFuture().join().isPresent());
            assertEquals(4, auditCount(store, "GROUP_MEMBER_CHANGE"));
            assertEquals("CL-M4-01", AuditActions.writerTask("GROUP_MEMBER_CHANGE"));
        }
    }

    @Test
    void membershipOnUnknownGroupFailsClosed() {
        try (PersistenceStore store = PersistenceStore.open(db("unknown.db"))) {
            SubjectGroupRepository repo = new SubjectGroupRepository(store);
            UUID owner = UUID.randomUUID();
            CompletionException add = assertThrows(CompletionException.class, () ->
                    repo.addMember(owner, UUID.randomUUID(), UUID.randomUUID(), owner, now())
                            .toCompletableFuture().join());
            assertEquals("group.unknown", reasonOf(add));
            CompletionException remove = assertThrows(CompletionException.class, () ->
                    repo.removeMember(owner, UUID.randomUUID(), UUID.randomUUID(), owner, now())
                            .toCompletableFuture().join());
            assertEquals("group.unknown", reasonOf(remove));
            CompletionException delete = assertThrows(CompletionException.class, () ->
                    repo.delete(owner, UUID.randomUUID(), owner, now()).toCompletableFuture().join());
            assertEquals("group.unknown", reasonOf(delete));
            assertEquals(0, auditCount(store, "GROUP_MEMBER_CHANGE"));
            assertEquals(0, auditCount(store, "GROUP_DELETE"));
        }
    }

    // ---- land interplay ----

    @Test
    void groupSurvivesLandDeleteAndIsReusableAcrossLands() {
        try (PersistenceStore store = PersistenceStore.open(db("survive.db"))) {
            SubjectGroupRepository groups = new SubjectGroupRepository(store);
            SqliteLandRepository lands = new SqliteLandRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            SubjectGroupRepository.GroupView group =
                    groups.create(owner, "Allies", owner, now()).toCompletableFuture().join();
            LandId first = new LandId(UUID.randomUUID());
            LandId second = new LandId(UUID.randomUUID());
            lands.save(land(world, first, OwnerRef.player(owner))).toCompletableFuture().join();
            UUID profileId = insertProfile(store, "PLAYER:" + owner);
            insertLandGroupBinding(store, first, group.id(), profileId);

            // Same canonical group id referenced from a second land: reusable.
            LandSnapshot secondSnap = new LandSnapshot(second, "Second", "second",
                    OwnerRef.player(owner), world, Set.of(), List.of(),
                    0, 0, Instant.now(), Instant.now());
            lands.save(secondSnap).toCompletableFuture().join();
            insertLandGroupBinding(store, second, group.id(), profileId);
            assertEquals(2, count(store,
                    "SELECT COUNT(*) FROM land_bindings WHERE subject_type = 'GROUP'"));

            // Deleting a land removes its bindings but never the group.
            lands.delete(first).toCompletableFuture().join();
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_bindings WHERE subject_type = 'GROUP'"));
            assertTrue(groups.findById(owner, group.id()).toCompletableFuture().join().isPresent());
            assertEquals(1, groups.list(owner).toCompletableFuture().join().size());
        }
    }

    @Test
    void normalDeleteIsRestrictedWithZeroMutation() {
        try (PersistenceStore store = PersistenceStore.open(db("restrict.db"))) {
            SubjectGroupRepository groups = new SubjectGroupRepository(store);
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteSubLandRepository subs = new SqliteSubLandRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            SubjectGroupRepository.GroupView group =
                    groups.create(owner, "Raiders", owner, now()).toCompletableFuture().join();
            groups.addMember(owner, group.id(), member, owner, now()).toCompletableFuture().join();

            LandId landId = new LandId(UUID.randomUUID());
            lands.save(land(world, landId, OwnerRef.player(owner))).toCompletableFuture().join();
            SubLandId subId = new SubLandId(UUID.randomUUID());
            subs.save(new SubLandSnapshot(subId, landId, "den",
                    new Cuboid(0, 0, 0, 15, 10, 15), world)).toCompletableFuture().join();
            UUID profileId = insertProfile(store, "PLAYER:" + owner);
            insertLandGroupBinding(store, landId, group.id(), profileId);
            insertSublandGroupBinding(store, subId, group.id(), profileId);

            int groupsBefore = count(store, "SELECT COUNT(*) FROM subject_groups");
            int membersBefore = count(store, "SELECT COUNT(*) FROM subject_group_members");
            int landBindingsBefore = count(store, "SELECT COUNT(*) FROM land_bindings");
            int subBindingsBefore = count(store, "SELECT COUNT(*) FROM subland_bindings");
            int deletesBefore = auditCount(store, "GROUP_DELETE");

            CompletionException restricted = assertThrows(CompletionException.class, () ->
                    groups.delete(owner, group.id(), owner, now()).toCompletableFuture().join());
            assertEquals("group.restricted:2", reasonOf(restricted));
            Throwable cause = restricted.getCause();
            while (!(cause instanceof GroupDeleteRestrictedException) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            GroupDeleteRestrictedException blocked = (GroupDeleteRestrictedException) cause;
            assertEquals(2, blocked.affected().size());
            assertTrue(blocked.affected().stream().anyMatch(b -> b.scope().equals("LAND")
                    && b.landId().equals(landId.value())));
            assertTrue(blocked.affected().stream().anyMatch(b -> b.scope().equals("SUBLAND")
                    && b.sublandId().equals(subId.value())));

            // Zero mutation: every row and every audit count is unchanged.
            assertEquals(groupsBefore, count(store, "SELECT COUNT(*) FROM subject_groups"));
            assertEquals(membersBefore, count(store, "SELECT COUNT(*) FROM subject_group_members"));
            assertEquals(landBindingsBefore, count(store, "SELECT COUNT(*) FROM land_bindings"));
            assertEquals(subBindingsBefore, count(store, "SELECT COUNT(*) FROM subland_bindings"));
            assertEquals(deletesBefore, auditCount(store, "GROUP_DELETE"));
            assertTrue(groups.findById(owner, group.id()).toCompletableFuture().join().isPresent());
        }
    }

    @Test
    void forceDeleteRemovesOnlyGroupReferencesAtomically() {
        try (PersistenceStore store = PersistenceStore.open(db("force.db"))) {
            SubjectGroupRepository groups = new SubjectGroupRepository(store);
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteSubLandRepository subs = new SqliteSubLandRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID otherPlayer = UUID.randomUUID();
            SubjectGroupRepository.GroupView group =
                    groups.create(owner, "Raiders", owner, now()).toCompletableFuture().join();
            groups.addMember(owner, group.id(), member, owner, now()).toCompletableFuture().join();

            LandId landId = new LandId(UUID.randomUUID());
            lands.save(land(world, landId, OwnerRef.player(owner))).toCompletableFuture().join();
            SubLandId subId = new SubLandId(UUID.randomUUID());
            subs.save(new SubLandSnapshot(subId, landId, "den",
                    new Cuboid(0, 0, 0, 15, 10, 15), world)).toCompletableFuture().join();
            UUID profileId = insertProfile(store, "PLAYER:" + owner);
            insertLandGroupBinding(store, landId, group.id(), profileId);
            insertSublandGroupBinding(store, subId, group.id(), profileId);
            // A PLAYER binding on the same profile must survive the force delete.
            store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO land_bindings (land_id, subject_type, subject_id, profile_id)"
                                + " VALUES (?, 'PLAYER', ?, ?)")) {
                    ps.setBytes(1, UuidBlob.encode(landId.value()));
                    ps.setBytes(2, UuidBlob.encode(otherPlayer));
                    ps.setBytes(3, UuidBlob.encode(profileId));
                    ps.executeUpdate();
                    return null;
                }
            });

            SubjectGroupRepository.ForceDeleteOutcome outcome = groups
                    .forceDelete(owner, group.id(), owner, now()).toCompletableFuture().join();
            assertEquals(group.id(), outcome.groupId());
            assertEquals(2, outcome.affected().size());

            assertTrue(groups.findById(owner, group.id()).toCompletableFuture().join().isEmpty());
            assertTrue(groups.list(owner).toCompletableFuture().join().isEmpty());
            assertEquals(0, count(store, "SELECT COUNT(*) FROM subject_group_members"));
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM land_bindings WHERE subject_type = 'GROUP'"));
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM subland_bindings WHERE subject_type = 'GROUP'"));
            // Untouched: the PLAYER binding, the profile row, the land and the audit trail.
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_bindings WHERE subject_type = 'PLAYER'"));
            assertEquals(1, count(store, "SELECT COUNT(*) FROM permission_profiles"));
            assertTrue(lands.findById(landId).toCompletableFuture().join().isPresent());
            assertEquals(1, auditCount(store, "GROUP_DELETE"));
            assertEquals("CL-M4-01", AuditActions.writerTask("GROUP_DELETE"));
            String metadata = store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT metadata_json FROM audit_log WHERE action = 'GROUP_DELETE'"
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
            SubjectGroupRepository repo = new SubjectGroupRepository(store);
            UUID owner = UUID.randomUUID();
            SubjectGroupRepository.GroupView group =
                    repo.create(owner, "Solo", owner, now()).toCompletableFuture().join();
            SubjectGroupRepository.ForceDeleteOutcome outcome = repo
                    .forceDelete(owner, group.id(), owner, now()).toCompletableFuture().join();
            assertTrue(outcome.affected().isEmpty());
            assertTrue(repo.findById(owner, group.id()).toCompletableFuture().join().isEmpty());
            assertEquals(1, auditCount(store, "GROUP_DELETE"));

            CompletionException second = assertThrows(CompletionException.class, () ->
                    repo.forceDelete(owner, group.id(), owner, now()).toCompletableFuture().join());
            assertEquals("group.unknown", reasonOf(second));
        }
    }

    // ---- atomicity ----

    @Test
    void auditFailureRollsBackEveryWrite() {
        for (SubjectGroupRepository.Step failing
                : List.of(SubjectGroupRepository.Step.AFTER_GROUP,
                        SubjectGroupRepository.Step.AFTER_AUDIT)) {
            try (PersistenceStore store = PersistenceStore.open(db("rollback-" + failing + ".db"))) {
                AtomicReference<SubjectGroupRepository.Step> failAt = new AtomicReference<>(failing);
                SubjectGroupRepository repo = new SubjectGroupRepository(store, step -> {
                    if (step == failAt.get()) {
                        throw new IllegalStateException("injected " + step);
                    }
                });
                UUID owner = UUID.randomUUID();
                CompletionException failure = assertThrows(CompletionException.class, () ->
                        repo.create(owner, "Doomed", owner, now()).toCompletableFuture().join());
                assertTrue(failure.getCause() instanceof IllegalStateException
                        || failure.getCause().getCause() instanceof IllegalStateException);
                assertEquals(0, count(store, "SELECT COUNT(*) FROM subject_groups"));
                assertEquals(0, auditCount(store, "GROUP_CREATE"));
                assertTrue(repo.list(owner).toCompletableFuture().join().isEmpty());
            }
        }
    }

    @Test
    void memberAndForceDeleteFailuresRollBack() {
        try (PersistenceStore store = PersistenceStore.open(db("rollback-member.db"))) {
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            SubjectGroupRepository plain = new SubjectGroupRepository(store);
            SubjectGroupRepository.GroupView group =
                    plain.create(owner, "Crew", owner, now()).toCompletableFuture().join();

            SubjectGroupRepository failingMember = new SubjectGroupRepository(store, step -> {
                if (step == SubjectGroupRepository.Step.AFTER_MEMBER) {
                    throw new IllegalStateException("injected member failure");
                }
            });
            assertThrows(CompletionException.class, () ->
                    failingMember.addMember(owner, group.id(), member, owner, now())
                            .toCompletableFuture().join());
            assertEquals(0, count(store, "SELECT COUNT(*) FROM subject_group_members"));
            assertEquals(0, auditCount(store, "GROUP_MEMBER_CHANGE"));
        }

        try (PersistenceStore store = PersistenceStore.open(db("rollback-force.db"))) {
            UUID owner = UUID.randomUUID();
            SubjectGroupRepository plain = new SubjectGroupRepository(store);
            SqliteLandRepository lands = new SqliteLandRepository(store);
            UUID world = UUID.randomUUID();
            SubjectGroupRepository.GroupView group =
                    plain.create(owner, "Crew", owner, now()).toCompletableFuture().join();
            LandId landId = new LandId(UUID.randomUUID());
            lands.save(land(world, landId, OwnerRef.player(owner))).toCompletableFuture().join();
            UUID profileId = insertProfile(store, "PLAYER:" + owner);
            insertLandGroupBinding(store, landId, group.id(), profileId);

            for (SubjectGroupRepository.Step failing : List.of(
                    SubjectGroupRepository.Step.AFTER_BINDING_DELETE,
                    SubjectGroupRepository.Step.AFTER_GROUP,
                    SubjectGroupRepository.Step.AFTER_AUDIT)) {
                SubjectGroupRepository attempt = new SubjectGroupRepository(store, step -> {
                    if (step == failing) {
                        throw new IllegalStateException("injected " + step);
                    }
                });
                assertThrows(CompletionException.class, () ->
                        attempt.forceDelete(owner, group.id(), owner, now())
                                .toCompletableFuture().join());
                assertTrue(plain.findById(owner, group.id()).toCompletableFuture().join().isPresent());
                assertEquals(1, count(store,
                        "SELECT COUNT(*) FROM land_bindings WHERE subject_type = 'GROUP'"));
                assertEquals(1, count(store, "SELECT COUNT(*) FROM permission_profiles"));
            }
            assertEquals(0, auditCount(store, "GROUP_DELETE"));
        }
    }

    @Test
    void concurrentDuplicateNameCommitsExactlyOnce() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(db("concurrent.db"))) {
            SubjectGroupRepository repo = new SubjectGroupRepository(store);
            UUID owner = UUID.randomUUID();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<SubjectGroupRepository.GroupView>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                CompletableFuture<SubjectGroupRepository.GroupView> future = new CompletableFuture<>();
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
            for (CompletableFuture<SubjectGroupRepository.GroupView> future : futures) {
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
                    if (cause instanceof GroupRejectedException rejected
                            && rejected.reason().equals("group.duplicate")) {
                        duplicates++;
                    } else {
                        throw new AssertionError("unexpected failure " + cause, failure);
                    }
                }
            }
            assertEquals(1, successes);
            assertEquals(1, duplicates);
            assertEquals(1, repo.list(owner).toCompletableFuture().join().size());
            assertEquals(1, auditCount(store, "GROUP_CREATE"));
        }
    }

    @Test
    void serverNamespaceCanNeverOwnAGroup() {
        try (PersistenceStore store = PersistenceStore.open(db("server.db"))) {
            SubjectGroupRepository repo = new SubjectGroupRepository(store);
            UUID owner = UUID.randomUUID();
            SubjectGroupRepository.GroupView group =
                    repo.create(owner, "Crew", owner, now()).toCompletableFuture().join();
            assertEquals("PLAYER:" + owner, store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT DISTINCT owner_key FROM subject_groups")) {
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
            assertFalse(("PLAYER:" + owner).equals(OwnerKey.SERVER_VALUE));
            assertThrows(NullPointerException.class,
                    () -> repo.create(null, "Crew", owner, now()));
            assertThrows(NullPointerException.class,
                    () -> repo.list(null));
            assertEquals(group.id(), repo.findById(owner, group.id())
                    .toCompletableFuture().join().orElseThrow().id());
        }
    }

    @Test
    void auditWriterRegistryCoversGroupActions() {
        assertEquals("CL-M4-01", AuditActions.writerTask("GROUP_CREATE"));
        assertEquals("CL-M4-01", AuditActions.writerTask("GROUP_DELETE"));
        assertEquals("CL-M4-01", AuditActions.writerTask("GROUP_MEMBER_CHANGE"));
    }

    // ---- helpers ----

    private static Consumer<SubjectGroupRepository.Step> failOn(
            SubjectGroupRepository.Step step) {
        return actual -> {
            if (actual == step) {
                throw new IllegalStateException("injected " + step);
            }
        };
    }

    @Test
    void failureInjectorHelperIsWired() {
        try (PersistenceStore store = PersistenceStore.open(db("injector.db"))) {
            SubjectGroupRepository repo =
                    new SubjectGroupRepository(store, failOn(SubjectGroupRepository.Step.AFTER_AUDIT));
            UUID owner = UUID.randomUUID();
            assertThrows(CompletionException.class, () ->
                    repo.create(owner, "Doomed", owner, now()).toCompletableFuture().join());
            assertEquals(0, count(store, "SELECT COUNT(*) FROM subject_groups"));
        }
    }
}

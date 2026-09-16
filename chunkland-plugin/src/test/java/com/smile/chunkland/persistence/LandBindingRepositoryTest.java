package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generic Land/SubLand binding persistence: canonical UUID subjects,
 * owner-isolated replace and remove, structured binding audits, land revision
 * and owner epoch bumps in the same transaction, fail-closed guards, and
 * rollback with no partial rows.
 */
class LandBindingRepositoryTest {

    @TempDir Path tmp;

    private Path db(String name) {
        return tmp.resolve(name);
    }

    private Instant now() {
        return Instant.now();
    }

    private LandSnapshot land(UUID worldId, LandId id, OwnerRef owner) {
        return new LandSnapshot(id, "Home", "home", owner, worldId,
                Set.of(new ChunkKey(worldId, 0, 0)), List.of(),
                0, 0, Instant.now(), Instant.now());
    }

    private void insertSubland(PersistenceStore store, SubLandId sublandId, LandId parent,
            UUID worldId) {
        store.execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO sublands (id, land_id, name,"
                            + " min_x, min_y, min_z, max_x, max_y, max_z, world_uuid)"
                            + " VALUES (?, ?, ?, 0, 0, 0, 15, 255, 15, ?)")) {
                ps.setBytes(1, UuidBlob.encode(sublandId.value()));
                ps.setBytes(2, UuidBlob.encode(parent.value()));
                ps.setString(3, "Den");
                ps.setBytes(4, UuidBlob.encode(worldId));
                ps.executeUpdate();
                return null;
            }
        });
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

    private long revision(PersistenceStore store, LandId land) {
        return store.execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT land_policy_revision FROM lands WHERE id = ?")) {
                ps.setBytes(1, UuidBlob.encode(land.value()));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private static String reasonOf(CompletionException failure) {
        Throwable cause = failure.getCause();
        while ((cause instanceof CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null
                && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof BindingRejectedException rejected) {
            return rejected.reason();
        }
        throw new AssertionError("expected BindingRejectedException, got " + cause, failure);
    }

    private record Fixture(PersistenceStore store, SqliteLandRepository lands,
            SubjectGroupRepository groups, PermissionProfileRepository profiles,
            LandBindingRepository bindings, UUID world, UUID owner, LandId land,
            UUID profileId) {
    }

    private Fixture fixture(String name) {
        PersistenceStore store = PersistenceStore.open(db(name));
        SqliteLandRepository lands = new SqliteLandRepository(store);
        SubjectGroupRepository groups = new SubjectGroupRepository(store);
        PermissionProfileRepository profiles = new PermissionProfileRepository(store);
        LandBindingRepository bindings = new LandBindingRepository(store);
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();
        UUID profileId = profiles.create(owner, "Crew", owner, now())
                .toCompletableFuture().join().id();
        return new Fixture(store, lands, groups, profiles, bindings, world, owner, land,
                profileId);
    }

    private LandBindingRepository.Subject player(UUID id) {
        return new LandBindingRepository.Subject(
                LandBindingRepository.SubjectKind.PLAYER, id);
    }

    private LandBindingRepository.Subject group(UUID id) {
        return new LandBindingRepository.Subject(
                LandBindingRepository.SubjectKind.GROUP, id);
    }

    // ---- land bind / replace / unbind ----

    @Test
    void bindPlayerCreatesRowAuditRevisionAndEpoch() {
        Fixture f = fixture("bind.db");
        try (PersistenceStore ignored = f.store()) {
            UUID target = UUID.randomUUID();
            LandBindingRepository.BindOutcome outcome = f.bindings()
                    .bindLand(f.owner(), f.land(), player(target), f.profileId(), f.owner(), now())
                    .toCompletableFuture().join();

            assertEquals(f.land().value(), outcome.landId());
            assertNull(outcome.sublandId());
            assertEquals(false, outcome.replaced());
            assertEquals(1, count(f.store(),
                    "SELECT COUNT(*) FROM land_bindings WHERE land_id = ?"
                            + " AND subject_type = 'PLAYER'",
                    f.land().value()));
            assertEquals(1, auditCount(f.store(), "BINDING_CREATE"));
            assertEquals(0, auditCount(f.store(), "BINDING_UPDATE"));
            assertEquals(1, revision(f.store(), f.land()));
            assertEquals(2L, f.bindings().epochOf(f.owner()).toCompletableFuture().join());
            assertEquals("CL-M4-03", AuditActions.writerTask("BINDING_CREATE"));
            assertEquals("CL-M4-03", AuditActions.writerTask("BINDING_UPDATE"));
            assertEquals("CL-M4-03", AuditActions.writerTask("BINDING_DELETE"));
        }
    }

    @Test
    void rebindSameSubjectReplacesRowWithUpdateAudit() {
        Fixture f = fixture("replace.db");
        try (PersistenceStore ignored = f.store()) {
            UUID target = UUID.randomUUID();
            f.bindings().bindLand(f.owner(), f.land(), player(target), f.profileId(), f.owner(),
                    now()).toCompletableFuture().join();
            UUID second = f.profiles().create(f.owner(), "Night", f.owner(), now())
                    .toCompletableFuture().join().id();

            LandBindingRepository.BindOutcome outcome = f.bindings()
                    .bindLand(f.owner(), f.land(), player(target), second, f.owner(), now())
                    .toCompletableFuture().join();

            assertEquals(true, outcome.replaced());
            assertEquals(1, count(f.store(),
                    "SELECT COUNT(*) FROM land_bindings WHERE land_id = ?"
                            + " AND subject_type = 'PLAYER'",
                    f.land().value()));
            assertEquals(second, f.store().execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT profile_id FROM land_bindings WHERE land_id = ?"
                                + " AND subject_type = 'PLAYER' AND subject_id = ?")) {
                    ps.setBytes(1, UuidBlob.encode(f.land().value()));
                    ps.setBytes(2, UuidBlob.encode(target));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return UuidBlob.decode(rs.getBytes(1));
                    }
                }
            }));
            assertEquals(1, auditCount(f.store(), "BINDING_CREATE"));
            assertEquals(1, auditCount(f.store(), "BINDING_UPDATE"));
            assertEquals(2, revision(f.store(), f.land()));
            assertEquals(4L, f.bindings().epochOf(f.owner()).toCompletableFuture().join());
        }
    }

    @Test
    void unbindRemovesRowAndResendStillSucceeds() {
        Fixture f = fixture("unbind.db");
        try (PersistenceStore ignored = f.store()) {
            UUID target = UUID.randomUUID();
            f.bindings().bindLand(f.owner(), f.land(), player(target), f.profileId(), f.owner(),
                    now()).toCompletableFuture().join();

            LandBindingRepository.UnbindOutcome removed = f.bindings()
                    .unbindLand(f.owner(), f.land(), player(target), f.owner(), now())
                    .toCompletableFuture().join();
            assertEquals(f.profileId(), removed.removedProfileId());
            assertEquals(0, count(f.store(),
                    "SELECT COUNT(*) FROM land_bindings WHERE land_id = ?", f.land().value()));
            assertEquals(1, auditCount(f.store(), "BINDING_DELETE"));

            LandBindingRepository.UnbindOutcome resend = f.bindings()
                    .unbindLand(f.owner(), f.land(), player(target), f.owner(), now())
                    .toCompletableFuture().join();
            assertNull(resend.removedProfileId());
            assertEquals(2, auditCount(f.store(), "BINDING_DELETE"));
        }
    }

    @Test
    void bindGroupSubjectRequiresKnownGroupInSameNamespace() {
        Fixture f = fixture("groupbind.db");
        try (PersistenceStore ignored = f.store()) {
            UUID groupId = f.groups().create(f.owner(), "Crew", f.owner(), now())
                    .toCompletableFuture().join().id();
            f.bindings().bindLand(f.owner(), f.land(), group(groupId), f.profileId(), f.owner(),
                    now()).toCompletableFuture().join();
            assertEquals(1, count(f.store(),
                    "SELECT COUNT(*) FROM land_bindings WHERE land_id = ?"
                            + " AND subject_type = 'GROUP'",
                    f.land().value()));

            CompletionException unknown = assertThrows(CompletionException.class, () ->
                    f.bindings().bindLand(f.owner(), f.land(), group(UUID.randomUUID()),
                            f.profileId(), f.owner(), now()).toCompletableFuture().join());
            assertEquals("binding.unknown", reasonOf(unknown));

            UUID foreignOwner = UUID.randomUUID();
            LandId foreignLand = new LandId(UUID.randomUUID());
            f.lands().save(land(f.world(), foreignLand, OwnerRef.player(foreignOwner)))
                    .toCompletableFuture().join();
            UUID foreignGroup = f.groups().create(foreignOwner, "Crew", foreignOwner, now())
                    .toCompletableFuture().join().id();
            CompletionException crossGroup = assertThrows(CompletionException.class, () ->
                    f.bindings().bindLand(f.owner(), f.land(), group(foreignGroup),
                            f.profileId(), f.owner(), now()).toCompletableFuture().join());
            assertEquals("binding.unknown", reasonOf(crossGroup));

            assertEquals(1, count(f.store(), "SELECT COUNT(*) FROM land_bindings"));
            assertEquals(1, auditCount(f.store(), "BINDING_CREATE"));
        }
    }

    // ---- guards ----

    @Test
    void unknownLandForeignLandAndServerLandFailClosedWithZeroMutation() {
        Fixture f = fixture("guards.db");
        try (PersistenceStore ignored = f.store()) {
            UUID target = UUID.randomUUID();

            CompletionException unknown = assertThrows(CompletionException.class, () ->
                    f.bindings().bindLand(f.owner(), new LandId(UUID.randomUUID()),
                            player(target), f.profileId(), f.owner(), now())
                            .toCompletableFuture().join());
            assertEquals("binding.unknown", reasonOf(unknown));

            UUID foreignOwner = UUID.randomUUID();
            LandId foreignLand = new LandId(UUID.randomUUID());
            f.lands().save(land(f.world(), foreignLand, OwnerRef.player(foreignOwner)))
                    .toCompletableFuture().join();
            CompletionException foreign = assertThrows(CompletionException.class, () ->
                    f.bindings().bindLand(f.owner(), foreignLand, player(target), f.profileId(),
                            f.owner(), now()).toCompletableFuture().join());
            assertEquals("binding.unknown", reasonOf(foreign));

            LandId serverLand = new LandId(UUID.randomUUID());
            f.lands().save(land(f.world(), serverLand, OwnerRef.server()))
                    .toCompletableFuture().join();
            CompletionException server = assertThrows(CompletionException.class, () ->
                    f.bindings().bindLand(f.owner(), serverLand, player(target), f.profileId(),
                            f.owner(), now()).toCompletableFuture().join());
            assertEquals("binding.unknown", reasonOf(server));

            assertEquals(0, count(f.store(), "SELECT COUNT(*) FROM land_bindings"));
            assertEquals(0, auditCount(f.store(), "BINDING_CREATE"));
            assertEquals(0, revision(f.store(), f.land()));
        }
    }

    @Test
    void directProfileAndForeignProfileAreRefused() {
        Fixture f = fixture("reserved.db");
        try (PersistenceStore ignored = f.store()) {
            // A direct-named profile inside the caller's own namespace is
            // refused as reserved; the trust-owned direct profile of another
            // player reads as unknown from here and never leaks.
            UUID directProfile = UUID.randomUUID();
            f.store().execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO permission_profiles (id, owner_key, name_key,"
                                + " display_name, created_at) VALUES (?, ?, 'direct',"
                                + " 'Direct', ?)")) {
                    ps.setBytes(1, UuidBlob.encode(directProfile));
                    ps.setString(2, "PLAYER:" + f.owner());
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                    return null;
                }
            });

            CompletionException reserved = assertThrows(CompletionException.class, () ->
                    f.bindings().bindLand(f.owner(), f.land(), player(UUID.randomUUID()),
                            directProfile, f.owner(), now()).toCompletableFuture().join());
            assertEquals("binding.reserved", reasonOf(reserved));

            UUID foreignOwner = UUID.randomUUID();
            PermissionProfileRepository foreignProfiles =
                    new PermissionProfileRepository(f.store());
            UUID foreignProfile = foreignProfiles.create(foreignOwner, "Crew", foreignOwner, now())
                    .toCompletableFuture().join().id();
            CompletionException foreign = assertThrows(CompletionException.class, () ->
                    f.bindings().bindLand(f.owner(), f.land(), player(UUID.randomUUID()),
                            foreignProfile, f.owner(), now()).toCompletableFuture().join());
            assertEquals("binding.unknown", reasonOf(foreign));

            CompletionException missing = assertThrows(CompletionException.class, () ->
                    f.bindings().bindLand(f.owner(), f.land(), player(UUID.randomUUID()),
                            UUID.randomUUID(), f.owner(), now()).toCompletableFuture().join());
            assertEquals("binding.unknown", reasonOf(missing));

            assertEquals(0, count(f.store(), "SELECT COUNT(*) FROM land_bindings"));
            assertEquals(0, auditCount(f.store(), "BINDING_CREATE"));
        }
    }

    // ---- subland scope ----

    @Test
    void bindAndUnbindSublandBumpParentRevisionAndEpoch() {
        Fixture f = fixture("subland.db");
        try (PersistenceStore ignored = f.store()) {
            SubLandId subland = new SubLandId(UUID.randomUUID());
            insertSubland(f.store(), subland, f.land(), f.world());
            UUID target = UUID.randomUUID();

            LandBindingRepository.BindOutcome bound = f.bindings()
                    .bindSubland(f.owner(), subland, player(target), f.profileId(), f.owner(),
                            now())
                    .toCompletableFuture().join();
            assertEquals(f.land().value(), bound.landId());
            assertEquals(subland.value(), bound.sublandId());
            assertEquals(false, bound.replaced());
            assertEquals(1, count(f.store(),
                    "SELECT COUNT(*) FROM subland_bindings WHERE subland_id = ?",
                    subland.value()));
            assertEquals(1, auditCount(f.store(), "BINDING_CREATE"));
            assertEquals(1, revision(f.store(), f.land()));

            LandBindingRepository.UnbindOutcome removed = f.bindings()
                    .unbindSubland(f.owner(), subland, player(target), f.owner(), now())
                    .toCompletableFuture().join();
            assertEquals(f.profileId(), removed.removedProfileId());
            assertEquals(0, count(f.store(), "SELECT COUNT(*) FROM subland_bindings"));
            assertEquals(1, auditCount(f.store(), "BINDING_DELETE"));
            assertEquals(2, revision(f.store(), f.land()));

            CompletionException unknown = assertThrows(CompletionException.class, () ->
                    f.bindings().bindSubland(f.owner(), new SubLandId(UUID.randomUUID()),
                            player(target), f.profileId(), f.owner(), now())
                            .toCompletableFuture().join());
            assertEquals("binding.unknown", reasonOf(unknown));
        }
    }

    // ---- atomicity / concurrency / backstop ----

    @Test
    void auditFailureRollsBackBindingRevisionAndEpoch() {
        Fixture f = fixture("rollback.db");
        try (PersistenceStore ignored = f.store()) {
            UUID target = UUID.randomUUID();
            Consumer<LandBindingRepository.Step> bomb = step -> {
                if (step == LandBindingRepository.Step.AFTER_AUDIT) {
                    throw new RuntimeException("audit boom");
                }
            };
            LandBindingRepository failing = new LandBindingRepository(f.store(), bomb);

            assertThrows(CompletionException.class, () ->
                    failing.bindLand(f.owner(), f.land(), player(target), f.profileId(),
                            f.owner(), now()).toCompletableFuture().join());

            assertEquals(0, count(f.store(), "SELECT COUNT(*) FROM land_bindings"));
            assertEquals(0, auditCount(f.store(), "BINDING_CREATE"));
            assertEquals(0, revision(f.store(), f.land()));
            assertEquals(1L, f.bindings().epochOf(f.owner()).toCompletableFuture().join());
        }
    }

    @Test
    void bindingFailureRollsBackBeforeAudit() {
        Fixture f = fixture("rollback2.db");
        try (PersistenceStore ignored = f.store()) {
            UUID target = UUID.randomUUID();
            Consumer<LandBindingRepository.Step> bomb = step -> {
                if (step == LandBindingRepository.Step.AFTER_BINDING) {
                    throw new RuntimeException("binding boom");
                }
            };
            LandBindingRepository failing = new LandBindingRepository(f.store(), bomb);

            assertThrows(CompletionException.class, () ->
                    failing.bindLand(f.owner(), f.land(), player(target), f.profileId(),
                            f.owner(), now()).toCompletableFuture().join());

            assertEquals(0, count(f.store(), "SELECT COUNT(*) FROM land_bindings"));
            assertEquals(0, auditCount(f.store(), "BINDING_CREATE"));
            assertEquals(1L, f.bindings().epochOf(f.owner()).toCompletableFuture().join());
        }
    }

    @Test
    void concurrentReplacesOnSameSubjectStaySingleRowed() {
        Fixture f = fixture("concurrent.db");
        try (PersistenceStore ignored = f.store()) {
            UUID target = UUID.randomUUID();
            UUID second = f.profiles().create(f.owner(), "Night", f.owner(), now())
                    .toCompletableFuture().join().id();
            CompletableFuture<LandBindingRepository.BindOutcome> first =
                    f.bindings().bindLand(f.owner(), f.land(), player(target), f.profileId(),
                            f.owner(), now()).toCompletableFuture();
            CompletableFuture<LandBindingRepository.BindOutcome> other =
                    f.bindings().bindLand(f.owner(), f.land(), player(target), second,
                            f.owner(), now()).toCompletableFuture();
            CompletableFuture.allOf(first, other).join();

            assertEquals(1, count(f.store(),
                    "SELECT COUNT(*) FROM land_bindings WHERE land_id = ?"
                            + " AND subject_type = 'PLAYER' AND subject_id = ?",
                    f.land().value(), target));
            assertEquals(1, auditCount(f.store(), "BINDING_CREATE"));
            assertEquals(1, auditCount(f.store(), "BINDING_UPDATE"));
        }
    }

    @Test
    void uniqueBackstopRejectsDuplicateScopeSubjectRows() {
        Fixture f = fixture("unique.db");
        try (PersistenceStore ignored = f.store()) {
            UUID target = UUID.randomUUID();
            f.bindings().bindLand(f.owner(), f.land(), player(target), f.profileId(), f.owner(),
                    now()).toCompletableFuture().join();
            f.store().execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO land_bindings (land_id, subject_type, subject_id, profile_id)"
                                + " VALUES (?, 'PLAYER', ?, ?)")) {
                    ps.setBytes(1, UuidBlob.encode(f.land().value()));
                    ps.setBytes(2, UuidBlob.encode(target));
                    ps.setBytes(3, UuidBlob.encode(f.profileId()));
                    assertThrows(java.sql.SQLException.class, ps::executeUpdate);
                    return null;
                }
            });
        }
    }

    @Test
    void epochSurvivesRestartAndStartsAtZeroForFreshOwners() {
        Path path = db("epoch-restart.db");
        UUID owner = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        UUID profile;
        try (PersistenceStore store = PersistenceStore.open(path)) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            PermissionProfileRepository profiles = new PermissionProfileRepository(store);
            LandBindingRepository bindings = new LandBindingRepository(store);
            assertEquals(0L, bindings.epochOf(owner).toCompletableFuture().join());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();
            profile = profiles.create(owner, "Crew", owner, now())
                    .toCompletableFuture().join().id();
            bindings.bindLand(owner, land, player(UUID.randomUUID()), profile, owner, now())
                    .toCompletableFuture().join();
            assertEquals(2L, bindings.epochOf(owner).toCompletableFuture().join());
        }
        try (PersistenceStore reopened = PersistenceStore.open(path)) {
            LandBindingRepository bindings = new LandBindingRepository(reopened);
            assertEquals(2L, bindings.epochOf(owner).toCompletableFuture().join());
            // Schema v6 adds the world-scoped audit index; fresh databases
            // migrate to the latest version on open.
            assertEquals(6, reopened.schemaVersion());
        }
    }

    @Test
    void groupAndProfileMutationsIncrementOwnerEpoch() {
        Fixture f = fixture("hooks.db");
        try (PersistenceStore ignored = f.store()) {
            assertEquals(1L, f.bindings().epochOf(f.owner()).toCompletableFuture().join());

            UUID groupId = f.groups().create(f.owner(), "Crew", f.owner(), now())
                    .toCompletableFuture().join().id();
            assertEquals(2L, f.bindings().epochOf(f.owner()).toCompletableFuture().join());

            f.groups().addMember(f.owner(), groupId, UUID.randomUUID(), f.owner(), now())
                    .toCompletableFuture().join();
            assertEquals(3L, f.bindings().epochOf(f.owner()).toCompletableFuture().join());

            f.groups().removeMember(f.owner(), groupId, UUID.randomUUID(), f.owner(), now())
                    .toCompletableFuture().join();
            assertEquals(4L, f.bindings().epochOf(f.owner()).toCompletableFuture().join());

            f.profiles().setEntry(f.owner(), f.profileId(), ProtectionActionType.BLOCK_BREAK,
                    PermissionState.ALLOW, f.owner(), now()).toCompletableFuture().join();
            assertEquals(5L, f.bindings().epochOf(f.owner()).toCompletableFuture().join());

            UUID foreignOwner = UUID.randomUUID();
            assertEquals(0L, f.bindings().epochOf(foreignOwner).toCompletableFuture().join());
        }
    }
}

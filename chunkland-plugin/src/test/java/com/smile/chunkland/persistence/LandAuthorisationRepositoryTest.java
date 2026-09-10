package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Direct-trust persistence: one player profile reused across lands, whitelist
 * entries, player bindings, land defaults and their audits commit atomically;
 * failures leave no partial rows and unsupported inputs fail closed.
 */
class LandAuthorisationRepositoryTest {

    @TempDir Path tmp;

    private Path db() {
        return tmp.resolve("auth.db");
    }

    private LandSnapshot land(UUID worldId, LandId id, OwnerRef owner) {
        return new LandSnapshot(id, "Home", "home", owner, worldId,
                Set.of(new ChunkKey(worldId, 0, 0)), List.of(),
                0, 0, Instant.now(), Instant.now());
    }

    private Instant now() {
        return Instant.now();
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

    @Test
    void trustCreatesReusableProfileWhitelistEntriesBindingAndAudit() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();

            UUID profileId = repo.trust(land, target, owner, now())
                    .toCompletableFuture().join();

            assertEquals("PLAYER:" + target,
                    store.execute(conn -> {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT owner_key FROM permission_profiles WHERE id = ?")) {
                            ps.setBytes(1, UuidBlob.encode(profileId));
                            try (ResultSet rs = ps.executeQuery()) {
                                rs.next();
                                return rs.getString(1);
                            }
                        }
                    }));
            assertEquals("direct",
                    store.execute(conn -> {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT name_key FROM permission_profiles WHERE id = ?")) {
                            ps.setBytes(1, UuidBlob.encode(profileId));
                            try (ResultSet rs = ps.executeQuery()) {
                                rs.next();
                                return rs.getString(1);
                            }
                        }
                    }));
            assertEquals(17, count(store,
                    "SELECT COUNT(*) FROM permission_profile_entries WHERE profile_id = ?", profileId));
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_bindings WHERE land_id = ? AND subject_type = 'PLAYER'",
                    land.value()));
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'DIRECT_BINDING_CHANGE' AND land_id = ?",
                    land.value()));
        }
    }

    @Test
    void trustIsIdempotentAndReusesOneProfileAcrossLands() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            LandId first = new LandId(UUID.randomUUID());
            LandId second = new LandId(UUID.randomUUID());
            lands.save(land(world, first, OwnerRef.player(owner))).toCompletableFuture().join();
            lands.save(new LandSnapshot(second, "Away", "away", OwnerRef.player(owner), world,
                    Set.of(new ChunkKey(world, 3, 3)), List.of(),
                    0, 0, Instant.now(), Instant.now())).toCompletableFuture().join();

            UUID once = repo.trust(first, target, owner, now())
                    .toCompletableFuture().join();
            UUID twice = repo.trust(first, target, owner, now())
                    .toCompletableFuture().join();
            assertEquals(once, twice);
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_bindings WHERE land_id = ?", first.value()));

            UUID other = repo.trust(second, target, owner, now())
                    .toCompletableFuture().join();
            assertEquals(once, other, "one player reuses one implicit direct profile across lands");
            assertEquals(17, count(store,
                    "SELECT COUNT(*) FROM permission_profile_entries WHERE profile_id = ?", once));
            assertEquals(1, count(store, "SELECT COUNT(*) FROM permission_profiles"));
        }
    }

    @Test
    void untrustDeletesOnlyTheBindingAndKeepsTheProfile() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();
            UUID profile = repo.trust(land, target, owner, now())
                    .toCompletableFuture().join();

            repo.untrust(land, target, owner, now())
                    .toCompletableFuture().join();
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM land_bindings WHERE land_id = ?", land.value()));
            assertEquals(1, count(store, "SELECT COUNT(*) FROM permission_profiles"));
            assertEquals(17, count(store,
                    "SELECT COUNT(*) FROM permission_profile_entries WHERE profile_id = ?", profile));

            // Resending untrust stays successful and safe.
            repo.untrust(land, target, owner, now())
                    .toCompletableFuture().join();
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM land_bindings WHERE land_id = ?", land.value()));
        }
    }

    @Test
    void landDefaultUpsertAndInheritDeleteWithAudit() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();

            repo.setDefault(land, ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW,
                    owner, now()).toCompletableFuture().join();
            assertEquals("ALLOW", store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT state FROM land_defaults WHERE land_id = ? AND permission = 'BLOCK_BREAK'")) {
                    ps.setBytes(1, UuidBlob.encode(land.value()));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getString(1);
                    }
                }
            }));
            repo.setDefault(land, ProtectionActionType.BLOCK_BREAK, PermissionState.DENY,
                    owner, now()).toCompletableFuture().join();
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_defaults WHERE land_id = ? AND permission = 'BLOCK_BREAK' AND state = 'DENY'",
                    land.value()));
            repo.setDefault(land, ProtectionActionType.BLOCK_BREAK, PermissionState.INHERIT,
                    owner, now()).toCompletableFuture().join();
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM land_defaults WHERE land_id = ?", land.value()));
            assertEquals(3, count(store,
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'DEFAULT_CHANGE' AND land_id = ?",
                    land.value()));
        }
    }

    @Test
    void auditFailureRollsBackTrustWithoutPartialRows() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            AtomicBoolean armed = new AtomicBoolean(true);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store, step -> {
                if (armed.get() && step == LandAuthorisationRepository.Step.AFTER_AUDIT) {
                    throw new IllegalStateException("injected audit failure");
                }
            });
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();

            assertThrows(CompletionException.class, () -> repo
                    .trust(land, target, owner, now())
                    .toCompletableFuture().join());
            assertEquals(0, count(store, "SELECT COUNT(*) FROM permission_profiles"));
            assertEquals(0, count(store, "SELECT COUNT(*) FROM permission_profile_entries"));
            assertEquals(0, count(store, "SELECT COUNT(*) FROM land_bindings"));
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'DIRECT_BINDING_CHANGE'"));

            armed.set(false);
            repo.trust(land, target, owner, now())
                    .toCompletableFuture().join();
            assertEquals(1, count(store, "SELECT COUNT(*) FROM permission_profiles"));
        }
    }

    @Test
    void unsupportedInputsFailClosedWithZeroRows() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();
            LandId unknown = new LandId(UUID.randomUUID());

            // Unknown land: no profile, no binding, no audit.
            assertThrows(CompletionException.class, () -> repo
                    .trust(unknown, UUID.randomUUID(), owner, now())
                    .toCompletableFuture().join());
            // Management and rule actions are not durable defaults.
            assertThrows(IllegalArgumentException.class, () -> repo.setDefault(land,
                    ProtectionActionType.MANAGE_MEMBER, PermissionState.ALLOW,
                    owner, now()));
            assertThrows(IllegalArgumentException.class, () -> repo.setDefault(land,
                    ProtectionActionType.FIRE_SPREAD, PermissionState.DENY,
                    owner, now()));
            assertThrows(NullPointerException.class, () -> repo.setDefault(land,
                    ProtectionActionType.BLOCK_BREAK, null, owner, now()));

            assertEquals(0, count(store, "SELECT COUNT(*) FROM permission_profiles"));
            assertEquals(0, count(store, "SELECT COUNT(*) FROM land_bindings"));
            assertEquals(0, count(store, "SELECT COUNT(*) FROM land_defaults"));
            assertEquals(0, count(store, "SELECT COUNT(*) FROM audit_log"));
        }
    }

    @Test
    void snapshotRoundTripSurvivesReopen() throws Exception {
        Path path = db();
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        try (PersistenceStore store = PersistenceStore.open(path)) {
            new SqliteLandRepository(store).save(land(world, land, OwnerRef.player(owner)))
                    .toCompletableFuture().join();
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            repo.trust(land, target, owner, now())
                    .toCompletableFuture().join();
            repo.setDefault(land, ProtectionActionType.CONTAINER_OPEN, PermissionState.DENY,
                    owner, now()).toCompletableFuture().join();
        }
        // Reopen: durable rows reload through a fresh repository (restart path).
        try (PersistenceStore store = PersistenceStore.open(path)) {
            LandAuthorisationRepository.SnapshotData data =
                    new LandAuthorisationRepository(store).loadSnapshotData()
                            .toCompletableFuture().join();
            assertTrue(data.directAllows().getOrDefault(land, Map.of())
                    .getOrDefault(target, Set.of()).contains(ProtectionActionType.BLOCK_BREAK));
            assertEquals(PermissionState.DENY, data.landDefaults().getOrDefault(land, Map.of())
                    .getOrDefault(ProtectionActionType.CONTAINER_OPEN, PermissionState.INHERIT));
        }
    }
}

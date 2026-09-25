package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every durable write that can change a {@code MANAGE_PERMISSION} decision
 * moves {@code lands.land_policy_revision}, so a revision pinned at gate
 * time reliably detects a lapsed authorisation. One test per path; each
 * asserts exactly one bump for a single-land fixture.
 */
class AuthorisationRevisionBumpTest {

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

    private static LandBindingRepository.Subject playerSubject(UUID player) {
        return new LandBindingRepository.Subject(
                LandBindingRepository.SubjectKind.PLAYER, player);
    }

    private static LandBindingRepository.Subject groupSubject(UUID groupId) {
        return new LandBindingRepository.Subject(
                LandBindingRepository.SubjectKind.GROUP, groupId);
    }

    private static final class Authz implements AutoCloseable {
        final PersistenceStore store;
        final SqliteLandRepository lands;
        final LandAuthorisationRepository auth;
        final LandBindingRepository bindings;
        final SubjectGroupRepository groups;
        final PermissionProfileRepository profiles;
        final UUID world = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final LandId land = new LandId(UUID.randomUUID());

        Authz(Path db, AuthorisationRevisionBumpTest outer) {
            store = PersistenceStore.open(db);
            lands = new SqliteLandRepository(store);
            auth = new LandAuthorisationRepository(store);
            bindings = new LandBindingRepository(store);
            groups = new SubjectGroupRepository(store);
            profiles = new PermissionProfileRepository(store);
            lands.save(outer.land(world, land, OwnerRef.player(owner)))
                    .toCompletableFuture().join();
        }

        long revision() {
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

        UUID profile() {
            return profiles.create(owner, "crew", owner, Instant.now())
                    .toCompletableFuture().join().id();
        }

        UUID group() {
            return groups.create(owner, "crew", owner, Instant.now())
                    .toCompletableFuture().join().id();
        }

        @Override
        public void close() {
            store.close();
        }
    }

    @Test
    void trustBumps() {
        try (Authz env = new Authz(db("trust.db"), this)) {
            long before = env.revision();
            env.auth.trust(env.land, UUID.randomUUID(), env.owner, now())
                    .toCompletableFuture().join();
            assertEquals(before + 1, env.revision());
        }
    }

    @Test
    void untrustBumps() {
        try (Authz env = new Authz(db("untrust.db"), this)) {
            UUID target = UUID.randomUUID();
            env.auth.trust(env.land, target, env.owner, now()).toCompletableFuture().join();
            long before = env.revision();
            env.auth.untrust(env.land, target, env.owner, now()).toCompletableFuture().join();
            assertEquals(before + 1, env.revision());
        }
    }

    @Test
    void setDefaultBumps() {
        try (Authz env = new Authz(db("default.db"), this)) {
            long before = env.revision();
            env.auth.setDefault(env.land, ProtectionActionType.BLOCK_BREAK,
                    PermissionState.DENY, env.owner, now()).toCompletableFuture().join();
            assertEquals(before + 1, env.revision());
        }
    }

    @Test
    void conditionalSetDefaultBumps() {
        try (Authz env = new Authz(db("cas.db"), this)) {
            long before = env.revision();
            env.auth.setDefaultIfCurrent(env.land, ProtectionActionType.BLOCK_BREAK,
                    PermissionState.INHERIT, before, PermissionState.DENY, env.owner, now())
                    .toCompletableFuture().join();
            assertEquals(before + 1, env.revision());
        }
    }

    @Test
    void bindLandBumps() {
        try (Authz env = new Authz(db("bind.db"), this)) {
            UUID profile = env.profile();
            long before = env.revision();
            env.bindings.bindLand(env.owner, env.land, playerSubject(UUID.randomUUID()),
                    profile, env.owner, now()).toCompletableFuture().join();
            assertEquals(before + 1, env.revision());
        }
    }

    @Test
    void unbindLandBumps() {
        try (Authz env = new Authz(db("unbind.db"), this)) {
            UUID profile = env.profile();
            UUID target = UUID.randomUUID();
            env.bindings.bindLand(env.owner, env.land, playerSubject(target),
                    profile, env.owner, now()).toCompletableFuture().join();
            long before = env.revision();
            env.bindings.unbindLand(env.owner, env.land, playerSubject(target),
                    env.owner, now()).toCompletableFuture().join();
            assertEquals(before + 1, env.revision());
        }
    }

    @Test
    void addMemberBumpsReferencingLand() {
        try (Authz env = new Authz(db("addmember.db"), this)) {
            UUID profile = env.profile();
            UUID group = env.group();
            env.bindings.bindLand(env.owner, env.land, groupSubject(group),
                    profile, env.owner, now()).toCompletableFuture().join();
            long before = env.revision();
            env.groups.addMember(env.owner, group, UUID.randomUUID(), env.owner, now())
                    .toCompletableFuture().join();
            assertEquals(before + 1, env.revision());
        }
    }

    @Test
    void removeMemberBumpsReferencingLand() {
        try (Authz env = new Authz(db("removemember.db"), this)) {
            UUID profile = env.profile();
            UUID group = env.group();
            UUID member = UUID.randomUUID();
            env.bindings.bindLand(env.owner, env.land, groupSubject(group),
                    profile, env.owner, now()).toCompletableFuture().join();
            env.groups.addMember(env.owner, group, member, env.owner, now())
                    .toCompletableFuture().join();
            long before = env.revision();
            env.groups.removeMember(env.owner, group, member, env.owner, now())
                    .toCompletableFuture().join();
            assertEquals(before + 1, env.revision());
        }
    }

    @Test
    void forceDeleteGroupBumpsReferencingLand() {
        try (Authz env = new Authz(db("forcedeletegroup.db"), this)) {
            UUID profile = env.profile();
            UUID group = env.group();
            env.bindings.bindLand(env.owner, env.land, groupSubject(group),
                    profile, env.owner, now()).toCompletableFuture().join();
            long before = env.revision();
            env.groups.forceDelete(env.owner, group, env.owner, now())
                    .toCompletableFuture().join();
            assertEquals(before + 1, env.revision());
        }
    }

    @Test
    void setEntryBumpsReferencingLand() {
        try (Authz env = new Authz(db("setentry.db"), this)) {
            UUID profile = env.profile();
            env.bindings.bindLand(env.owner, env.land, playerSubject(UUID.randomUUID()),
                    profile, env.owner, now()).toCompletableFuture().join();
            long before = env.revision();
            env.profiles.setEntry(env.owner, profile, ProtectionActionType.BLOCK_BREAK,
                    PermissionState.ALLOW, env.owner, now()).toCompletableFuture().join();
            assertEquals(before + 1, env.revision());
        }
    }

    @Test
    void forceDeleteProfileBumpsReferencingLand() {
        try (Authz env = new Authz(db("forcedeleteprofile.db"), this)) {
            UUID profile = env.profile();
            env.bindings.bindLand(env.owner, env.land, playerSubject(UUID.randomUUID()),
                    profile, env.owner, now()).toCompletableFuture().join();
            long before = env.revision();
            env.profiles.forceDelete(env.owner, profile, env.owner, now())
                    .toCompletableFuture().join();
            assertEquals(before + 1, env.revision());
        }
    }
}

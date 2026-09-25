package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.binding.LandBindingService;
import com.smile.chunkland.group.SubjectGroupService;
import com.smile.chunkland.profile.PermissionProfileService;
import com.smile.chunkland.protection.LandAuthorisationCache;
import com.smile.chunkland.trust.LandAuthorisationService;
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
 * Every authorisation write is followed by a refresh that publishes a
 * fresh durable generation map, so a revision pinned from the published
 * authorisation snapshot can never go permanently stale: the direct
 * refresh path and the generic refresh path both reload the map.
 */
class AuthorisationRevisionPublishTest {

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

    private long durableRevision(PersistenceStore store, LandId land) {
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

    private static final class Stores implements AutoCloseable {
        final PersistenceStore store;
        final SqliteLandRepository lands;
        final LandAuthorisationRepository auth;
        final LandBindingRepository bindings;
        final SubjectGroupRepository groups;
        final PermissionProfileRepository profiles;
        final LandAuthorisationCache cache = new LandAuthorisationCache();
        final UUID world = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final LandId land = new LandId(UUID.randomUUID());

        Stores(Path db, AuthorisationRevisionPublishTest outer) {
            store = PersistenceStore.open(db);
            lands = new SqliteLandRepository(store);
            auth = new LandAuthorisationRepository(store);
            bindings = new LandBindingRepository(store);
            groups = new SubjectGroupRepository(store);
            profiles = new PermissionProfileRepository(store);
            lands.save(outer.land(world, land, OwnerRef.player(owner)))
                    .toCompletableFuture().join();
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
    void directWriteRefreshPublishesFreshRevision() {
        try (Stores env = new Stores(db("direct.db"), this)) {
            LandAuthorisationService service =
                    new LandAuthorisationService(env.auth, env.cache);
            env.cache.publish(com.smile.chunkland.protection.LandAuthorisationSnapshot.empty());

            service.trust(env.owner, env.land, UUID.randomUUID())
                    .toCompletableFuture().join();

            assertEquals(durableRevision(env.store, env.land),
                    env.cache.snapshot().policyRevision(env.land).orElseThrow(),
                    "the direct refresh must publish the post-commit generation");
        }
    }

    @Test
    void bindingWriteRefreshPublishesFreshRevision() {
        try (Stores env = new Stores(db("binding.db"), this)) {
            LandBindingService service = new LandBindingService(env.bindings, env.cache);
            UUID profile = env.profile();
            env.cache.publish(com.smile.chunkland.protection.LandAuthorisationSnapshot.empty());

            service.bindLand(env.owner, env.land, playerSubject(UUID.randomUUID()), profile)
                    .toCompletableFuture().join();

            assertEquals(durableRevision(env.store, env.land),
                    env.cache.snapshot().policyRevision(env.land).orElseThrow(),
                    "the generic refresh must publish the post-commit generation");
        }
    }

    @Test
    void groupWriteRefreshPublishesFreshRevision() {
        try (Stores env = new Stores(db("group.db"), this)) {
            LandBindingService bindingService =
                    new LandBindingService(env.bindings, env.cache);
            SubjectGroupService groups =
                    new SubjectGroupService(env.groups, bindingService::refresh);
            UUID profile = env.profile();
            UUID group = env.group();
            bindingService.bindLand(env.owner, env.land, groupSubject(group), profile)
                    .toCompletableFuture().join();
            env.cache.publish(com.smile.chunkland.protection.LandAuthorisationSnapshot.empty());

            groups.addMember(env.owner, group, UUID.randomUUID())
                    .toCompletableFuture().join();

            assertEquals(durableRevision(env.store, env.land),
                    env.cache.snapshot().policyRevision(env.land).orElseThrow(),
                    "the group refresh hook must publish the post-commit generation");
            assertTrue(env.cache.snapshot().policyRevision(env.land).orElseThrow() > 0L);
        }
    }

    @Test
    void profileWriteRefreshPublishesFreshRevision() {
        try (Stores env = new Stores(db("profile.db"), this)) {
            LandBindingService bindingService =
                    new LandBindingService(env.bindings, env.cache);
            PermissionProfileService profiles =
                    new PermissionProfileService(env.profiles, bindingService::refresh);
            UUID profile = env.profile();
            bindingService.bindLand(env.owner, env.land, playerSubject(UUID.randomUUID()),
                    profile).toCompletableFuture().join();
            env.cache.publish(com.smile.chunkland.protection.LandAuthorisationSnapshot.empty());

            profiles.setEntry(env.owner, profile.toString(), "BLOCK_BREAK", "ALLOW")
                    .toCompletableFuture().join();

            assertEquals(durableRevision(env.store, env.land),
                    env.cache.snapshot().policyRevision(env.land).orElseThrow(),
                    "the profile refresh hook must publish the post-commit generation");
        }
    }
}

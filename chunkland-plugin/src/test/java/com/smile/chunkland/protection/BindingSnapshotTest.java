package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.PermissionResolver;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.binding.LandBindingService;
import com.smile.chunkland.persistence.LandAuthorisationRepository;
import com.smile.chunkland.persistence.LandBindingRepository;
import com.smile.chunkland.persistence.PermissionProfileRepository;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.persistence.SqliteSubLandRepository;
import com.smile.chunkland.persistence.SubjectGroupRepository;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generic binding snapshot flow: profile ALLOW/DENY rows and group
 * membership reach immutable decision contexts, group bindings stay visible
 * to members only, one layer aggregates DENY-first, ENTRY bans still win,
 * and subland bindings decide ahead of the land chain with INHERIT fallback.
 */
class BindingSnapshotTest {

    @TempDir Path tmp;

    private Instant now() {
        return Instant.now();
    }

    private record World(UUID worldId, LandId landId, UUID owner, SubLandId sublandId,
            PersistenceStore store, LandBindingRepository bindings, LandBindingService service,
            LandRegistryStore registries) {
    }

    private World world(String name) {
        PersistenceStore store = PersistenceStore.open(tmp.resolve(name));
        SqliteLandRepository lands = new SqliteLandRepository(store);
        LandBindingRepository bindings = new LandBindingRepository(store);
        UUID worldId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        SubLandId sublandId = new SubLandId(UUID.randomUUID());
        SubLandSnapshot sub = new SubLandSnapshot(sublandId, landId, "Den",
                new Cuboid(0, 0, 0, 15, 255, 15), worldId);
        lands.save(new LandSnapshot(landId, "Home", "home", OwnerRef.player(owner), worldId,
                Set.of(new ChunkKey(worldId, 0, 0)), List.of(sub),
                0, 0, Instant.now(), Instant.now())).toCompletableFuture().join();
        new SqliteSubLandRepository(store).save(sub).toCompletableFuture().join();
        LandAuthorisationCache cache = new LandAuthorisationCache();
        LandBindingService service = new LandBindingService(bindings, cache);
        LandRegistryStore registries = new LandRegistryStore();
        registries.publish(LandRegistry.from(List.of(
                new LandSnapshot(landId, "Home", "home", OwnerRef.player(owner), worldId,
                        Set.of(new ChunkKey(worldId, 0, 0)), List.of(sub),
                        0, 0, Instant.now(), Instant.now()))));
        return new World(worldId, landId, owner, sublandId, store, bindings, service,
                registries);
    }

    private UUID profileWith(World w, String name, ProtectionActionType action,
            PermissionState state) {
        PermissionProfileRepository profiles = new PermissionProfileRepository(w.store());
        UUID profile = profiles.create(w.owner(), name, w.owner(), now())
                .toCompletableFuture().join().id();
        profiles.setEntry(w.owner(), profile, action, state, w.owner(), now())
                .toCompletableFuture().join();
        return profile;
    }

    private UUID groupWith(World w, String name, UUID member) {
        SubjectGroupRepository groups = new SubjectGroupRepository(w.store());
        UUID group = groups.create(w.owner(), name, w.owner(), now())
                .toCompletableFuture().join().id();
        groups.addMember(w.owner(), group, member, w.owner(), now())
                .toCompletableFuture().join();
        return group;
    }

    private SnapshotPermissionContextProvider provider(World w) {
        return new SnapshotPermissionContextProvider(null,
                new ConfigSubjectPermissionLookup(
                        PermissionDefaultsSnapshot::empty, w.service().cache()::snapshot));
    }

    private PermissionState decideLand(World w, UUID actor, ProtectionActionType action) {
        return PermissionResolver.resolve(provider(w).provide(actor, w.landId(), action,
                w.registries().snapshot())).outcome();
    }

    private PermissionState decideAt(World w, UUID actor, int x, int y, int z,
            ProtectionActionType action) {
        return PermissionResolver.resolve(provider(w).provideAtBlock(actor, w.landId(),
                x, y, z, action, w.registries().snapshot())).outcome();
    }

    /**
     * Publish both snapshot halves the way production startup does: the
     * generic binding load and the direct-trust load merge into one cached
     * snapshot, and only the pair together reads as loaded.
     */
    private void publishAll(World w) {
        w.service().refresh().toCompletableFuture().join();
        new com.smile.chunkland.trust.LandAuthorisationService(
                new LandAuthorisationRepository(w.store()),
                w.service().cache()).refresh().toCompletableFuture().join();
    }

    @Test
    void playerAllowIsVisibleToSubjectOnly() {
        World w = world("player-allow.db");
        try (PersistenceStore ignored = w.store()) {
            UUID target = UUID.randomUUID();
            UUID profile = profileWith(w, "Crew", ProtectionActionType.BLOCK_BREAK,
                    PermissionState.ALLOW);
            w.bindings().bindLand(w.owner(), w.landId(),
                    new LandBindingRepository.Subject(
                            LandBindingRepository.SubjectKind.PLAYER, target),
                    profile, w.owner(), now()).toCompletableFuture().join();
            publishAll(w);

            assertEquals(PermissionState.ALLOW,
                    decideLand(w, target, ProtectionActionType.BLOCK_BREAK));
            assertEquals(PermissionState.DENY,
                    decideLand(w, UUID.randomUUID(), ProtectionActionType.BLOCK_BREAK));
            assertEquals(PermissionState.DENY,
                    decideLand(w, target, ProtectionActionType.BLOCK_PLACE));
        }
    }

    @Test
    void groupBindingIsVisibleToMembersOnly() {
        World w = world("group-member.db");
        try (PersistenceStore ignored = w.store()) {
            UUID member = UUID.randomUUID();
            UUID group = groupWith(w, "Crew", member);
            UUID profile = profileWith(w, "Crew", ProtectionActionType.CONTAINER_OPEN,
                    PermissionState.ALLOW);
            w.bindings().bindLand(w.owner(), w.landId(),
                    new LandBindingRepository.Subject(
                            LandBindingRepository.SubjectKind.GROUP, group),
                    profile, w.owner(), now()).toCompletableFuture().join();
            publishAll(w);

            assertEquals(PermissionState.ALLOW,
                    decideLand(w, member, ProtectionActionType.CONTAINER_OPEN));
            assertEquals(PermissionState.DENY,
                    decideLand(w, UUID.randomUUID(), ProtectionActionType.CONTAINER_OPEN));
        }
    }

    @Test
    void profileDenyDeniesAndBeatsPlayerAllowFlat() {
        World w = world("deny-first.db");
        try (PersistenceStore ignored = w.store()) {
            UUID target = UUID.randomUUID();
            UUID allow = profileWith(w, "Day", ProtectionActionType.BLOCK_PLACE,
                    PermissionState.ALLOW);
            UUID deny = profileWith(w, "Night", ProtectionActionType.BLOCK_PLACE,
                    PermissionState.DENY);
            UUID group = groupWith(w, "Crew", target);
            w.bindings().bindLand(w.owner(), w.landId(),
                    new LandBindingRepository.Subject(
                            LandBindingRepository.SubjectKind.PLAYER, target),
                    allow, w.owner(), now()).toCompletableFuture().join();
            w.bindings().bindLand(w.owner(), w.landId(),
                    new LandBindingRepository.Subject(
                            LandBindingRepository.SubjectKind.GROUP, group),
                    deny, w.owner(), now()).toCompletableFuture().join();
            publishAll(w);

            assertEquals(PermissionState.DENY,
                    decideLand(w, target, ProtectionActionType.BLOCK_PLACE));
        }
    }

    @Test
    void entryBanDenyBeatsGroupAllow() {
        World w = world("ban-beats.db");
        try (PersistenceStore ignored = w.store()) {
            UUID member = UUID.randomUUID();
            UUID group = groupWith(w, "Crew", member);
            UUID profile = profileWith(w, "Crew", ProtectionActionType.ENTRY,
                    PermissionState.ALLOW);
            w.bindings().bindLand(w.owner(), w.landId(),
                    new LandBindingRepository.Subject(
                            LandBindingRepository.SubjectKind.GROUP, group),
                    profile, w.owner(), now()).toCompletableFuture().join();
            new LandAuthorisationRepository(w.store())
                    .ban(w.landId(), member, w.owner(), now()).toCompletableFuture().join();
            publishAll(w);

            assertEquals(PermissionState.DENY, decideLand(w, member, ProtectionActionType.ENTRY));        }
    }

    @Test
    void directTrustStaysOnItsOwnPathAndGenericSkipsIt() {
        World w = world("direct-isolation.db");
        try (PersistenceStore ignored = w.store()) {
            UUID trusted = UUID.randomUUID();
            new LandAuthorisationRepository(w.store())
                    .trust(w.landId(), trusted, w.owner(), now()).toCompletableFuture().join();
            publishAll(w);

            assertEquals(PermissionState.ALLOW,
                    decideLand(w, trusted, ProtectionActionType.BLOCK_BREAK));
            assertEquals(PermissionState.DENY,
                    decideLand(w, UUID.randomUUID(), ProtectionActionType.BLOCK_BREAK));
        }
    }

    @Test
    void sublandBindingDecidesFirstThenFallsBackToLand() {
        World w = world("subland-priority.db");
        try (PersistenceStore ignored = w.store()) {
            UUID target = UUID.randomUUID();
            UUID landProfile = profileWith(w, "Day", ProtectionActionType.BLOCK_BREAK,
                    PermissionState.ALLOW);
            UUID subProfile = profileWith(w, "Night", ProtectionActionType.BLOCK_BREAK,
                    PermissionState.DENY);
            w.bindings().bindLand(w.owner(), w.landId(),
                    new LandBindingRepository.Subject(
                            LandBindingRepository.SubjectKind.PLAYER, target),
                    landProfile, w.owner(), now()).toCompletableFuture().join();
            w.bindings().bindSubland(w.owner(), w.sublandId(),
                    new LandBindingRepository.Subject(
                            LandBindingRepository.SubjectKind.PLAYER, target),
                    subProfile, w.owner(), now()).toCompletableFuture().join();
            publishAll(w);

            assertEquals(PermissionState.DENY, decideAt(w, target, 1, 64, 1,
                    ProtectionActionType.BLOCK_BREAK));
            assertEquals(PermissionState.ALLOW, decideAt(w, target, 100, 64, 100,
                    ProtectionActionType.BLOCK_BREAK));
            assertEquals(PermissionState.ALLOW,
                    decideLand(w, target, ProtectionActionType.BLOCK_BREAK));
        }
    }

    @Test
    void unloadedSnapshotFailsClosedForBindings() {
        World w = world("unloaded.db");
        try (PersistenceStore ignored = w.store()) {
            UUID target = UUID.randomUUID();
            UUID profile = profileWith(w, "Crew", ProtectionActionType.BLOCK_BREAK,
                    PermissionState.ALLOW);
            w.bindings().bindLand(w.owner(), w.landId(),
                    new LandBindingRepository.Subject(
                            LandBindingRepository.SubjectKind.PLAYER, target),
                    profile, w.owner(), now()).toCompletableFuture().join();

            assertEquals(PermissionState.DENY,
                    decideLand(w, target, ProtectionActionType.BLOCK_BREAK));
            assertEquals(PermissionState.DENY, decideAt(w, target, 1, 64, 1,
                    ProtectionActionType.BLOCK_BREAK));
        }
    }

    @Test
    void unbindAndRefreshFlipsDecisionBack() {
        World w = world("unbind-flip.db");
        try (PersistenceStore ignored = w.store()) {
            UUID target = UUID.randomUUID();
            UUID profile = profileWith(w, "Crew", ProtectionActionType.DOOR_USE,
                    PermissionState.ALLOW);
            LandBindingRepository.Subject subject = new LandBindingRepository.Subject(
                    LandBindingRepository.SubjectKind.PLAYER, target);
            w.service().bindLand(w.owner(), w.landId(), subject, profile)
                    .toCompletableFuture().join();
            publishAll(w);
            assertEquals(PermissionState.ALLOW, decideLand(w, target, ProtectionActionType.DOOR_USE));

            w.service().unbindLand(w.owner(), w.landId(), subject).toCompletableFuture().join();
            assertEquals(PermissionState.DENY, decideLand(w, target, ProtectionActionType.DOOR_USE));
        }
    }
}

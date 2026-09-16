package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Server namespace isolation for the direct authorisation mutations.
 *
 * <p>Trust, untrust, land defaults and ENTRY bans are player-namespace
 * concepts: their durable rows key profiles by {@code PLAYER:<uuid>}, so a
 * {@code SERVER} land row must never gain one. The ban path already refuses
 * Server Land; trust, untrust, defaults and unbans must fail the same way so
 * no production path can smuggle a {@code SERVER} owner into a player
 * namespace (or vice versa).
 */
class ServerLandAuthorisationIsolationTest {

    @TempDir Path tmp;

    private Path db() {
        return tmp.resolve("auth-" + UUID.randomUUID() + ".db");
    }

    private LandSnapshot land(UUID worldId, LandId id, OwnerRef owner) {
        return new LandSnapshot(id, "Home", "home", owner, worldId,
                Set.of(new ChunkKey(worldId, 0, 0)), List.of(),
                0, 0, Instant.now(), Instant.now());
    }

    private LandId saveServerLand(PersistenceStore store, SqliteLandRepository lands, UUID world) {
        LandId land = new LandId(UUID.randomUUID());
        lands.save(land(world, land, OwnerRef.server())).toCompletableFuture().join();
        return land;
    }

    @Test
    void trustOnServerLandFailsClosed() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            LandId land = saveServerLand(store, lands, world);
            UUID actor = UUID.randomUUID();
            UUID target = UUID.randomUUID();

            assertThrows(RuntimeException.class,
                    () -> repo.trust(land, target, actor, Instant.now())
                            .toCompletableFuture().join(),
                    "trust on Server Land must fail closed");
        }
    }

    @Test
    void untrustOnServerLandFailsClosed() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            LandId land = saveServerLand(store, lands, world);

            assertThrows(RuntimeException.class,
                    () -> repo.untrust(land, UUID.randomUUID(), UUID.randomUUID(), Instant.now())
                            .toCompletableFuture().join(),
                    "untrust on Server Land must fail closed");
        }
    }

    @Test
    void defaultOnServerLandFailsClosed() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            LandId land = saveServerLand(store, lands, world);

            assertThrows(RuntimeException.class,
                    () -> repo.setDefault(land, ProtectionActionType.ENTRY,
                                    PermissionState.ALLOW, UUID.randomUUID(), Instant.now())
                            .toCompletableFuture().join(),
                    "land default on Server Land must fail closed");
        }
    }

    @Test
    void unbanOnServerLandFailsClosed() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            LandId land = saveServerLand(store, lands, world);

            assertThrows(RuntimeException.class,
                    () -> repo.unban(land, UUID.randomUUID(), UUID.randomUUID(), Instant.now())
                            .toCompletableFuture().join(),
                    "unban on Server Land must fail closed like ban does");
        }
    }

    @Test
    void trustOnPlayerLandStillSucceeds() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();

            UUID profileId = repo.trust(land, target, owner, Instant.now())
                    .toCompletableFuture().join();

            assertEquals("PLAYER:" + target, store.execute(conn -> {
                try (java.sql.PreparedStatement ps = conn.prepareStatement(
                        "SELECT owner_key FROM permission_profiles WHERE id = ?")) {
                    ps.setBytes(1, UuidBlob.encode(profileId));
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getString(1);
                    }
                }
            }));
        }
    }
}

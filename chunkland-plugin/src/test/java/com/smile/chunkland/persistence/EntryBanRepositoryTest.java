package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-land ENTRY bans: durable rows in their own table, atomic audit and
 * revision, idempotent resends, owner/Server Land guards, and restart
 * persistence. Bans never touch trust bindings or land defaults.
 */
class EntryBanRepositoryTest {

    @TempDir Path tmp;

    private Path db() {
        return tmp.resolve("bans.db");
    }

    private LandSnapshot land(UUID worldId, LandId id, OwnerRef owner, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "Home", "home", owner, worldId,
                Set.of(new ChunkKey(worldId, chunkX, chunkZ)), List.of(),
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

    private String auditAction(PersistenceStore store, LandId land) {
        return store.execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT action FROM audit_log WHERE land_id = ? ORDER BY id DESC LIMIT 1")) {
                ps.setBytes(1, UuidBlob.encode(land.value()));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    @Test
    void schemaUpgradesToV4WithEntryBanTable() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            assertEquals(4, store.schemaVersion());
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'land_entry_bans'"));
        }
    }

    @Test
    void banPersistsRowAuditsEntryBanAndBumpsRevision() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner), 0, 0))
                    .toCompletableFuture().join();
            long before = revision(store, land);

            repo.ban(land, target, owner, now()).toCompletableFuture().join();

            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_entry_bans WHERE land_id = ? AND player_uuid = ?",
                    land.value(), target));
            assertEquals("ENTRY_BAN", auditAction(store, land));
            assertTrue(revision(store, land) > before, "ban must bump the land policy revision");
        }
    }

    @Test
    void banIsIdempotentAndKeepsASingleRow() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner), 0, 0))
                    .toCompletableFuture().join();

            repo.ban(land, target, owner, now()).toCompletableFuture().join();
            repo.ban(land, target, owner, now()).toCompletableFuture().join();

            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_entry_bans WHERE land_id = ? AND player_uuid = ?",
                    land.value(), target));
        }
    }

    @Test
    void unbanRemovesOnlyTheBanRowAndKeepsTrust() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner), 0, 0))
                    .toCompletableFuture().join();
            repo.trust(land, target, owner, now()).toCompletableFuture().join();
            repo.ban(land, target, owner, now()).toCompletableFuture().join();

            repo.unban(land, target, owner, now()).toCompletableFuture().join();

            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM land_entry_bans WHERE land_id = ? AND player_uuid = ?",
                    land.value(), target));
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_bindings WHERE land_id = ? AND subject_type = 'PLAYER'",
                    land.value()),
                    "unban must never delete the trust binding");
            assertEquals("ENTRY_UNBAN", auditAction(store, land));
        }
    }

    @Test
    void unbanWithoutBanSucceedsSafely() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner), 0, 0))
                    .toCompletableFuture().join();

            repo.unban(land, target, owner, now()).toCompletableFuture().join();

            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM land_entry_bans WHERE land_id = ?",
                    land.value()));
            assertEquals("ENTRY_UNBAN", auditAction(store, land));
        }
    }

    @Test
    void banLandOwnerFailsClosedWithZeroSideEffects() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner), 0, 0))
                    .toCompletableFuture().join();
            long before = revision(store, land);

            assertThrows(Exception.class,
                    () -> repo.ban(land, owner, owner, now()).toCompletableFuture().join());

            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM land_entry_bans WHERE land_id = ?",
                    land.value()));
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM audit_log WHERE land_id = ? AND action = 'ENTRY_BAN'",
                    land.value()));
            assertEquals(before, revision(store, land));
        }
    }

    @Test
    void banOnServerLandFailsClosedWithZeroSideEffects() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            UUID steward = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.server(), 0, 0))
                    .toCompletableFuture().join();
            long before = revision(store, land);

            assertThrows(Exception.class,
                    () -> repo.ban(land, target, steward, now()).toCompletableFuture().join());

            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM land_entry_bans WHERE land_id = ?",
                    land.value()));
            assertEquals(before, revision(store, land));
        }
    }

    @Test
    void banUnknownLandFailsClosed() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            LandId unknown = new LandId(UUID.randomUUID());

            assertThrows(CompletionException.class,
                    () -> repo.ban(unknown, UUID.randomUUID(), UUID.randomUUID(), now())
                            .toCompletableFuture().join());
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM land_entry_bans WHERE land_id = ?",
                    unknown.value()));
        }
    }

    @Test
    void banFailureRollsBackWithoutPartialRows() {
        Path path = tmp.resolve("ban-atomic.db");
        try (PersistenceStore store = PersistenceStore.open(path)) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner), 0, 0))
                    .toCompletableFuture().join();
            long before = revision(store, land);
            LandAuthorisationRepository failing = new LandAuthorisationRepository(store, step -> {
                if (step == LandAuthorisationRepository.Step.AFTER_BAN) {
                    throw new RuntimeException("injected ban failure");
                }
            });

            assertThrows(Exception.class,
                    () -> failing.ban(land, target, owner, now()).toCompletableFuture().join());

            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM land_entry_bans WHERE land_id = ?",
                    land.value()));
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM audit_log WHERE land_id = ? AND action = 'ENTRY_BAN'",
                    land.value()));
            assertEquals(before, revision(store, land));
        }
    }

    @Test
    void restartReloadsBansIntoSnapshot() {
        Path path = tmp.resolve("ban-restart.db");
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        try (PersistenceStore store = PersistenceStore.open(path)) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            lands.save(land(world, land, OwnerRef.player(owner), 0, 0))
                    .toCompletableFuture().join();
            repo.trust(land, target, owner, now()).toCompletableFuture().join();
            repo.ban(land, target, owner, now()).toCompletableFuture().join();
        }
        try (PersistenceStore reopened = PersistenceStore.open(path)) {
            LandAuthorisationRepository.SnapshotData data =
                    new LandAuthorisationRepository(reopened).loadSnapshotData()
                            .toCompletableFuture().join();
            assertTrue(data.entryBans().getOrDefault(land, Set.of()).contains(target),
                    "restart must reload the durable ban into the snapshot");
            assertTrue(data.directAllows().getOrDefault(land, java.util.Map.of())
                    .getOrDefault(target, Set.of()).contains(
                            com.smile.chunkland.api.permission.ProtectionActionType.ENTRY),
                    "restart must keep the trust ALLOW next to the ban");
        }
    }
}

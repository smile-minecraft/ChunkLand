package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.runtime.storage.OrphanWorldGuard;
import com.smile.chunkland.runtime.storage.WorldCatalogSnapshot;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Red contract for the orphan-world purge repository.
 *
 * <p>An orphan world is a {@code world_uuid} with durable land rows but no
 * entry in the server world catalog. Listing isolates those worlds with
 * bounded counters; purging removes exactly one world's Land-owned rows in a
 * single transaction together with one {@code ORPHAN_PURGE} audit row. Healthy
 * worlds, the player-namespace group/profile rows, the operation ledger and
 * any Economy state are never touched, and no refund is ever issued.
 */
class OrphanPurgeRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    @TempDir java.nio.file.Path temp;

    private PersistenceStore db() {
        return PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
    }

    private static LandSnapshot land(UUID world, LandId lid, OwnerRef owner, ChunkKey chunk) {
        com.smile.chunkland.api.land.LandName name =
                com.smile.chunkland.api.land.LandName.of("Home " + chunk.chunkX());
        return new LandSnapshot(lid, name.displayName(), name.nameKey(), owner, world, Set.of(chunk),
                List.of(), 4, 0, NOW, NOW);
    }

    private int countAll(PersistenceStore store, String table) {
        return store.execute(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rows.next();
                return rows.getInt(1);
            }
        });
    }

    private int countWorld(PersistenceStore store, String table, String column, UUID world) {
        return store.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?")) {
                statement.setBytes(1, UuidBlob.encode(world));
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getInt(1);
                }
            }
        });
    }

    /** Seeds one land with a chunk, a subland and one row in every Land-owned table. */
    private LandId seedOwnedLand(PersistenceStore store, UUID world, OwnerRef owner,
            int chunkX, UUID groupId, UUID profileId) throws Exception {
        SqliteLandRepository lands = new SqliteLandRepository(store);
        SqliteChunkRepository chunks = new SqliteChunkRepository(store);
        SqliteSubLandRepository subs = new SqliteSubLandRepository(store);
        LandId land = new LandId(UUID.randomUUID());
        ChunkKey key = new ChunkKey(world, chunkX, 0);
        lands.save(land(world, land, owner, key)).toCompletableFuture().join();
        chunks.addChunk(land, key, 64, UUID.randomUUID(), 100L).toCompletableFuture().join();
        SubLandId sub = new SubLandId(UUID.randomUUID());
        int baseX = chunkX * 16;
        subs.save(new SubLandSnapshot(sub, land, "den",
                new Cuboid(baseX, 60, 0, baseX + 15, 70, 15), world)).toCompletableFuture().join();
        UUID banned = UUID.randomUUID();
        store.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO land_bindings (land_id, subject_type, subject_id, profile_id)"
                            + " VALUES (?, ?, ?, ?)")) {
                statement.setBytes(1, UuidBlob.encode(land.value()));
                statement.setString(2, "GROUP");
                statement.setBytes(3, UuidBlob.encode(groupId));
                statement.setBytes(4, UuidBlob.encode(profileId));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO subland_bindings (subland_id, subject_type, subject_id, profile_id)"
                            + " VALUES (?, ?, ?, ?)")) {
                statement.setBytes(1, UuidBlob.encode(sub.value()));
                statement.setString(2, "GROUP");
                statement.setBytes(3, UuidBlob.encode(groupId));
                statement.setBytes(4, UuidBlob.encode(profileId));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO land_defaults (land_id, permission, state) VALUES (?, ?, ?)")) {
                statement.setBytes(1, UuidBlob.encode(land.value()));
                statement.setString(2, "BLOCK_BREAK");
                statement.setString(3, "DENY");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO subland_defaults (subland_id, permission, state) VALUES (?, ?, ?)")) {
                statement.setBytes(1, UuidBlob.encode(sub.value()));
                statement.setString(2, "BLOCK_BREAK");
                statement.setString(3, "ALLOW");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO land_rules (land_id, rule_type, state) VALUES (?, ?, ?)")) {
                statement.setBytes(1, UuidBlob.encode(land.value()));
                statement.setString(2, "FIRE_SPREAD");
                statement.setString(3, "DENY");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO subland_rules (subland_id, rule_type, state) VALUES (?, ?, ?)")) {
                statement.setBytes(1, UuidBlob.encode(sub.value()));
                statement.setString(2, "FIRE_SPREAD");
                statement.setString(3, "ALLOW");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO land_entry_bans (land_id, player_uuid, banned_at, banned_by)"
                            + " VALUES (?, ?, ?, ?)")) {
                statement.setBytes(1, UuidBlob.encode(land.value()));
                statement.setBytes(2, UuidBlob.encode(banned));
                statement.setLong(3, NOW.toEpochMilli());
                statement.setBytes(4, UuidBlob.encode(owner.key().startsWith("PLAYER:")
                        ? UUID.fromString(owner.key().substring("PLAYER:".length())) : UUID.randomUUID()));
                statement.executeUpdate();
            }
            return null;
        });
        return land;
    }

    private void seedNamespace(PersistenceStore store, String ownerKey, UUID groupId, UUID profileId) {
        store.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO subject_groups (id, owner_key, name_key, display_name, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)")) {
                statement.setBytes(1, UuidBlob.encode(groupId));
                statement.setString(2, ownerKey);
                statement.setString(3, "friends");
                statement.setString(4, "Friends");
                statement.setLong(5, NOW.toEpochMilli());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO permission_profiles (id, owner_key, name_key, display_name, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)")) {
                statement.setBytes(1, UuidBlob.encode(profileId));
                statement.setString(2, ownerKey);
                statement.setString(3, "member");
                statement.setString(4, "Member");
                statement.setLong(5, NOW.toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static Throwable root(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Guard published with the given loaded set, mimicking a verified catalog. */
    private static OrphanWorldGuard guardWith(Set<UUID> loaded) {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        guard.publish(loaded);
        return guard;
    }

    @Test
    void listShowsOnlyWorldsAbsentFromTheLoadedSet() throws Exception {
        try (PersistenceStore store = db()) {
            UUID healthy = UUID.randomUUID();
            UUID orphan = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            seedNamespace(store, owner.key(), groupId, profileId);
            seedOwnedLand(store, orphan, owner, 1, groupId, profileId);
            seedOwnedLand(store, orphan, owner, 2, groupId, profileId);
            seedOwnedLand(store, healthy, owner, 3, groupId, profileId);

            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            List<OrphanPurgeRepository.OrphanWorldSummary> rows = repos
                    .listOrphanSummaries(Set.of(healthy), 20)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(1, rows.size());
            assertEquals(orphan, rows.get(0).worldId());
            assertEquals(2, rows.get(0).landCount());
        }
    }

    @Test
    void listIsEmptyWhenEveryWorldIsLoaded() throws Exception {
        try (PersistenceStore store = db()) {
            UUID healthy = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            seedNamespace(store, owner.key(), groupId, profileId);
            seedOwnedLand(store, healthy, owner, 1, groupId, profileId);

            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            List<OrphanPurgeRepository.OrphanWorldSummary> rows = repos
                    .listOrphanSummaries(Set.of(healthy), 20)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertTrue(rows.isEmpty());
        }
    }

    @Test
    void listIsBoundedByTheLimit() throws Exception {
        try (PersistenceStore store = db()) {
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            seedNamespace(store, owner.key(), groupId, profileId);
            for (int i = 0; i < 25; i++) {
                seedOwnedLand(store, UUID.randomUUID(), owner, i, groupId, profileId);
            }

            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            List<OrphanPurgeRepository.OrphanWorldSummary> rows = repos
                    .listOrphanSummaries(Set.of(), 20)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(20, rows.size());
        }
    }

    @Test
    void purgeDeletesOneWorldAndWritesTheAuditAtomically() throws Exception {
        try (PersistenceStore store = db()) {
            UUID orphan = UUID.randomUUID();
            UUID healthy = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            UUID operator = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            seedNamespace(store, owner.key(), groupId, profileId);
            LandId orphanA = seedOwnedLand(store, orphan, owner, 1, groupId, profileId);
            LandId orphanB = seedOwnedLand(store, orphan, owner, 5, groupId, profileId);
            LandId kept = seedOwnedLand(store, healthy, owner, 9, groupId, profileId);

            OperationLedger ledger = new OperationLedger(store);
            OperationPayload payload = OperationPayload.claim(UUID.randomUUID(), actor, healthy, kept,
                    List.of(new OperationPayload.Chunk(new ChunkKey(healthy, 9, 0), 12,
                            UUID.randomUUID(), 100L)),
                    100L, "test-economy", NOW, "Home");
            ledger.create(payload).toCompletableFuture().join();
            SqliteAuditRepository audits = new SqliteAuditRepository(store);

            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            OrphanWorldGuard guard = guardWith(Set.of(healthy));
            OrphanPurgeRepository.OrphanPurgeResult result = repos
                    .purgeOrphanWorld(orphan, operator, guard.snapshot(), NOW, 2, guard)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(orphan, result.worldId());
            assertEquals(2, result.landCount());
            assertEquals(Set.of(orphanA.value(), orphanB.value()),
                    Set.copyOf(result.landIds().stream().map(LandId::value).toList()));

            SqliteLandRepository lands = new SqliteLandRepository(store);
            assertTrue(lands.findById(orphanA).toCompletableFuture().join().isEmpty());
            assertTrue(lands.findById(orphanB).toCompletableFuture().join().isEmpty());
            assertTrue(lands.findById(kept).toCompletableFuture().join().isPresent());
            assertEquals(0, countWorld(store, "lands", "world_uuid", orphan));
            assertEquals(1, countWorld(store, "lands", "world_uuid", healthy));
            assertEquals(0, countWorld(store, "land_chunks", "world_uuid", orphan));
            assertEquals(1, countAll(store, "sublands"), "only the healthy subland survives");
            assertEquals(1, countAll(store, "subland_bindings"),
                    "only the healthy subland binding survives");
            assertEquals(1, countAll(store, "land_bindings"), "healthy land binding survives");
            assertEquals(1, countAll(store, "subland_defaults"),
                    "only the healthy subland default survives");
            assertEquals(1, countAll(store, "subland_rules"),
                    "only the healthy subland rule survives");
            assertEquals(1, countAll(store, "land_defaults"), "healthy land default survives");
            assertEquals(1, countAll(store, "land_rules"), "healthy land rule survives");
            assertEquals(1, countAll(store, "land_entry_bans"), "healthy land ban survives");

            // Player-namespace rows, the ledger and Economy state survive untouched.
            assertEquals(1, countAll(store, "subject_groups"));
            assertEquals(1, countAll(store, "permission_profiles"));
            assertEquals(1, countAll(store, "operation_ledger"));
            assertEquals(0, countAll(store, "economy_transactions"));
            assertEquals("CREATED",
                    ledger.find(payload.operationId()).toCompletableFuture().join().state());

            List<AuditEntry> purgeRows = audits.findByAction(OrphanPurgeRepository.AUDIT_ACTION, 10)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(1, purgeRows.size());
            AuditEntry audit = purgeRows.get(0);
            assertEquals(operator, audit.actor());
            assertEquals(orphan, audit.worldId());
            String metadata = audit.metadataJson();
            assertTrue(metadata.contains(orphan.toString()));
            assertTrue(metadata.contains(operator.toString()));
            assertTrue(metadata.contains("\"count\":2"));
            assertTrue(metadata.contains(orphanA.value().toString()));
            assertTrue(metadata.contains(orphanB.value().toString()));
        }
    }

    @Test
    void purgeUnknownWorldFailsWithZeroSideEffect() throws Exception {
        try (PersistenceStore store = db()) {
            UUID healthy = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            seedNamespace(store, owner.key(), groupId, profileId);
            seedOwnedLand(store, healthy, owner, 1, groupId, profileId);
            SqliteAuditRepository audits = new SqliteAuditRepository(store);
            int auditsBefore = audits.findAll(100, 0).toCompletableFuture().get(10, TimeUnit.SECONDS).size();

            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            OrphanWorldGuard guard = guardWith(Set.of(healthy));
            try {
                repos.purgeOrphanWorld(UUID.randomUUID(), UUID.randomUUID(), guard.snapshot(), NOW, 1, guard)
                        .toCompletableFuture().join();
                fail("unknown world must be rejected");
            } catch (CompletionException expected) {
                assertTrue(root(expected) instanceof OrphanUnknownException,
                        "must fail as unknown, got " + root(expected));
            }

            assertEquals(1, countWorld(store, "lands", "world_uuid", healthy));
            assertEquals(auditsBefore,
                    audits.findAll(100, 0).toCompletableFuture().get(10, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void purgeLoadedWorldFailsWithZeroSideEffect() throws Exception {
        try (PersistenceStore store = db()) {
            UUID world = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            seedNamespace(store, owner.key(), groupId, profileId);
            seedOwnedLand(store, world, owner, 1, groupId, profileId);
            SqliteAuditRepository audits = new SqliteAuditRepository(store);
            int auditsBefore = audits.findAll(100, 0).toCompletableFuture().get(10, TimeUnit.SECONDS).size();

            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            OrphanWorldGuard guard = guardWith(Set.of(world));
            try {
                repos.purgeOrphanWorld(world, UUID.randomUUID(), guard.snapshot(), NOW, 1, guard)
                        .toCompletableFuture().join();
                fail("loaded world must be rejected");
            } catch (CompletionException expected) {
                assertTrue(root(expected) instanceof OrphanConflictException,
                        "must fail as conflict, got " + root(expected));
            }

            assertEquals(1, countWorld(store, "lands", "world_uuid", world));
            assertEquals(auditsBefore,
                    audits.findAll(100, 0).toCompletableFuture().get(10, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void purgeCountMismatchFailsWithZeroSideEffect() throws Exception {
        try (PersistenceStore store = db()) {
            UUID orphan = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            seedNamespace(store, owner.key(), groupId, profileId);
            seedOwnedLand(store, orphan, owner, 1, groupId, profileId);

            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            OrphanWorldGuard guard = guardWith(Set.of());
            try {
                repos.purgeOrphanWorld(orphan, UUID.randomUUID(), guard.snapshot(), NOW, 7, guard)
                        .toCompletableFuture().join();
                fail("stale count must be rejected");
            } catch (CompletionException expected) {
                assertTrue(root(expected) instanceof OrphanConflictException,
                        "must fail as conflict, got " + root(expected));
            }

            assertEquals(1, countWorld(store, "lands", "world_uuid", orphan));
            assertEquals(0, countAll(store, "audit_log"));
        }
    }

    @Test
    void concurrentPurgesLeaveExactlyOneWinner() throws Exception {
        try (PersistenceStore store = db()) {
            UUID orphan = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            seedNamespace(store, owner.key(), groupId, profileId);
            seedOwnedLand(store, orphan, owner, 1, groupId, profileId);
            seedOwnedLand(store, orphan, owner, 2, groupId, profileId);
            SqliteAuditRepository audits = new SqliteAuditRepository(store);

            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            OrphanWorldGuard guard = guardWith(Set.of());
            WorldCatalogSnapshot confirmed = guard.snapshot();
            CompletableFuture<OrphanPurgeRepository.OrphanPurgeResult> first =
                    repos.purgeOrphanWorld(orphan, UUID.randomUUID(), confirmed, NOW, 2, guard)
                            .toCompletableFuture();
            CompletableFuture<OrphanPurgeRepository.OrphanPurgeResult> second =
                    repos.purgeOrphanWorld(orphan, UUID.randomUUID(), confirmed, NOW, 2, guard)
                            .toCompletableFuture();
            int wins = 0;
            for (CompletableFuture<OrphanPurgeRepository.OrphanPurgeResult> attempt
                    : List.of(first, second)) {
                try {
                    attempt.get(10, TimeUnit.SECONDS);
                    wins++;
                } catch (Exception lost) {
                    Throwable cause = root(lost);
                    assertTrue(cause instanceof OrphanUnknownException
                            || cause instanceof OrphanConflictException,
                            "loser must fail as unknown/conflict, got " + cause);
                }
            }
            assertEquals(1, wins, "exactly one concurrent purge must win");
            assertEquals(1, audits.findByAction(OrphanPurgeRepository.AUDIT_ACTION, 10)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).size());
            assertEquals(0, countWorld(store, "lands", "world_uuid", orphan));
        }
    }

    @Test
    void countLandsInWorldMatchesStoredRows() throws Exception {
        try (PersistenceStore store = db()) {
            UUID orphan = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            seedNamespace(store, owner.key(), groupId, profileId);
            seedOwnedLand(store, orphan, owner, 1, groupId, profileId);
            seedOwnedLand(store, orphan, owner, 2, groupId, profileId);

            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            assertEquals(2, repos.countLandsInWorld(orphan)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(0, repos.countLandsInWorld(UUID.randomUUID())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void generationChangeBetweenConfirmAndSqlAbortsBeforeAnyDelete() throws Exception {
        try (PersistenceStore store = db()) {
            UUID orphan = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            seedNamespace(store, owner.key(), groupId, profileId);
            seedOwnedLand(store, orphan, owner, 1, groupId, profileId);
            SqliteAuditRepository audits = new SqliteAuditRepository(store);

            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            OrphanWorldGuard guard = guardWith(Set.of());
            WorldCatalogSnapshot confirmed = guard.snapshot();
            // An unrelated world loads after the confirmation: the catalog
            // moved, so the commit must abort even though the target is
            // still absent.
            guard.publish(Set.of(UUID.randomUUID()));
            try {
                repos.purgeOrphanWorld(orphan, UUID.randomUUID(), confirmed, NOW, 1, guard)
                        .toCompletableFuture().join();
                fail("generation change must abort the purge");
            } catch (CompletionException expected) {
                assertTrue(root(expected) instanceof OrphanConflictException,
                        "must fail as conflict, got " + root(expected));
            }

            assertEquals(1, countWorld(store, "lands", "world_uuid", orphan));
            assertEquals(0, countAll(store, "audit_log"));
            assertEquals(0, audits.findByAction(OrphanPurgeRepository.AUDIT_ACTION, 10)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void worldReloadAfterSnapshotAbortsBeforeAnyDelete() throws Exception {
        try (PersistenceStore store = db()) {
            UUID orphan = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            seedNamespace(store, owner.key(), groupId, profileId);
            seedOwnedLand(store, orphan, owner, 1, groupId, profileId);
            SqliteAuditRepository audits = new SqliteAuditRepository(store);

            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            OrphanWorldGuard guard = guardWith(Set.of());
            WorldCatalogSnapshot confirmed = guard.snapshot();
            // The target world reloads between the confirmation snapshot
            // and the SQL transaction.
            guard.publish(Set.of(orphan));
            try {
                repos.purgeOrphanWorld(orphan, UUID.randomUUID(), confirmed, NOW, 1, guard)
                        .toCompletableFuture().join();
                fail("reloaded world must abort the purge");
            } catch (CompletionException expected) {
                assertTrue(root(expected) instanceof OrphanConflictException,
                        "must fail as conflict, got " + root(expected));
            }

            assertEquals(1, countWorld(store, "lands", "world_uuid", orphan));
            assertEquals(0, audits.findByAction(OrphanPurgeRepository.AUDIT_ACTION, 10)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void unverifiedGuardAbortsPurgeWithZeroSideEffect() throws Exception {
        try (PersistenceStore store = db()) {
            UUID orphan = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            seedNamespace(store, owner.key(), groupId, profileId);
            seedOwnedLand(store, orphan, owner, 1, groupId, profileId);

            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            OrphanWorldGuard guard = new OrphanWorldGuard();
            assertFalse(guard.snapshot().isVerified());
            try {
                repos.purgeOrphanWorld(orphan, UUID.randomUUID(), guard.snapshot(), NOW, 1, guard)
                        .toCompletableFuture().join();
                fail("unverified catalog must abort the purge");
            } catch (CompletionException expected) {
                assertTrue(root(expected) instanceof OrphanConflictException,
                        "must fail as conflict, got " + root(expected));
            }

            assertEquals(1, countWorld(store, "lands", "world_uuid", orphan));
            assertEquals(0, countAll(store, "audit_log"));
        }
    }

    @Test
    void purgeWaitsForCatalogUpdatesInsteadOfInterleaving() throws Exception {
        try (PersistenceStore store = db()) {
            UUID orphan = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(actor);
            UUID groupId = UUID.randomUUID();
            UUID profileId = UUID.randomUUID();
            seedNamespace(store, owner.key(), groupId, profileId);
            seedOwnedLand(store, orphan, owner, 1, groupId, profileId);

            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            OrphanWorldGuard guard = guardWith(Set.of());
            WorldCatalogSnapshot confirmed = guard.snapshot();
            // Hold the write side: the purge transaction must wait for the
            // catalog update rather than running past it. The timeout below
            // is only a liveness detector — mutual exclusion itself is
            // proven by the tryLock tests — so a broken lock fails here by
            // completing instead of by racing.
            guard.writeLock().lock();
            CompletableFuture<OrphanPurgeRepository.OrphanPurgeResult> purge;
            try {
                purge = repos.purgeOrphanWorld(orphan, UUID.randomUUID(), confirmed, NOW, 1, guard)
                        .toCompletableFuture();
                try {
                    purge.get(2, TimeUnit.SECONDS);
                    fail("purge must wait while a catalog update holds the guard");
                } catch (TimeoutException waiting) {
                    // Expected: the transaction is parked behind the write lock.
                }
            } finally {
                guard.writeLock().unlock();
            }
            OrphanPurgeRepository.OrphanPurgeResult result =
                    purge.get(10, TimeUnit.SECONDS);
            assertEquals(orphan, result.worldId());
            assertEquals(0, countWorld(store, "lands", "world_uuid", orphan));
        }
    }
}

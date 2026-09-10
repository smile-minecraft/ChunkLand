package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.LandAuthorisationCache;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import com.smile.chunkland.trust.LandAuthorisationService;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Direct-profile ownership, durable audit and whitelist cleanup.
 *
 * <p>A binding whose profile is not the subject's own direct profile must
 * never publish foreign entries; audit before values must come from the same
 * durable transaction rather than the volatile cache; trust must leave only
 * the explicit whitelist in the direct profile.
 */
class LandAuthorisationTrustRepairTest {

    @TempDir Path tmp;

    private Path db() {
        return tmp.resolve("repair.db");
    }

    private LandSnapshot land(UUID worldId, LandId id, OwnerRef owner) {
        return new LandSnapshot(id, "Home", "home", owner, worldId,
                Set.of(new ChunkKey(worldId, 0, 0)), List.of(),
                0, 0, Instant.now(), Instant.now());
    }

    private Instant now() {
        return Instant.now();
    }

    private List<String> auditBefores(PersistenceStore store, String action, UUID landId) {
        return store.execute(conn -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT before_json FROM audit_log WHERE action = ? AND land_id = ? ORDER BY rowid")) {
                ps.setString(1, action);
                ps.setBytes(2, UuidBlob.encode(landId));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }

    @Test
    void foreignProfileBindingIsSkippedAndNeverPublished() throws Exception {
        Path path = tmp.resolve("foreign.db");
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        UUID playerC = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        try (PersistenceStore store = PersistenceStore.open(path)) {
            new SqliteLandRepository(store).save(land(world, land, OwnerRef.player(owner)))
                    .toCompletableFuture().join();
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            // A legal binding that must keep working next to the foreign row.
            repo.trust(land, playerC, owner, now()).toCompletableFuture().join();
            // Player B owns a direct profile with entries; bind it to player A.
            UUID foreignProfile = UUID.randomUUID();
            store.execute(conn -> {
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO permission_profiles (id, owner_key, name_key, display_name, created_at)"
                                + " VALUES (?, ?, 'direct', 'Direct', ?)")) {
                    insert.setBytes(1, UuidBlob.encode(foreignProfile));
                    insert.setString(2, "PLAYER:" + playerB);
                    insert.setLong(3, Instant.now().toEpochMilli());
                    insert.executeUpdate();
                }
                try (PreparedStatement entry = conn.prepareStatement(
                        "INSERT INTO permission_profile_entries (profile_id, permission, state)"
                                + " VALUES (?, 'BLOCK_BREAK', 'ALLOW')")) {
                    entry.setBytes(1, UuidBlob.encode(foreignProfile));
                    entry.executeUpdate();
                }
                try (PreparedStatement bind = conn.prepareStatement(
                        "INSERT INTO land_bindings (land_id, subject_type, subject_id, profile_id)"
                                + " VALUES (?, 'PLAYER', ?, ?)")) {
                    bind.setBytes(1, UuidBlob.encode(land.value()));
                    bind.setBytes(2, UuidBlob.encode(playerA));
                    bind.setBytes(3, UuidBlob.encode(foreignProfile));
                    bind.executeUpdate();
                }
                return null;
            });

            java.util.logging.Logger logger =
                    java.util.logging.Logger.getLogger(
                            LandAuthorisationRepository.class.getName());
            List<java.util.logging.LogRecord> warnings = new ArrayList<>();
            java.util.logging.Handler handler = new java.util.logging.Handler() {
                @Override
                public void publish(java.util.logging.LogRecord record) {
                    warnings.add(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            logger.addHandler(handler);
            try {
                LandAuthorisationRepository.SnapshotData data = repo.loadSnapshotData()
                        .toCompletableFuture().join();
                Set<ProtectionActionType> published = data.directAllows()
                        .getOrDefault(land, Map.of()).getOrDefault(playerA, Set.of());
                assertTrue(published.isEmpty(),
                        "binding to another player's profile must not publish foreign entries");
                Set<ProtectionActionType> legal = data.directAllows()
                        .getOrDefault(land, Map.of()).getOrDefault(playerC, Set.of());
                assertTrue(legal.contains(ProtectionActionType.BLOCK_BREAK),
                        "a foreign row must not hide other legal bindings");
            } finally {
                logger.removeHandler(handler);
            }
            assertTrue(warnings.stream().anyMatch(record ->
                            record.getMessage() != null
                                    && record.getMessage().contains("Skipping land binding")),
                    "skipped foreign bindings must leave a warning");
        }
    }

    @Test
    void nonDirectProfileBindingIsSkipped() throws Exception {
        Path path = tmp.resolve("nondirect.db");
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID playerA = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        try (PersistenceStore store = PersistenceStore.open(path)) {
            new SqliteLandRepository(store).save(land(world, land, OwnerRef.player(owner)))
                    .toCompletableFuture().join();
            UUID groupProfile = UUID.randomUUID();
            store.execute(conn -> {
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO permission_profiles (id, owner_key, name_key, display_name, created_at)"
                                + " VALUES (?, ?, 'group', 'Group', ?)")) {
                    insert.setBytes(1, UuidBlob.encode(groupProfile));
                    insert.setString(2, "PLAYER:" + playerA);
                    insert.setLong(3, Instant.now().toEpochMilli());
                    insert.executeUpdate();
                }
                try (PreparedStatement entry = conn.prepareStatement(
                        "INSERT INTO permission_profile_entries (profile_id, permission, state)"
                                + " VALUES (?, 'BLOCK_BREAK', 'ALLOW')")) {
                    entry.setBytes(1, UuidBlob.encode(groupProfile));
                    entry.executeUpdate();
                }
                try (PreparedStatement bind = conn.prepareStatement(
                        "INSERT INTO land_bindings (land_id, subject_type, subject_id, profile_id)"
                                + " VALUES (?, 'PLAYER', ?, ?)")) {
                    bind.setBytes(1, UuidBlob.encode(land.value()));
                    bind.setBytes(2, UuidBlob.encode(playerA));
                    bind.setBytes(3, UuidBlob.encode(groupProfile));
                    bind.executeUpdate();
                }
                return null;
            });

            LandAuthorisationRepository.SnapshotData data =
                    new LandAuthorisationRepository(store).loadSnapshotData()
                            .toCompletableFuture().join();
            Set<ProtectionActionType> published = data.directAllows()
                    .getOrDefault(land, Map.of()).getOrDefault(playerA, Set.of());
            assertTrue(published.isEmpty(),
                    "binding to a non-direct profile must not publish entries");
        }
    }

    @Test
    void trustCleansNonWhitelistEntriesLeavingOnlySeventeen() throws Exception {
        Path path = tmp.resolve("cleanup.db");
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        try (PersistenceStore store = PersistenceStore.open(path)) {
            new SqliteLandRepository(store).save(land(world, land, OwnerRef.player(owner)))
                    .toCompletableFuture().join();
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID profile = repo.trust(land, target, owner, now())
                    .toCompletableFuture().join();
            store.execute(conn -> {
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO permission_profile_entries (profile_id, permission, state)"
                                + " VALUES (?, 'MANAGE_MEMBER', 'ALLOW')")) {
                    insert.setBytes(1, UuidBlob.encode(profile));
                    insert.executeUpdate();
                }
                return null;
            });
            repo.trust(land, target, owner, now())
                    .toCompletableFuture().join();
            int total = store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM permission_profile_entries WHERE profile_id = ?")) {
                    ps.setBytes(1, UuidBlob.encode(profile));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getInt(1);
                    }
                }
            });
            int bad = store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM permission_profile_entries"
                                + " WHERE profile_id = ? AND permission = 'MANAGE_MEMBER'")) {
                    ps.setBytes(1, UuidBlob.encode(profile));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getInt(1);
                    }
                }
            });
            assertEquals(0, bad, "non-whitelist durable entries must be removed on trust");
            assertEquals(17, total, "direct profile must hold exactly the whitelist after trust");
            // Other profiles keep their rows: cleanup is scoped to the direct profile.
            UUID otherProfile = UUID.randomUUID();
            store.execute(conn -> {
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO permission_profiles (id, owner_key, name_key, display_name, created_at)"
                                + " VALUES (?, 'GROUP:shared', 'shared', 'Shared', ?)")) {
                    insert.setBytes(1, UuidBlob.encode(otherProfile));
                    insert.setLong(2, Instant.now().toEpochMilli());
                    insert.executeUpdate();
                }
                try (PreparedStatement entry = conn.prepareStatement(
                        "INSERT INTO permission_profile_entries (profile_id, permission, state)"
                                + " VALUES (?, 'MANAGE_MEMBER', 'ALLOW')")) {
                    entry.setBytes(1, UuidBlob.encode(otherProfile));
                    entry.executeUpdate();
                }
                return null;
            });
            repo.trust(land, target, owner, now())
                    .toCompletableFuture().join();
            int otherKept = store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM permission_profile_entries WHERE profile_id = ?")) {
                    ps.setBytes(1, UuidBlob.encode(otherProfile));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getInt(1);
                    }
                }
            });
            assertEquals(1, otherKept, "cleanup must not touch other profiles");
        }
    }

    @Test
    void repeatedUntrustWithoutBindingRecordsEmptyBefore() throws Exception {
        Path path = tmp.resolve("untrust-audit.db");
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        try (PersistenceStore store = PersistenceStore.open(path)) {
            new SqliteLandRepository(store).save(land(world, land, OwnerRef.player(owner)))
                    .toCompletableFuture().join();
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            LandAuthorisationService service =
                    new LandAuthorisationService(repo, new LandAuthorisationCache(), clock);
            service.trust(owner, land, target).toCompletableFuture().join();
            service.untrust(owner, land, target).toCompletableFuture().join();
            service.untrust(owner, land, target).toCompletableFuture().join();
            List<String> befores = auditBefores(store, "DIRECT_BINDING_CHANGE", land.value());
            assertEquals(3, befores.size());
            assertTrue(befores.get(0) == null,
                    "first trust has no durable binding before it");
            assertTrue(befores.get(1) != null && befores.get(1).contains("\"bound\":true"),
                    "first untrust before must show the durable binding");
            assertTrue(befores.get(2) == null,
                    "resent untrust without a binding must record no binding before it");
        }
    }

    @Test
    void defaultAuditBeforeComesFromDurableStateDespiteStaleCache() throws Exception {
        Path path = tmp.resolve("audit.db");
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        try (PersistenceStore store = PersistenceStore.open(path)) {
            new SqliteLandRepository(store).save(land(world, land, OwnerRef.player(owner)))
                    .toCompletableFuture().join();
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            LandAuthorisationCache cache = new LandAuthorisationCache();
            LandAuthorisationService service =
                    new LandAuthorisationService(repo, cache, clock);
            service.setDefault(owner, land, ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW)
                    .toCompletableFuture().join();
            // Simulate a refresh race: the volatile cache falls behind the durable row.
            cache.publish(LandAuthorisationSnapshot.empty());
            service.setDefault(owner, land, ProtectionActionType.BLOCK_BREAK, PermissionState.DENY)
                    .toCompletableFuture().join();
            List<String> befores = auditBefores(store, "DEFAULT_CHANGE", land.value());
            assertEquals(2, befores.size());
            assertTrue(befores.get(0).contains("\"INHERIT\""),
                    "first default before durable empty state");
            assertTrue(befores.get(1).contains("\"ALLOW\""),
                    "second default before must be the durable ALLOW, not the stale cache");
        }
    }

    @Test
    void repeatedTrustAuditReflectsDurableBindingPresence() throws Exception {
        Path path = tmp.resolve("binding-audit.db");
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        try (PersistenceStore store = PersistenceStore.open(path)) {
            new SqliteLandRepository(store).save(land(world, land, OwnerRef.player(owner)))
                    .toCompletableFuture().join();
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            LandAuthorisationService service =
                    new LandAuthorisationService(repo, new LandAuthorisationCache(), clock);
            service.trust(owner, land, target).toCompletableFuture().join();
            service.trust(owner, land, target).toCompletableFuture().join();
            List<String> befores = auditBefores(store, "DIRECT_BINDING_CHANGE", land.value());
            assertEquals(2, befores.size());
            assertTrue(befores.get(0) == null,
                    "first trust has no durable binding before it");
            assertTrue(befores.get(1) != null && befores.get(1).contains("\"bound\":true"),
                    "second trust before must show the durable binding");
        }
    }
}

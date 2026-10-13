package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Durable per-land environment rules: every rule writes and reloads, INHERIT
 * deletes its row, conditional writes pin both the observed value and the
 * authorisation generation in the same transaction, and corrupt rows never
 * break the load.
 */
class LandRuleRepositoryTest {

    @TempDir Path tmp;

    private Path db() {
        return tmp.resolve(UUID.randomUUID() + ".db");
    }

    private LandSnapshot land(UUID worldId, LandId id, OwnerRef owner) {
        return new LandSnapshot(id, "Home", "home", owner, worldId,
                Set.of(new ChunkKey(worldId, 0, 0)), List.of(),
                0, 0, Instant.now(), Instant.now());
    }

    private Instant now() {
        return Instant.now();
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

    private PermissionState loadedRule(PersistenceStore store, LandId land, LandRuleType rule) {
        LandAuthorisationRepository.SnapshotData data =
                new LandAuthorisationRepository(store).loadSnapshotData()
                        .toCompletableFuture().join();
        return data.landRules().getOrDefault(land, Map.of())
                .getOrDefault(rule, PermissionState.INHERIT);
    }

    @ParameterizedTest
    @EnumSource(LandRuleType.class)
    void everyRuleRoundTripsAllowAndDeny(LandRuleType rule) {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();

            repo.setRule(land, rule, PermissionState.ALLOW, owner, now())
                    .toCompletableFuture().join();
            assertEquals(PermissionState.ALLOW, loadedRule(store, land, rule),
                    rule + " ALLOW must reload");

            repo.setRule(land, rule, PermissionState.DENY, owner, now())
                    .toCompletableFuture().join();
            assertEquals(PermissionState.DENY, loadedRule(store, land, rule),
                    rule + " DENY must reload");
        }
    }

    @Test
    void inheritDeletesOverrideRow() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();

            repo.setRule(land, LandRuleType.PVP, PermissionState.DENY, owner, now())
                    .toCompletableFuture().join();
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_rules WHERE land_id = ?", land.value()));

            repo.setRule(land, LandRuleType.PVP, PermissionState.INHERIT, owner, now())
                    .toCompletableFuture().join();
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM land_rules WHERE land_id = ?", land.value()));
            assertEquals(PermissionState.INHERIT, loadedRule(store, land, LandRuleType.PVP),
                    "a removed override must read as INHERIT so the world/global chain applies");
        }
    }

    @Test
    void missingRowLoadsAbsent() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();

            assertEquals(PermissionState.INHERIT, loadedRule(store, land, LandRuleType.FIRE_SPREAD));
        }
    }

    @Test
    void conditionalMatchingPinsWriteAuditAndBump() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();
            repo.setRule(land, LandRuleType.PVP, PermissionState.DENY, owner, now())
                    .toCompletableFuture().join();
            long before = revision(store, land);

            repo.setRuleIfCurrent(land, LandRuleType.PVP, PermissionState.DENY,
                    before, PermissionState.ALLOW, owner, now()).toCompletableFuture().join();

            assertEquals(PermissionState.ALLOW, loadedRule(store, land, LandRuleType.PVP));
            assertEquals(2, count(store,
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'RULE_CHANGE' AND land_id = ?",
                    land.value()));
            assertTrue(revision(store, land) > before,
                    "a committed rule write must bump the authorisation generation");
        }
    }

    @Test
    void staleValueFailsWithoutWriteOrAudit() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();
            repo.setRule(land, LandRuleType.PVP, PermissionState.DENY, owner, now())
                    .toCompletableFuture().join();
            long pinned = revision(store, land);

            CompletionException conflict = assertThrows(CompletionException.class, () ->
                    repo.setRuleIfCurrent(land, LandRuleType.PVP, PermissionState.ALLOW,
                            pinned, PermissionState.DENY, owner, now())
                            .toCompletableFuture().join());
            assertTrue(conflict.getCause() instanceof LandRuleConflictException,
                    "stale expected must fail as a rule conflict, got: " + conflict.getCause());

            assertEquals(PermissionState.DENY, loadedRule(store, land, LandRuleType.PVP));
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'RULE_CHANGE' AND land_id = ?",
                    land.value()));
            assertEquals(pinned, revision(store, land),
                    "a refused write must not bump the authorisation generation");
        }
    }

    @Test
    void lapsedRevisionFailsWithoutWriteOrAudit() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();
            repo.setRule(land, LandRuleType.PVP, PermissionState.DENY, owner, now())
                    .toCompletableFuture().join();
            long stale = revision(store, land);
            // An unrelated authorisation write moves the generation forward.
            repo.setDefault(land, com.smile.chunkland.api.permission.ProtectionActionType.BLOCK_BREAK,
                    PermissionState.ALLOW, owner, now()).toCompletableFuture().join();

            CompletionException lapsed = assertThrows(CompletionException.class, () ->
                    repo.setRuleIfCurrent(land, LandRuleType.PVP, PermissionState.DENY,
                            stale, PermissionState.ALLOW, owner, now())
                            .toCompletableFuture().join());
            assertTrue(lapsed.getCause() instanceof StaleAuthorisationException,
                    "lapsed generation must fail as stale authorisation, got: " + lapsed.getCause());

            assertEquals(PermissionState.DENY, loadedRule(store, land, LandRuleType.PVP));
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'RULE_CHANGE' AND land_id = ?",
                    land.value()));
        }
    }

    @Test
    void unconditionalWriteAuditsRuleChange() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();

            repo.setRule(land, LandRuleType.FIRE_SPREAD, PermissionState.DENY, owner, now())
                    .toCompletableFuture().join();

            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'RULE_CHANGE' AND land_id = ?",
                    land.value()));
        }
    }

    @Test
    void injectedFailureAfterRuleRowRollsBackEverything() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();
            long before = revision(store, land);
            AtomicBoolean armed = new AtomicBoolean(true);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store, step -> {
                if (armed.get() && step == LandAuthorisationRepository.Step.AFTER_RULE) {
                    throw new IllegalStateException("injected rule failure");
                }
            });

            CompletionException injected = assertThrows(CompletionException.class, () ->
                    repo.setRule(land, LandRuleType.PVP, PermissionState.DENY, owner, now())
                            .toCompletableFuture().join());
            assertTrue(injected.getCause() instanceof IllegalStateException,
                    "injected failure must surface, got: " + injected.getCause());

            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM land_rules WHERE land_id = ?", land.value()));
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'RULE_CHANGE' AND land_id = ?",
                    land.value()));
            assertEquals(before, revision(store, land),
                    "a rolled-back write must not bump the authorisation generation");
        }
    }

    @Test
    void corruptRowsSkippedOnLoad() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();
            store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO land_rules (land_id, rule_type, state) VALUES (?, ?, ?)")) {
                    ps.setBytes(1, UuidBlob.encode(land.value()));
                    ps.setString(2, "PVP");
                    ps.setString(3, "DENY");
                    ps.executeUpdate();
                    ps.setBytes(1, UuidBlob.encode(land.value()));
                    ps.setString(2, "BOGUS_RULE");
                    ps.setString(3, "ALLOW");
                    ps.executeUpdate();
                    ps.setBytes(1, UuidBlob.encode(land.value()));
                    ps.setString(2, "PISTON");
                    ps.setString(3, "BOGUS_STATE");
                    ps.executeUpdate();
                    ps.setBytes(1, UuidBlob.encode(land.value()));
                    ps.setString(2, "FIRE_SPREAD");
                    ps.setString(3, "INHERIT");
                    ps.executeUpdate();
                }
                return null;
            });

            LandAuthorisationRepository.SnapshotData data =
                    new LandAuthorisationRepository(store).loadSnapshotData()
                            .toCompletableFuture().join();
            assertEquals(PermissionState.DENY,
                    data.landRules().getOrDefault(land, Map.of())
                            .getOrDefault(LandRuleType.PVP, PermissionState.INHERIT),
                    "the valid row must keep loading");
            assertTrue(!data.landRules().getOrDefault(land, Map.of())
                    .containsKey(LandRuleType.PISTON),
                    "an unknown state string must be skipped row-wise");
            assertTrue(!data.landRules().getOrDefault(land, Map.of())
                    .containsKey(LandRuleType.FIRE_SPREAD),
                    "a stored INHERIT must read as unset, never as a stored value");
        }
    }

    @Test
    void serverLandWriteFailsClosed() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.server())).toCompletableFuture().join();

            CompletionException refused = assertThrows(CompletionException.class, () ->
                    repo.setRule(land, LandRuleType.PVP, PermissionState.DENY, owner, now())
                            .toCompletableFuture().join());
            assertTrue(refused.getCause() instanceof IllegalArgumentException,
                    "Server Land rule writes must fail closed, got: " + refused.getCause());
            assertEquals(0, count(store,
                    "SELECT COUNT(*) FROM land_rules WHERE land_id = ?", land.value()));
            assertEquals(0, count(store, "SELECT COUNT(*) FROM audit_log"));
        }
    }

    @Test
    void unknownLandWriteFails() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            LandId missing = new LandId(UUID.randomUUID());

            assertThrows(CompletionException.class, () ->
                    repo.setRule(missing, LandRuleType.PVP, PermissionState.DENY,
                            UUID.randomUUID(), now()).toCompletableFuture().join());
            assertEquals(0, count(store, "SELECT COUNT(*) FROM land_rules"));
            assertEquals(0, count(store, "SELECT COUNT(*) FROM audit_log"));
        }
    }

    @Test
    void restartPreservesRules() {
        Path path = db();
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        try (PersistenceStore store = PersistenceStore.open(path)) {
            new SqliteLandRepository(store).save(land(world, land, OwnerRef.player(owner)))
                    .toCompletableFuture().join();
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            repo.setRule(land, LandRuleType.PVP, PermissionState.DENY, owner, now())
                    .toCompletableFuture().join();
            repo.setRule(land, LandRuleType.PISTON, PermissionState.ALLOW, owner, now())
                    .toCompletableFuture().join();
        }
        try (PersistenceStore store = PersistenceStore.open(path)) {
            LandAuthorisationRepository.SnapshotData data =
                    new LandAuthorisationRepository(store).loadSnapshotData()
                            .toCompletableFuture().join();
            assertEquals(PermissionState.DENY, data.landRules().getOrDefault(land, Map.of())
                    .getOrDefault(LandRuleType.PVP, PermissionState.INHERIT));
            assertEquals(PermissionState.ALLOW, data.landRules().getOrDefault(land, Map.of())
                    .getOrDefault(LandRuleType.PISTON, PermissionState.INHERIT));
        }
    }
}

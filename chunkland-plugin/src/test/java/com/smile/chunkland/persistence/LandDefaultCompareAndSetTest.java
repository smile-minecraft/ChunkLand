package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compare-and-set land defaults: the write commits only when the durable
 * current still equals the caller-observed expected value. A mismatch fails
 * with {@link LandDefaultConflictException} and leaves the row and the
 * audit log untouched, so a stale confirm page can never silently overwrite
 * a newer change.
 */
class LandDefaultCompareAndSetTest {

    @TempDir Path tmp;

    private Path db() {
        return tmp.resolve("auth-cas.db");
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

    @Test
    void matchingExpectedWritesAndAudits() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();
            repo.setDefault(land, ProtectionActionType.BLOCK_BREAK, PermissionState.DENY,
                    owner, now()).toCompletableFuture().join();

            repo.setDefaultIfCurrent(land, ProtectionActionType.BLOCK_BREAK,
                    PermissionState.DENY, revision(store, land), PermissionState.ALLOW, owner,
                    now()).toCompletableFuture().join();

            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_defaults WHERE land_id = ? AND permission = 'BLOCK_BREAK' AND state = 'ALLOW'",
                    land.value()));
            assertEquals(2, count(store,
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'DEFAULT_CHANGE' AND land_id = ?",
                    land.value()));
        }
    }

    @Test
    void missingRowReadsAsInherit() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();

            repo.setDefaultIfCurrent(land, ProtectionActionType.BLOCK_BREAK,
                    PermissionState.INHERIT, revision(store, land), PermissionState.DENY, owner,
                    now()).toCompletableFuture().join();

            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_defaults WHERE land_id = ? AND permission = 'BLOCK_BREAK' AND state = 'DENY'",
                    land.value()));
        }
    }

    @Test
    void staleExpectedFailsWithoutWritingOrAuditing() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();
            repo.setDefault(land, ProtectionActionType.BLOCK_BREAK, PermissionState.DENY,
                    owner, now()).toCompletableFuture().join();

            CompletionException conflict = assertThrows(CompletionException.class, () ->
                    repo.setDefaultIfCurrent(land, ProtectionActionType.BLOCK_BREAK,
                            PermissionState.ALLOW, revision(store, land), PermissionState.DENY,
                            owner, now()).toCompletableFuture().join());
            assertTrue(conflict.getCause() instanceof LandDefaultConflictException,
                    "stale expected must fail as a conflict, got: " + conflict.getCause());

            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_defaults WHERE land_id = ? AND permission = 'BLOCK_BREAK' AND state = 'DENY'",
                    land.value()));
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'DEFAULT_CHANGE' AND land_id = ?",
                    land.value()));
        }
    }

    @Test
    void nonWhitelistedActionsFailBeforeAnySql() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();

            assertThrows(IllegalArgumentException.class, () -> repo.setDefaultIfCurrent(land,
                    ProtectionActionType.DELETE_LAND, PermissionState.INHERIT, 0L,
                    PermissionState.DENY, owner, now()));
            assertEquals(0, count(store, "SELECT COUNT(*) FROM land_defaults"));
            assertEquals(0, count(store, "SELECT COUNT(*) FROM audit_log"));
        }
    }
}

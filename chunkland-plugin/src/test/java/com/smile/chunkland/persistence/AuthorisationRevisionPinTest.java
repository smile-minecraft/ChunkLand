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
 * Revision-pinned land-default writes: the commit aborts unless the durable
 * {@code land_policy_revision} still equals the generation observed at gate
 * time. An authorization-relevant write landing between the gate check and
 * the transaction (here simulated by a real {@code trust} commit) refuses
 * with {@link StaleAuthorisationException} — never with the value-conflict
 * type — leaving the row, the revision and the audit log untouched.
 */
class AuthorisationRevisionPinTest {

    @TempDir Path tmp;

    private Path db() {
        return tmp.resolve("auth-pin.db");
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

    @Test
    void revokedAuthorisationAbortsWithZeroSideEffects() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();
            repo.setDefault(land, ProtectionActionType.BLOCK_BREAK, PermissionState.DENY,
                    owner, now()).toCompletableFuture().join();
            long pinned = revision(store, land);

            repo.trust(land, UUID.randomUUID(), owner, now()).toCompletableFuture().join();

            CompletionException refused = assertThrows(CompletionException.class, () ->
                    repo.setDefaultIfCurrent(land, ProtectionActionType.BLOCK_BREAK,
                            PermissionState.DENY, pinned, PermissionState.ALLOW, owner, now())
                            .toCompletableFuture().join());
            assertTrue(refused.getCause() instanceof StaleAuthorisationException,
                    "a lapsed authorisation must fail as an authorisation error, got: "
                            + refused.getCause());

            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_defaults WHERE land_id = ? AND permission = 'BLOCK_BREAK' AND state = 'DENY'",
                    land.value()));
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'DEFAULT_CHANGE' AND land_id = ?",
                    land.value()));
        }
    }

    @Test
    void pinnedWriteCommitsWhenNothingChanged() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            UUID world = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(world, land, OwnerRef.player(owner))).toCompletableFuture().join();
            long pinned = revision(store, land);

            repo.setDefaultIfCurrent(land, ProtectionActionType.BLOCK_BREAK,
                    PermissionState.INHERIT, pinned, PermissionState.ALLOW, owner, now())
                    .toCompletableFuture().join();

            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM land_defaults WHERE land_id = ? AND permission = 'BLOCK_BREAK' AND state = 'ALLOW'",
                    land.value()));
            assertEquals(1, count(store,
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'DEFAULT_CHANGE' AND land_id = ?",
                    land.value()));
        }
    }

    @Test
    void unknownLandAbortsWithoutSideEffects() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            LandId unknown = new LandId(UUID.randomUUID());

            assertThrows(CompletionException.class, () ->
                    repo.setDefaultIfCurrent(unknown, ProtectionActionType.BLOCK_BREAK,
                            PermissionState.INHERIT, 0L, PermissionState.ALLOW,
                            UUID.randomUUID(), now()).toCompletableFuture().join());
            assertEquals(0, count(store, "SELECT COUNT(*) FROM land_defaults"));
            assertEquals(0, count(store, "SELECT COUNT(*) FROM audit_log"));
        }
    }
}

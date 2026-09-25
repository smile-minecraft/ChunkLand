package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.rename.RenameRejectedException;
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
 * Rename is not an authorisation change: it must leave
 * {@code lands.land_policy_revision} untouched, so a revision pinned
 * before the rename still commits afterwards. A rename that bumped the
 * generation without refreshing the authorisation snapshot would refuse
 * every later land-default write permanently (not conservatively), with
 * no self-healing until an unrelated authorisation write.
 */
class AuthorisationRevisionRenameTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    @TempDir Path tmp;

    private Path db(String name) {
        return tmp.resolve(name);
    }

    private Instant now() {
        return Instant.now();
    }

    private static LandSnapshot land(LandId id, String display) {
        String key = display.strip().toLowerCase(java.util.Locale.ROOT);
        Instant now = Instant.now();
        return new LandSnapshot(id, display, key, OwnerRef.player(OWNER), WORLD,
                Set.of(new ChunkKey(WORLD, 0, 0)), List.of(),
                3, 5, now, now);
    }

    private static long revision(PersistenceStore store, LandId land) {
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

    private static int defaultAudits(PersistenceStore store, LandId land) {
        return store.execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'DEFAULT_CHANGE'"
                            + " AND land_id = ?")) {
                ps.setBytes(1, UuidBlob.encode(land.value()));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    private static void rename(LandRenameRepository repo, LandId land, String name) {
        repo.rename(land, OWNER, false, name, Instant.now())
                .toCompletableFuture().join();
    }

    private static RenameRejectedException renameRejected(LandRenameRepository repo,
            LandId land, String name) {
        try {
            repo.rename(land, OWNER, false, name, Instant.now())
                    .toCompletableFuture().join();
        } catch (RenameRejectedException rejected) {
            return rejected;
        } catch (CompletionException failure) {
            Throwable current = failure;
            while (current != null) {
                if (current instanceof RenameRejectedException rejected) {
                    return rejected;
                }
                current = current.getCause();
            }
            throw new AssertionError("expected a rename rejection", failure);
        }
        throw new AssertionError("expected a rename rejection for " + land);
    }

    @Test
    void renameLeavesGenerationUntouchedThenPinnedWriteSucceeds() {
        try (PersistenceStore store = PersistenceStore.open(db("rename.db"))) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandRenameRepository renames = new LandRenameRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(land, "Home")).toCompletableFuture().join();
            long pinned = revision(store, land);

            rename(renames, land, "Garden");

            assertEquals(pinned, revision(store, land),
                    "rename must not move the authorisation generation");
            repo.setDefaultIfCurrent(land, ProtectionActionType.BLOCK_BREAK,
                    PermissionState.INHERIT, pinned, PermissionState.DENY, OWNER, now())
                    .toCompletableFuture().join();
            assertEquals(1, defaultAudits(store, land));
        }
    }

    @Test
    void duplicateRenameLeavesGenerationUntouched() {
        try (PersistenceStore store = PersistenceStore.open(db("duplicate.db"))) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandRenameRepository renames = new LandRenameRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            LandId first = new LandId(UUID.randomUUID());
            LandId second = new LandId(UUID.randomUUID());
            lands.save(land(first, "Home")).toCompletableFuture().join();
            lands.save(land(second, "Garden")).toCompletableFuture().join();
            long pinned = revision(store, second);

            assertEquals("rename.duplicate",
                    renameRejected(renames, second, "Home").diagnosticKey());

            assertEquals(pinned, revision(store, second),
                    "a rejected rename must not move the authorisation generation");
            repo.setDefaultIfCurrent(second, ProtectionActionType.BLOCK_BREAK,
                    PermissionState.INHERIT, pinned, PermissionState.DENY, OWNER, now())
                    .toCompletableFuture().join();
            assertEquals(1, defaultAudits(store, second));
        }
    }

    @Test
    void resendSameRenameLeavesGenerationUntouched() {
        try (PersistenceStore store = PersistenceStore.open(db("resend.db"))) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandRenameRepository renames = new LandRenameRepository(store);
            LandAuthorisationRepository repo = new LandAuthorisationRepository(store);
            LandId land = new LandId(UUID.randomUUID());
            lands.save(land(land, "Home")).toCompletableFuture().join();
            long pinned = revision(store, land);

            rename(renames, land, "Garden");
            rename(renames, land, "Garden");

            assertEquals(pinned, revision(store, land),
                    "a resent rename must not move the authorisation generation");
            repo.setDefaultIfCurrent(land, ProtectionActionType.BLOCK_BREAK,
                    PermissionState.INHERIT, pinned, PermissionState.DENY, OWNER, now())
                    .toCompletableFuture().join();
            assertEquals(1, defaultAudits(store, land));
        }
    }
}

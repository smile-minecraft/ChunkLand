package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.rename.RenameRejectedException;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Land rename durability: the mutation re-reads owner and names inside one
 * persistence transaction, enforces the owner-scoped name key against the
 * database (not a memory precheck), leaves the policy revision untouched,
 * and records a structured rename audit atomically with the row change.
 */
class LandRenameRepositoryTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OWNER_A = UUID.randomUUID();
    private static final UUID OWNER_B = UUID.randomUUID();
    private static final UUID ACTOR_OTHER = UUID.randomUUID();

    @TempDir Path tmp;

    private Path db() {
        return tmp.resolve("rename.db");
    }

    private static LandSnapshot land(LandId id, OwnerRef owner, String display, int chunkX) {
        String key = display.strip().toLowerCase(java.util.Locale.ROOT);
        Instant now = Instant.now();
        return new LandSnapshot(id, display, key, owner, WORLD,
                Set.of(new ChunkKey(WORLD, chunkX, 7)), List.of(),
                3, 5, now, now);
    }

    private static LandSnapshot playerLand(LandId id, UUID owner, String display, int chunkX) {
        return land(id, OwnerRef.player(owner), display, chunkX);
    }

    private static LandSnapshot serverLand(LandId id, String display, int chunkX) {
        return land(id, OwnerRef.server(), display, chunkX);
    }

    private static void save(SqliteLandRepository lands, LandSnapshot snapshot) {
        lands.save(snapshot).toCompletableFuture().join();
    }

    private record Row(String display, String key, long structure, long policy) {
    }

    private static Row readRow(PersistenceStore store, LandId land) {
        return store.execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT display_name, name_key, structure_revision, land_policy_revision"
                            + " FROM lands WHERE id = ?")) {
                ps.setBytes(1, UuidBlob.encode(land.value()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("land row missing: " + land);
                    }
                    return new Row(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4));
                }
            }
        });
    }

    private record AuditRow(String action, String beforeJson, String afterJson, String metadataJson) {
    }

    private static List<AuditRow> audits(PersistenceStore store, LandId land) {
        return store.execute(conn -> {
            List<AuditRow> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT action, before_json, after_json, metadata_json FROM audit_log"
                            + " WHERE land_id = ? ORDER BY id")) {
                ps.setBytes(1, UuidBlob.encode(land.value()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new AuditRow(
                                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    private static LandRenameRepository.Outcome rename(LandRenameRepository repo, LandId land,
            UUID actor, boolean steward, String name) {
        return repo.rename(land, actor, steward, name, Instant.now())
                .toCompletableFuture().join();
    }

    private static RenameRejectedException renameRejected(LandRenameRepository repo, LandId land,
            UUID actor, boolean steward, String name) {
        try {
            repo.rename(land, actor, steward, name, Instant.now())
                    .toCompletableFuture().join();
        } catch (RenameRejectedException rejected) {
            return rejected;
        } catch (CompletionException failure) {
            return unwrapRejected(failure);
        }
        throw new AssertionError("expected a rename rejection for " + land);
    }

    private static RenameRejectedException unwrapRejected(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof RenameRejectedException rejected) {
                return rejected;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected a rename rejection in " + failure, failure);
    }

    private static void assertRejectedWith(LandRenameRepository repo, LandId land,
            UUID actor, boolean steward, String name, String diagnosticKey) {
        assertEquals(diagnosticKey,
                renameRejected(repo, land, actor, steward, name).diagnosticKey());
    }

    @Test
    void renamePersistsNamesAndAuditsWithoutTouchingPolicy() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandRenameRepository repo = new LandRenameRepository(store);
            LandId land = new LandId(UUID.randomUUID());
            save(lands, playerLand(land, OWNER_A, "Home", 0));

            LandRenameRepository.Outcome outcome =
                    rename(repo, land, OWNER_A, false, "Garden");

            assertEquals("Home", outcome.oldDisplayName());
            assertEquals("home", outcome.oldNameKey());
            assertEquals("Garden", outcome.newDisplayName());
            assertEquals("garden", outcome.newNameKey());
            assertEquals(5, outcome.oldPolicyRevision());
            assertEquals(5, outcome.newPolicyRevision(),
                    "rename is not an authorisation change and must not move the generation");
            Row row = readRow(store, land);
            assertEquals("Garden", row.display());
            assertEquals("garden", row.key());
            assertEquals(3, row.structure(), "rename must not touch the structure revision");
            assertEquals(5, row.policy());
            List<AuditRow> rows = audits(store, land);
            assertEquals(1, rows.size());
            AuditRow audit = rows.get(0);
            assertEquals("LAND_RENAME", audit.action());
            assertTrue(audit.beforeJson().contains("\"displayName\":\"Home\""), audit.beforeJson());
            assertTrue(audit.beforeJson().contains("\"nameKey\":\"home\""), audit.beforeJson());
            assertTrue(audit.beforeJson().contains("\"policyRevision\":5"), audit.beforeJson());
            assertTrue(audit.afterJson().contains("\"displayName\":\"Garden\""), audit.afterJson());
            assertTrue(audit.afterJson().contains("\"nameKey\":\"garden\""), audit.afterJson());
            assertTrue(audit.afterJson().contains("\"policyRevision\":5"), audit.afterJson());
        }
    }

    @Test
    void sameOwnerDuplicateRejectedAtMutationTime() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandRenameRepository repo = new LandRenameRepository(store);
            LandId first = new LandId(UUID.randomUUID());
            LandId second = new LandId(UUID.randomUUID());
            save(lands, playerLand(first, OWNER_A, "Home", 0));
            save(lands, playerLand(second, OWNER_A, "Garden", 1));

            assertRejectedWith(repo, second, OWNER_A, false, "Home", "rename.duplicate");

            Row row = readRow(store, second);
            assertEquals("Garden", row.display());
            assertEquals("garden", row.key());
            assertEquals(5, row.policy());
            assertTrue(audits(store, second).isEmpty(), "a rejected rename must not audit");
        }
    }

    @Test
    void caseOnlyDisplayChangeAllowed() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandRenameRepository repo = new LandRenameRepository(store);
            LandId land = new LandId(UUID.randomUUID());
            save(lands, playerLand(land, OWNER_A, "Home", 0));

            LandRenameRepository.Outcome outcome = rename(repo, land, OWNER_A, false, "HOME");

            assertEquals("home", outcome.newNameKey());
            assertEquals("HOME", outcome.newDisplayName());
            assertEquals(5, outcome.newPolicyRevision());
            Row row = readRow(store, land);
            assertEquals("HOME", row.display());
            assertEquals("home", row.key());
            assertEquals(3, row.structure());
        }
    }

    @Test
    void differentOwnersMayShareANameKey() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandRenameRepository repo = new LandRenameRepository(store);
            LandId mine = new LandId(UUID.randomUUID());
            LandId theirs = new LandId(UUID.randomUUID());
            save(lands, playerLand(mine, OWNER_A, "Cabin", 0));
            save(lands, playerLand(theirs, OWNER_B, "Home", 1));

            LandRenameRepository.Outcome outcome = rename(repo, mine, OWNER_A, false, "Home");

            assertEquals("home", outcome.newNameKey());
            assertEquals("Home", readRow(store, mine).display());
            assertEquals("Home", readRow(store, theirs).display());
        }
    }

    @Test
    void unknownLandRejected() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            LandRenameRepository repo = new LandRenameRepository(store);
            assertRejectedWith(repo, new LandId(UUID.randomUUID()), OWNER_A, false,
                    "Garden", "rename.unknown_land");
        }
    }

    @Test
    void nonOwnerDeniedAtMutationTime() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandRenameRepository repo = new LandRenameRepository(store);
            LandId land = new LandId(UUID.randomUUID());
            save(lands, playerLand(land, OWNER_A, "Home", 0));

            assertRejectedWith(repo, land, ACTOR_OTHER, false, "Garden", "rename.not_allowed");

            Row row = readRow(store, land);
            assertEquals("Home", row.display());
            assertEquals(5, row.policy());
            assertTrue(audits(store, land).isEmpty());
        }
    }

    @Test
    void serverLandRequiresStewardAtMutationTime() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandRenameRepository repo = new LandRenameRepository(store);
            LandId land = new LandId(UUID.randomUUID());
            UUID actor = UUID.randomUUID();
            save(lands, serverLand(land, "Spawn", 0));

            assertRejectedWith(repo, land, actor, false, "Plaza", "rename.not_allowed");
            assertEquals("Spawn", readRow(store, land).display());

            LandRenameRepository.Outcome outcome = rename(repo, land, actor, true, "Plaza");
            assertEquals("Plaza", outcome.newDisplayName());
            assertEquals("Spawn", outcome.oldDisplayName());
            assertEquals("plaza", readRow(store, land).key());
        }
    }

    @Test
    void stewardFlagNeverGrantsPlayerLand() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandRenameRepository repo = new LandRenameRepository(store);
            LandId land = new LandId(UUID.randomUUID());
            save(lands, playerLand(land, OWNER_A, "Home", 0));

            assertRejectedWith(repo, land, ACTOR_OTHER, true, "Garden", "rename.not_allowed");
            assertEquals("Home", readRow(store, land).display());
        }
    }

    @Test
    void blankAndIllegalNamesRejected() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandRenameRepository repo = new LandRenameRepository(store);
            LandId land = new LandId(UUID.randomUUID());
            save(lands, playerLand(land, OWNER_A, "Home", 0));

            for (String bad : new String[] {"", "   ", "a\nb", "a\tb"}) {
                assertRejectedWith(repo, land, OWNER_A, false, bad, "rename.invalid");
            }
            assertEquals("Home", readRow(store, land).display());
            assertTrue(audits(store, land).isEmpty());
        }
    }

    @Test
    void auditFailureRollsBackTheNameChange() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandId land = new LandId(UUID.randomUUID());
            save(lands, playerLand(land, OWNER_A, "Home", 0));
            LandRenameRepository repo = new LandRenameRepository(store, step -> {
                if (step == LandRenameRepository.Step.AFTER_AUDIT) {
                    throw new IllegalStateException("injected audit failure");
                }
            });

            CompletionException failure = assertThrows(CompletionException.class,
                    () -> repo.rename(land, OWNER_A, false, "Garden", Instant.now())
                            .toCompletableFuture().join());
            assertTrue(failure.getCause() instanceof IllegalStateException, "" + failure.getCause());

            Row row = readRow(store, land);
            assertEquals("Home", row.display());
            assertEquals("home", row.key());
            assertEquals(5, row.policy());
            assertTrue(audits(store, land).isEmpty());
        }
    }

    @Test
    void concurrentCollidingRenamesYieldExactlyOneWinner() {
        try (PersistenceStore store = PersistenceStore.open(db())) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandRenameRepository repo = new LandRenameRepository(store);
            LandId first = new LandId(UUID.randomUUID());
            LandId second = new LandId(UUID.randomUUID());
            save(lands, playerLand(first, OWNER_A, "North", 0));
            save(lands, playerLand(second, OWNER_A, "South", 1));

            CompletableFuture<LandRenameRepository.Outcome> a = repo
                    .rename(first, OWNER_A, false, "Clash", Instant.now()).toCompletableFuture();
            CompletableFuture<LandRenameRepository.Outcome> b = repo
                    .rename(second, OWNER_A, false, "Clash", Instant.now()).toCompletableFuture();
            int wins = 0;
            int duplicates = 0;
            for (CompletableFuture<LandRenameRepository.Outcome> slot : List.of(a, b)) {
                try {
                    slot.join();
                    wins++;
                } catch (CompletionException failure) {
                    assertEquals("rename.duplicate", unwrapRejected(failure).diagnosticKey());
                    duplicates++;
                }
            }
            assertEquals(1, wins, "exactly one colliding rename must win");
            assertEquals(1, duplicates, "the loser must report a duplicate");
            int clashRows = store.execute(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM lands WHERE owner_key = ? AND name_key = ?")) {
                    ps.setString(1, OwnerRef.player(OWNER_A).key());
                    ps.setString(2, "clash");
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getInt(1);
                    }
                }
            });
            assertEquals(1, clashRows, "the owner-scoped unique key must hold exactly one row");
        }
    }

    @Test
    void closedStoreFailsWithoutTouchingTheExecutor() {
        PersistenceStore store = PersistenceStore.open(db());
        LandRenameRepository repo = new LandRenameRepository(store);
        store.close();
        assertThrows(PersistenceClosedException.class,
                () -> repo.rename(new LandId(UUID.randomUUID()), OWNER_A, false,
                        "Garden", Instant.now()));
    }
}

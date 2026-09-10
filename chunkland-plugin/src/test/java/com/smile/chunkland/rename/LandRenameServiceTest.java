package com.smile.chunkland.rename;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.persistence.LandRenameRepository;
import com.smile.chunkland.persistence.LandRepository;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rename service: the owner/steward check runs against the published
 * snapshot before any write, the mutation-time transaction stays the final
 * authority, and a failed runtime publish degrades without rolling back the
 * durable rename.
 */
class LandRenameServiceTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @TempDir Path tmp;

    private static LandSnapshot playerLand(LandId id, UUID owner, String display, int chunkX) {
        Instant now = Instant.now();
        return new LandSnapshot(id, display,
                display.strip().toLowerCase(java.util.Locale.ROOT), OwnerRef.player(owner),
                WORLD, Set.of(new ChunkKey(WORLD, chunkX, 3)), List.of(),
                2, 4, now, now);
    }

    private static LandSnapshot serverLand(LandId id, String display, int chunkX) {
        Instant now = Instant.now();
        return new LandSnapshot(id, display,
                display.strip().toLowerCase(java.util.Locale.ROOT), OwnerRef.server(),
                WORLD, Set.of(new ChunkKey(WORLD, chunkX, 3)), List.of(),
                2, 4, now, now);
    }

    private static final class Fixture implements AutoCloseable {
        final PersistenceStore store;
        final SqliteLandRepository lands;
        final LandRegistryStore registry = new LandRegistryStore();

        Fixture(Path db) {
            store = PersistenceStore.open(db);
            lands = new SqliteLandRepository(store);
        }

        void save(LandSnapshot snapshot) {
            lands.save(snapshot).toCompletableFuture().join();
        }

        void publish() {
            new RuntimeRegistryRebuilder(lands, registry).rebuild()
                    .toCompletableFuture().join();
        }

        LandRenameService service() {
            return new LandRenameService(new LandRenameRepository(store), registry::snapshot,
                    new RuntimeRegistryRebuilder(lands, registry), Clock.systemUTC());
        }

        LandSnapshot durable(LandId land) {
            return lands.findById(land).toCompletableFuture().join().orElseThrow();
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static LandRenameResult rename(LandRenameService service, UUID actor,
            LandId land, String name, boolean steward) {
        return service.rename(actor, land, name, steward).toCompletableFuture().join();
    }

    @Test
    void ownerRenamePublishesNewSnapshot() {
        try (Fixture fix = new Fixture(tmp.resolve("owner.db"))) {
            LandId land = new LandId(UUID.randomUUID());
            fix.save(playerLand(land, OWNER, "Home", 0));
            fix.publish();

            LandRenameResult result = rename(fix.service(), OWNER, land, "Garden", false);

            assertEquals(LandRenameResult.Status.SUCCESS, result.status());
            assertEquals("Home", result.oldDisplayName());
            assertEquals("Garden", result.newDisplayName());
            LandSnapshot durable = fix.durable(land);
            assertEquals("Garden", durable.displayName());
            assertEquals("garden", durable.nameKey());
            assertEquals(2, durable.structureRevision());
            assertEquals(5, durable.landPolicyRevision());
            LandSnapshot runtime = fix.registry.snapshot().land(land);
            assertEquals("Garden", runtime.displayName());
            assertEquals("garden", runtime.nameKey());
            assertEquals(5, runtime.landPolicyRevision());
        }
    }

    @Test
    void nonOwnerDeniedWithoutWrite() {
        try (Fixture fix = new Fixture(tmp.resolve("denied.db"))) {
            LandId land = new LandId(UUID.randomUUID());
            fix.save(playerLand(land, OWNER, "Home", 0));
            fix.publish();

            LandRenameResult result = rename(fix.service(), OTHER, land, "Garden", false);

            assertEquals(LandRenameResult.Status.REJECTED, result.status());
            assertEquals("rename.not_allowed", result.diagnosticKey());
            assertEquals("Home", fix.durable(land).displayName());
            assertEquals("Home", fix.registry.snapshot().land(land).displayName());
        }
    }

    @Test
    void stewardFlagNeverGrantsPlayerLand() {
        try (Fixture fix = new Fixture(tmp.resolve("steward-player.db"))) {
            LandId land = new LandId(UUID.randomUUID());
            fix.save(playerLand(land, OWNER, "Home", 0));
            fix.publish();

            LandRenameResult result = rename(fix.service(), OTHER, land, "Garden", true);

            assertEquals(LandRenameResult.Status.REJECTED, result.status());
            assertEquals("rename.not_allowed", result.diagnosticKey());
            assertEquals("Home", fix.durable(land).displayName());
        }
    }

    @Test
    void serverLandStewardSucceeds() {
        try (Fixture fix = new Fixture(tmp.resolve("server.db"))) {
            LandId land = new LandId(UUID.randomUUID());
            fix.save(serverLand(land, "Spawn", 0));
            fix.publish();

            LandRenameResult result = rename(fix.service(), OTHER, land, "Plaza", true);

            assertEquals(LandRenameResult.Status.SUCCESS, result.status());
            assertEquals("Spawn", result.oldDisplayName());
            assertEquals("Plaza", result.newDisplayName());
            assertEquals("Plaza", fix.registry.snapshot().land(land).displayName());
        }
    }

    @Test
    void serverLandWithoutStewardDenied() {
        try (Fixture fix = new Fixture(tmp.resolve("server-denied.db"))) {
            LandId land = new LandId(UUID.randomUUID());
            fix.save(serverLand(land, "Spawn", 0));
            fix.publish();

            LandRenameResult ownerActing = rename(fix.service(), OWNER, land, "Plaza", false);

            assertEquals(LandRenameResult.Status.REJECTED, ownerActing.status());
            assertEquals("rename.not_allowed", ownerActing.diagnosticKey());
            assertEquals("Spawn", fix.durable(land).displayName());
        }
    }

    @Test
    void unknownLandRejectedBeforeWrite() {
        try (Fixture fix = new Fixture(tmp.resolve("unknown.db"))) {
            fix.publish();

            LandRenameResult result =
                    rename(fix.service(), OWNER, new LandId(UUID.randomUUID()), "Garden", false);

            assertEquals(LandRenameResult.Status.REJECTED, result.status());
            assertEquals("rename.unknown_land", result.diagnosticKey());
        }
    }

    @Test
    void invalidNameRejectedBeforeWrite() {
        try (Fixture fix = new Fixture(tmp.resolve("invalid.db"))) {
            LandId land = new LandId(UUID.randomUUID());
            fix.save(playerLand(land, OWNER, "Home", 0));
            fix.publish();

            for (String bad : new String[] {null, "", "   ", "a\nb"}) {
                LandRenameResult result = rename(fix.service(), OWNER, land, bad, false);
                assertEquals(LandRenameResult.Status.REJECTED, result.status(), "name: " + bad);
                assertEquals("rename.invalid", result.diagnosticKey(), "name: " + bad);
            }
            assertEquals("Home", fix.durable(land).displayName());
        }
    }

    @Test
    void duplicateMapsToRejected() {
        try (Fixture fix = new Fixture(tmp.resolve("duplicate.db"))) {
            LandId first = new LandId(UUID.randomUUID());
            LandId second = new LandId(UUID.randomUUID());
            fix.save(playerLand(first, OWNER, "Home", 0));
            fix.save(playerLand(second, OWNER, "Garden", 1));
            fix.publish();

            LandRenameResult result = rename(fix.service(), OWNER, second, "Home", false);

            assertEquals(LandRenameResult.Status.REJECTED, result.status());
            assertEquals("rename.duplicate", result.diagnosticKey());
            assertEquals("Garden", fix.durable(second).displayName());
        }
    }

    @Test
    void caseOnlyChangeKeepsNameKey() {
        try (Fixture fix = new Fixture(tmp.resolve("case.db"))) {
            LandId land = new LandId(UUID.randomUUID());
            fix.save(playerLand(land, OWNER, "Home", 0));
            fix.publish();

            LandRenameResult result = rename(fix.service(), OWNER, land, "HOME", false);

            assertEquals(LandRenameResult.Status.SUCCESS, result.status());
            assertEquals("HOME", fix.durable(land).displayName());
            assertEquals("home", fix.durable(land).nameKey());
            assertEquals("home", fix.registry.snapshot().land(land).nameKey());
        }
    }

    @Test
    void staleSnapshotOwnerCannotBypassMutationTimeCheck() {
        try (Fixture fix = new Fixture(tmp.resolve("stale.db"))) {
            LandId land = new LandId(UUID.randomUUID());
            fix.save(playerLand(land, OWNER, "Home", 0));
            fix.publish();
            // The database moved on while the published snapshot still names
            // the old owner; the mutation-time re-read must win over the
            // stale view even though the precheck passed.
            fix.save(playerLand(land, OTHER, "Home", 0));

            LandRenameResult result = rename(fix.service(), OWNER, land, "Garden", false);

            assertEquals(LandRenameResult.Status.REJECTED, result.status());
            assertEquals("rename.not_allowed", result.diagnosticKey());
            assertEquals("Home", fix.durable(land).displayName());
        }
    }

    @Test
    void publishFailureDegradesWhileDurableRenameStays() {
        try (Fixture fix = new Fixture(tmp.resolve("degraded.db"))) {
            LandId land = new LandId(UUID.randomUUID());
            fix.save(playerLand(land, OWNER, "Home", 0));
            fix.publish();
            LandRepository broken = new LandRepository() {
                @Override
                public CompletionStage<Void> save(LandSnapshot snapshot) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<Optional<LandSnapshot>> findById(LandId id) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }

                @Override
                public CompletionStage<List<LandSnapshot>> findByOwner(OwnerRef owner) {
                    return CompletableFuture.completedFuture(List.of());
                }

                @Override
                public CompletionStage<List<LandSnapshot>> findAll() {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("injected publish failure"));
                }

                @Override
                public CompletionStage<Void> delete(LandId id) {
                    return CompletableFuture.completedFuture(null);
                }
            };
            LandRenameService service = new LandRenameService(new LandRenameRepository(fix.store),
                    fix.registry::snapshot,
                    new RuntimeRegistryRebuilder(broken, fix.registry), Clock.systemUTC());

            LandRenameResult result = rename(service, OWNER, land, "Garden", false);

            assertEquals(LandRenameResult.Status.DEGRADED, result.status());
            assertEquals("rename.publish_failed", result.diagnosticKey());
            assertEquals("Home", result.oldDisplayName());
            assertEquals("Garden", result.newDisplayName());
            assertEquals("Garden", fix.durable(land).displayName(),
                    "the durable commit already won before the publish failed");
        }
    }

    @Test
    void closedStoreFailsClosed() {
        Fixture fix = new Fixture(tmp.resolve("closed.db"));
        LandId land = new LandId(UUID.randomUUID());
        fix.save(playerLand(land, OWNER, "Home", 0));
        fix.publish();
        fix.close();

        LandRenameResult result = rename(fix.service(), OWNER, land, "Garden", false);

        assertEquals(LandRenameResult.Status.FAILED, result.status());
        assertEquals("rename.failed", result.diagnosticKey());
        assertTrue(fix.registry.snapshot().land(land).displayName().equals("Home"));
    }
}

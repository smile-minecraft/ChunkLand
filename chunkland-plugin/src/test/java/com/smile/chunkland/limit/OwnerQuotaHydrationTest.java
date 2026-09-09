package com.smile.chunkland.limit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Restart hydration regression: a fresh quota service rebuilt after a restart
 * must reflect the authoritative durable land/chunk counts, otherwise the
 * owner limit is enforced from zero and claims breach it.
 */
class OwnerQuotaHydrationTest {

    @TempDir Path temp;

    private static OwnerQuotaService service(int maxLands, int maxChunks) {
        return new OwnerQuotaService(new LimitResolver(
                new ChunkLandConfig(Map.of(), new LimitSettings(maxLands, maxChunks, 128, 16), 0L, Map.of())));
    }

    private static LandSnapshot land(OwnerRef owner, UUID world, Set<ChunkKey> chunks, String name) {
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        return new LandSnapshot(new LandId(UUID.randomUUID()), name, LandName.normalize(name),
                owner, world, chunks, List.of(), 0, 0, now, now);
    }

    @Test
    void restartHydratesDurableCountsAndEnforcesLimit() throws Exception {
        Path db = temp.resolve("quota-hydration.db");
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        Set<ChunkKey> firstChunks = Set.of(new ChunkKey(world, 1, 1), new ChunkKey(world, 1, 2));
        Set<ChunkKey> secondChunks = Set.of(new ChunkKey(world, 2, 1));

        // Simulate the pre-restart server: two durable lands (2 + 1 chunks) for the owner.
        try (PersistenceStore store = PersistenceStore.open(db)) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            LandSnapshot first = land(owner, world, firstChunks, "Home");
            LandSnapshot second = land(owner, world, secondChunks, "Farm");
            lands.save(first).toCompletableFuture().join();
            lands.save(second).toCompletableFuture().join();
            for (ChunkKey chunk : firstChunks) {
                chunks.addChunk(first.id(), chunk, 64, UUID.randomUUID(), 0L)
                        .toCompletableFuture().join();
            }
            for (ChunkKey chunk : secondChunks) {
                chunks.addChunk(second.id(), chunk, 64, UUID.randomUUID(), 0L)
                        .toCompletableFuture().join();
            }
        }

        // Simulate the restart: a brand-new quota service starts from zero.
        try (PersistenceStore store = PersistenceStore.open(db)) {
            OwnerQuotaService fresh = service(2, 3);
            assertEquals(0, fresh.landCommitted(owner), "fresh service must start empty before hydration");

            OwnerQuotaHydrator.hydrateBlocking(fresh, new SqliteLandRepository(store));

            assertEquals(2, fresh.landCommitted(owner), "hydration must restore the durable land count");
            assertEquals(3, fresh.chunkCommitted(owner), "hydration must restore the durable chunk total");
            assertEquals(0, fresh.landReserved(owner));
            assertEquals(0, fresh.chunkReserved(owner));

            // The owner is exactly at the limit: the next claim must be rejected.
            assertTrue(fresh.tryReserveLand(owner).isEmpty(), "hydrated quota must refuse over-limit land");
            assertTrue(fresh.tryReserveChunks(owner, 1).isEmpty(), "hydrated quota must refuse over-limit chunks");
        }
    }
}

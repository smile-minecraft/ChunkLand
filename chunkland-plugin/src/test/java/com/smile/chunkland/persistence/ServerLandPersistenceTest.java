package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies persistence boundary for Server Land non-transferable.
 */
class ServerLandPersistenceTest {

    @Test
    void serverLandSaveRejectsPlayerOwnerChange(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("test.db");
        PersistenceStore store = PersistenceStore.open(db);
        try {
            SqliteLandRepository repo = new SqliteLandRepository(store);
            UUID world = UUID.randomUUID();
            LandId id = new LandId(UUID.randomUUID());
            Set<ChunkKey> chunks = Set.of(new ChunkKey(world, 0, 0));
            LandSnapshot serverLand = new LandSnapshot(
                    id, "Spawn", "spawn", OwnerRef.server(), world, chunks, List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
            repo.save(serverLand).toCompletableFuture().get(5, TimeUnit.SECONDS);
            // Attempt to re-save same id with player owner must be rejected
            LandSnapshot playerVersion = new LandSnapshot(
                    id, "Spawn", "spawn", OwnerRef.player(UUID.randomUUID()), world, chunks, List.of(), 1L, 0L, Instant.EPOCH, Instant.EPOCH);
            var future = repo.save(playerVersion).toCompletableFuture();
            var ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            assertTrue(cause instanceof IllegalStateException || cause.getMessage().contains("Server Land"),
                    "must reject server->player transfer, got: " + cause);
            // Server -> server remains allowed (update display name)
            LandSnapshot serverUpdate = new LandSnapshot(
                    id, "Spawn2", "spawn2", OwnerRef.server(), world, chunks, List.of(), 2L, 0L, Instant.EPOCH, Instant.EPOCH);
            assertDoesNotThrow(() -> repo.save(serverUpdate).toCompletableFuture().get(5, TimeUnit.SECONDS));
        } finally {
            store.close();
        }
    }

    @Test
    void newServerLandCreationIsAllowed(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("test2.db");
        PersistenceStore store = PersistenceStore.open(db);
        try {
            SqliteLandRepository repo = new SqliteLandRepository(store);
            UUID world = UUID.randomUUID();
            LandId id = new LandId(UUID.randomUUID());
            Set<ChunkKey> chunks = Set.of(new ChunkKey(world, 1, 1));
            LandSnapshot serverLand = new LandSnapshot(
                    id, "Hub", "hub", OwnerRef.server(), world, chunks, List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
            assertDoesNotThrow(() -> repo.save(serverLand).toCompletableFuture().get(5, TimeUnit.SECONDS));
        } finally {
            store.close();
        }
    }
}

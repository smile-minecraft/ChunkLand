package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.runtime.vertical.VerticalDepths;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Durable {@code NULL stored_min_protected_y} normalizes to the shared
 * legacy fallback instead of surfacing {@code null} or dropping the row.
 */
class StoredDepthLegacyFallbackTest {

    @TempDir Path temp;

    @Test
    void nullStoredMinProtectedYReadsAsFallback() {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey ck = new ChunkKey(world, 5, 5);
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        LandSnapshot snap = new LandSnapshot(landId, "Home", LandName.normalize("Home"),
                owner, world, Set.of(ck), List.of(), 0, 0, now, now);
        Path db = temp.resolve("legacy-null.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            lands.save(snap).toCompletableFuture().join();
            chunks.addChunk(landId, ck, 40, UUID.randomUUID(), 5L).toCompletableFuture().join();
            store.submitAsync(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE land_chunks SET stored_min_protected_y = NULL WHERE land_id = ?")) {
                    ps.setBytes(1, UuidBlob.encode(landId.value()));
                    ps.executeUpdate();
                }
                return null;
            }).toCompletableFuture().join();
            Map<ChunkKey, Integer> depths = chunks.listDepthsByLand(landId).toCompletableFuture().join();
            assertEquals(VerticalDepths.LEGACY_STORED_FALLBACK_Y, depths.get(ck));
        }
    }
}

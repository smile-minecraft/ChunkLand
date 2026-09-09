package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ChunkRepository {

    CompletionStage<Void> addChunk(LandId landId, ChunkKey chunk, int storedMinY, UUID claimLotId, long costBasisMinor);

    CompletionStage<List<ChunkKey>> listByLand(LandId landId);

    /**
     * Authoritative per-chunk stored depths for a land.
     *
     * <p>Reads {@code land_chunks.stored_min_protected_y} for every row of the
     * land. Historical nullable rows (pre-depth claims, legacy backfills) are
     * normalized to the shared legacy fallback so callers never observe
     * {@code null}. The returned map is immutable.
     */
    CompletionStage<Map<ChunkKey, Integer>> listDepthsByLand(LandId landId);

    CompletionStage<Optional<LandId>> findLandByChunk(ChunkKey chunk);

    CompletionStage<Void> removeChunk(ChunkKey chunk);

    CompletionStage<Void> deleteByLand(LandId landId);
}

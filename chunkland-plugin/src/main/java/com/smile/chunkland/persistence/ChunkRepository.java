package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ChunkRepository {

    CompletionStage<Void> addChunk(LandId landId, ChunkKey chunk, int storedMinY, UUID claimLotId, long costBasisMinor);

    CompletionStage<List<ChunkKey>> listByLand(LandId landId);

    CompletionStage<Optional<LandId>> findLandByChunk(ChunkKey chunk);

    CompletionStage<Void> removeChunk(ChunkKey chunk);

    CompletionStage<Void> deleteByLand(LandId landId);
}

package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface SubLandRepository {

    CompletionStage<Void> save(SubLandSnapshot subLand);

    CompletionStage<Optional<SubLandSnapshot>> findById(SubLandId id);

    CompletionStage<List<SubLandSnapshot>> findByLand(LandId landId);

    CompletionStage<Void> delete(SubLandId id);
}

package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Repository for {@link LandSnapshot}. All SQL runs on the single persistence executor;
 * implementations must not expose JDBC objects and must return immutable data.
 */
public interface LandRepository {

    CompletionStage<Void> save(LandSnapshot land);

    CompletionStage<Optional<LandSnapshot>> findById(LandId id);

    CompletionStage<List<LandSnapshot>> findByOwner(OwnerRef owner);

    CompletionStage<List<LandSnapshot>> findAll();

    CompletionStage<Void> delete(LandId id);
}

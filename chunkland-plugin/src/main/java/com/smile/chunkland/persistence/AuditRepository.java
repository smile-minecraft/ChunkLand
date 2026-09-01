package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.LandId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface AuditRepository {

    CompletionStage<Long> insert(AuditEntry entry);

    CompletionStage<Optional<AuditEntry>> findById(long id);

    CompletionStage<List<AuditEntry>> findByLand(LandId landId, int limit, int offset);

    CompletionStage<List<AuditEntry>> findByAction(String action, int limit);

    CompletionStage<List<AuditEntry>> findAll(int limit, int offset);
}

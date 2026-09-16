package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.LandId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface AuditRepository {

    CompletionStage<Long> insert(AuditEntry entry);

    CompletionStage<Optional<AuditEntry>> findById(long id);

    CompletionStage<List<AuditEntry>> findByLand(LandId landId, int limit, int offset);

    CompletionStage<List<AuditEntry>> findByAction(String action, int limit);

    CompletionStage<List<AuditEntry>> findAll(int limit, int offset);

    /**
     * Combined actor/time/action/land/world search, newest-first with
     * {@code LIMIT/OFFSET} paging. Only supplied filters become
     * {@code WHERE} terms; every term is a bound prepared-statement
     * parameter backed by a dedicated audit index.
     */
    CompletionStage<List<AuditEntry>> search(AuditSearchQuery query);

    /**
     * Explain the query plan for {@link #search} without running it, so
     * operators and tests can verify no full table scan is involved.
     * Each returned line is one {@code EXPLAIN QUERY PLAN} detail row.
     */
    CompletionStage<List<String>> explainSearch(AuditSearchQuery query);

    /**
     * Delete audit rows strictly older than {@code cutoff} (event time) and
     * return the number of removed {@code audit_log} rows. Connected
     * {@code audit_chunks} rows go with their audit row; no other table is
     * touched.
     */
    CompletionStage<Integer> purgeOlderThan(Instant cutoff);
}

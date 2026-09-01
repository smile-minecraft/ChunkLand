package com.smile.chunkland.runtime.mutation;

import com.smile.chunkland.api.mutation.MutationOutcome;
import com.smile.chunkland.api.mutation.MutationRequest;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Minimal ledger seam for the coordinator. Full state machine and crash recovery
 * are owned by the next milestone; this interface keeps the current stage ordering
 * testable without pulling in the complete ledger implementation.
 */
public interface LedgerWriter {

    CompletionStage<Void> insert(UUID operationId, MutationRequest request);

    CompletionStage<Void> finalizeState(UUID operationId, MutationOutcome outcome);
}

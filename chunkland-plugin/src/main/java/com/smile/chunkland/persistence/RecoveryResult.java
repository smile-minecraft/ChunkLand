package com.smile.chunkland.persistence;

import java.util.Objects;
import java.util.UUID;

/** Deterministic, audit-friendly result for one startup scan row. */
public record RecoveryResult(
        UUID operationId,
        String previousState,
        String resultingState,
        LedgerState.RecoveryClassification classification,
        String diagnostic) {
    public RecoveryResult {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(previousState, "previousState");
        Objects.requireNonNull(resultingState, "resultingState");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(diagnostic, "diagnostic");
    }
}

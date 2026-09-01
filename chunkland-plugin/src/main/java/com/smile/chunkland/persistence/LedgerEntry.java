package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.LandId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable ledger entry stored in {@code operation_ledger}. */
public record LedgerEntry(
        UUID operationId,
        String operationType,
        String state,
        UUID actor,
        UUID worldId,
        LandId targetLandId,
        Long priceMinorUnits,
        String economyProviderId,
        String economyTransactionRef,
        String payloadJson,
        int metadataVersion,
        Instant createdAt,
        Instant updatedAt,
        int compensationAttempts) {

    public LedgerEntry(
            UUID operationId,
            String operationType,
            String state,
            UUID actor,
            UUID worldId,
            LandId targetLandId,
            Long priceMinorUnits,
            String economyProviderId,
            String economyTransactionRef,
            String payloadJson,
            int metadataVersion,
            Instant createdAt,
            Instant updatedAt) {
        this(operationId, operationType, state, actor, worldId, targetLandId, priceMinorUnits,
                economyProviderId, economyTransactionRef, payloadJson, metadataVersion,
                createdAt, updatedAt, 0);
    }

    public LedgerEntry {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(operationType, "operationType");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (compensationAttempts < 0) {
            throw new IllegalArgumentException("compensationAttempts must not be negative");
        }
    }

    public LedgerState typedState() {
        return LedgerState.parse(state);
    }

    public static LedgerEntry fromPayload(OperationPayload payload, LedgerState state) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(state, "state");
        return fromPayload(payload, state, payload.economyTransactionRef(), payload.updatedAt());
    }

    public static LedgerEntry fromPayload(
            OperationPayload payload, LedgerState state, String transactionRef, Instant updatedAt) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(updatedAt, "updatedAt");
        return new LedgerEntry(payload.operationId(), payload.operationType(), state.name(),
                payload.actorUuid(), payload.worldUuid(), payload.targetLandId(),
                payload.priceMinorUnits(), payload.economyProviderId(), transactionRef,
                payload.toJson(), payload.schemaVersion(), payload.createdAt(), updatedAt, 0);
    }
}

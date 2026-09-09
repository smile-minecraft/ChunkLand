package com.smile.chunkland.refund;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.CostBasisCalculator;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.persistence.ChunkRepository;
import com.smile.chunkland.persistence.LandRepository;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.runtime.vertical.VerticalDepths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Production validator that resolves a refund against durable storage.
 *
 * <p>Reads the land row for ownership and the durable per-chunk cost basis for
 * membership and amount math. Unknown lands, server-owned lands, chunks that
 * are no longer part of the land, missing or negative stored bases, and
 * invalid ratios are all rejected fail-closed: no ledger row, no domain
 * write, no Economy call. The current pricing table is never consulted; the
 * refund amount is the durable basis times the requested ratio in integer
 * minor units.
 */
public final class DurableRefundValidator implements RefundValidator {

    private final LandRepository lands;
    private final ChunkRepository chunks;
    private final Currency currency;

    public DurableRefundValidator(LandRepository lands, ChunkRepository chunks, Currency currency) {
        this.lands = Objects.requireNonNull(lands, "lands");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    @Override
    public CompletionStage<ValidatedRefund> validate(RefundRequest request) {
        Objects.requireNonNull(request, "request");
        UUID operationId = request.operationId() != null ? request.operationId() : UUID.randomUUID();
        CompletionStage<Optional<LandSnapshot>> land;
        try {
            land = lands.findById(request.landId());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new RefundRejectedException("refund.validation_failed", failure));
        }
        if (land == null) {
            return CompletableFuture.failedFuture(new RefundRejectedException("refund.validation_failed"));
        }
        return land.thenCompose(snapshot -> validateAgainst(snapshot, request, operationId));
    }

    private CompletionStage<ValidatedRefund> validateAgainst(
            Optional<LandSnapshot> snapshot, RefundRequest request, UUID operationId) {
        if (snapshot == null || snapshot.isEmpty()) {
            return rejected("refund.unknown_land");
        }
        LandSnapshot land = snapshot.get();
        if (land.ownerRef() instanceof OwnerRef.ServerOwnerRef) {
            return rejected("refund.server_land_no_refund");
        }
        if (!land.worldId().equals(request.worldUuid())) {
            return rejected("refund.invalid_request");
        }
        CompletionStage<Map<ChunkKey, ChunkRepository.ChunkFact>> facts;
        try {
            facts = chunks.factsByLand(request.landId());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new RefundRejectedException("refund.validation_failed", failure));
        }
        if (facts == null) {
            return CompletableFuture.failedFuture(new RefundRejectedException("refund.validation_failed"));
        }
        return facts.thenApply(all -> buildRefund(request, operationId, land, all));
    }

    private ValidatedRefund buildRefund(RefundRequest request, UUID operationId,
            LandSnapshot land, Map<ChunkKey, ChunkRepository.ChunkFact> all) {
        if (all == null) {
            throw new RefundRejectedException("refund.validation_failed");
        }
        List<ChunkKey> ordered = new ArrayList<>(request.chunks());
        ordered.sort(Comparator.comparingInt(ChunkKey::chunkX).thenComparingInt(ChunkKey::chunkZ));
        List<OperationPayload.Chunk> payloadChunks = new ArrayList<>(ordered.size());
        long total = 0L;
        for (ChunkKey key : ordered) {
            ChunkRepository.ChunkFact fact = all.get(key);
            if (fact == null) {
                throw new RefundRejectedException("refund.stale");
            }
            Long basis = fact.costBasisMinorUnits();
            if (basis == null) {
                throw new RefundRejectedException("refund.missing_basis");
            }
            if (basis < 0) {
                throw new RefundRejectedException("refund.invalid_basis");
            }
            try {
                total = Math.addExact(total, basis);
            } catch (ArithmeticException overflow) {
                throw new RefundRejectedException("refund.invalid_basis", overflow);
            }
            int storedMin = fact.storedMinProtectedY() != null
                    ? fact.storedMinProtectedY() : VerticalDepths.LEGACY_STORED_FALLBACK_Y;
            UUID lot = fact.claimLotId() != null ? fact.claimLotId() : syntheticLot(operationId, key);
            payloadChunks.add(new OperationPayload.Chunk(key, storedMin, lot, basis));
        }
        final Money refund;
        try {
            refund = CostBasisCalculator.refund(new Money(total, currency),
                    request.refundNumerator(), request.refundDenominator());
        } catch (IllegalArgumentException invalid) {
            throw new RefundRejectedException("refund.invalid_ratio", invalid);
        } catch (ArithmeticException overflow) {
            throw new RefundRejectedException("refund.invalid_basis", overflow);
        }
        String displayName = land.displayName() == null || land.displayName().isBlank()
                ? "Refunded land" : land.displayName();
        return new ValidatedRefund(operationId, request.actorUuid(), request.worldUuid(),
                request.landId(), displayName, List.copyOf(payloadChunks), total, refund.minorUnits(),
                request.refundNumerator(), request.refundDenominator());
    }

    /**
     * Deterministic lot id for legacy rows that predate lot tracking. The lot
     * is inert for refunds (the commit only re-checks land membership and cost
     * basis), so a stable synthetic value keeps the payload self-contained
     * without inventing durable history.
     */
    static UUID syntheticLot(UUID operationId, ChunkKey chunk) {
        String seed = "refund-lot:" + operationId + ":" + chunk.chunkX() + ":" + chunk.chunkZ();
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static <T> CompletionStage<T> rejected(String key) {
        return CompletableFuture.failedFuture(new RefundRejectedException(key));
    }
}

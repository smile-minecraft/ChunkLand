package com.smile.chunkland.refund;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.LedgerState;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.RefundCommit;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Single production execution path for land refunds.
 *
 * <p>Step order is fixed and independent from the claim path: validate the
 * request against durable storage, take logical reservations, record the
 * {@code CREATED} refund ledger row in its own transaction, commit the domain
 * (release chunks, write the refund audit, advance to {@code DOMAIN_COMMITTED})
 * in one atomic transaction, deposit through Economy with the operation id as
 * the idempotency key strictly outside any SQL transaction, publish a fresh
 * runtime snapshot from the authoritative database, and finalize the row.
 *
 * <p>The order is deliberately the reverse of the claim path: once the domain
 * commit succeeds it is never rolled back. A failed or unconfirmed deposit
 * parks the row in {@code COMPENSATION_PENDING} (the shared recovery retry
 * contract) and a confirmed deposit settles it as {@code COMPENSATED}; the
 * retry limit moves it to {@code NEEDS_RECONCILIATION}. No step of this saga
 * calls into the claim ordering, and the refund amount always comes from the
 * durable per-chunk cost basis times the requested ratio, never from the
 * current pricing table.
 *
 * <p>Threading: validation starts on the caller thread; every continuation
 * runs on the injected async executor, so Economy deposits and publish work
 * never run on the persistence thread. No stage blocks a thread waiting, and
 * no sleep, polling loop, or unbounded executor is used.
 */
public final class RefundSaga {

    /** Operation type stored on refund ledger rows. */
    public static final String REFUND_OPERATION_TYPE = "REFUND";

    /** Marker recorded as the transaction ref when no money moves. */
    public static final String ZERO_VALUE_TRANSACTION_REF = "zero-value";

    /** Diagnostic key when a reused operation id carries a different refund identity. */
    public static final String IDEMPOTENCY_CONFLICT_KEY = "refund.idempotency_conflict";

    private final RefundValidator validator;
    private final LogicalReservationRegistry reservations;
    private final OperationLedger ledger;
    private final RefundEconomy economy;
    private final RuntimeRegistryRebuilder rebuilder;
    private final Clock clock;
    private final Executor asyncExecutor;
    private final int compensationRetryLimit;
    private final ConcurrentHashMap<UUID, InFlight> inFlight = new ConcurrentHashMap<>();

    private static final class InFlight {
        final RefundRequest request;
        final CompletableFuture<RefundResult> future;

        InFlight(RefundRequest request, CompletableFuture<RefundResult> future) {
            this.request = request;
            this.future = future;
        }
    }

    public RefundSaga(
            RefundValidator validator,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            RefundEconomy economy,
            RuntimeRegistryRebuilder rebuilder,
            Clock clock,
            Executor asyncExecutor,
            int compensationRetryLimit) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.rebuilder = Objects.requireNonNull(rebuilder, "rebuilder");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor");
        if (compensationRetryLimit < 1) {
            throw new IllegalArgumentException("compensationRetryLimit must be positive");
        }
        this.compensationRetryLimit = compensationRetryLimit;
    }

    /**
     * Execute one refund.
     *
     * <p>Concurrent calls sharing an operation id observe a single execution:
     * the second caller shares the in-flight outcome instead of committing or
     * depositing twice.
     */
    public CompletionStage<RefundResult> refund(RefundRequest request) {
        Objects.requireNonNull(request, "request");
        UUID operationId = request.operationId() != null ? request.operationId() : UUID.randomUUID();
        RefundRequest effective = request.operationId() != null ? request : request.withOperationId(operationId);
        CompletableFuture<RefundResult> slot = new CompletableFuture<>();
        InFlight holder = new InFlight(effective, slot);
        InFlight existing = inFlight.putIfAbsent(operationId, holder);
        if (existing != null) {
            if (existing.request != null && !sameRequestIdentity(existing.request, effective)) {
                return CompletableFuture.completedFuture(
                        RefundResult.rejected(IDEMPOTENCY_CONFLICT_KEY));
            }
            return existing.future.minimalCompletionStage();
        }
        slot.whenComplete((ignored, failure) -> inFlight.remove(operationId, holder));
        gateNew(effective, slot);
        return slot.minimalCompletionStage();
    }

    /**
     * Resume or retry a refund by operation id.
     *
     * <p>Terminal rows replay their outcome without touching the domain or
     * Economy; {@code CREATED} rows re-commit the domain, {@code
     * DOMAIN_COMMITTED} and {@code COMPENSATION_PENDING} rows retry the
     * deposit. This is the seam a later caller (for example an online-player
     * retry) uses without revalidating the original request.
     */
    public CompletionStage<RefundResult> retry(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        CompletableFuture<RefundResult> slot = new CompletableFuture<>();
        InFlight holder = new InFlight(null, slot);
        InFlight existing = inFlight.putIfAbsent(operationId, holder);
        if (existing != null) {
            return existing.future.minimalCompletionStage();
        }
        slot.whenComplete((ignored, failure) -> inFlight.remove(operationId, holder));
        CompletionStage<Optional<LedgerEntry>> found;
        try {
            found = ledger.findById(operationId);
        } catch (RuntimeException failure) {
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return slot.minimalCompletionStage();
        }
        if (found == null) {
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return slot.minimalCompletionStage();
        }
        found.whenCompleteAsync((entry, failure) -> {
            if (failure != null || entry == null) {
                complete(slot, RefundResult.failed("refund.ledger_failed"));
                return;
            }
            if (entry.isEmpty()) {
                complete(slot, RefundResult.failed("refund.unknown_operation"));
                return;
            }
            resumeExisting(entry.get(), slot);
        }, asyncExecutor);
        return slot.minimalCompletionStage();
    }

    // ---- Step 1: validate against durable storage. ----

    /**
     * Idempotency gate ahead of validation: a repeated operation id resumes
     * from its ledger row even when the domain already changed (which would
     * fail revalidation), so duplicates never recommit or redeposit.
     */
    private void gateNew(RefundRequest request, CompletableFuture<RefundResult> slot) {
        CompletionStage<Optional<LedgerEntry>> found;
        try {
            found = ledger.findById(request.operationId());
        } catch (RuntimeException failure) {
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return;
        }
        if (found == null) {
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return;
        }
        found.whenCompleteAsync((entry, failure) -> {
            if (failure != null || entry == null) {
                complete(slot, RefundResult.failed("refund.ledger_failed"));
                return;
            }
            if (entry.isPresent()) {
                if (isUnparseableRefundPayload(entry.get())) {
                    handleMalformedExisting(entry.get(), slot);
                    return;
                }
                if (!requestMatchesEntry(entry.get(), request)) {
                    complete(slot, RefundResult.rejected(IDEMPOTENCY_CONFLICT_KEY));
                    return;
                }
                resumeExisting(entry.get(), slot);
                return;
            }
            executeNew(request, slot);
        }, asyncExecutor);
    }

    private void executeNew(RefundRequest request, CompletableFuture<RefundResult> slot) {
        CompletionStage<ValidatedRefund> validated;
        try {
            validated = validator.validate(request);
        } catch (RefundRejectedException rejected) {
            complete(slot, RefundResult.rejected(rejected.diagnosticKey()));
            return;
        } catch (RuntimeException failure) {
            complete(slot, RefundResult.failed("refund.validation_failed"));
            return;
        }
        if (validated == null) {
            complete(slot, RefundResult.failed("refund.validation_failed"));
            return;
        }
        validated.whenCompleteAsync((plan, failure) -> {
            if (failure != null || plan == null) {
                complete(slot, rejectedOrFailed(failure));
                return;
            }
            driveValidated(plan, slot);
        }, asyncExecutor);
    }

    private static RefundResult rejectedOrFailed(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof RefundRejectedException rejected) {
                return RefundResult.rejected(rejected.diagnosticKey());
            }
            current = current.getCause();
        }
        return RefundResult.failed("refund.validation_failed");
    }

    // ---- Idempotency identity: a reused operation id must carry the same refund. ----

    /**
     * Raw request identity for concurrent duplicates that share an in-flight
     * slot: actor, world, land, exact chunk set, and mathematically equivalent
     * ratio. Amounts are not compared here because the durable cost basis is
     * not known yet; the durable gates below re-check the money effect. Ratios
     * use canonical mathematical equivalence (1/2 == 2/4) so the in-flight gate
     * agrees with the durable gate.
     */
    private static boolean sameRequestIdentity(RefundRequest first, RefundRequest second) {
        if (!first.actorUuid().equals(second.actorUuid())) {
            return false;
        }
        if (!first.worldUuid().equals(second.worldUuid())) {
            return false;
        }
        if (!first.landId().equals(second.landId())) {
            return false;
        }
        if (!first.chunks().equals(second.chunks())) {
            return false;
        }
        return ratiosEquivalent(first.refundNumerator(), first.refundDenominator(),
                second.refundNumerator(), second.refundDenominator());
    }

    /**
     * Canonical refund ratio equivalence: two ratios are the same identity
     * when they denote the same rational number. Cross-multiplication uses
     * BigInteger so large minor-unit ratios never overflow into a false
     * mismatch or a false match.
     */
    static boolean ratiosEquivalent(long firstNumerator, long firstDenominator,
            long secondNumerator, long secondDenominator) {
        if (firstDenominator <= 0 || secondDenominator <= 0) {
            return false;
        }
        return java.math.BigInteger.valueOf(firstNumerator).multiply(
                        java.math.BigInteger.valueOf(secondDenominator))
                .equals(java.math.BigInteger.valueOf(secondNumerator).multiply(
                        java.math.BigInteger.valueOf(firstDenominator)));
    }

    /**
     * Durable gate ahead of validation: the new request matches the stored
     * payload only when operation type, actor, world, land, sorted unique
     * chunk coordinates, canonical ratio equivalence, and the ratio-implied
     * amount all agree. A stored ratio, when present, must be mathematically
     * equivalent to the request ratio; legacy rows without a stored ratio fall
     * back to the amount check. Corrupt payloads never match: callers route
     * them to quarantine handling first, and this returns false so a malformed
     * row can never be mistaken for an identity match. A non-refund row is
     * always a conflict for a refund request.
     */
    private static boolean requestMatchesEntry(LedgerEntry entry, RefundRequest request) {
        if (!REFUND_OPERATION_TYPE.equals(entry.operationType())) {
            return false;
        }
        final OperationPayload payload;
        try {
            payload = OperationPayload.fromJson(entry.payloadJson());
        } catch (RuntimeException malformed) {
            return false;
        }
        if (!REFUND_OPERATION_TYPE.equals(payload.operationType())) {
            return false;
        }
        if (!payload.operationId().equals(request.operationId())) {
            return false;
        }
        if (!payload.actorUuid().equals(request.actorUuid())) {
            return false;
        }
        if (!payload.worldUuid().equals(request.worldUuid())) {
            return false;
        }
        if (payload.targetLandId() == null || !payload.targetLandId().equals(request.landId())) {
            return false;
        }
        if (!chunkCoordinatesOf(payload).equals(chunkCoordinatesOf(request))) {
            return false;
        }
        if (payload.refundNumerator() != null && payload.refundDenominator() != null) {
            if (!ratiosEquivalent(payload.refundNumerator(), payload.refundDenominator(),
                    request.refundNumerator(), request.refundDenominator())) {
                return false;
            }
        }
        long totalBasis;
        try {
            totalBasis = 0L;
            for (OperationPayload.Chunk chunk : payload.chunkSet()) {
                totalBasis = Math.addExact(totalBasis, chunk.costBasisMinorUnits());
            }
        } catch (ArithmeticException overflow) {
            return false;
        }
        final long expected;
        try {
            expected = refundAmount(totalBasis, request.refundNumerator(), request.refundDenominator());
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return false;
        }
        return expected == payload.priceMinorUnits();
    }

    /**
     * Durable gate after validation: the validated plan matches the stored
     * payload only when operation type, actor, world, land, per-chunk
     * coordinates plus cost basis, canonical ratio equivalence, total basis,
     * and refund amount all agree. Stored and validated ratios, when both
     * present, must be mathematically equivalent; legacy rows without a stored
     * ratio fall back to the amount check. Corrupt payloads never match.
     */
    private static boolean validatedMatchesEntry(LedgerEntry entry, ValidatedRefund plan) {
        if (!REFUND_OPERATION_TYPE.equals(entry.operationType())) {
            return false;
        }
        final OperationPayload payload;
        try {
            payload = OperationPayload.fromJson(entry.payloadJson());
        } catch (RuntimeException malformed) {
            return false;
        }
        if (!REFUND_OPERATION_TYPE.equals(payload.operationType())) {
            return false;
        }
        if (!payload.operationId().equals(plan.operationId())) {
            return false;
        }
        if (!payload.actorUuid().equals(plan.actorUuid())) {
            return false;
        }
        if (!payload.worldUuid().equals(plan.worldUuid())) {
            return false;
        }
        if (payload.targetLandId() == null || !payload.targetLandId().equals(plan.landId())) {
            return false;
        }
        if (!chunkBasisOf(payload).equals(chunkBasisOf(plan))) {
            return false;
        }
        if (payload.refundNumerator() != null && payload.refundDenominator() != null
                && plan.refundNumerator() != null && plan.refundDenominator() != null) {
            if (!ratiosEquivalent(payload.refundNumerator(), payload.refundDenominator(),
                    plan.refundNumerator(), plan.refundDenominator())) {
                return false;
            }
        }
        long payloadTotal;
        try {
            payloadTotal = 0L;
            for (OperationPayload.Chunk chunk : payload.chunkSet()) {
                payloadTotal = Math.addExact(payloadTotal, chunk.costBasisMinorUnits());
            }
        } catch (ArithmeticException overflow) {
            return false;
        }
        return payloadTotal == plan.totalCostBasisMinorUnits()
                && payload.priceMinorUnits() == plan.refundAmountMinorUnits();
    }

    private static Set<String> chunkCoordinatesOf(OperationPayload payload) {
        Set<String> coords = new HashSet<>();
        for (OperationPayload.Chunk chunk : payload.chunkSet()) {
            ChunkKey key = chunk.chunk();
            coords.add(key.worldId() + ":" + key.chunkX() + ":" + key.chunkZ());
        }
        return Set.copyOf(coords);
    }

    private static Set<String> chunkCoordinatesOf(RefundRequest request) {
        Set<String> coords = new HashSet<>();
        for (ChunkKey key : request.chunks()) {
            coords.add(key.worldId() + ":" + key.chunkX() + ":" + key.chunkZ());
        }
        return Set.copyOf(coords);
    }

    private static Set<String> chunkBasisOf(OperationPayload payload) {
        Set<String> coords = new HashSet<>();
        for (OperationPayload.Chunk chunk : payload.chunkSet()) {
            ChunkKey key = chunk.chunk();
            coords.add(key.worldId() + ":" + key.chunkX() + ":" + key.chunkZ()
                    + ":" + chunk.costBasisMinorUnits());
        }
        return Set.copyOf(coords);
    }

    private static Set<String> chunkBasisOf(ValidatedRefund plan) {
        Set<String> coords = new HashSet<>();
        for (OperationPayload.Chunk chunk : plan.chunks()) {
            ChunkKey key = chunk.chunk();
            coords.add(key.worldId() + ":" + key.chunkX() + ":" + key.chunkZ()
                    + ":" + chunk.costBasisMinorUnits());
        }
        return Set.copyOf(coords);
    }

    /**
     * Half-up {@code total * numerator / denominator} on minor units, mirroring
     * the durable refund math without a currency dependency.
     */
    private static long refundAmount(long totalBasis, long numerator, long denominator) {
        if (totalBasis < 0) {
            throw new IllegalArgumentException("totalBasis must not be negative");
        }
        if (numerator < 0 || denominator <= 0 || numerator > denominator) {
            throw new IllegalArgumentException("invalid refund ratio");
        }
        long product = Math.multiplyExact(totalBasis, numerator);
        long quotient = product / denominator;
        long remainder = product % denominator;
        long threshold = denominator / 2 + denominator % 2;
        if (remainder >= threshold) {
            quotient = Math.incrementExact(quotient);
        }
        return quotient;
    }

    // ---- Idempotency gate: an existing row resumes instead of restarting. ----

    private void driveValidated(ValidatedRefund plan, CompletableFuture<RefundResult> slot) {
        CompletionStage<Optional<LedgerEntry>> found;
        try {
            found = ledger.findById(plan.operationId());
        } catch (RuntimeException failure) {
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return;
        }
        if (found == null) {
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return;
        }
        found.whenCompleteAsync((entry, failure) -> {
            if (failure != null || entry == null) {
                complete(slot, RefundResult.failed("refund.ledger_failed"));
                return;
            }
            if (entry.isPresent()) {
                if (isUnparseableRefundPayload(entry.get())) {
                    handleMalformedExisting(entry.get(), slot);
                    return;
                }
                if (!validatedMatchesEntry(entry.get(), plan)) {
                    complete(slot, RefundResult.rejected(IDEMPOTENCY_CONFLICT_KEY));
                    return;
                }
                resumeExisting(entry.get(), slot);
                return;
            }
            driveFresh(plan, slot);
        }, asyncExecutor);
    }

    /**
     * A stored refund payload is unparseable when its JSON cannot be decoded
     * or it does not describe a refund for this row. Such rows never count as
     * an identity match: they fail closed through quarantine handling instead
     * of resuming into Economy, the domain commit, or the runtime rebuild.
     */
    private static boolean isUnparseableRefundPayload(LedgerEntry entry) {
        if (!REFUND_OPERATION_TYPE.equals(entry.operationType())) {
            return false;
        }
        try {
            OperationPayload payload = OperationPayload.fromJson(entry.payloadJson());
            return !REFUND_OPERATION_TYPE.equals(payload.operationType())
                    || !entry.operationId().equals(payload.operationId());
        } catch (RuntimeException malformed) {
            return true;
        }
    }

    /**
     * Fail-closed handling for an existing row whose payload cannot be
     * trusted. Non-terminal rows quarantine to NEEDS_RECONCILIATION without
     * touching Economy, the domain, or the runtime; terminal rows keep their
     * terminal state and report a fail-closed invalid-payload result so the
     * row stays available for manual reconciliation.
     */
    private void handleMalformedExisting(LedgerEntry entry, CompletableFuture<RefundResult> slot) {
        final LedgerState state;
        try {
            state = LedgerState.parse(entry.state());
        } catch (RuntimeException malformed) {
            complete(slot, RefundResult.failed("refund.invalid_payload"));
            return;
        }
        if (state.isTerminal()) {
            if (state == LedgerState.NEEDS_RECONCILIATION) {
                complete(slot, RefundResult.needsReconciliation(
                        entry.targetLandId(), entry.priceMinorUnits()));
                return;
            }
            if (state == LedgerState.FAILED) {
                complete(slot, RefundResult.failed("refund.failed"));
                return;
            }
            complete(slot, RefundResult.failed("refund.invalid_payload"));
            return;
        }
        quarantine(entry.operationId(), state, slot);
    }

    private void resumeExisting(LedgerEntry entry, CompletableFuture<RefundResult> slot) {
        if (!REFUND_OPERATION_TYPE.equals(entry.operationType())) {
            complete(slot, RefundResult.rejected("refund.unsupported"));
            return;
        }
        final LedgerState state;
        try {
            state = LedgerState.parse(entry.state());
        } catch (RuntimeException malformed) {
            complete(slot, RefundResult.failed("refund.invalid_payload"));
            return;
        }
        if (isUnparseableRefundPayload(entry)) {
            handleMalformedExisting(entry, slot);
            return;
        }
        switch (state) {
            case COMPENSATED -> complete(slot, RefundResult.success(
                    entry.targetLandId(), entry.priceMinorUnits() == null ? 0L : entry.priceMinorUnits()));
            case NEEDS_RECONCILIATION -> complete(slot,
                    RefundResult.needsReconciliation(entry.targetLandId(), entry.priceMinorUnits()));
            case FAILED -> complete(slot, RefundResult.failed("refund.failed"));
            case CREATED -> resumeCreated(entry, slot);
            case DOMAIN_COMMITTED, COMPENSATION_PENDING -> depositThenPublish(
                    entry.operationId(), amountOf(entry), entry.targetLandId(), slot);
            default -> complete(slot, RefundResult.failed("refund.unexpected_state"));
        }
    }

    private static long amountOf(LedgerEntry entry) {
        return entry.priceMinorUnits() == null ? 0L : entry.priceMinorUnits();
    }

    private void resumeCreated(LedgerEntry entry, CompletableFuture<RefundResult> slot) {
        final ValidatedRefund plan;
        try {
            plan = validatedFromPayload(entry);
        } catch (RefundRejectedException rejected) {
            quarantine(entry.operationId(), LedgerState.CREATED, slot);
            return;
        } catch (RuntimeException failure) {
            // Unparseable JSON carries no trustworthy plan: quarantine the
            // non-terminal row instead of failing open into a domain commit.
            quarantine(entry.operationId(), LedgerState.CREATED, slot);
            return;
        }
        if (plan == null) {
            complete(slot, RefundResult.failed("refund.invalid_payload"));
            return;
        }
        Set<String> keys = reservationKeys(plan);
        if (!reservations.tryAcquire(keys, plan.operationId())) {
            complete(slot, RefundResult.rejected("refund.reservation_conflict"));
            return;
        }
        commitDomain(plan, keys, slot);
    }

    private static ValidatedRefund validatedFromPayload(LedgerEntry entry) {
        OperationPayload payload = OperationPayload.fromJson(entry.payloadJson());
        if (!REFUND_OPERATION_TYPE.equals(payload.operationType())) {
            throw new RefundRejectedException("refund.invalid_payload");
        }
        if (!entry.operationId().equals(payload.operationId())) {
            throw new RefundRejectedException("refund.invalid_payload");
        }
        if (payload.chunkSet().isEmpty()) {
            throw new RefundRejectedException("refund.invalid_payload");
        }
        long total = 0L;
        for (OperationPayload.Chunk chunk : payload.chunkSet()) {
            if (chunk.costBasisMinorUnits() < 0) {
                throw new RefundRejectedException("refund.invalid_basis");
            }
            try {
                total = Math.addExact(total, chunk.costBasisMinorUnits());
            } catch (ArithmeticException overflow) {
                throw new RefundRejectedException("refund.invalid_basis", overflow);
            }
        }
        if (payload.priceMinorUnits() < 0 || payload.priceMinorUnits() > total) {
            throw new RefundRejectedException("refund.invalid_payload");
        }
        String displayName = payload.landDisplayName() == null || payload.landDisplayName().isBlank()
                ? "Refunded land" : payload.landDisplayName();
        return new ValidatedRefund(payload.operationId(), payload.actorUuid(), payload.worldUuid(),
                payload.targetLandId(), displayName, payload.chunkSet(), total, payload.priceMinorUnits(),
                payload.refundNumerator(), payload.refundDenominator());
    }

    private void quarantine(UUID operationId, LedgerState expected, CompletableFuture<RefundResult> slot) {
        CompletionStage<Void> quarantined;
        try {
            quarantined = ledger.quarantine(operationId, expected, clock.instant());
        } catch (RuntimeException failure) {
            complete(slot, RefundResult.failed("refund.invalid_payload"));
            return;
        }
        if (quarantined == null) {
            complete(slot, RefundResult.failed("refund.invalid_payload"));
            return;
        }
        quarantined.whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                // The durable state is unconfirmed: report an explicit ledger
                // failure instead of claiming a quarantine that never happened.
                complete(slot, RefundResult.failed("refund.ledger_failed"));
                return;
            }
            complete(slot, RefundResult.needsReconciliation(null, null));
        }, asyncExecutor);
    }

    // ---- Steps 2-3: reserve, then record the CREATED row in its own transaction. ----

    private void driveFresh(ValidatedRefund plan, CompletableFuture<RefundResult> slot) {
        boolean available;
        try {
            available = economy.isAvailable();
        } catch (RuntimeException failure) {
            available = false;
        }
        if (!available) {
            complete(slot, RefundResult.rejected("refund.economy_unavailable"));
            return;
        }
        String providerId;
        try {
            providerId = economy.providerId();
        } catch (RuntimeException failure) {
            complete(slot, RefundResult.rejected("refund.unknown_provider"));
            return;
        }
        if (providerId == null || providerId.isBlank()) {
            complete(slot, RefundResult.rejected("refund.unknown_provider"));
            return;
        }
        Set<String> keys = reservationKeys(plan);
        if (!reservations.tryAcquire(keys, plan.operationId())) {
            complete(slot, RefundResult.rejected("refund.reservation_conflict"));
            return;
        }
        OperationPayload payload;
        try {
            payload = buildRefundPayload(plan, providerId, clock.instant());
        } catch (RuntimeException failure) {
            release(keys, plan.operationId());
            complete(slot, RefundResult.failed("refund.invalid_request"));
            return;
        }
        CompletionStage<Void> created;
        try {
            created = ledger.create(payload);
        } catch (RuntimeException failure) {
            release(keys, plan.operationId());
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return;
        }
        if (created == null) {
            release(keys, plan.operationId());
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return;
        }
        created.whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                // A lost insert race lands here: release and resume from the
                // winning row instead of refunding twice.
                release(keys, plan.operationId());
                resumeAfterInsertRace(plan, slot);
                return;
            }
            commitDomain(plan, keys, slot);
        }, asyncExecutor);
    }

    private void resumeAfterInsertRace(ValidatedRefund plan, CompletableFuture<RefundResult> slot) {
        CompletionStage<Optional<LedgerEntry>> found;
        try {
            found = ledger.findById(plan.operationId());
        } catch (RuntimeException failure) {
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return;
        }
        if (found == null) {
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return;
        }
        found.whenCompleteAsync((entry, failure) -> {
            if (failure != null || entry == null || entry.isEmpty()) {
                complete(slot, RefundResult.failed("refund.ledger_failed"));
                return;
            }
            if (isUnparseableRefundPayload(entry.get())) {
                handleMalformedExisting(entry.get(), slot);
                return;
            }
            if (!validatedMatchesEntry(entry.get(), plan)) {
                complete(slot, RefundResult.rejected(IDEMPOTENCY_CONFLICT_KEY));
                return;
            }
            resumeExisting(entry.get(), slot);
        }, asyncExecutor);
    }

    // ---- Step 4: single atomic domain transaction. Money never moves before this. ----

    private void commitDomain(ValidatedRefund plan, Set<String> keys, CompletableFuture<RefundResult> slot) {
        RefundCommit commit;
        try {
            commit = buildRefundCommit(plan, clock.instant());
        } catch (RuntimeException failure) {
            release(keys, plan.operationId());
            complete(slot, RefundResult.failed("refund.invalid_request"));
            return;
        }
        CompletionStage<Void> committed;
        try {
            committed = ledger.commitRefundAtomically(commit);
        } catch (RuntimeException failure) {
            release(keys, plan.operationId());
            complete(slot, RefundResult.failed("refund.commit_failed"));
            return;
        }
        if (committed == null) {
            release(keys, plan.operationId());
            complete(slot, RefundResult.failed("refund.commit_failed"));
            return;
        }
        committed.whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                // The transaction rolled back: the domain is untouched, no
                // deposit was attempted, and the row is not compensated.
                release(keys, plan.operationId());
                complete(slot, RefundResult.failed("refund.commit_failed"));
                return;
            }
            release(keys, plan.operationId());
            depositThenPublish(plan.operationId(), plan.refundAmountMinorUnits(), plan.landId(), slot);
        }, asyncExecutor);
    }

    // ---- Step 5: Economy deposit outside any SQL transaction. ----

    private void depositThenPublish(UUID operationId, long amount, com.smile.chunkland.api.land.LandId landId,
            CompletableFuture<RefundResult> slot) {
        CompletionStage<LedgerEntry> found;
        try {
            found = ledger.find(operationId);
        } catch (RuntimeException failure) {
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return;
        }
        if (found == null) {
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return;
        }
        found.whenCompleteAsync((entry, failure) -> {
            if (failure != null || entry == null) {
                complete(slot, RefundResult.failed("refund.ledger_failed"));
                return;
            }
            final LedgerState committed;
            try {
                committed = LedgerState.parse(entry.state());
            } catch (RuntimeException malformed) {
                complete(slot, RefundResult.failed("refund.invalid_payload"));
                return;
            }
            if (committed != LedgerState.DOMAIN_COMMITTED
                    && committed != LedgerState.COMPENSATION_PENDING) {
                complete(slot, RefundResult.failed("refund.unexpected_state"));
                return;
            }
            // The re-read row must still carry a trustworthy refund payload:
            // a corrupt payload quarantines instead of moving money, even when
            // the state column still looks depositable.
            try {
                OperationPayload reread = OperationPayload.fromJson(entry.payloadJson());
                if (!REFUND_OPERATION_TYPE.equals(reread.operationType())
                        || !operationId.equals(reread.operationId())) {
                    throw new IllegalArgumentException("refund payload does not match ledger row");
                }
            } catch (RuntimeException malformed) {
                quarantine(entry.operationId(), committed, slot);
                return;
            }
            if (amount == 0L) {
                settleCompensated(entry, committed, ZERO_VALUE_TRANSACTION_REF, slot);
                return;
            }
            CompletionStage<RefundOutcome> deposit;
            try {
                deposit = economy.deposit(entry);
            } catch (Throwable thrown) {
                depositUnconfirmed(entry, committed, slot);
                return;
            }
            if (deposit == null) {
                depositUnconfirmed(entry, committed, slot);
                return;
            }
            deposit.whenCompleteAsync((outcome, depositFailure) -> {
                if (depositFailure != null || outcome == null) {
                    depositUnconfirmed(entry, committed, slot);
                    return;
                }
                if (outcome == RefundOutcome.REFUNDED) {
                    settleCompensated(entry, committed, "refund:" + operationId, slot);
                    return;
                }
                depositUnconfirmed(entry, committed, slot);
            }, asyncExecutor);
        }, asyncExecutor);
    }

    /**
     * Route an unconfirmed deposit: a first failure parks the row for
     * compensation, while a row that is already parked only advances its
     * durable retry count. Neither path rolls back the domain.
     */
    private void depositUnconfirmed(LedgerEntry entry, LedgerState committed,
            CompletableFuture<RefundResult> slot) {
        if (committed == LedgerState.COMPENSATION_PENDING) {
            recordCompensationAttempt(entry, slot);
            return;
        }
        parkForCompensation(entry, slot);
    }

    private void settleCompensated(LedgerEntry entry, LedgerState committed, String transactionRef,
            CompletableFuture<RefundResult> slot) {
        CompletionStage<Void> settled;
        try {
            settled = ledger.settleRefundCompensated(
                    entry.operationId(), committed, transactionRef, clock.instant());
        } catch (RuntimeException failure) {
            // Money may have moved but the marking failed: degrade so recovery
            // (which parks refund rows from DOMAIN_COMMITTED and retries
            // through the idempotent deposit) can settle the row.
            complete(slot, RefundResult.degraded(
                    entry.targetLandId(), amountOf(entry), "refund.finalize_failed"));
            return;
        }
        if (settled == null) {
            complete(slot, RefundResult.degraded(
                    entry.targetLandId(), amountOf(entry), "refund.finalize_failed"));
            return;
        }
        settled.whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                complete(slot, RefundResult.degraded(
                        entry.targetLandId(), amountOf(entry), "refund.finalize_failed"));
                return;
            }
            publishAndSucceed(entry, slot);
        }, asyncExecutor);
    }

    // ---- Deposit unconfirmed: park for compensation, never roll back. ----

    private void parkForCompensation(LedgerEntry entry, CompletableFuture<RefundResult> slot) {
        CompletionStage<Void> parked;
        try {
            parked = ledger.parkForRefundCompensation(
                    entry.operationId(), "refund:" + entry.operationId(), clock.instant());
        } catch (RuntimeException failure) {
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return;
        }
        if (parked == null) {
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return;
        }
        parked.whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                complete(slot, RefundResult.failed("refund.ledger_failed"));
                return;
            }
            recordCompensationAttempt(entry, slot);
        }, asyncExecutor);
    }

    private void recordCompensationAttempt(LedgerEntry entry, CompletableFuture<RefundResult> slot) {
        CompletionStage<OperationLedger.CompensationDecision> recorded;
        try {
            recorded = ledger.recordCompensationFailure(
                    entry.operationId(), compensationRetryLimit, clock.instant());
        } catch (RuntimeException failure) {
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return;
        }
        if (recorded == null) {
            complete(slot, RefundResult.failed("refund.ledger_failed"));
            return;
        }
        recorded.whenCompleteAsync((decision, failure) -> {
            if (failure != null || decision == null) {
                complete(slot, RefundResult.failed("refund.ledger_failed"));
                return;
            }
            if (decision.resultingState() == LedgerState.NEEDS_RECONCILIATION) {
                complete(slot, RefundResult.needsReconciliation(entry.targetLandId(), amountOf(entry)));
                return;
            }
            complete(slot, RefundResult.compensationPending(entry.targetLandId(), amountOf(entry)));
        }, asyncExecutor);
    }

    // ---- Steps 6-7: publish from authoritative state, then report. ----

    private void publishAndSucceed(LedgerEntry entry, CompletableFuture<RefundResult> slot) {
        CompletionStage<?> rebuilt;
        try {
            rebuilt = rebuilder.rebuild();
        } catch (RuntimeException failure) {
            complete(slot, RefundResult.degraded(
                    entry.targetLandId(), amountOf(entry), "refund.publish_failed"));
            return;
        }
        if (rebuilt == null) {
            complete(slot, RefundResult.degraded(
                    entry.targetLandId(), amountOf(entry), "refund.publish_failed"));
            return;
        }
        rebuilt.whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                complete(slot, RefundResult.degraded(
                        entry.targetLandId(), amountOf(entry), "refund.publish_failed"));
                return;
            }
            complete(slot, RefundResult.success(entry.targetLandId(), amountOf(entry)));
        }, asyncExecutor);
    }

    // ---- Builders. ----

    private static OperationPayload buildRefundPayload(ValidatedRefund plan, String providerId, Instant now) {
        return new OperationPayload(plan.operationId(), REFUND_OPERATION_TYPE, plan.actorUuid(),
                plan.worldUuid(), plan.landId(), plan.chunks(), plan.refundAmountMinorUnits(),
                providerId, null, now, now, OperationPayload.CURRENT_SCHEMA_VERSION,
                plan.landDisplayName(), plan.refundNumerator(), plan.refundDenominator());
    }

    private static RefundCommit buildRefundCommit(ValidatedRefund plan, Instant now) {
        List<ChunkKey> chunkKeys = new ArrayList<>(plan.chunks().size());
        for (OperationPayload.Chunk chunk : plan.chunks()) {
            chunkKeys.add(chunk.chunk());
        }
        Long singlePacked = chunkKeys.size() == 1 ? chunkKeys.get(0).pack() : null;
        AuditEntry audit = new AuditEntry(0, now, plan.actorUuid(), RefundCommit.REFUND_AUDIT_ACTION,
                plan.landId(), plan.worldUuid(), singlePacked, OperationPayload.CURRENT_SCHEMA_VERSION,
                null, null,
                "{\"refundMinorUnits\":" + plan.refundAmountMinorUnits()
                        + ",\"costBasisMinorUnits\":" + plan.totalCostBasisMinorUnits()
                        + ",\"chunks\":" + chunkKeys.size() + "}",
                new ArrayList<>(chunkKeys));
        return new RefundCommit(plan.operationId(), plan.landId(), plan.worldUuid(),
                plan.chunks(), plan.refundAmountMinorUnits(), audit);
    }

    private static Set<String> reservationKeys(ValidatedRefund plan) {
        Set<String> keys = new HashSet<>();
        for (OperationPayload.Chunk chunk : plan.chunks()) {
            ChunkKey key = chunk.chunk();
            keys.add(key.worldId() + ":" + key.chunkX() + ":" + key.chunkZ());
        }
        return Set.copyOf(keys);
    }

    private void release(Set<String> keys, UUID operationId) {
        try {
            reservations.release(keys, operationId);
        } catch (RuntimeException ignored) {
            // Best effort on terminal paths; registry removal is idempotent.
        }
    }

    private static void complete(CompletableFuture<RefundResult> slot, RefundResult result) {
        slot.complete(result);
    }
}

package com.smile.chunkland.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Applies the startup recovery table in created-at/operation-id order. */
public final class CrashRecoveryScanner {

    private final OperationLedger ledger;
    private final RecoveryHandlers handlers;
    private final Clock clock;
    private final int compensationRetryLimit;

    public CrashRecoveryScanner(OperationLedger ledger, RecoveryHandlers handlers) {
        this(ledger, handlers, Clock.systemUTC(), 3);
    }

    public CrashRecoveryScanner(
            OperationLedger ledger, RecoveryHandlers handlers, Clock clock, int compensationRetryLimit) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (compensationRetryLimit < 1) {
            throw new IllegalArgumentException("compensationRetryLimit must be positive");
        }
        this.compensationRetryLimit = compensationRetryLimit;
    }

    /**
     * Startup-facing entry point. The lifecycle owner supplies the already-open
     * ledger after persistence bootstrap and before publishing runtime state,
     * together with the integrations available in that runtime; this method
     * does not construct Economy or runtime handlers.
     */
    public static CompletionStage<List<RecoveryResult>> scanAtStartup(
            OperationLedger ledger, RecoveryHandlers handlers) {
        return new CrashRecoveryScanner(ledger, handlers).scan();
    }

    public CompletionStage<List<RecoveryResult>> scan() {
        return ledger.findAll().thenCompose(entries -> processSequentially(entries, 0, new ArrayList<>()));
    }

    private CompletionStage<List<RecoveryResult>> processSequentially(
            List<LedgerEntry> entries, int index, List<RecoveryResult> results) {
        if (index == entries.size()) return CompletableFuture.completedFuture(List.copyOf(results));
        return process(entries.get(index)).thenCompose(result -> {
            results.add(result);
            return processSequentially(entries, index + 1, results);
        });
    }

    private CompletionStage<RecoveryResult> process(LedgerEntry entry) {
        final LedgerState state;
        try {
            state = LedgerState.parse(entry.state());
        } catch (RuntimeException malformed) {
            return completed(new RecoveryResult(entry.operationId(), entry.state(), entry.state(),
                    LedgerState.RecoveryClassification.INVALID_RECORD, "unknown or malformed state"));
        }
        return switch (state) {
            case CREATED -> failCreated(entry);
            case PAYMENT_PENDING -> recoverPaymentPending(entry);
            case CHARGED -> recoverCharged(entry);
            case DOMAIN_COMMITTED -> recoverDomainCommitted(entry);
            case ACTIVE, FAILED, COMPENSATED -> completed(noOp(entry, state));
            case COMPENSATION_PENDING -> recoverCompensation(entry);
            case NEEDS_RECONCILIATION -> completed(noOp(entry, state));
        };
    }

    private CompletionStage<RecoveryResult> failCreated(LedgerEntry entry) {
        return ledger.compareAndSetState(entry.operationId(), LedgerState.CREATED, LedgerState.FAILED, now())
                .thenApply(ignored -> changed(entry, LedgerState.FAILED,
                        LedgerState.RecoveryClassification.FAIL_UNCHARGED, "created operation was not started"))
                .exceptionally(failure -> unchanged(entry, LedgerState.RecoveryClassification.INVALID_RECORD,
                        "could not mark CREATED operation failed"));
    }

    private CompletionStage<RecoveryResult> recoverPaymentPending(LedgerEntry entry) {
        return payloadFor(entry).thenCompose(payload -> {
            if (payload == null) return moveToReconciliation(entry, "payment lookup payload is invalid");
            CompletionStage<PaymentLookup> lookup;
            try {
                lookup = handlers.lookupPayment(entry);
            } catch (Throwable failure) {
                lookup = CompletableFuture.failedFuture(failure);
            }
            if (lookup == null) lookup = CompletableFuture.failedFuture(new IllegalStateException("payment lookup returned null"));
            return lookup.handle((result, failure) -> failure == null && result != null ? result : PaymentLookup.unknown())
                    .thenCompose(payment -> switch (payment.status()) {
                        case CONFIRMED_UNPAID -> ledger.compareAndSetState(entry.operationId(), LedgerState.PAYMENT_PENDING,
                                LedgerState.FAILED, now()).thenApply(ignored -> changed(entry, LedgerState.FAILED,
                                LedgerState.RecoveryClassification.LOOKUP_PAYMENT, "Economy confirmed unpaid"));
                        case UNKNOWN -> moveToReconciliation(entry, "Economy payment status is unknown");
                        case CONFIRMED_PAID -> ledger.transitionToCharged(entry.operationId(), payment.transactionRef(), now())
                                .thenCompose(ignored -> ledger.find(entry.operationId()))
                                .thenCompose(this::recoverCharged);
                    }).exceptionally(failure -> unchanged(entry, LedgerState.RecoveryClassification.INVALID_RECORD,
                            "payment lookup could not be reconciled"));
        });
    }

    private CompletionStage<RecoveryResult> recoverCharged(LedgerEntry entry) {
        return payloadFor(entry).thenCompose(payload -> {
            if (payload == null) return moveToReconciliation(entry, "charged operation payload is invalid");
            CompletionStage<ClaimCommit> replay;
            try {
                replay = handlers.replayClaim(entry, payload);
            } catch (Throwable failure) {
                replay = CompletableFuture.failedFuture(failure);
            }
            if (replay == null) replay = CompletableFuture.failedFuture(new IllegalStateException("claim replay returned null"));
            return replay.handle((commit, failure) -> failure == null ? commit : null)
                    .thenCompose(commit -> {
                        if (commit == null) return beginCompensation(entry, "claim replay failed");
                        return ledger.commitClaimAtomically(commit)
                                .thenCompose(ignored -> ledger.find(entry.operationId()))
                                .thenCompose(committed -> recoverDomainCommitted(committed, payload))
                                .exceptionallyCompose(failure -> beginCompensation(entry, "domain replay failed"));
                    });
        });
    }

    private CompletionStage<RecoveryResult> recoverDomainCommitted(LedgerEntry entry) {
        return payloadFor(entry).thenCompose(payload -> {
            if (payload == null) return moveToReconciliation(entry, "domain-committed payload is invalid");
            return recoverDomainCommitted(entry, payload);
        });
    }

    private CompletionStage<RecoveryResult> recoverDomainCommitted(LedgerEntry entry, OperationPayload payload) {
        if (payload.targetLandId() == null) {
            return moveToReconciliation(entry, "domain-committed operation has no target land");
        }
        return ledger.landExists(payload.targetLandId()).thenCompose(exists -> {
            if (!exists) return moveToReconciliation(entry, "domain-committed target land is missing");
            return recoverDomainCommittedWithRuntime(entry, payload);
        }).exceptionally(failure -> unchanged(entry, LedgerState.RecoveryClassification.REBUILD_RUNTIME,
                "domain lookup failed; durable row was retained"));
    }

    private CompletionStage<RecoveryResult> recoverDomainCommittedWithRuntime(
            LedgerEntry entry, OperationPayload payload) {
        CompletionStage<Void> rebuild;
        try {
            rebuild = handlers.rebuildRuntime(entry, payload);
        } catch (Throwable failure) {
            rebuild = CompletableFuture.failedFuture(failure);
        }
        if (rebuild == null) rebuild = CompletableFuture.failedFuture(new IllegalStateException("runtime rebuild returned null"));
                return rebuild.thenCompose(ignored -> ledger.compareAndSetState(entry.operationId(), LedgerState.DOMAIN_COMMITTED,
                        LedgerState.ACTIVE, now())
                .thenApply(done -> changed(entry, LedgerState.ACTIVE,
                        LedgerState.RecoveryClassification.REBUILD_RUNTIME, "runtime rebuilt from durable domain")))
                .exceptionally(failure -> unchanged(entry, LedgerState.RecoveryClassification.REBUILD_RUNTIME,
                        "runtime rebuild is unavailable or failed"));
    }

    private CompletionStage<RecoveryResult> beginCompensation(LedgerEntry entry, String reason) {
        return ledger.compareAndSetState(entry.operationId(), LedgerState.CHARGED, LedgerState.COMPENSATION_PENDING, now())
                .thenCompose(ignored -> ledger.find(entry.operationId()))
                .thenCompose(this::recoverCompensation)
                .exceptionally(failure -> unchanged(entry, LedgerState.RecoveryClassification.INVALID_RECORD, reason));
    }

    private CompletionStage<RecoveryResult> recoverCompensation(LedgerEntry entry) {
        if (entry.economyTransactionRef() == null || entry.economyTransactionRef().isBlank()) {
            return moveToReconciliation(entry, "compensation has no Economy transaction reference");
        }
        CompletionStage<RefundOutcome> refund;
        try {
            refund = handlers.refund(entry);
        } catch (Throwable failure) {
            refund = CompletableFuture.failedFuture(failure);
        }
        if (refund == null) refund = CompletableFuture.failedFuture(new IllegalStateException("refund returned null"));
        return refund.handle((result, failure) -> failure == null && result != null ? result : RefundOutcome.UNKNOWN)
                .thenCompose(result -> {
                    if (result == RefundOutcome.REFUNDED) {
                        return ledger.compareAndSetState(entry.operationId(), LedgerState.COMPENSATION_PENDING,
                                LedgerState.COMPENSATED, now())
                                .thenApply(ignored -> changed(entry, LedgerState.COMPENSATED,
                                        LedgerState.RecoveryClassification.RETRY_COMPENSATION, "refund confirmed"));
                    }
                    return ledger.recordCompensationFailure(entry.operationId(), compensationRetryLimit, now())
                            .thenApply(decision -> new RecoveryResult(entry.operationId(), entry.state(),
                                    decision.resultingState().name(), LedgerState.RecoveryClassification.RETRY_COMPENSATION,
                                    "refund was not confirmed; attempt " + decision.attempts()));
                }).exceptionally(failure -> unchanged(entry, LedgerState.RecoveryClassification.RETRY_COMPENSATION,
                        "compensation retry could not be persisted"));
    }

    private CompletionStage<RecoveryResult> moveToReconciliation(LedgerEntry entry, String diagnostic) {
        try {
            LedgerState expected = LedgerState.parse(entry.state());
            return ledger.quarantine(entry.operationId(), expected, now())
                    .thenApply(ignored -> changed(entry, LedgerState.NEEDS_RECONCILIATION,
                            LedgerState.RecoveryClassification.INVALID_RECORD, diagnostic))
                    .exceptionally(failure -> unchanged(entry, LedgerState.RecoveryClassification.INVALID_RECORD, diagnostic));
        } catch (RuntimeException failure) {
            return completed(unchanged(entry, LedgerState.RecoveryClassification.INVALID_RECORD, diagnostic));
        }
    }

    private CompletionStage<OperationPayload> payloadFor(LedgerEntry entry) {
        try {
            OperationPayload payload = OperationPayload.fromJson(entry.payloadJson());
            if (!entry.operationId().equals(payload.operationId())
                    || !Objects.equals(entry.actor(), payload.actorUuid())
                    || !Objects.equals(entry.worldId(), payload.worldUuid())
                    || !Objects.equals(entry.targetLandId(), payload.targetLandId())
                    || !Objects.equals(entry.priceMinorUnits(), payload.priceMinorUnits())
                    || !Objects.equals(entry.economyProviderId(), payload.economyProviderId())
                    || entry.metadataVersion() != payload.schemaVersion()) {
                return completed(null);
            }
            LedgerState state = LedgerState.parse(entry.state());
            if (state == LedgerState.PAYMENT_PENDING && entry.economyTransactionRef() != null) {
                return completed(null);
            }
            if ((state == LedgerState.CHARGED || state == LedgerState.COMPENSATION_PENDING)
                    && (entry.economyTransactionRef() == null || entry.economyTransactionRef().isBlank())) {
                return completed(null);
            }
            return completed(payload);
        } catch (RuntimeException failure) {
            return completed(null);
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private static RecoveryResult changed(
            LedgerEntry entry, LedgerState resultingState, LedgerState.RecoveryClassification classification, String diagnostic) {
        return new RecoveryResult(entry.operationId(), entry.state(), resultingState.name(), classification, diagnostic);
    }

    private static RecoveryResult unchanged(
            LedgerEntry entry, LedgerState.RecoveryClassification classification, String diagnostic) {
        return new RecoveryResult(entry.operationId(), entry.state(), entry.state(), classification, diagnostic);
    }

    private static RecoveryResult noOp(LedgerEntry entry, LedgerState state) {
        return new RecoveryResult(entry.operationId(), state.name(), state.name(),
                state.recoveryClassification(), "terminal state is authoritative");
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}

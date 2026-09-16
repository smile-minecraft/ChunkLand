package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.ChunkRepository;
import com.smile.chunkland.persistence.DeleteCommit;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.LedgerState;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
import com.smile.chunkland.selection.SelectionSessionManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Single production execution path for whole-land delete.
 *
 * <p>Step order is domain-first: revalidate (structure token, target, owner)
 * on the caller thread while holding logical reservations; read the durable
 * per-chunk cost bases and derive the refund as the full durable sum (never
 * the current pricing table, never the shrink half-ratio); record the {@code
 * CREATED} delete ledger row in its own transaction; commit the domain
 * (remove every Land-owned row and the land row itself under a
 * structure-revision compare, record {@code LAND_DELETE}) in one atomic
 * transaction; clear selection sessions pointing at the land; publish a fresh
 * immutable runtime snapshot built from the authoritative database; deposit
 * through Economy with the operation id as the idempotency key strictly
 * outside any SQL transaction; finally settle the row.
 *
 * <p>Once the domain commit succeeds it is never rolled back. A publish
 * failure reports {@code DEGRADED} without touching Economy: the refund waits
 * for startup recovery, which rebuilds the runtime and retries the deposit
 * through the shared compensation path. A failed or unconfirmed deposit parks
 * the row in {@code COMPENSATION_PENDING} and a confirmed deposit settles it
 * as {@code COMPENSATED}; the retry limit moves it to {@code
 * NEEDS_RECONCILIATION}. Server Land moves no money and never calls Economy.
 *
 * <p>Threading: validation runs synchronously on the caller thread so
 * reservation contention stays deterministic; every continuation runs on the
 * injected async executor, so persistence reads, Economy deposits and publish
 * work never block the caller and no stage blocks a thread waiting.
 */
public final class DeleteSaga {

    /** Operation type stored on delete ledger rows. */
    public static final String DELETE_OPERATION_TYPE = "DELETE";

    /** Audit action recorded by the atomic domain commit. */
    static final String DELETE_AUDIT_ACTION = "LAND_DELETE";

    /** Marker recorded as the transaction ref when no money moves. */
    public static final String ZERO_VALUE_TRANSACTION_REF = "zero-value";

    /**
     * Provider marker recorded on Server Land rows. A Server delete moves no
     * money, so the saga must not call Economy at all; the marker keeps the
     * ledger payload valid without a live provider lookup.
     */
    public static final String SERVER_LAND_PROVIDER_ID = "server-land";

    private final DeleteValidator validator;
    private final ChunkRepository chunks;
    private final LogicalReservationRegistry reservations;
    private final OperationLedger ledger;
    private final ClaimEconomy economy;
    private final RuntimeRegistryRebuilder rebuilder;
    private final OwnerQuotaService quotas;
    private final SelectionSessionManager selections;
    private final Clock clock;
    private final Executor asyncExecutor;
    private final int compensationRetryLimit;

    public DeleteSaga(
            DeleteValidator validator,
            ChunkRepository chunks,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            RuntimeRegistryRebuilder rebuilder,
            OwnerQuotaService quotas,
            SelectionSessionManager selections,
            Clock clock,
            Executor asyncExecutor,
            int compensationRetryLimit) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.rebuilder = Objects.requireNonNull(rebuilder, "rebuilder");
        this.quotas = Objects.requireNonNull(quotas, "quotas");
        this.selections = Objects.requireNonNull(selections, "selections");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor");
        if (compensationRetryLimit < 1) {
            throw new IllegalArgumentException("compensationRetryLimit must be positive");
        }
        this.compensationRetryLimit = compensationRetryLimit;
    }

    /**
     * Execute one land delete.
     *
     * <p>Step one runs synchronously on the caller thread so reservation
     * contention stays deterministic; the remaining steps are chained
     * asynchronously and never block a thread waiting.
     */
    public CompletionStage<DeleteOutcome> delete(DeleteRequest request) {
        Objects.requireNonNull(request, "request");
        final ValidatedDelete plan;
        try {
            plan = validator.validate(request);
        } catch (ClaimRejectedException rejected) {
            return completed(DeleteOutcome.rejected(rejected.diagnosticKey()));
        } catch (RuntimeException failure) {
            return completed(DeleteOutcome.failed("delete.validation_failed"));
        }
        if (plan == null) {
            return completed(DeleteOutcome.failed("delete.validation_failed"));
        }
        final boolean serverOwned = plan.owner() instanceof OwnerRef.ServerOwnerRef;
        if (!serverOwned) {
            boolean available;
            try {
                available = economy.isAvailable();
            } catch (RuntimeException failure) {
                available = false;
            }
            if (!available) {
                return completed(DeleteOutcome.rejected("delete.economy_unavailable"));
            }
        }
        final UUID operationId = UUID.randomUUID();
        final Set<String> reservationKeys = reservationKeys(plan);
        if (!reservations.tryAcquire(reservationKeys, operationId)) {
            return completed(DeleteOutcome.rejected("delete.reservation_conflict"));
        }
        CompletionStage<Map<ChunkKey, ChunkRepository.ChunkFact>> facts;
        try {
            facts = chunks.factsByLand(plan.targetLandId());
        } catch (RuntimeException failure) {
            release(reservationKeys, operationId);
            return completed(DeleteOutcome.failed("delete.validation_failed"));
        }
        if (facts == null) {
            release(reservationKeys, operationId);
            return completed(DeleteOutcome.failed("delete.validation_failed"));
        }
        return facts.thenComposeAsync(all -> driveWithFacts(plan, operationId, reservationKeys,
                all, serverOwned), asyncExecutor)
                .exceptionally(failure -> {
                    release(reservationKeys, operationId);
                    return rejectedOrFailed(failure);
                });
    }

    private CompletionStage<DeleteOutcome> driveWithFacts(ValidatedDelete plan, UUID operationId,
            Set<String> reservationKeys, Map<ChunkKey, ChunkRepository.ChunkFact> all,
            boolean serverOwned) {
        final DeleteMaterials materials;
        try {
            materials = buildMaterials(plan, operationId, all, serverOwned);
        } catch (ClaimRejectedException rejected) {
            release(reservationKeys, operationId);
            return completed(DeleteOutcome.rejected(rejected.diagnosticKey()));
        } catch (RuntimeException failure) {
            release(reservationKeys, operationId);
            return completed(DeleteOutcome.failed("delete.validation_failed"));
        }
        CompletionStage<Void> created;
        try {
            created = ledger.create(materials.payload());
        } catch (RuntimeException failure) {
            release(reservationKeys, operationId);
            return completed(DeleteOutcome.failed("delete.ledger_failed"));
        }
        if (created == null) {
            release(reservationKeys, operationId);
            return completed(DeleteOutcome.failed("delete.ledger_failed"));
        }
        return created.thenComposeAsync(ignored -> commitDomain(
                plan, operationId, reservationKeys, materials, serverOwned), asyncExecutor)
                .exceptionally(failure -> {
                    release(reservationKeys, operationId);
                    return DeleteOutcome.failed("delete.ledger_failed");
                });
    }

    private static DeleteOutcome rejectedOrFailed(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ClaimRejectedException rejected) {
                return DeleteOutcome.rejected(rejected.diagnosticKey());
            }
            if (current instanceof java.util.concurrent.CompletionException completion
                    && completion.getCause() != null) {
                current = completion.getCause();
                continue;
            }
            break;
        }
        return DeleteOutcome.failed("delete.validation_failed");
    }

    // ---- Step 4: single atomic domain transaction. Money never moves before this. ----

    private CompletionStage<DeleteOutcome> commitDomain(ValidatedDelete plan, UUID operationId,
            Set<String> reservationKeys, DeleteMaterials materials, boolean serverOwned) {
        CompletionStage<Void> committed;
        try {
            committed = ledger.commitDeleteAtomically(materials.commit());
        } catch (RuntimeException failure) {
            release(reservationKeys, operationId);
            return completed(DeleteOutcome.failed("delete.commit_failed"));
        }
        if (committed == null) {
            release(reservationKeys, operationId);
            return completed(DeleteOutcome.failed("delete.commit_failed"));
        }
        return committed.handle((ignored, failure) -> failure)
                .thenComposeAsync(commitFailure -> {
                    // Reservations are always released once the domain attempt
                    // settles: on success the land is gone, on failure the
                    // next attempt must revalidate from scratch.
                    release(reservationKeys, operationId);
                    if (commitFailure != null) {
                        return completed(mapCommitFailure(commitFailure));
                    }
                    try {
                        // The deleted land frees one committed land slot plus
                        // every committed chunk it held. Best effort: the
                        // durable domain already won, so quota counters never
                        // block the publish or the refund, and Server owners
                        // are a no-op inside the service.
                        quotas.releaseCommittedLands(plan.owner(), 1);
                        quotas.releaseCommittedChunks(plan.owner(), materials.commit().chunks().size());
                    } catch (RuntimeException ignored) {
                        // Best effort: the durable domain already won; quota
                        // counters never block the publish or the refund.
                    }
                    clearSessions(plan);
                    return publishThenRefund(operationId, materials.refundAmount(),
                            plan.targetLandId());
                }, asyncExecutor)
                .exceptionally(failure -> DeleteOutcome.failed("delete.commit_failed"));
    }

    static DeleteOutcome mapCommitFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                if (message.contains("stale land structure revision")) {
                    return DeleteOutcome.rejected("structure.stale");
                }
                if (message.contains("unknown deleted land")) {
                    return DeleteOutcome.rejected("delete.unknown_land");
                }
                if (message.contains("delete chunk set changed since validation")) {
                    return DeleteOutcome.rejected("delete.stale");
                }
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return DeleteOutcome.failed("delete.commit_failed");
    }

    private void clearSessions(ValidatedDelete plan) {
        try {
            selections.onLandDeleted(plan.targetLandId());
        } catch (RuntimeException ignored) {
            // Selection cleanup must never break a durable delete.
        }
    }

    // ---- Step 5: publish from authoritative state before any money moves. ----

    private CompletionStage<DeleteOutcome> publishThenRefund(UUID operationId, long refundAmount,
            com.smile.chunkland.api.land.LandId landId) {
        CompletionStage<?> rebuilt;
        try {
            rebuilt = rebuilder.rebuild();
        } catch (RuntimeException failure) {
            return completed(DeleteOutcome.degraded(landId, refundAmount, "delete.publish_failed"));
        }
        if (rebuilt == null) {
            return completed(DeleteOutcome.degraded(landId, refundAmount, "delete.publish_failed"));
        }
        return rebuilt.handle((ignored, failure) -> failure)
                .thenComposeAsync(publishFailure -> {
                    if (publishFailure != null) {
                        // The delete is durable but the runtime is stale: no
                        // refund is attempted yet, recovery completes both.
                        return completed(DeleteOutcome.degraded(
                                landId, refundAmount, "delete.publish_failed"));
                    }
                    return depositAfterPublish(operationId, refundAmount, landId);
                }, asyncExecutor)
                .exceptionally(failure -> DeleteOutcome.degraded(
                        landId, refundAmount, "delete.publish_failed"));
    }

    // ---- Step 6: Economy deposit outside any SQL transaction. ----

    private CompletionStage<DeleteOutcome> depositAfterPublish(UUID operationId, long refundAmount,
            com.smile.chunkland.api.land.LandId landId) {
        CompletionStage<LedgerEntry> found;
        try {
            found = ledger.find(operationId);
        } catch (RuntimeException failure) {
            return completed(DeleteOutcome.failed("delete.ledger_failed"));
        }
        if (found == null) {
            return completed(DeleteOutcome.failed("delete.ledger_failed"));
        }
        return found.thenComposeAsync(entry -> {
            if (entry == null) {
                return completed(DeleteOutcome.failed("delete.ledger_failed"));
            }
            final LedgerState committed;
            try {
                committed = LedgerState.parse(entry.state());
            } catch (RuntimeException malformed) {
                return completed(DeleteOutcome.failed("delete.invalid_payload"));
            }
            if (committed != LedgerState.DOMAIN_COMMITTED
                    && committed != LedgerState.COMPENSATION_PENDING) {
                return completed(DeleteOutcome.failed("delete.unexpected_state"));
            }
            if (refundAmount == 0L) {
                return settleCompensated(entry, committed, ZERO_VALUE_TRANSACTION_REF);
            }
            CompletionStage<RefundOutcome> deposit;
            try {
                deposit = economy.refund(entry);
            } catch (Throwable thrown) {
                return depositUnconfirmed(entry, committed);
            }
            if (deposit == null) {
                return depositUnconfirmed(entry, committed);
            }
            return deposit.thenComposeAsync(outcome -> {
                if (outcome == RefundOutcome.REFUNDED) {
                    return settleCompensated(entry, committed, "delete:" + operationId);
                }
                return depositUnconfirmed(entry, committed);
            }, asyncExecutor).exceptionally(failure -> depositUnconfirmedSync(entry));
        }, asyncExecutor).exceptionally(failure -> DeleteOutcome.failed("delete.ledger_failed"));
    }

    private CompletionStage<DeleteOutcome> depositUnconfirmed(LedgerEntry entry, LedgerState committed) {
        if (committed == LedgerState.COMPENSATION_PENDING) {
            return recordCompensationAttempt(entry);
        }
        return parkForCompensation(entry);
    }

    private DeleteOutcome depositUnconfirmedSync(LedgerEntry entry) {
        return DeleteOutcome.compensationPending(entry.targetLandId(), amountOf(entry));
    }

    private CompletionStage<DeleteOutcome> settleCompensated(LedgerEntry entry, LedgerState committed,
            String transactionRef) {
        CompletionStage<Void> settled;
        try {
            settled = ledger.settleRefundCompensated(
                    entry.operationId(), committed, transactionRef, clock.instant());
        } catch (RuntimeException failure) {
            return completed(DeleteOutcome.degraded(
                    entry.targetLandId(), amountOf(entry), "delete.finalize_failed"));
        }
        if (settled == null) {
            return completed(DeleteOutcome.degraded(
                    entry.targetLandId(), amountOf(entry), "delete.finalize_failed"));
        }
        return settled.thenApplyAsync(ignored ->
                DeleteOutcome.success(entry.targetLandId(), amountOf(entry)), asyncExecutor)
                .exceptionally(failure -> DeleteOutcome.degraded(
                        entry.targetLandId(), amountOf(entry), "delete.finalize_failed"));
    }

    private CompletionStage<DeleteOutcome> parkForCompensation(LedgerEntry entry) {
        CompletionStage<Void> parked;
        try {
            parked = ledger.parkForRefundCompensation(
                    entry.operationId(), "delete:" + entry.operationId(), clock.instant());
        } catch (RuntimeException failure) {
            return completed(DeleteOutcome.failed("delete.ledger_failed"));
        }
        if (parked == null) {
            return completed(DeleteOutcome.failed("delete.ledger_failed"));
        }
        return parked.thenComposeAsync(ignored -> recordCompensationAttempt(entry), asyncExecutor)
                .exceptionally(failure -> DeleteOutcome.failed("delete.ledger_failed"));
    }

    private CompletionStage<DeleteOutcome> recordCompensationAttempt(LedgerEntry entry) {
        CompletionStage<OperationLedger.CompensationDecision> recorded;
        try {
            recorded = ledger.recordCompensationFailure(
                    entry.operationId(), compensationRetryLimit, clock.instant());
        } catch (RuntimeException failure) {
            return completed(DeleteOutcome.failed("delete.ledger_failed"));
        }
        if (recorded == null) {
            return completed(DeleteOutcome.failed("delete.ledger_failed"));
        }
        return recorded.thenApplyAsync(decision -> {
            if (decision.resultingState() == LedgerState.NEEDS_RECONCILIATION) {
                return DeleteOutcome.needsReconciliation(entry.targetLandId(), amountOf(entry));
            }
            return DeleteOutcome.compensationPending(entry.targetLandId(), amountOf(entry));
        }, asyncExecutor).exceptionally(failure -> DeleteOutcome.failed("delete.ledger_failed"));
    }

    // ---- Builders. ----

    private DeleteMaterials buildMaterials(ValidatedDelete plan, UUID operationId,
            Map<ChunkKey, ChunkRepository.ChunkFact> all, boolean serverOwned) {
        if (all == null) {
            throw new ClaimRejectedException("delete.validation_failed");
        }
        List<ChunkKey> ordered = new ArrayList<>(plan.chunks());
        ordered.sort(Comparator.comparingInt(ChunkKey::chunkX).thenComparingInt(ChunkKey::chunkZ));
        List<OperationPayload.Chunk> payloadChunks = new ArrayList<>(ordered.size());
        long total = 0L;
        for (ChunkKey key : ordered) {
            ChunkRepository.ChunkFact fact = all.get(key);
            if (fact == null) {
                throw new ClaimRejectedException("delete.stale");
            }
            Long basis = fact.costBasisMinorUnits();
            if (basis == null) {
                throw new ClaimRejectedException("delete.missing_basis");
            }
            if (basis < 0) {
                throw new ClaimRejectedException("delete.invalid_basis");
            }
            try {
                total = Math.addExact(total, basis);
            } catch (ArithmeticException overflow) {
                throw new ClaimRejectedException("delete.invalid_basis");
            }
            int storedMin = fact.storedMinProtectedY() != null
                    ? fact.storedMinProtectedY()
                    : com.smile.chunkland.runtime.vertical.VerticalDepths.LEGACY_STORED_FALLBACK_Y;
            UUID lot = fact.claimLotId() != null ? fact.claimLotId() : operationId;
            payloadChunks.add(new OperationPayload.Chunk(key, storedMin, lot, basis));
        }
        // Whole-land delete refunds the full durable cost basis. Server Land
        // moves no money at all.
        final long refund = serverOwned ? 0L : total;
        Instant now = clock.instant();
        final String providerId;
        if (serverOwned) {
            // Server Land moves no money: record the marker without touching
            // Economy, so a provider outage can never block the delete.
            providerId = SERVER_LAND_PROVIDER_ID;
        } else {
            try {
                providerId = economy.providerId();
            } catch (RuntimeException failure) {
                throw new IllegalStateException("economy provider identity failed", failure);
            }
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalStateException("economy provider identity must not be blank");
            }
        }
        OperationPayload payload = OperationPayload.delete(operationId, plan.actorUuid(),
                plan.worldId(), plan.targetLandId(), List.copyOf(payloadChunks), refund,
                providerId, now, plan.displayName());
        Long singlePacked = payloadChunks.size() == 1 ? payloadChunks.get(0).chunk().pack() : null;
        Set<ChunkKey> chunkKeys = new HashSet<>(ordered);
        AuditEntry audit = new AuditEntry(0, now, plan.actorUuid(), DELETE_AUDIT_ACTION,
                plan.targetLandId(), plan.worldId(), singlePacked, OperationPayload.CURRENT_SCHEMA_VERSION,
                null, payload.toJson(),
                "{\"refundMinorUnits\":" + refund + ",\"costBasisMinorUnits\":" + total
                        + ",\"chunks\":" + payloadChunks.size() + "}",
                new ArrayList<>(chunkKeys));
        return new DeleteMaterials(payload,
                new DeleteCommit(operationId, plan.targetLandId(), plan.worldId(), plan.owner(),
                        plan.expectedStructureRevision(), List.copyOf(payloadChunks), refund, audit),
                refund, total);
    }

    private static Set<String> reservationKeys(ValidatedDelete plan) {
        Set<String> keys = new HashSet<>();
        for (ChunkKey chunk : plan.chunks()) {
            keys.add(chunk.worldId() + ":" + chunk.chunkX() + ":" + chunk.chunkZ());
        }
        return Set.copyOf(keys);
    }

    private static long amountOf(LedgerEntry entry) {
        return entry.priceMinorUnits() == null ? 0L : entry.priceMinorUnits();
    }

    private void release(Set<String> keys, UUID operationId) {
        try {
            reservations.release(keys, operationId);
        } catch (RuntimeException ignored) {
            // Best effort on terminal paths; registry removal is idempotent.
        }
    }

    private static CompletableFuture<DeleteOutcome> completed(DeleteOutcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }

    private record DeleteMaterials(OperationPayload payload, DeleteCommit commit,
            long refundAmount, long totalBasis) {
    }
}

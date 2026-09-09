package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.ChunkCoordinate;
import com.smile.chunkland.api.money.CostBasisAllocation;
import com.smile.chunkland.api.money.CostBasisCalculator;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.ClaimCommit;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.LedgerState;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
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
import java.util.concurrent.Executor;

/**
 * Single production execution path for paid land claims.
 *
 * <p>Step order is fixed: revalidate (selection, collision, connectivity,
 * limit, structure revision) and price on the caller thread while holding the
 * quota and logical reservations; create the {@code PAYMENT_PENDING} ledger row
 * in its own transaction; withdraw through Economy with the operation id as the
 * idempotency key strictly outside any SQL transaction; commit lands, chunks,
 * cost basis, audit trail and {@code DOMAIN_COMMITTED} in the pre-existing
 * single atomic transaction; publish a fresh immutable runtime snapshot built
 * from the authoritative database; finally mark {@code ACTIVE}.
 *
 * <p>Threading: step one runs synchronously on the caller thread so quota and
 * reservation contention stay deterministic. Every continuation attached to a
 * ledger stage uses the injected async executor, so Economy bridge calls and
 * refund work never run on the persistence thread. No stage blocks a thread
 * waiting, and no sleep, polling loop, or unbounded executor is used.
 *
 * <p>Failure contract: an Economy failure marks {@code FAILED} with no refund
 * and no reservation side effects. A domain-commit failure after a successful
 * charge moves to {@code COMPENSATION_PENDING}, refunds once, and lands on
 * {@code COMPENSATED}; an unconfirmed refund advances the durable retry count
 * and reaches {@code NEEDS_RECONCILIATION} at the retry limit. A publish
 * failure never refunds: the outcome is degraded, the ledger stays
 * {@code DOMAIN_COMMITTED}, and startup recovery rebuilds the runtime before
 * advancing to {@code ACTIVE}.
 */
public final class ClaimSaga {

    /** Marker recorded as the transaction ref when no money moves. */
    public static final String ZERO_VALUE_TRANSACTION_REF = "zero-value";

    /** Audit action recorded by the atomic domain commit. */
    static final String CLAIM_AUDIT_ACTION = "LAND_CREATE";

    private final ClaimValidator validator;
    private final OwnerQuotaService quotas;
    private final PricingTable pricing;
    private final LogicalReservationRegistry reservations;
    private final OperationLedger ledger;
    private final ClaimEconomy economy;
    private final RuntimeRegistryRebuilder rebuilder;
    private final Clock clock;
    private final Executor asyncExecutor;
    private final int compensationRetryLimit;
    private final PublishSideEffects publishSideEffects;

    /**
     * Terminal in-memory side effects applied once the durable domain commit
     * succeeded: committed quota counts and released logical reservations.
     *
     * <p>Production uses the live registries held by the saga. Tests supply a
     * throwing implementation to prove that any synchronous failure here can
     * only degrade the outcome and never trigger compensation.
     */
    @FunctionalInterface
    interface PublishSideEffects {
        void apply(ClaimAttempt attempt);
    }

    public ClaimSaga(
            ClaimValidator validator,
            OwnerQuotaService quotas,
            PricingTable pricing,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            RuntimeRegistryRebuilder rebuilder,
            Clock clock,
            Executor asyncExecutor,
            int compensationRetryLimit) {
        this(validator, quotas, pricing, reservations, ledger, economy, rebuilder,
                clock, asyncExecutor, compensationRetryLimit, null);
    }

    ClaimSaga(
            ClaimValidator validator,
            OwnerQuotaService quotas,
            PricingTable pricing,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            RuntimeRegistryRebuilder rebuilder,
            Clock clock,
            Executor asyncExecutor,
            int compensationRetryLimit,
            PublishSideEffects publishSideEffects) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.quotas = Objects.requireNonNull(quotas, "quotas");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
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
        this.publishSideEffects =
                publishSideEffects != null ? publishSideEffects : this::releasePublishGuards;
    }

    /**
     * Execute the six-step claim.
     *
     * <p>Step one runs synchronously on the caller thread so quota and
     * reservation contention stay deterministic; the remaining steps are
     * chained asynchronously and never block a thread waiting.
     */
    public CompletionStage<ClaimOutcome> claim(ClaimRequest request) {
        Objects.requireNonNull(request, "request");
        // ---- Step 1 on the caller thread: revalidate, price, reserve. ----
        final ValidatedClaim plan;
        try {
            plan = validator.validate(request);
        } catch (ClaimRejectedException rejected) {
            return completed(ClaimOutcome.rejected(rejected.diagnosticKey()));
        } catch (RuntimeException failure) {
            return completed(ClaimOutcome.failed("validation.failed"));
        }
        if (plan == null) {
            return completed(ClaimOutcome.failed("validation.failed"));
        }
        final boolean serverOwned = plan.owner() instanceof OwnerRef.ServerOwnerRef;
        final Money price;
        try {
            price = serverOwned
                    ? Money.zero(pricing.currency())
                    : pricing.priceForClaim(plan.ownerTotalChunks(), plan.chunks().size());
        } catch (RuntimeException failure) {
            return completed(ClaimOutcome.failed("pricing.failed"));
        }
        // Production fail-closed: a player claim must never slip through a
        // placeholder zero table or a missing Vault provider into a free
        // land. Server land stays free by design and skips both checks.
        if (!serverOwned) {
            boolean available;
            try {
                available = economy.isAvailable();
            } catch (RuntimeException failure) {
                available = false;
            }
            if (!available) {
                return completed(ClaimOutcome.rejected("economy.unavailable"));
            }
            if (price.isZero()) {
                return completed(ClaimOutcome.rejected("pricing.unavailable"));
            }
        }
        final OwnerQuotaService.QuotaReservation landReservation;
        final OwnerQuotaService.QuotaReservation chunkReservation;
        if (serverOwned) {
            landReservation = null;
            chunkReservation = null;
        } else {
            Optional<OwnerQuotaService.QuotaReservation> land = tryReserveLand(plan.owner());
            if (land.isEmpty()) {
                return completed(ClaimOutcome.rejected("limit.reached"));
            }
            landReservation = land.get();
            Optional<OwnerQuotaService.QuotaReservation> chunks =
                    tryReserveChunks(plan.owner(), plan.chunks().size());
            if (chunks.isEmpty()) {
                landReservation.release();
                return completed(ClaimOutcome.rejected("limit.reached"));
            }
            chunkReservation = chunks.get();
        }
        final Set<String> reservationKeys = reservationKeys(plan);
        final UUID operationId = UUID.randomUUID();
        if (!reservations.tryAcquire(reservationKeys, operationId)) {
            releaseQuota(landReservation, chunkReservation);
            return completed(ClaimOutcome.rejected("reservation.conflict"));
        }
        final ClaimMaterials materials;
        try {
            materials = buildMaterials(plan, price, operationId);
        } catch (RuntimeException failure) {
            releaseAll(reservationKeys, operationId, landReservation, chunkReservation);
            return completed(ClaimOutcome.failed("claim.invalid"));
        }
        ClaimAttempt attempt = new ClaimAttempt(plan, price, operationId, reservationKeys,
                landReservation, chunkReservation, materials, serverOwned);
        // ---- Step 2: durable PAYMENT_PENDING boundary in its own transaction. ----
        CompletionStage<ClaimOutcome> stage;
        try {
            stage = ledger.createPaymentPending(materials.payload())
                    .thenComposeAsync(ignored -> chargeStep(attempt), asyncExecutor);
        } catch (RuntimeException failure) {
            releaseAll(reservationKeys, operationId, landReservation, chunkReservation);
            return completed(ClaimOutcome.failed("ledger.failed"));
        }
        return stage.exceptionally(failure -> {
            releaseAll(reservationKeys, operationId, landReservation, chunkReservation);
            return ClaimOutcome.failed("ledger.failed");
        });
    }

    // ---- Step 3: Economy withdraw outside any SQL transaction. ----

    private CompletionStage<ClaimOutcome> chargeStep(ClaimAttempt attempt) {
        if (attempt.price().isZero()) {
            return chargedStep(attempt, ZERO_VALUE_TRANSACTION_REF);
        }
        CompletionStage<ClaimEconomy.ChargeResult> charge;
        try {
            charge = economy.charge(attempt.operationId(), attempt.requestView(), attempt.price());
        } catch (Throwable failure) {
            return failUncharged(attempt, "economy.failed");
        }
        if (charge == null) {
            return failUncharged(attempt, "economy.failed");
        }
        return charge.thenComposeAsync(result -> {
            if (result == null || !result.success()) {
                String key = result == null || result.diagnosticKey() == null
                        ? "economy.failed" : result.diagnosticKey();
                return failUncharged(attempt, key);
            }
            return chargedStep(attempt, chargeTransactionRef(attempt.operationId()));
        }, asyncExecutor).toCompletableFuture()
                .exceptionallyCompose(failure -> failUncharged(attempt, "economy.failed"));
    }

    private CompletionStage<ClaimOutcome> failUncharged(ClaimAttempt attempt, String key) {
        try {
            return ledger.compareAndSetState(attempt.operationId(),
                            LedgerState.PAYMENT_PENDING, LedgerState.FAILED, clock.instant())
                    .thenApplyAsync(ignored -> {
                        releaseAll(attempt);
                        return ClaimOutcome.failed(key);
                    }, asyncExecutor)
                    .exceptionally(failure -> {
                        releaseAll(attempt);
                        return ClaimOutcome.failed(key);
                    });
        } catch (RuntimeException failure) {
            releaseAll(attempt);
            return completed(ClaimOutcome.failed(key));
        }
    }

    private CompletionStage<ClaimOutcome> chargedStep(ClaimAttempt attempt, String transactionRef) {
        CompletionStage<Void> marked;
        try {
            marked = ledger.transitionToCharged(attempt.operationId(), transactionRef, clock.instant());
        } catch (RuntimeException failure) {
            // Money may have moved but the ledger did not advance; startup
            // recovery reconciles PAYMENT_PENDING rows via payment lookup.
            releaseAll(attempt);
            return completed(ClaimOutcome.failed("ledger.failed"));
        }
        return marked.thenComposeAsync(ignored -> commitStep(attempt), asyncExecutor)
                .exceptionally(failure -> {
                    releaseAll(attempt);
                    return ClaimOutcome.failed("ledger.failed");
                });
    }

    // ---- Step 4: single atomic domain transaction, then compensate on failure. ----
    //
    // The routing below is structural: only a failed domain commit compensates.
    // Once the durable commit succeeds, every later failure can only degrade.
    // The branch itself runs synchronously on the commit completion without an
    // executor, so executor rejection cannot blur the two paths.

    private CompletionStage<ClaimOutcome> commitStep(ClaimAttempt attempt) {
        CompletionStage<Void> committed;
        try {
            committed = ledger.commitClaimAtomically(attempt.materials().commit());
        } catch (RuntimeException failure) {
            return compensate(attempt);
        }
        if (committed == null) {
            return compensate(attempt);
        }
        CompletionStage<Throwable> settled;
        try {
            settled = committed.handle((ignored, failure) -> failure);
        } catch (RuntimeException failure) {
            return compensate(attempt);
        }
        if (settled == null) {
            return compensate(attempt);
        }
        return settleCommitted(attempt, settled);
    }

    /**
     * Route an observed domain-commit completion: a failed commit compensates,
     * a successful one publishes. Package-visible so tests can drive a
     * commit-success completion through hostile continuation stages and prove
     * the post-commit path can only degrade.
     *
     * <p>Once the durable commit succeeded, every registration failure below
     * degrades: the continuation registration and its body run after the
     * commit, so they can never reopen compensation.
     */
    CompletionStage<ClaimOutcome> settleCommitted(ClaimAttempt attempt, CompletionStage<Throwable> settled) {
        CompletionStage<ClaimOutcome> next;
        try {
            next = settled.thenCompose(commitFailure -> {
                if (commitFailure != null) {
                    return compensate(attempt);
                }
                try {
                    return publishOnAsyncExecutor(attempt);
                } catch (Throwable failure) {
                    return completed(degraded(attempt, "claim.publish_failed"));
                }
            });
        } catch (Throwable failure) {
            return completed(degraded(attempt, "claim.publish_failed"));
        }
        if (next == null) {
            return completed(degraded(attempt, "claim.publish_failed"));
        }
        return next;
    }

    /**
     * Hop the post-commit publish off the persistence thread without letting
     * executor rejection blur the routing: rejection degrades, and the
     * publish body below can only degrade as well.
     */
    private CompletionStage<ClaimOutcome> publishOnAsyncExecutor(ClaimAttempt attempt) {
        CompletableFuture<ClaimOutcome> published = new CompletableFuture<>();
        try {
            asyncExecutor.execute(() -> settlePublish(attempt, published));
        } catch (RuntimeException failure) {
            return completed(degraded(attempt, "claim.publish_failed"));
        }
        return published.exceptionally(failure -> degraded(attempt, "claim.publish_failed"));
    }

    private void settlePublish(ClaimAttempt attempt, CompletableFuture<ClaimOutcome> published) {
        CompletionStage<ClaimOutcome> stage;
        try {
            stage = publishStep(attempt);
        } catch (Throwable failure) {
            published.complete(degraded(attempt, "claim.publish_failed"));
            return;
        }
        if (stage == null) {
            published.complete(degraded(attempt, "claim.publish_failed"));
            return;
        }
        settlePublishStage(attempt, published, stage);
    }

    /**
     * Bridge one publish stage into the outcome future. Package-visible so
     * tests can drive hostile publish stages (registration throw, callback
     * body throw, null outcome) and prove every case completes the outcome
     * exactly once as degraded without touching compensation.
     *
     * <p>Both the registration call and the callback body are guarded: either
     * throwing completes the outcome as degraded. Completion is idempotent, so
     * a stage that runs the callback and then throws still lands exactly once.
     */
    void settlePublishStage(ClaimAttempt attempt, CompletableFuture<ClaimOutcome> published,
            CompletionStage<ClaimOutcome> stage) {
        try {
            stage.whenComplete((outcome, failure) -> {
                try {
                    if (failure != null || outcome == null) {
                        published.complete(degraded(attempt, "claim.publish_failed"));
                    } else {
                        published.complete(outcome);
                    }
                } catch (Throwable bodyFailure) {
                    published.complete(degraded(attempt, "claim.publish_failed"));
                }
            });
        } catch (Throwable registrationFailure) {
            published.complete(degraded(attempt, "claim.publish_failed"));
        }
    }

    private CompletionStage<ClaimOutcome> compensate(ClaimAttempt attempt) {
        CompletionStage<Void> pending;
        try {
            pending = ledger.compareAndSetState(attempt.operationId(),
                    LedgerState.CHARGED, LedgerState.COMPENSATION_PENDING, clock.instant());
        } catch (RuntimeException failure) {
            releaseAll(attempt);
            return completed(ClaimOutcome.failed("ledger.failed"));
        }
        return pending.thenComposeAsync(ignored -> refundStep(attempt), asyncExecutor)
                .exceptionally(failure -> {
                    releaseAll(attempt);
                    return ClaimOutcome.failed("ledger.failed");
                });
    }

    private CompletionStage<ClaimOutcome> refundStep(ClaimAttempt attempt) {
        CompletionStage<LedgerEntry> current;
        try {
            current = ledger.find(attempt.operationId());
        } catch (RuntimeException failure) {
            return recordUnconfirmedRefund(attempt);
        }
        return current.thenComposeAsync(entry -> {
            CompletionStage<RefundOutcome> refund;
            try {
                refund = economy.refund(entry);
            } catch (Throwable failure) {
                return recordUnconfirmedRefund(attempt);
            }
            if (refund == null) {
                return recordUnconfirmedRefund(attempt);
            }
            return refund.thenComposeAsync(outcome ->
                    outcome == RefundOutcome.REFUNDED
                            ? markCompensated(attempt) : recordUnconfirmedRefund(attempt),
                    asyncExecutor).exceptionally(failure -> recordUnconfirmedRefundSync(attempt));
        }, asyncExecutor).exceptionally(failure -> recordUnconfirmedRefundSync(attempt));
    }

    private CompletionStage<ClaimOutcome> markCompensated(ClaimAttempt attempt) {
        CompletionStage<Void> marked;
        try {
            marked = ledger.compareAndSetState(attempt.operationId(),
                    LedgerState.COMPENSATION_PENDING, LedgerState.COMPENSATED, clock.instant());
        } catch (RuntimeException failure) {
            releaseAll(attempt);
            return completed(ClaimOutcome.failed("ledger.failed"));
        }
        return marked.thenApplyAsync(ignored -> {
            releaseAll(attempt);
            return ClaimOutcome.failed("claim.compensated");
        }, asyncExecutor).exceptionally(failure -> {
            releaseAll(attempt);
            return ClaimOutcome.failed("claim.compensated");
        });
    }

    private CompletionStage<ClaimOutcome> recordUnconfirmedRefund(ClaimAttempt attempt) {
        CompletionStage<OperationLedger.CompensationDecision> recorded;
        try {
            recorded = ledger.recordCompensationFailure(
                    attempt.operationId(), compensationRetryLimit, clock.instant());
        } catch (RuntimeException failure) {
            return completed(recordUnconfirmedRefundSync(attempt));
        }
        return recorded.thenApplyAsync(decision -> {
            releaseAll(attempt);
            return decision.resultingState() == LedgerState.NEEDS_RECONCILIATION
                    ? ClaimOutcome.failed("claim.reconciliation")
                    : ClaimOutcome.failed("claim.compensation_pending");
        }, asyncExecutor).exceptionally(failure -> recordUnconfirmedRefundSync(attempt));
    }

    private ClaimOutcome recordUnconfirmedRefundSync(ClaimAttempt attempt) {
        releaseAll(attempt);
        return ClaimOutcome.failed("claim.compensation_pending");
    }

    // ---- Steps 5-6: publish from authoritative state, then mark ACTIVE. ----

    private CompletionStage<ClaimOutcome> publishStep(ClaimAttempt attempt) {
        try {
            publishSideEffects.apply(attempt);
        } catch (RuntimeException failure) {
            return completed(degraded(attempt, "claim.publish_failed"));
        }
        CompletionStage<?> rebuilt;
        try {
            rebuilt = rebuilder.rebuild();
        } catch (RuntimeException failure) {
            return completed(degraded(attempt, "claim.publish_failed"));
        }
        if (rebuilt == null) {
            return completed(degraded(attempt, "claim.publish_failed"));
        }
        CompletionStage<ClaimOutcome> activated;
        try {
            activated = rebuilt.thenComposeAsync(ignored -> activateStep(attempt), asyncExecutor);
        } catch (RuntimeException failure) {
            return completed(degraded(attempt, "claim.publish_failed"));
        }
        if (activated == null) {
            return completed(degraded(attempt, "claim.publish_failed"));
        }
        try {
            CompletionStage<ClaimOutcome> guarded =
                    activated.exceptionally(failure -> degraded(attempt, "claim.publish_failed"));
            if (guarded == null) {
                return completed(degraded(attempt, "claim.publish_failed"));
            }
            return guarded;
        } catch (Throwable failure) {
            return completed(degraded(attempt, "claim.publish_failed"));
        }
    }

    private CompletionStage<ClaimOutcome> activateStep(ClaimAttempt attempt) {
        CompletionStage<Void> marked;
        try {
            marked = ledger.compareAndSetState(attempt.operationId(),
                    LedgerState.DOMAIN_COMMITTED, LedgerState.ACTIVE, clock.instant());
        } catch (RuntimeException failure) {
            return completed(degraded(attempt, "claim.finalize_failed"));
        }
        return marked.thenApplyAsync(ignored ->
                ClaimOutcome.success(attempt.plan().landId()), asyncExecutor)
                .exceptionally(failure -> degraded(attempt, "claim.finalize_failed"));
    }

    private static ClaimOutcome degraded(ClaimAttempt attempt, String key) {
        // Durable commit already happened and no refund is ever issued here;
        // recovery rebuilds the runtime and advances the row to ACTIVE.
        return ClaimOutcome.degraded(attempt.plan().landId(), key);
    }

    // ---- Helpers. ----

    private Optional<OwnerQuotaService.QuotaReservation> tryReserveLand(OwnerRef owner) {
        try {
            return quotas.tryReserveLand(owner);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private Optional<OwnerQuotaService.QuotaReservation> tryReserveChunks(OwnerRef owner, int chunks) {
        try {
            return quotas.tryReserveChunks(owner, chunks);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static Set<String> reservationKeys(ValidatedClaim plan) {
        Set<String> keys = new HashSet<>();
        for (ValidatedClaim.ChunkDetail detail : plan.chunks()) {
            ChunkKey chunk = detail.chunk();
            keys.add(chunk.worldId() + ":" + chunk.chunkX() + ":" + chunk.chunkZ());
        }
        return Set.copyOf(keys);
    }

    private ClaimMaterials buildMaterials(ValidatedClaim plan, Money price, UUID operationId) {
        Instant now = clock.instant();
        String providerId;
        try {
            providerId = economy.providerId();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("economy provider identity failed", failure);
        }
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalStateException("economy provider identity must not be blank");
        }
        Set<ChunkCoordinate> coordinates = new HashSet<>();
        for (ValidatedClaim.ChunkDetail detail : plan.chunks()) {
            ChunkKey chunk = detail.chunk();
            coordinates.add(new ChunkCoordinate(chunk.chunkX(), chunk.chunkZ()));
        }
        CostBasisAllocation allocation = CostBasisCalculator.allocate(price, coordinates);
        UUID claimLotId = UUID.randomUUID();
        List<OperationPayload.Chunk> payloadChunks = new ArrayList<>(plan.chunks().size());
        Set<ChunkKey> landChunks = new HashSet<>();
        for (ValidatedClaim.ChunkDetail detail : plan.chunks()) {
            ChunkKey chunk = detail.chunk();
            long basis = allocation.costBasisFor(
                    new ChunkCoordinate(chunk.chunkX(), chunk.chunkZ())).minorUnits();
            payloadChunks.add(new OperationPayload.Chunk(chunk, detail.storedMinProtectedY(), claimLotId, basis));
            landChunks.add(chunk);
        }
        String displayName = plan.displayName();
        LandSnapshot land = new LandSnapshot(plan.landId(), displayName,
                LandName.normalize(displayName), plan.owner(), plan.worldId(),
                landChunks, List.of(), 0, 0, now, now);
        OperationPayload payload = OperationPayload.claim(operationId, plan.actorUuid(), plan.worldId(),
                plan.landId(), List.copyOf(payloadChunks), price.minorUnits(),
                providerId, now, displayName);
        Long singlePacked = payloadChunks.size() == 1 ? payloadChunks.get(0).chunk().pack() : null;
        AuditEntry audit = new AuditEntry(0, now, plan.actorUuid(), CLAIM_AUDIT_ACTION,
                plan.landId(), plan.worldId(), singlePacked, OperationPayload.CURRENT_SCHEMA_VERSION,
                null, payload.toJson(),
                "{\"priceMinorUnits\":" + price.minorUnits() + ",\"chunks\":" + payloadChunks.size() + "}",
                new ArrayList<>(landChunks));
        return new ClaimMaterials(payload, new ClaimCommit(operationId, land, List.copyOf(payloadChunks), audit));
    }

    /** Vault never returns a provider receipt, so the saga records its own charge identity. */
    private static String chargeTransactionRef(UUID operationId) {
        return "charge:" + operationId;
    }

    private void releasePublishGuards(ClaimAttempt attempt) {
        // Only called after the durable domain commit; moves reserved counts to
        // committed and releases the logical reservations. The logical release
        // runs even when a quota transition fails, so a partial quota failure
        // can only degrade without leaking the chunk/land locks.
        try {
            completeQuotaOrThrow(attempt);
        } finally {
            try {
                reservations.release(attempt.reservationKeys(), attempt.operationId());
            } catch (RuntimeException ignored) {
                // Best effort on terminal paths; registry removal is idempotent.
            }
        }
    }

    private void completeQuotaOrThrow(ClaimAttempt attempt) {
        // Best effort across both reservations, then surface the first failure
        // so the publish guard degrades instead of silently continuing. A
        // reservation that failed to complete is repaired to committed: the
        // durable domain commit already succeeded, so the counters must match
        // the durable truth rather than leak a reservation or under-count.
        // The original failure still propagates, keeping the outcome degraded
        // and observable (startup recovery advances the row to ACTIVE).
        RuntimeException firstFailure = null;
        boolean landFailed = false;
        boolean chunkFailed = false;
        try {
            if (attempt.landReservation() != null) {
                attempt.landReservation().complete();
            }
        } catch (RuntimeException failure) {
            firstFailure = failure;
            landFailed = true;
        }
        try {
            if (attempt.chunkReservation() != null) {
                attempt.chunkReservation().complete();
            }
        } catch (RuntimeException failure) {
            if (firstFailure == null) {
                firstFailure = failure;
            }
            chunkFailed = true;
        }
        if (firstFailure != null) {
            if (landFailed && attempt.landReservation() != null) {
                try {
                    attempt.landReservation().forceComplete();
                } catch (RuntimeException ignored) {
                    // Repair overflow stays releasable; the original failure below
                    // still degrades the outcome for recovery to reconcile.
                }
            }
            if (chunkFailed && attempt.chunkReservation() != null) {
                try {
                    attempt.chunkReservation().forceComplete();
                } catch (RuntimeException ignored) {
                    // Same as above: never mask the original failure.
                }
            }
            throw firstFailure;
        }
    }

    private void releaseQuota(OwnerQuotaService.QuotaReservation land,
            OwnerQuotaService.QuotaReservation chunks) {
        if (land != null) {
            try {
                land.release();
            } catch (RuntimeException ignored) {
                // Best effort on terminal paths; counters never go negative.
            }
        }
        if (chunks != null) {
            try {
                chunks.release();
            } catch (RuntimeException ignored) {
                // Best effort on terminal paths; counters never go negative.
            }
        }
    }

    private void releaseAll(ClaimAttempt attempt) {
        releaseAll(attempt.reservationKeys(), attempt.operationId(),
                attempt.landReservation(), attempt.chunkReservation());
    }

    private void releaseAll(Set<String> keys, UUID operationId,
            OwnerQuotaService.QuotaReservation land, OwnerQuotaService.QuotaReservation chunks) {
        releaseQuota(land, chunks);
        try {
            reservations.release(keys, operationId);
        } catch (RuntimeException ignored) {
            // Best effort on terminal paths; registry removal is idempotent.
        }
    }

    private static CompletableFuture<ClaimOutcome> completed(ClaimOutcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }

    private record ClaimMaterials(OperationPayload payload, ClaimCommit commit) {
    }

    record ClaimAttempt(
            ValidatedClaim plan,
            Money price,
            UUID operationId,
            Set<String> reservationKeys,
            OwnerQuotaService.QuotaReservation landReservation,
            OwnerQuotaService.QuotaReservation chunkReservation,
            ClaimMaterials materials,
            boolean serverOwned) {

        ClaimRequest requestView() {
            Set<ChunkKey> chunks = new HashSet<>();
            for (ValidatedClaim.ChunkDetail detail : plan.chunks()) {
                chunks.add(detail.chunk());
            }
            return new ClaimRequest(plan.owner(), plan.actorUuid(), plan.worldId(), chunks,
                    plan.displayName());
        }
    }
}

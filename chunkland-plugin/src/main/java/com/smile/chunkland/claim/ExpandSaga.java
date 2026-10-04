package com.smile.chunkland.claim;

import com.smile.chunkland.api.event.LandChunkAddPostEvent;
import com.smile.chunkland.api.event.LandChunkAddPreEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.ChunkCoordinate;
import com.smile.chunkland.event.PublicEventCancelledException;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.event.bukkit.LandChunkAddPostBukkitEvent;
import com.smile.chunkland.event.bukkit.LandChunkAddPreBukkitEvent;
import com.smile.chunkland.api.money.CostBasisAllocation;
import com.smile.chunkland.api.money.CostBasisCalculator;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.ExpandCommit;
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
 * Single production execution path for paid land expansions.
 *
 * <p>Step order mirrors the claim saga: revalidate (tokens, target, collision,
 * union connectivity, holes) and price on the caller thread while holding the
 * chunk quota and logical reservations; create the {@code PAYMENT_PENDING}
 * ledger row in its own transaction; withdraw through Economy with the
 * operation id as the idempotency key strictly outside any SQL transaction;
 * append the delta chunks, bump the land {@code structure_revision} and record
 * the {@code CHUNK_ADD} audit trail with {@code DOMAIN_COMMITTED} in the
 * pre-existing single atomic transaction; publish a fresh immutable runtime
 * snapshot built from the authoritative database; finally mark {@code ACTIVE}.
 *
 * <p>Expansion reserves chunk capacity only — the land count is unchanged, so
 * no land-slot reservation is taken. The stored cost basis covers only the new
 * chunks; existing land chunks and their basis are never rewritten.
 *
 * <p>Threading and failure contracts match the claim saga: step one runs
 * synchronously on the caller thread, continuations use the injected async
 * executor, an Economy failure marks {@code FAILED} with no refund, a
 * domain-commit failure compensates once towards {@code COMPENSATED} (or
 * {@code NEEDS_RECONCILIATION} at the retry limit), and a publish failure only
 * degrades with the ledger at {@code DOMAIN_COMMITTED} for startup recovery.
 */
public final class ExpandSaga {

    /** Marker recorded as the transaction ref when no money moves. */
    public static final String ZERO_VALUE_TRANSACTION_REF = ClaimSaga.ZERO_VALUE_TRANSACTION_REF;

    /** Audit action recorded by the atomic domain commit. */
    static final String EXPAND_AUDIT_ACTION = "CHUNK_ADD";

    private final ExpandValidator validator;
    private final OwnerQuotaService quotas;
    private final PricingTable pricing;
    private final LogicalReservationRegistry reservations;
    private final OperationLedger ledger;
    private final ClaimEconomy economy;
    private final RuntimeRegistryRebuilder rebuilder;
    private final Clock clock;
    private final Executor asyncExecutor;
    private final int compensationRetryLimit;
    private final PublicEvents events;
    private final PublishSideEffects publishSideEffects;

    /**
     * Terminal in-memory side effects applied once the durable domain commit
     * succeeded: committed chunk counts and released logical reservations.
     */
    @FunctionalInterface
    interface PublishSideEffects {
        void apply(ExpandAttempt attempt);
    }

    public ExpandSaga(
            ExpandValidator validator,
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
                clock, asyncExecutor, compensationRetryLimit, PublicEvents.noop(), null);
    }

    /**
     * @param events public Pre/Post dispatch; {@code null} means no public
     *         events (the mutation still commits exactly as before)
     */
    public ExpandSaga(
            ExpandValidator validator,
            OwnerQuotaService quotas,
            PricingTable pricing,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            RuntimeRegistryRebuilder rebuilder,
            Clock clock,
            Executor asyncExecutor,
            int compensationRetryLimit,
            PublicEvents events) {
        this(validator, quotas, pricing, reservations, ledger, economy, rebuilder,
                clock, asyncExecutor, compensationRetryLimit, events, null);
    }

    ExpandSaga(
            ExpandValidator validator,
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
        this(validator, quotas, pricing, reservations, ledger, economy, rebuilder,
                clock, asyncExecutor, compensationRetryLimit, PublicEvents.noop(), publishSideEffects);
    }

    private ExpandSaga(
            ExpandValidator validator,
            OwnerQuotaService quotas,
            PricingTable pricing,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            RuntimeRegistryRebuilder rebuilder,
            Clock clock,
            Executor asyncExecutor,
            int compensationRetryLimit,
            PublicEvents events,
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
        this.events = events == null ? PublicEvents.noop() : events;
        this.publishSideEffects =
                publishSideEffects != null ? publishSideEffects : this::releasePublishGuards;
    }

    /**
     * Execute the six-step expansion.
     *
     * <p>Step one runs synchronously on the caller thread so quota and
     * reservation contention stay deterministic; the remaining steps are
     * chained asynchronously and never block a thread waiting.
     */
    public CompletionStage<ClaimOutcome> expand(ExpandRequest request) {
        Objects.requireNonNull(request, "request");
        // ---- Step 1 on the caller thread: revalidate, price, reserve. ----
        final ValidatedExpand plan;
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
                    : pricing.priceForClaim(plan.ownerTotalChunks(), plan.delta().size());
        } catch (RuntimeException failure) {
            return completed(ClaimOutcome.failed("pricing.failed"));
        }
        if (!serverOwned && chargesClaims()) {
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
        // Public Pre: synchronous veto before quota, reservations, ledger
        // and Economy. A veto (or any dispatch failure, which fails closed)
        // rejects with zero side effects.
        if (fireChunkAddPre(plan)) {
            return completed(ClaimOutcome.rejected(PublicEventCancelledException.DIAGNOSTIC_KEY));
        }
        final OwnerQuotaService.QuotaReservation chunkReservation;
        if (serverOwned) {
            chunkReservation = null;
        } else {
            Optional<OwnerQuotaService.QuotaReservation> chunks =
                    tryReserveChunks(plan.owner(), plan.delta().size());
            if (chunks.isEmpty()) {
                return completed(ClaimOutcome.rejected("limit.reached"));
            }
            chunkReservation = chunks.get();
        }
        final Set<String> reservationKeys = reservationKeys(plan);
        final UUID operationId = UUID.randomUUID();
        if (!reservations.tryAcquire(reservationKeys, operationId)) {
            releaseQuota(chunkReservation);
            return completed(ClaimOutcome.rejected("reservation.conflict"));
        }
        final ExpandMaterials materials;
        try {
            materials = buildMaterials(plan, price, operationId);
        } catch (RuntimeException failure) {
            releaseAll(reservationKeys, operationId, chunkReservation);
            return completed(ClaimOutcome.failed("claim.invalid"));
        }
        ExpandAttempt attempt = new ExpandAttempt(plan, price, operationId, reservationKeys,
                chunkReservation, materials, serverOwned);
        // ---- Step 2: durable PAYMENT_PENDING boundary in its own transaction. ----
        CompletionStage<ClaimOutcome> stage;
        try {
            stage = ledger.createPaymentPending(materials.payload())
                    .thenComposeAsync(ignored -> chargeStep(attempt), asyncExecutor);
        } catch (RuntimeException failure) {
            releaseAll(reservationKeys, operationId, chunkReservation);
            return completed(ClaimOutcome.failed("ledger.failed"));
        }
        return stage.exceptionally(failure -> {
            releaseAll(reservationKeys, operationId, chunkReservation);
            return ClaimOutcome.failed("ledger.failed");
        });
    }

    /**
     * Purchases switched off never reach the provider or zero-price gates.
     * An economy that cannot answer is treated as charging, so the gates
     * stay in force (fail-closed).
     */
    private boolean chargesClaims() {
        try {
            return economy.chargesClaims();
        } catch (RuntimeException failure) {
            return true;
        }
    }

    // ---- Step 3: Economy withdraw outside any SQL transaction. ----

    private CompletionStage<ClaimOutcome> chargeStep(ExpandAttempt attempt) {
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

    private CompletionStage<ClaimOutcome> failUncharged(ExpandAttempt attempt, String key) {
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

    private CompletionStage<ClaimOutcome> chargedStep(ExpandAttempt attempt, String transactionRef) {
        CompletionStage<Void> marked;
        try {
            marked = ledger.transitionToCharged(attempt.operationId(), transactionRef, clock.instant());
        } catch (RuntimeException failure) {
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

    private CompletionStage<ClaimOutcome> commitStep(ExpandAttempt attempt) {
        CompletionStage<Void> committed;
        try {
            committed = ledger.commitExpandAtomically(attempt.materials().commit());
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

    CompletionStage<ClaimOutcome> settleCommitted(ExpandAttempt attempt, CompletionStage<Throwable> settled) {
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

    private CompletionStage<ClaimOutcome> publishOnAsyncExecutor(ExpandAttempt attempt) {
        CompletableFuture<ClaimOutcome> published = new CompletableFuture<>();
        try {
            asyncExecutor.execute(() -> settlePublish(attempt, published));
        } catch (RuntimeException failure) {
            return completed(degraded(attempt, "claim.publish_failed"));
        }
        return published.exceptionally(failure -> degraded(attempt, "claim.publish_failed"));
    }

    private void settlePublish(ExpandAttempt attempt, CompletableFuture<ClaimOutcome> published) {
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

    private CompletionStage<ClaimOutcome> compensate(ExpandAttempt attempt) {
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

    private CompletionStage<ClaimOutcome> refundStep(ExpandAttempt attempt) {
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

    private CompletionStage<ClaimOutcome> markCompensated(ExpandAttempt attempt) {
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

    private CompletionStage<ClaimOutcome> recordUnconfirmedRefund(ExpandAttempt attempt) {
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

    private ClaimOutcome recordUnconfirmedRefundSync(ExpandAttempt attempt) {
        releaseAll(attempt);
        return ClaimOutcome.failed("claim.compensation_pending");
    }

    // ---- Steps 5-6: publish from authoritative state, then mark ACTIVE. ----

    private CompletionStage<ClaimOutcome> publishStep(ExpandAttempt attempt) {
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
            activated = rebuilt.thenComposeAsync(ignored -> {
                // Public Post: exactly once, after the durable commit plus
                // the runtime publish. Listener failures are isolated and
                // never roll back the committed expansion.
                fireChunkAddPost(attempt);
                return activateStep(attempt);
            }, asyncExecutor);
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

    private CompletionStage<ClaimOutcome> activateStep(ExpandAttempt attempt) {
        CompletionStage<Void> marked;
        try {
            marked = ledger.compareAndSetState(attempt.operationId(),
                    LedgerState.DOMAIN_COMMITTED, LedgerState.ACTIVE, clock.instant());
        } catch (RuntimeException failure) {
            return completed(degraded(attempt, "claim.finalize_failed"));
        }
        return marked.thenApplyAsync(ignored ->
                ClaimOutcome.success(attempt.plan().targetLandId()), asyncExecutor)
                .exceptionally(failure -> degraded(attempt, "claim.finalize_failed"));
    }

    private static ClaimOutcome degraded(ExpandAttempt attempt, String key) {
        return ClaimOutcome.degraded(attempt.plan().targetLandId(), key);
    }

    // ---- Helpers. ----

    /**
     * Fire the public expansion Pre on the caller thread. Any dispatch
     * failure fails closed to cancelled so the expansion produces zero side
     * effects.
     */
    private boolean fireChunkAddPre(ValidatedExpand plan) {
        final LandChunkAddPreEvent pre;
        try {
            Set<ChunkKey> chunks = new HashSet<>();
            for (ValidatedExpand.ChunkDetail detail : plan.delta()) {
                chunks.add(detail.chunk());
            }
            pre = new LandChunkAddPreEvent(plan.actorUuid(), plan.worldId(),
                    plan.targetLandId(), chunks);
        } catch (RuntimeException failure) {
            return true;
        }
        try {
            return events.firePre(pre, () -> new LandChunkAddPreBukkitEvent(pre.actorUuid(),
                    pre.worldId(), pre.targetLandId(), pre.chunks(), false));
        } catch (Throwable failure) {
            return true;
        }
    }

    /**
     * Fire the public expansion Post after commit plus publish. Never throws:
     * listener failures are isolated by the facade.
     */
    private void fireChunkAddPost(ExpandAttempt attempt) {
        try {
            Set<ChunkKey> added = new HashSet<>();
            for (ValidatedExpand.ChunkDetail detail : attempt.plan().delta()) {
                added.add(detail.chunk());
            }
            LandChunkAddPostEvent post = new LandChunkAddPostEvent(attempt.plan().targetLandId(),
                    attempt.plan().actorUuid(), added, attempt.price().minorUnits());
            events.firePost(post, () -> new LandChunkAddPostBukkitEvent(post.landId(),
                    post.actorUuid(), post.addedChunks(), post.priceMinorUnits(), true));
        } catch (Throwable ignored) {
            // Post dispatch must never break the committed expansion path.
        }
    }

    private Optional<OwnerQuotaService.QuotaReservation> tryReserveChunks(OwnerRef owner, int chunks) {
        try {
            return quotas.tryReserveChunks(owner, chunks);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static Set<String> reservationKeys(ValidatedExpand plan) {
        Set<String> keys = new HashSet<>();
        for (ValidatedExpand.ChunkDetail detail : plan.delta()) {
            ChunkKey chunk = detail.chunk();
            keys.add(chunk.worldId() + ":" + chunk.chunkX() + ":" + chunk.chunkZ());
        }
        return Set.copyOf(keys);
    }

    private ExpandMaterials buildMaterials(ValidatedExpand plan, Money price, UUID operationId) {
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
        for (ValidatedExpand.ChunkDetail detail : plan.delta()) {
            ChunkKey chunk = detail.chunk();
            coordinates.add(new ChunkCoordinate(chunk.chunkX(), chunk.chunkZ()));
        }
        CostBasisAllocation allocation = CostBasisCalculator.allocate(price, coordinates);
        UUID expandLotId = UUID.randomUUID();
        List<OperationPayload.Chunk> payloadChunks = new ArrayList<>(plan.delta().size());
        Set<ChunkKey> deltaKeys = new HashSet<>();
        for (ValidatedExpand.ChunkDetail detail : plan.delta()) {
            ChunkKey chunk = detail.chunk();
            long basis = allocation.costBasisFor(
                    new ChunkCoordinate(chunk.chunkX(), chunk.chunkZ())).minorUnits();
            payloadChunks.add(new OperationPayload.Chunk(chunk, detail.storedMinProtectedY(), expandLotId, basis));
            deltaKeys.add(chunk);
        }
        OperationPayload payload = OperationPayload.expand(operationId, plan.actorUuid(), plan.worldId(),
                plan.targetLandId(), List.copyOf(payloadChunks), price.minorUnits(),
                providerId, now, plan.displayName());
        Long singlePacked = payloadChunks.size() == 1 ? payloadChunks.get(0).chunk().pack() : null;
        AuditEntry audit = new AuditEntry(0, now, plan.actorUuid(), EXPAND_AUDIT_ACTION,
                plan.targetLandId(), plan.worldId(), singlePacked, OperationPayload.CURRENT_SCHEMA_VERSION,
                null, payload.toJson(),
                "{\"priceMinorUnits\":" + price.minorUnits() + ",\"chunks\":" + payloadChunks.size() + "}",
                new ArrayList<>(deltaKeys));
        return new ExpandMaterials(payload, new ExpandCommit(operationId, plan.targetLandId(),
                plan.worldId(), plan.owner(), plan.expectedStructureRevision(),
                List.copyOf(payloadChunks), audit));
    }

    /** Vault never returns a provider receipt, so the saga records its own charge identity. */
    private static String chargeTransactionRef(UUID operationId) {
        return "charge:" + operationId;
    }

    private void releasePublishGuards(ExpandAttempt attempt) {
        try {
            if (attempt.chunkReservation() != null) {
                attempt.chunkReservation().complete();
            }
        } finally {
            try {
                reservations.release(attempt.reservationKeys(), attempt.operationId());
            } catch (RuntimeException ignored) {
                // Best effort on terminal paths; registry removal is idempotent.
            }
        }
    }

    private void releaseQuota(OwnerQuotaService.QuotaReservation chunks) {
        if (chunks != null) {
            try {
                chunks.release();
            } catch (RuntimeException ignored) {
                // Best effort on terminal paths; counters never go negative.
            }
        }
    }

    private void releaseAll(ExpandAttempt attempt) {
        releaseAll(attempt.reservationKeys(), attempt.operationId(), attempt.chunkReservation());
    }

    private void releaseAll(Set<String> keys, UUID operationId,
            OwnerQuotaService.QuotaReservation chunks) {
        releaseQuota(chunks);
        try {
            reservations.release(keys, operationId);
        } catch (RuntimeException ignored) {
            // Best effort on terminal paths; registry removal is idempotent.
        }
    }

    private static CompletableFuture<ClaimOutcome> completed(ClaimOutcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }

    private record ExpandMaterials(OperationPayload payload, ExpandCommit commit) {
    }

    record ExpandAttempt(
            ValidatedExpand plan,
            Money price,
            UUID operationId,
            Set<String> reservationKeys,
            OwnerQuotaService.QuotaReservation chunkReservation,
            ExpandMaterials materials,
            boolean serverOwned) {

        ClaimRequest requestView() {
            Set<ChunkKey> chunks = new HashSet<>();
            for (ValidatedExpand.ChunkDetail detail : plan.delta()) {
                chunks.add(detail.chunk());
            }
            return new ClaimRequest(plan.owner(), plan.actorUuid(), plan.worldId(), chunks,
                    plan.displayName());
        }
    }
}

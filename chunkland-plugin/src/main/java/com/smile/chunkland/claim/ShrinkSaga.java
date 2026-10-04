package com.smile.chunkland.claim;

import com.smile.chunkland.api.event.LandChunkRemovePostEvent;
import com.smile.chunkland.api.event.LandChunkRemovePreEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.CostBasisCalculator;
import com.smile.chunkland.event.PublicEventCancelledException;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.event.bukkit.LandChunkRemovePostBukkitEvent;
import com.smile.chunkland.event.bukkit.LandChunkRemovePreBukkitEvent;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.ChunkRepository;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.LedgerState;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.ShrinkCommit;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
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
 * Single production execution path for land shrink (unclaim of chunks).
 *
 * <p>Step order is domain-first, mirroring the refund saga: revalidate
 * (tokens, target, membership, connectivity, SubLand containment) on the
 * caller thread while holding logical reservations; read the durable
 * per-chunk cost bases and derive the refund as {@code durable basis × ratio}
 * (never the current pricing table); record the {@code CREATED} shrink ledger
 * row in its own transaction; commit the domain (delete the delta chunks,
 * bump {@code structure_revision}, record {@code CHUNK_REMOVE}) in one atomic
 * transaction; deposit through Economy with the operation id as the idempotency
 * key strictly outside any SQL transaction; publish a fresh immutable runtime
 * snapshot built from the authoritative database; finally settle the row.
 *
 * <p>Once the domain commit succeeds it is never rolled back. The deposit
 * itself is always preceded by a durably parked execution intent ({@code
 * DOMAIN_COMMITTED} to {@code COMPENSATION_PENDING} in its own
 * transaction): only a row that is provably never sent may be deposited, a
 * confirmed deposit settles it as {@code COMPENSATED} and publishes the
 * runtime, and any other outcome quarantines it as {@code
 * NEEDS_RECONCILIATION} for operator reconciliation instead of resending.
 * Removing the last chunk is rejected towards the delete flow with zero
 * mutation, so an empty land row can never be written.
 *
 * <p>The provider cannot dedup deposits (Vault Legacy drops the idempotency
 * key at the bridge), so cross-restart exactly-once rests solely on this
 * ledger ordering: never resend a parked row. See the execution-intent note
 * on the deposit step.
 *
 * <p>Economy gate: a refund needs a provider only when the derived amount is
 * positive, so the check runs after the durable bases are read and fails closed
 * with {@code shrink.economy_unavailable} before the ledger row and the domain
 * commit. A zero refund moves no money and settles without a provider.
 *
 * <p>Threading: validation runs synchronously on the caller thread so
 * reservation contention stays deterministic; every continuation runs on the
 * injected async executor, so persistence reads, Economy deposits and publish
 * work never block the caller and no stage blocks a thread waiting.
 */
public final class ShrinkSaga {

    /** Operation type stored on shrink ledger rows. */
    public static final String SHRINK_OPERATION_TYPE = "SHRINK";

    /** Audit action recorded by the atomic domain commit. */
    static final String SHRINK_AUDIT_ACTION = "CHUNK_REMOVE";

    /** Marker recorded as the transaction ref when no money moves. */
    public static final String ZERO_VALUE_TRANSACTION_REF = "zero-value";

    /**
     * Provider marker recorded on Server Land rows. A Server shrink moves no
     * money, so the saga must not call Economy at all; the marker keeps the
     * ledger payload valid without a live provider lookup.
     */
    public static final String SERVER_LAND_PROVIDER_ID = "server-land";

    /** Default refund ratio numerator (one half of the original cost basis). */
    public static final long REFUND_NUMERATOR = 1L;

    /** Default refund ratio denominator. */
    public static final long REFUND_DENOMINATOR = 2L;

    private final ShrinkValidator validator;
    private final ChunkRepository chunks;
    private final Currency currency;
    private final LogicalReservationRegistry reservations;
    private final OperationLedger ledger;
    private final ClaimEconomy economy;
    private final RuntimeRegistryRebuilder rebuilder;
    private final OwnerQuotaService quotas;
    private final Clock clock;
    private final Executor asyncExecutor;
    /**
     * Kept for construction compatibility with the shared saga wiring. Refund
     * rows are single-attempt by ledger state (a parked row is never
     * resent), so this limit no longer gates the shrink compensation path.
     */
    @SuppressWarnings("unused")
    private final int compensationRetryLimit;
    private final PublicEvents events;

    public ShrinkSaga(
            ShrinkValidator validator,
            ChunkRepository chunks,
            Currency currency,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            RuntimeRegistryRebuilder rebuilder,
            OwnerQuotaService quotas,
            Clock clock,
            Executor asyncExecutor,
            int compensationRetryLimit) {
        this(validator, chunks, currency, reservations, ledger, economy, rebuilder, quotas,
                clock, asyncExecutor, compensationRetryLimit, PublicEvents.noop());
    }

    /**
     * @param events public Pre/Post dispatch; {@code null} means no public
     *         events (the mutation still commits exactly as before)
     */
    public ShrinkSaga(
            ShrinkValidator validator,
            ChunkRepository chunks,
            Currency currency,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            RuntimeRegistryRebuilder rebuilder,
            OwnerQuotaService quotas,
            Clock clock,
            Executor asyncExecutor,
            int compensationRetryLimit,
            PublicEvents events) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.rebuilder = Objects.requireNonNull(rebuilder, "rebuilder");
        this.quotas = Objects.requireNonNull(quotas, "quotas");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor");
        if (compensationRetryLimit < 1) {
            throw new IllegalArgumentException("compensationRetryLimit must be positive");
        }
        this.compensationRetryLimit = compensationRetryLimit;
        this.events = events == null ? PublicEvents.noop() : events;
    }

    /**
     * Execute one shrink.
     *
     * <p>Step one runs synchronously on the caller thread so reservation
     * contention stays deterministic; the remaining steps are chained
     * asynchronously and never block a thread waiting.
     */
    public CompletionStage<ShrinkOutcome> shrink(ShrinkRequest request) {
        Objects.requireNonNull(request, "request");
        final ValidatedShrink plan;
        try {
            plan = validator.validate(request);
        } catch (ClaimRejectedException rejected) {
            return completed(ShrinkOutcome.rejected(rejected.diagnosticKey()));
        } catch (RuntimeException failure) {
            return completed(ShrinkOutcome.failed("shrink.validation_failed"));
        }
        if (plan == null) {
            return completed(ShrinkOutcome.failed("shrink.validation_failed"));
        }
        final boolean serverOwned = plan.owner() instanceof OwnerRef.ServerOwnerRef;
        // Public Pre: synchronous veto before reservations, the ledger row,
        // the domain commit and any Economy refund. A veto (or any dispatch
        // failure, which fails closed) rejects with zero side effects.
        if (fireChunkRemovePre(plan)) {
            return completed(ShrinkOutcome.rejected(PublicEventCancelledException.DIAGNOSTIC_KEY));
        }
        final UUID operationId = UUID.randomUUID();
        final Set<String> reservationKeys = reservationKeys(plan);
        if (!reservations.tryAcquire(reservationKeys, operationId)) {
            return completed(ShrinkOutcome.rejected("shrink.reservation_conflict"));
        }
        CompletionStage<Map<ChunkKey, ChunkRepository.ChunkFact>> facts;
        try {
            facts = chunks.factsByLand(plan.targetLandId());
        } catch (RuntimeException failure) {
            release(reservationKeys, operationId);
            return completed(ShrinkOutcome.failed("shrink.validation_failed"));
        }
        if (facts == null) {
            release(reservationKeys, operationId);
            return completed(ShrinkOutcome.failed("shrink.validation_failed"));
        }
        return facts.thenComposeAsync(all -> driveWithFacts(plan, operationId, reservationKeys,
                all, serverOwned), asyncExecutor)
                .exceptionally(failure -> {
                    release(reservationKeys, operationId);
                    return rejectedOrFailed(failure);
                });
    }

    private CompletionStage<ShrinkOutcome> driveWithFacts(ValidatedShrink plan, UUID operationId,
            Set<String> reservationKeys, Map<ChunkKey, ChunkRepository.ChunkFact> all,
            boolean serverOwned) {
        final ShrinkMaterials materials;
        try {
            materials = buildMaterials(plan, operationId, all, serverOwned);
        } catch (ClaimRejectedException rejected) {
            release(reservationKeys, operationId);
            return completed(ShrinkOutcome.rejected(rejected.diagnosticKey()));
        } catch (RuntimeException failure) {
            release(reservationKeys, operationId);
            return completed(ShrinkOutcome.failed("shrink.validation_failed"));
        }
        // A refund only needs an Economy provider when it actually pays out, so the
        // provider gate belongs here: the durable bases are read and the refund
        // is derived above, and a positive amount with no provider fails closed
        // before the ledger row, the domain commit and any deposit. A zero
        // refund (Server Land, or chunks bought while purchases were off) moves
        // no money and settles without one.
        if (materials.refundAmount() > 0L && !refundProviderReady()) {
            release(reservationKeys, operationId);
            return completed(ShrinkOutcome.rejected("shrink.economy_unavailable"));
        }
        CompletionStage<Void> created;
        try {
            created = ledger.create(materials.payload());
        } catch (RuntimeException failure) {
            release(reservationKeys, operationId);
            return completed(ShrinkOutcome.failed("shrink.ledger_failed"));
        }
        if (created == null) {
            release(reservationKeys, operationId);
            return completed(ShrinkOutcome.failed("shrink.ledger_failed"));
        }
        return created.thenComposeAsync(ignored -> commitDomain(
                plan, operationId, reservationKeys, materials, serverOwned), asyncExecutor)
                .exceptionally(failure -> {
                    release(reservationKeys, operationId);
                    return ShrinkOutcome.failed("shrink.ledger_failed");
                });
    }

    private static ShrinkOutcome rejectedOrFailed(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ClaimRejectedException rejected) {
                return ShrinkOutcome.rejected(rejected.diagnosticKey());
            }
            if (current instanceof java.util.concurrent.CompletionException completion
                    && completion.getCause() != null) {
                current = completion.getCause();
                continue;
            }
            break;
        }
        return ShrinkOutcome.failed("shrink.validation_failed");
    }

    // ---- Step 4: single atomic domain transaction. Money never moves before this. ----

    private CompletionStage<ShrinkOutcome> commitDomain(ValidatedShrink plan, UUID operationId,
            Set<String> reservationKeys, ShrinkMaterials materials, boolean serverOwned) {
        CompletionStage<Void> committed;
        try {
            committed = ledger.commitShrinkAtomically(materials.commit());
        } catch (RuntimeException failure) {
            release(reservationKeys, operationId);
            return completed(ShrinkOutcome.failed("shrink.commit_failed"));
        }
        if (committed == null) {
            release(reservationKeys, operationId);
            return completed(ShrinkOutcome.failed("shrink.commit_failed"));
        }
        return committed.handle((ignored, failure) -> failure)
                .thenComposeAsync(commitFailure -> {
                    // Reservations are always released once the domain attempt
                    // settles: on success the chunks are gone, on failure the
                    // next attempt must revalidate from scratch.
                    release(reservationKeys, operationId);
                    if (commitFailure != null) {
                        return completed(mapCommitFailure(commitFailure));
                    }
                    try {
                        quotas.releaseCommittedChunks(plan.owner(), materials.commit().chunks().size());
                    } catch (RuntimeException ignored) {
                        // Best effort: the durable domain already won; quota
                        // counters never block the refund or the publish.
                    }
                    return depositThenPublish(operationId, materials.refundAmount(),
                            plan.targetLandId(), plan.actorUuid(), deltaKeys(materials));
                }, asyncExecutor)
                .exceptionally(failure -> ShrinkOutcome.failed("shrink.commit_failed"));
    }

    static ShrinkOutcome mapCommitFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                if (message.contains("shrink.split")) {
                    return ShrinkOutcome.rejected("shrink.split");
                }
                if (message.contains("shrink.subland_overlap")) {
                    return ShrinkOutcome.rejected("shrink.subland_overlap");
                }
                if (message.contains("shrink.delete_required")) {
                    return ShrinkOutcome.rejected("shrink.delete_required");
                }
                if (message.contains("stale land structure revision")) {
                    return ShrinkOutcome.rejected("structure.stale");
                }
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return ShrinkOutcome.failed("shrink.commit_failed");
    }

    // ---- Step 5: Economy deposit outside any SQL transaction. ----
    //
    // Execution-intent ordering: the deposit runs only after the row has been
    // durably parked from DOMAIN_COMMITTED to COMPENSATION_PENDING in its own
    // transaction, and it runs at most once per row. DOMAIN_COMMITTED is the
    // only state that proves the provider was never contacted. A parked row
    // may already have moved money (the process can crash between the deposit
    // and the settle), and the provider cannot dedup, so a parked row is
    // never resent: any outcome other than a confirmed deposit quarantines
    // for operator reconciliation.

    private CompletionStage<ShrinkOutcome> depositThenPublish(UUID operationId, long refundAmount,
            LandId landId, UUID actorUuid, Set<ChunkKey> delta) {
        CompletionStage<LedgerEntry> found;
        try {
            found = ledger.find(operationId);
        } catch (RuntimeException failure) {
            return completed(ShrinkOutcome.failed("shrink.ledger_failed"));
        }
        if (found == null) {
            return completed(ShrinkOutcome.failed("shrink.ledger_failed"));
        }
        return found.thenComposeAsync(entry -> {
            if (entry == null) {
                return completed(ShrinkOutcome.failed("shrink.ledger_failed"));
            }
            final LedgerState committed;
            try {
                committed = LedgerState.parse(entry.state());
            } catch (RuntimeException malformed) {
                return completed(ShrinkOutcome.failed("shrink.invalid_payload"));
            }
            if (refundAmount == 0L) {
                if (committed != LedgerState.DOMAIN_COMMITTED
                        && committed != LedgerState.COMPENSATION_PENDING) {
                    return completed(ShrinkOutcome.failed("shrink.unexpected_state"));
                }
                return settleCompensated(entry, committed, ZERO_VALUE_TRANSACTION_REF, actorUuid, delta);
            }
            if (committed == LedgerState.COMPENSATION_PENDING) {
                return quarantineForReconciliation(entry);
            }
            if (committed != LedgerState.DOMAIN_COMMITTED) {
                return completed(ShrinkOutcome.failed("shrink.unexpected_state"));
            }
            return parkThenDeposit(entry, actorUuid, delta);
        }, asyncExecutor).exceptionally(failure -> ShrinkOutcome.failed("shrink.ledger_failed"));
    }

    /**
     * Persist the execution intent before touching the provider, then deposit
     * exactly once. A park failure leaves the row provably unsent, so it
     * fails without calling Economy and stays retryable by recovery; after
     * the park, only a confirmed deposit settles and publishes, and every
     * other outcome quarantines without resending.
     */
    private CompletionStage<ShrinkOutcome> parkThenDeposit(LedgerEntry entry, UUID actorUuid,
            Set<ChunkKey> delta) {
        CompletionStage<Void> parked;
        try {
            parked = ledger.parkForRefundCompensation(
                    entry.operationId(), "shrink:" + entry.operationId(), clock.instant());
        } catch (RuntimeException failure) {
            return completed(ShrinkOutcome.failed("shrink.ledger_failed"));
        }
        if (parked == null) {
            return completed(ShrinkOutcome.failed("shrink.ledger_failed"));
        }
        return parked.thenComposeAsync(ignored -> depositOnce(entry, actorUuid, delta), asyncExecutor)
                .exceptionallyCompose(failure -> quarantineForReconciliation(entry));
    }

    private CompletionStage<ShrinkOutcome> depositOnce(LedgerEntry entry, UUID actorUuid,
            Set<ChunkKey> delta) {
        CompletionStage<RefundOutcome> deposit;
        try {
            deposit = economy.refund(entry);
        } catch (Throwable thrown) {
            return quarantineForReconciliation(entry);
        }
        if (deposit == null) {
            return quarantineForReconciliation(entry);
        }
        return deposit.thenComposeAsync(outcome -> {
            if (outcome == RefundOutcome.REFUNDED) {
                return settleCompensated(entry, LedgerState.COMPENSATION_PENDING,
                        "shrink:" + entry.operationId(), actorUuid, delta);
            }
            return quarantineForReconciliation(entry);
        }, asyncExecutor).exceptionallyCompose(failure -> quarantineForReconciliation(entry));
    }

    /**
     * Fail closed to operator reconciliation: the row carries a parked
     * execution intent, so the deposit may already have moved money and must
     * not be resent.
     */
    private CompletionStage<ShrinkOutcome> quarantineForReconciliation(LedgerEntry entry) {
        CompletionStage<Void> quarantined;
        try {
            quarantined = ledger.quarantine(
                    entry.operationId(), LedgerState.COMPENSATION_PENDING, clock.instant());
        } catch (RuntimeException failure) {
            return completed(ShrinkOutcome.failed("shrink.ledger_failed"));
        }
        if (quarantined == null) {
            return completed(ShrinkOutcome.failed("shrink.ledger_failed"));
        }
        return quarantined.thenApplyAsync(ignored ->
                ShrinkOutcome.needsReconciliation(entry.targetLandId(), amountOf(entry)),
                asyncExecutor)
                .exceptionally(failure -> ShrinkOutcome.failed("shrink.ledger_failed"));
    }

    private CompletionStage<ShrinkOutcome> settleCompensated(LedgerEntry entry, LedgerState committed,
            String transactionRef, UUID actorUuid, Set<ChunkKey> delta) {
        CompletionStage<Void> settled;
        try {
            settled = ledger.settleRefundCompensated(
                    entry.operationId(), committed, transactionRef, clock.instant());
        } catch (RuntimeException failure) {
            return completed(ShrinkOutcome.degraded(
                    entry.targetLandId(), amountOf(entry), "shrink.finalize_failed"));
        }
        if (settled == null) {
            return completed(ShrinkOutcome.degraded(
                    entry.targetLandId(), amountOf(entry), "shrink.finalize_failed"));
        }
        return settled.thenComposeAsync(ignored -> publishAndSucceed(entry, actorUuid, delta), asyncExecutor)
                .exceptionally(failure -> ShrinkOutcome.degraded(
                        entry.targetLandId(), amountOf(entry), "shrink.finalize_failed"));
    }

    // ---- Steps 6-7: publish from authoritative state, then report. ----

    private CompletionStage<ShrinkOutcome> publishAndSucceed(LedgerEntry entry, UUID actorUuid,
            Set<ChunkKey> delta) {
        CompletionStage<?> rebuilt;
        try {
            rebuilt = rebuilder.rebuild();
        } catch (RuntimeException failure) {
            return completed(ShrinkOutcome.degraded(
                    entry.targetLandId(), amountOf(entry), "shrink.publish_failed"));
        }
        if (rebuilt == null) {
            return completed(ShrinkOutcome.degraded(
                    entry.targetLandId(), amountOf(entry), "shrink.publish_failed"));
        }
        return rebuilt.thenApplyAsync(ignored -> {
                    // Public Post: exactly once, and only because the refund
                    // above was confirmed successful — failed or unconfirmed
                    // refunds quarantine for reconciliation and never reach
                    // this publish step. Listener failures are isolated and
                    // never roll back the committed shrink.
                    fireChunkRemovePost(entry.targetLandId(), actorUuid, delta, amountOf(entry));
                    return ShrinkOutcome.success(entry.targetLandId(), amountOf(entry));
                }, asyncExecutor)
                .exceptionally(failure -> ShrinkOutcome.degraded(
                        entry.targetLandId(), amountOf(entry), "shrink.publish_failed"));
    }

    /**
     * Fire the public shrink Pre on the caller thread. Any dispatch failure
     * fails closed to cancelled so the shrink produces zero side effects.
     */
    private boolean fireChunkRemovePre(ValidatedShrink plan) {
        final LandChunkRemovePreEvent pre;
        try {
            pre = new LandChunkRemovePreEvent(plan.actorUuid(), plan.worldId(),
                    plan.targetLandId(), Set.copyOf(plan.delta()));
        } catch (RuntimeException failure) {
            return true;
        }
        try {
            return events.firePre(pre, () -> new LandChunkRemovePreBukkitEvent(pre.actorUuid(),
                    pre.worldId(), pre.targetLandId(), pre.chunks(), false));
        } catch (Throwable failure) {
            return true;
        }
    }

    /**
     * Fire the public shrink Post after commit, a confirmed refund, and
     * publish. Never throws: listener failures are isolated by the facade.
     */
    private void fireChunkRemovePost(LandId landId, UUID actorUuid, Set<ChunkKey> delta,
            long refundAmount) {
        try {
            LandChunkRemovePostEvent post =
                    new LandChunkRemovePostEvent(landId, actorUuid, delta, refundAmount);
            events.firePost(post, () -> new LandChunkRemovePostBukkitEvent(post.landId(),
                    post.actorUuid(), post.removedChunks(), post.refundMinorUnits(), true));
        } catch (Throwable ignored) {
            // Post dispatch must never break the committed shrink path.
        }
    }

    private static Set<ChunkKey> deltaKeys(ShrinkMaterials materials) {
        Set<ChunkKey> keys = new HashSet<>();
        for (OperationPayload.Chunk chunk : materials.commit().chunks()) {
            keys.add(chunk.chunk());
        }
        return Set.copyOf(keys);
    }

    // ---- Builders. ----

    private ShrinkMaterials buildMaterials(ValidatedShrink plan, UUID operationId,
            Map<ChunkKey, ChunkRepository.ChunkFact> all, boolean serverOwned) {
        if (all == null) {
            throw new ClaimRejectedException("shrink.validation_failed");
        }
        List<ChunkKey> ordered = new ArrayList<>(plan.delta());
        ordered.sort(Comparator.comparingInt(ChunkKey::chunkX).thenComparingInt(ChunkKey::chunkZ));
        List<OperationPayload.Chunk> payloadChunks = new ArrayList<>(ordered.size());
        long total = 0L;
        for (ChunkKey key : ordered) {
            ChunkRepository.ChunkFact fact = all.get(key);
            if (fact == null) {
                throw new ClaimRejectedException("shrink.stale");
            }
            Long basis = fact.costBasisMinorUnits();
            if (basis == null) {
                throw new ClaimRejectedException("shrink.missing_basis");
            }
            if (basis < 0) {
                throw new ClaimRejectedException("shrink.invalid_basis");
            }
            try {
                total = Math.addExact(total, basis);
            } catch (ArithmeticException overflow) {
                throw new ClaimRejectedException("shrink.invalid_basis");
            }
            int storedMin = fact.storedMinProtectedY() != null
                    ? fact.storedMinProtectedY()
                    : com.smile.chunkland.runtime.vertical.VerticalDepths.LEGACY_STORED_FALLBACK_Y;
            UUID lot = fact.claimLotId() != null ? fact.claimLotId() : operationId;
            payloadChunks.add(new OperationPayload.Chunk(key, storedMin, lot, basis));
        }
        final long refund;
        if (serverOwned) {
            refund = 0L;
        } else {
            try {
                Money amount = CostBasisCalculator.refund(new Money(total, currency),
                        REFUND_NUMERATOR, REFUND_DENOMINATOR);
                refund = amount.minorUnits();
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                throw new ClaimRejectedException("shrink.invalid_basis");
            }
        }
        Instant now = clock.instant();
        final String providerId;
        if (serverOwned) {
            // Server Land moves no money: record the marker without touching
            // Economy, so a provider outage can never block the shrink.
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
        OperationPayload payload = OperationPayload.shrink(operationId, plan.actorUuid(),
                plan.worldId(), plan.targetLandId(), List.copyOf(payloadChunks), refund,
                providerId, now, plan.displayName(), REFUND_NUMERATOR, REFUND_DENOMINATOR);
        Long singlePacked = payloadChunks.size() == 1 ? payloadChunks.get(0).chunk().pack() : null;
        Set<ChunkKey> deltaKeys = new HashSet<>(ordered);
        AuditEntry audit = new AuditEntry(0, now, plan.actorUuid(), SHRINK_AUDIT_ACTION,
                plan.targetLandId(), plan.worldId(), singlePacked, OperationPayload.CURRENT_SCHEMA_VERSION,
                null, payload.toJson(),
                "{\"refundMinorUnits\":" + refund + ",\"costBasisMinorUnits\":" + total
                        + ",\"chunks\":" + payloadChunks.size() + "}",
                new ArrayList<>(deltaKeys));
        return new ShrinkMaterials(payload,
                new ShrinkCommit(operationId, plan.targetLandId(), plan.worldId(), plan.owner(),
                        plan.expectedStructureRevision(), List.copyOf(payloadChunks), refund, audit),
                refund, total);
    }

    /**
     * Whether a provider that can move money exists right now. An economy that
     * cannot answer fails closed, so a missing provider is never mistaken for a
     * free one.
     */
    private boolean refundProviderReady() {
        try {
            return economy.isAvailable();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static Set<String> reservationKeys(ValidatedShrink plan) {
        Set<String> keys = new HashSet<>();
        for (ChunkKey chunk : plan.delta()) {
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

    private static CompletableFuture<ShrinkOutcome> completed(ShrinkOutcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }

    private record ShrinkMaterials(OperationPayload payload, ShrinkCommit commit,
            long refundAmount, long totalBasis) {
    }
}

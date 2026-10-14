package com.smile.chunkland.runtime.mutation;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.mutation.ChunkLandMutations;
import com.smile.chunkland.api.mutation.ExpectedVersion;
import com.smile.chunkland.api.mutation.MutationKind;
import com.smile.chunkland.api.mutation.MutationRequest;
import com.smile.chunkland.api.mutation.MutationResult;
import com.smile.chunkland.api.mutation.TypedMutationRequest;
import com.smile.chunkland.api.mutation.TypedMutationResult;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.claim.ClaimOutcome;
import com.smile.chunkland.claim.ClaimRequest;
import com.smile.chunkland.claim.ClaimSaga;
import com.smile.chunkland.claim.DeleteOutcome;
import com.smile.chunkland.claim.DeleteRequest;
import com.smile.chunkland.claim.DeleteSaga;
import com.smile.chunkland.claim.ExpandRequest;
import com.smile.chunkland.claim.ExpandSaga;
import com.smile.chunkland.claim.ShrinkOutcome;
import com.smile.chunkland.claim.ShrinkRequest;
import com.smile.chunkland.claim.ShrinkSaga;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.LedgerState;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.rename.LandRenameResult;
import com.smile.chunkland.rename.LandRenameService;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.time.Clock;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Production typed mutation entry backed by the real sagas.
 *
 * <p>Every typed request runs through the same production flow the chat
 * handlers use — the four claim-family sagas plus the rename service — with
 * the same validators, quotas, reservations, pricing, ledger, economy,
 * atomic commits, public events and recovery. There is no second write
 * path: this facade only translates the typed envelope into the saga
 * request shapes and maps the saga outcomes back.
 *
 * <p>The five safety fields are all enforced, never accepted-but-dropped:
 *
 * <ul>
 *   <li>Actor and target owner stay separate. Player land requires the
 *   actor to be the durable owner; Server land requires the steward grant.
 *   Expansion additionally passes the shared management gate with
 *   {@code EXPAND_LAND}, exactly as the gated chat handler does.</li>
 *   <li>Expected version is compared against the live snapshot before any
 *   side effect, and the sagas re-check the structure token against their
 *   live sources with the atomic commit as the final compare-and-set
 *   authority. Stale expectations reject with
 *   {@code mutation.stale-expected-version} and write nothing.</li>
 *   <li>Operation id travels end to end into the ledger. A resubmitted id
 *   returns the recorded result — success stays success, failure stays
 *   failure, and in-progress rows report as submitted failures — without
 *   charging, refunding or publishing twice. The same id with different
 *   content rejects with {@code mutation.operation-id-mismatch} and runs
 *   nothing. A different id with the same content is an independent
 *   operation and runs the full flow again.</li>
 * </ul>
 *
 * <p>Post-commit failures stay distinguishable: anything reported with
 * {@code submitted} set may already have taken effect (ledger row, economy
 * movement, domain write), so callers reconcile by operation id instead of
 * blindly retrying. No JDBC type ever reaches the caller; every persistence
 * failure is mapped to a stable diagnostic key.
 *
 * <p>After {@link #close()} the handle rejects new operations with
 * {@code FAILED / coordinator.rejected} and creates no new side effects.
 * The facade never touches Bukkit state, so an offline actor changes
 * nothing except the steward and bypass lookups, which resolve to
 * {@code false} when they cannot prove the grant.
 */
public final class SagaBackedMutations implements ChunkLandMutations {

    /** Same id resubmitted with different content: rejected, nothing runs. */
    public static final String OPERATION_ID_MISMATCH = "mutation.operation-id-mismatch";

    /** Resubmitted id whose ledger row has not reached a terminal state. */
    public static final String IN_PROGRESS = "mutation.in-progress";

    /** Stable key reported after the handle is closed. */
    public static final String COORDINATOR_REJECTED = "coordinator.rejected";

    /** Provider marker recorded on rename rows, which never move money. */
    static final String RENAME_PROVIDER_ID = "none";

    private final MutationServiceAdapter legacy;
    private final ClaimSaga claimSaga;
    private final ExpandSaga expandSaga;
    private final ShrinkSaga shrinkSaga;
    private final DeleteSaga deleteSaga;
    private final LandRenameService renameService;
    private final OperationLedger ledger;
    private final Supplier<LandRegistry> snapshots;
    private final Supplier<PermissionContextProvider> contexts;
    private final Function<UUID, Boolean> stewards;
    private final Function<UUID, Boolean> bypassStates;
    private final Clock clock;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * @param legacyDelegate backing for the legacy envelope entry; never
     *         {@code null} (production passes a defensive stub plus an empty
     *         supported set, so legacy envelopes stay rejected)
     * @param legacySupported kinds the legacy envelope may describe;
     *         defensively copied, never {@code null}
     * @param claimSaga production claim flow; never {@code null}
     * @param expandSaga production expansion flow; never {@code null}
     * @param shrinkSaga production shrink flow; never {@code null}
     * @param deleteSaga production delete flow; never {@code null}
     * @param renameService production rename flow; never {@code null}
     * @param ledger durable operation log backing idempotency; never
     *         {@code null}
     * @param snapshots live immutable registry snapshots; never {@code null}
     * @param contexts snapshot-backed permission contexts for the management
     *         gate; never {@code null}
     * @param stewards server-land grant reads per actor UUID; {@code null}
     *         or failing reads resolve to {@code false} (fail-closed)
     * @param bypassStates admin-bypass reads per actor UUID; {@code null} or
     *         failing reads resolve to {@code false} (fail-closed)
     */
    public SagaBackedMutations(
            Function<MutationRequest, CompletionStage<MutationResult>> legacyDelegate,
            Set<MutationKind> legacySupported,
            ClaimSaga claimSaga,
            ExpandSaga expandSaga,
            ShrinkSaga shrinkSaga,
            DeleteSaga deleteSaga,
            LandRenameService renameService,
            OperationLedger ledger,
            Supplier<LandRegistry> snapshots,
            Supplier<PermissionContextProvider> contexts,
            Function<UUID, Boolean> stewards,
            Function<UUID, Boolean> bypassStates,
            Clock clock) {
        this.legacy = new MutationServiceAdapter(
                Objects.requireNonNull(legacyDelegate, "legacyDelegate"),
                Objects.requireNonNull(legacySupported, "legacySupported"));
        this.claimSaga = Objects.requireNonNull(claimSaga, "claimSaga");
        this.expandSaga = Objects.requireNonNull(expandSaga, "expandSaga");
        this.shrinkSaga = Objects.requireNonNull(shrinkSaga, "shrinkSaga");
        this.deleteSaga = Objects.requireNonNull(deleteSaga, "deleteSaga");
        this.renameService = Objects.requireNonNull(renameService, "renameService");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.stewards = stewards;
        this.bypassStates = bypassStates;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<MutationResult> submit(MutationRequest request) {
        return legacy.submit(request);
    }

    @Override
    public CompletionStage<TypedMutationResult> submitTyped(TypedMutationRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            return completed(TypedMutationResult.failedBeforeSubmit(
                    COORDINATOR_REJECTED, request.operationId()));
        }
        UUID operationId = request.operationId();
        CompletionStage<Optional<LedgerEntry>> lookup;
        try {
            lookup = ledger.findById(operationId);
        } catch (RuntimeException failure) {
            return completed(TypedMutationResult.failedBeforeSubmit("ledger.failed", operationId));
        }
        if (lookup == null) {
            return completed(TypedMutationResult.failedBeforeSubmit("ledger.failed", operationId));
        }
        return lookup.thenCompose(existing -> {
            if (existing != null && existing.isPresent()) {
                return completed(mapResubmit(request, existing.get()));
            }
            return dispatchFresh(request);
        }).exceptionallyCompose(failure -> failedWithLedgerCheckStage(operationId));
    }

    /**
     * Stop accepting new operations. Idempotent: later submits complete
     * with {@code FAILED / coordinator.rejected} and create no side
     * effects, while operations already running converge on their own.
     */
    public void close() {
        closed.set(true);
    }

    /** Whether {@link #close()} has been called. */
    public boolean isClosed() {
        return closed.get();
    }

    // ------------------------------------------------------------------
    // Fresh dispatch
    // ------------------------------------------------------------------

    private CompletionStage<TypedMutationResult> dispatchFresh(TypedMutationRequest request) {
        try {
            return switch (request) {
                case TypedMutationRequest.LandCreate create -> createFlow(create);
                case TypedMutationRequest.LandChunkAdd add -> expandFlow(add);
                case TypedMutationRequest.LandChunkRemove remove -> shrinkFlow(remove);
                case TypedMutationRequest.LandDelete delete -> deleteFlow(delete);
                case TypedMutationRequest.LandRename rename -> renameFlow(rename);
                case TypedMutationRequest.SublandCreate ignored ->
                        completed(TypedMutationResult.rejected(
                                MutationServiceAdapter.UNSUPPORTED_DIAGNOSTIC, request.operationId()));
                case TypedMutationRequest.SublandDelete ignored ->
                        completed(TypedMutationResult.rejected(
                                MutationServiceAdapter.UNSUPPORTED_DIAGNOSTIC, request.operationId()));
                case TypedMutationRequest.PermissionChange ignored ->
                        completed(TypedMutationResult.rejected(
                                MutationServiceAdapter.UNSUPPORTED_DIAGNOSTIC, request.operationId()));
                case TypedMutationRequest.RuleChange ignored ->
                        completed(TypedMutationResult.rejected(
                                MutationServiceAdapter.UNSUPPORTED_DIAGNOSTIC, request.operationId()));
            };
        } catch (Throwable failure) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", request.operationId()));
        }
    }

    private CompletionStage<TypedMutationResult> createFlow(TypedMutationRequest.LandCreate request) {
        UUID operationId = request.operationId();
        UUID actor = request.actorUuid();
        OwnerRef target = request.targetOwner();
        if (target instanceof OwnerRef.PlayerOwnerRef player) {
            if (!player.uuid().equals(actor)) {
                return completed(TypedMutationResult.rejected("claim.not_allowed", operationId));
            }
        } else if (target instanceof OwnerRef.ServerOwnerRef) {
            if (!isSteward(actor)) {
                return completed(TypedMutationResult.rejected("claim.not_allowed", operationId));
            }
        } else {
            return completed(TypedMutationResult.rejected("claim.not_allowed", operationId));
        }
        ClaimRequest sagaRequest;
        try {
            sagaRequest = new ClaimRequest(target, actor, request.worldId(), request.chunks(),
                    request.displayName(), null, null, null, null, operationId);
        } catch (RuntimeException invalid) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId));
        }
        CompletionStage<ClaimOutcome> stage;
        try {
            stage = claimSaga.claim(sagaRequest);
        } catch (Throwable failure) {
            return failedWithLedgerCheckStage(operationId);
        }
        if (stage == null) {
            return failedWithLedgerCheckStage(operationId);
        }
        return stage.toCompletableFuture().thenCompose(outcome -> mapClaimOutcome(outcome, operationId))
                .exceptionallyCompose(failure -> failedWithLedgerCheckStage(operationId));
    }

    private CompletionStage<TypedMutationResult> expandFlow(TypedMutationRequest.LandChunkAdd request) {
        UUID operationId = request.operationId();
        UUID actor = request.actorUuid();
        LandSnapshot target;
        try {
            target = liveLand(request.landId());
        } catch (SnapshotUnavailableException unavailable) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId));
        }
        if (target == null) {
            return completed(TypedMutationResult.rejected("expand.unknown_land", operationId));
        }
        if (!request.worldId().equals(target.worldId())) {
            return completed(TypedMutationResult.rejected("expand.world_mismatch", operationId));
        }
        if (!claimedOwnerMatches(request.targetOwner(), target)) {
            return completed(TypedMutationResult.rejected("expand.owner_mismatch", operationId));
        }
        OwnerRef requestOwner = sagaOwnerFor(target, actor);
        if (requestOwner == null) {
            return completed(TypedMutationResult.rejected("expand.owner_mismatch", operationId));
        }
        if (!gateAllows(actor, request.landId(), ProtectionActionType.EXPAND_LAND)) {
            return completed(TypedMutationResult.rejected("expand.owner_mismatch", operationId));
        }
        String stale = checkExpected(request.expectedVersion(), target);
        if (stale != null) {
            return completed(TypedMutationResult.rejected(stale, operationId));
        }
        long structure = request.expectedVersion().requireStructureRevision();
        ExpandRequest sagaRequest;
        try {
            sagaRequest = new ExpandRequest(requestOwner, actor, request.worldId(), request.landId(),
                    request.chunks(), null, null, structure, operationId, true);
        } catch (RuntimeException invalid) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId));
        }
        CompletionStage<ClaimOutcome> stage;
        try {
            stage = expandSaga.expand(sagaRequest);
        } catch (Throwable failure) {
            return failedWithLedgerCheckStage(operationId);
        }
        if (stage == null) {
            return failedWithLedgerCheckStage(operationId);
        }
        return stage.toCompletableFuture().thenCompose(outcome -> mapClaimOutcome(outcome, operationId))
                .exceptionallyCompose(failure -> failedWithLedgerCheckStage(operationId));
    }

    private CompletionStage<TypedMutationResult> shrinkFlow(TypedMutationRequest.LandChunkRemove request) {
        UUID operationId = request.operationId();
        UUID actor = request.actorUuid();
        LandSnapshot target;
        try {
            target = liveLand(request.landId());
        } catch (SnapshotUnavailableException unavailable) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId));
        }
        if (target == null) {
            return completed(TypedMutationResult.rejected("shrink.unknown_land", operationId));
        }
        if (!request.worldId().equals(target.worldId())) {
            return completed(TypedMutationResult.rejected("shrink.world_mismatch", operationId));
        }
        if (!claimedOwnerMatches(request.targetOwner(), target)) {
            return completed(TypedMutationResult.rejected("shrink.owner_mismatch", operationId));
        }
        OwnerRef requestOwner = sagaOwnerFor(target, actor);
        if (requestOwner == null) {
            return completed(TypedMutationResult.rejected("shrink.owner_mismatch", operationId));
        }
        String stale = checkExpected(request.expectedVersion(), target);
        if (stale != null) {
            return completed(TypedMutationResult.rejected(stale, operationId));
        }
        long structure = request.expectedVersion().requireStructureRevision();
        ShrinkRequest sagaRequest;
        try {
            sagaRequest = new ShrinkRequest(requestOwner, actor, request.worldId(), request.landId(),
                    request.chunks(), null, null, structure, operationId, true);
        } catch (RuntimeException invalid) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId));
        }
        CompletionStage<ShrinkOutcome> stage;
        try {
            stage = shrinkSaga.shrink(sagaRequest);
        } catch (Throwable failure) {
            return failedWithLedgerCheckStage(operationId);
        }
        if (stage == null) {
            return failedWithLedgerCheckStage(operationId);
        }
        return stage.toCompletableFuture().thenCompose(outcome -> mapShrinkOutcome(outcome, operationId))
                .exceptionallyCompose(failure -> failedWithLedgerCheckStage(operationId));
    }

    private CompletionStage<TypedMutationResult> deleteFlow(TypedMutationRequest.LandDelete request) {
        UUID operationId = request.operationId();
        UUID actor = request.actorUuid();
        LandSnapshot target;
        try {
            target = liveLand(request.landId());
        } catch (SnapshotUnavailableException unavailable) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId));
        }
        if (target == null) {
            return completed(TypedMutationResult.rejected("delete.unknown_land", operationId));
        }
        if (!claimedOwnerMatches(request.targetOwner(), target)) {
            return completed(TypedMutationResult.rejected("delete.owner_mismatch", operationId));
        }
        OwnerRef requestOwner = sagaOwnerFor(target, actor);
        if (requestOwner == null) {
            return completed(TypedMutationResult.rejected("delete.owner_mismatch", operationId));
        }
        String stale = checkExpected(request.expectedVersion(), target);
        if (stale != null) {
            return completed(TypedMutationResult.rejected(stale, operationId));
        }
        long structure = request.expectedVersion().requireStructureRevision();
        DeleteRequest sagaRequest;
        try {
            sagaRequest = new DeleteRequest(requestOwner, actor, target.worldId(), request.landId(),
                    structure, operationId);
        } catch (RuntimeException invalid) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId));
        }
        CompletionStage<DeleteOutcome> stage;
        try {
            stage = deleteSaga.delete(sagaRequest);
        } catch (Throwable failure) {
            return failedWithLedgerCheckStage(operationId);
        }
        if (stage == null) {
            return failedWithLedgerCheckStage(operationId);
        }
        return stage.toCompletableFuture().thenCompose(outcome -> mapDeleteOutcome(outcome, operationId))
                .exceptionallyCompose(failure -> failedWithLedgerCheckStage(operationId));
    }

    /**
     * Rename through the production rename service with a durable ledger
     * row, so the operation id converges exactly like the saga flows: the
     * row is created first, the service commits second, and the row settles
     * to {@code ACTIVE} (or {@code DOMAIN_COMMITTED} for recovery when the
     * publish lags, {@code FAILED} when nothing committed). A rename moves
     * no money and publishes no public events, so the only convergence that
     * matters is running the durable update once.
     */
    private CompletionStage<TypedMutationResult> renameFlow(TypedMutationRequest.LandRename request) {
        UUID operationId = request.operationId();
        UUID actor = request.actorUuid();
        LandSnapshot target;
        try {
            target = liveLand(request.landId());
        } catch (SnapshotUnavailableException unavailable) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId));
        }
        if (target == null) {
            return completed(TypedMutationResult.rejected("rename.unknown_land", operationId));
        }
        if (!claimedOwnerMatches(request.targetOwner(), target)) {
            return completed(TypedMutationResult.rejected("rename.owner_mismatch", operationId));
        }
        boolean steward = isSteward(actor);
        if (!renameAuthorised(actor, target, steward)) {
            return completed(TypedMutationResult.rejected("rename.not_allowed", operationId));
        }
        String stale = checkExpected(request.expectedVersion(), target);
        if (stale != null) {
            return completed(TypedMutationResult.rejected(stale, operationId));
        }
        OperationPayload payload;
        try {
            payload = OperationPayload.rename(operationId, actor, target.worldId(), request.landId(),
                    request.displayName(), RENAME_PROVIDER_ID, clock.instant());
        } catch (RuntimeException invalid) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId));
        }
        CompletionStage<Void> inserted;
        try {
            inserted = ledger.create(payload);
        } catch (RuntimeException failure) {
            return convergeOnExisting(request, operationId);
        }
        if (inserted == null) {
            return convergeOnExisting(request, operationId);
        }
        return inserted.toCompletableFuture().thenCompose(ignored -> {
            CompletionStage<LandRenameResult> stage;
            try {
                stage = renameService.rename(actor, request.landId(), request.displayName(), steward);
            } catch (Throwable failure) {
                return settleRenameCreated(operationId, request.landId(),
                        LandRenameResult.failed("rename.failed"));
            }
            if (stage == null) {
                return settleRenameCreated(operationId, request.landId(),
                        LandRenameResult.failed("rename.failed"));
            }
            return stage.thenCompose(outcome -> settleRenameCreated(operationId, request.landId(),
                            outcome == null ? LandRenameResult.failed("rename.failed") : outcome))
                    .exceptionallyCompose(failure ->
                            settleRenameCreated(operationId, request.landId(),
                                    LandRenameResult.failed("rename.failed")));
        }).exceptionallyCompose(failure -> convergeOnExisting(request, operationId));
    }

    private CompletionStage<TypedMutationResult> settleRenameCreated(
            UUID operationId, LandId landId, LandRenameResult outcome) {
        return switch (outcome.status()) {
            case SUCCESS -> settleRenameCommitted(operationId, landId, true);
            case DEGRADED -> settleRenameCommitted(operationId, landId, false);
            case REJECTED -> markRenameTerminal(operationId, LedgerState.CREATED, LedgerState.FAILED,
                    TypedMutationResult.rejected(outcome.diagnosticKey(), operationId));
            case FAILED -> markRenameTerminal(operationId, LedgerState.CREATED, LedgerState.FAILED,
                    TypedMutationResult.failedBeforeSubmit(outcome.diagnosticKey(), operationId));
        };
    }

    private CompletionStage<TypedMutationResult> settleRenameCommitted(
            UUID operationId, LandId landId, boolean markActive) {
        CompletionStage<Void> committed;
        try {
            committed = ledger.compareAndSetState(operationId,
                    LedgerState.CREATED, LedgerState.DOMAIN_COMMITTED, clock.instant());
        } catch (RuntimeException failure) {
            return convergeOnExisting(operationId,
                    TypedMutationResult.failedAfterCommit("rename.publish_failed", operationId));
        }
        if (committed == null) {
            return completed(TypedMutationResult.failedAfterCommit("rename.publish_failed", operationId));
        }
        return committed.toCompletableFuture().thenCompose(ignored -> {
            if (!markActive) {
                return completed(TypedMutationResult.failedAfterCommit("rename.publish_failed", operationId));
            }
            CompletionStage<Void> activated;
            try {
                activated = ledger.compareAndSetState(operationId,
                        LedgerState.DOMAIN_COMMITTED, LedgerState.ACTIVE, clock.instant());
            } catch (RuntimeException failure) {
                return completed(TypedMutationResult.failedAfterCommit("rename.publish_failed", operationId));
            }
            if (activated == null) {
                return completed(TypedMutationResult.failedAfterCommit("rename.publish_failed", operationId));
            }
            return activated.toCompletableFuture()
                    .thenApply(done -> TypedMutationResult.success(landId, operationId))
                    .exceptionally(failure ->
                            TypedMutationResult.failedAfterCommit("rename.publish_failed", operationId));
        }).exceptionallyCompose(failure -> convergeOnExisting(operationId));
    }

    // ------------------------------------------------------------------
    // Outcome mapping
    // ------------------------------------------------------------------

    private CompletionStage<TypedMutationResult> mapClaimOutcome(ClaimOutcome outcome, UUID operationId) {
        if (outcome == null) {
            return failedWithLedgerCheckStage(operationId);
        }
        return switch (outcome.status()) {
            case SUCCESS -> completed(TypedMutationResult.success(outcome.landId(), operationId));
            case REJECTED -> completed(TypedMutationResult.rejected(outcome.diagnosticKey(), operationId));
            case FAILED -> "ledger.failed".equals(outcome.diagnosticKey())
                    ? convergeOnExisting(operationId,
                            TypedMutationResult.failedBeforeSubmit(outcome.diagnosticKey(), operationId))
                    : completed(TypedMutationResult.failedBeforeSubmit(outcome.diagnosticKey(), operationId));
            case DEGRADED -> completed(TypedMutationResult.failedAfterCommit(outcome.diagnosticKey(), operationId));
        };
    }

    private CompletionStage<TypedMutationResult> mapShrinkOutcome(ShrinkOutcome outcome, UUID operationId) {
        if (outcome == null) {
            return failedWithLedgerCheckStage(operationId);
        }
        return switch (outcome.status()) {
            case SUCCESS -> completed(TypedMutationResult.success(outcome.landId(), operationId));
            case COMPENSATION_PENDING, NEEDS_RECONCILIATION ->
                    completed(TypedMutationResult.failedAfterCommit(outcome.diagnosticKey(), operationId));
            case REJECTED -> completed(TypedMutationResult.rejected(outcome.diagnosticKey(), operationId));
            case FAILED -> "shrink.ledger_failed".equals(outcome.diagnosticKey())
                    ? convergeOnExisting(operationId,
                            TypedMutationResult.failedBeforeSubmit(outcome.diagnosticKey(), operationId))
                    : completed(TypedMutationResult.failedBeforeSubmit(outcome.diagnosticKey(), operationId));
            case DEGRADED -> completed(TypedMutationResult.failedAfterCommit(outcome.diagnosticKey(), operationId));
        };
    }

    private CompletionStage<TypedMutationResult> mapDeleteOutcome(DeleteOutcome outcome, UUID operationId) {
        if (outcome == null) {
            return failedWithLedgerCheckStage(operationId);
        }
        return switch (outcome.status()) {
            case SUCCESS -> completed(TypedMutationResult.success(outcome.landId(), operationId));
            case COMPENSATION_PENDING, NEEDS_RECONCILIATION ->
                    completed(TypedMutationResult.failedAfterCommit(outcome.diagnosticKey(), operationId));
            case REJECTED -> completed(TypedMutationResult.rejected(outcome.diagnosticKey(), operationId));
            case FAILED -> "delete.ledger_failed".equals(outcome.diagnosticKey())
                    ? convergeOnExisting(operationId,
                            TypedMutationResult.failedBeforeSubmit(outcome.diagnosticKey(), operationId))
                    : completed(TypedMutationResult.failedBeforeSubmit(outcome.diagnosticKey(), operationId));
            case DEGRADED -> completed(TypedMutationResult.failedAfterCommit(outcome.diagnosticKey(), operationId));
        };
    }

    // ------------------------------------------------------------------
    // Resubmit convergence over the existing ledger row
    // ------------------------------------------------------------------

    private TypedMutationResult mapResubmit(TypedMutationRequest request, LedgerEntry entry) {
        UUID operationId = request.operationId();
        Boolean matches;
        try {
            matches = contentMatches(request, entry);
        } catch (Throwable unreadable) {
            return TypedMutationResult.failedAfterCommit("ledger.failed", operationId);
        }
        if (matches == null) {
            return TypedMutationResult.failedAfterCommit("ledger.failed", operationId);
        }
        if (!matches) {
            return TypedMutationResult.rejected(OPERATION_ID_MISMATCH, operationId);
        }
        if (!resubmitOwnerMatches(request, entry)) {
            return TypedMutationResult.rejected(OPERATION_ID_MISMATCH, operationId);
        }
        LedgerState state;
        try {
            state = LedgerState.parse(entry.state());
        } catch (RuntimeException malformed) {
            return TypedMutationResult.failedAfterCommit("mutation.failed", operationId);
        }
        return switch (state) {
            case ACTIVE -> entry.targetLandId() == null
                    ? TypedMutationResult.failedAfterCommit("mutation.failed", operationId)
                    : TypedMutationResult.success(entry.targetLandId(), operationId);
            case COMPENSATED -> "CLAIM".equals(entry.operationType())
                    || "EXPAND".equals(entry.operationType())
                    ? TypedMutationResult.failedAfterCommit("claim.compensated", operationId)
                    : entry.targetLandId() == null
                            ? TypedMutationResult.failedAfterCommit("mutation.failed", operationId)
                            : TypedMutationResult.success(entry.targetLandId(), operationId);
            case NEEDS_RECONCILIATION ->
                    TypedMutationResult.failedAfterCommit(reconciliationKey(entry), operationId);
            case FAILED -> TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId);
            case RESOLVED, REFUNDED, IGNORED ->
                    TypedMutationResult.failedAfterCommit("mutation.failed", operationId);
            case CREATED, PAYMENT_PENDING, CHARGED, DOMAIN_COMMITTED, COMPENSATION_PENDING ->
                    TypedMutationResult.failedAfterCommit(IN_PROGRESS, operationId);
        };
    }

    /**
     * Whether the stored row describes the same operation. Compares only
     * fields the ledger persists: kind, actor, world, target, chunk
     * identities and display name where the typed shape carries one.
     * Returns {@code null} when the stored payload cannot be read.
     */
    private static Boolean contentMatches(TypedMutationRequest request, LedgerEntry entry) {
        OperationPayload payload;
        try {
            payload = OperationPayload.fromJson(entry.payloadJson());
        } catch (RuntimeException corrupt) {
            return null;
        }
        if (!operationTypeOf(request.kind()).equals(payload.operationType())) {
            return false;
        }
        if (!request.actorUuid().equals(payload.actorUuid())) {
            return false;
        }
        return switch (request) {
            case TypedMutationRequest.LandCreate create ->
                create.worldId().equals(payload.worldUuid())
                        && chunkIdentities(payload).equals(create.chunks())
                        && payload.landDisplayName().equals(create.displayName());
            case TypedMutationRequest.LandChunkAdd add ->
                add.worldId().equals(payload.worldUuid())
                        && add.landId().equals(payload.targetLandId())
                        && chunkIdentities(payload).equals(add.chunks());
            case TypedMutationRequest.LandChunkRemove remove ->
                remove.worldId().equals(payload.worldUuid())
                        && remove.landId().equals(payload.targetLandId())
                        && chunkIdentities(payload).equals(remove.chunks());
            case TypedMutationRequest.LandDelete delete ->
                delete.landId().equals(payload.targetLandId());
            case TypedMutationRequest.LandRename rename ->
                rename.landId().equals(payload.targetLandId())
                        && payload.worldUuid().equals(entry.worldId())
                        && payload.landDisplayName().equals(rename.displayName());
            default -> false;
        };
    }

    private static Set<ChunkKey> chunkIdentities(OperationPayload payload) {
        Set<ChunkKey> keys = new HashSet<>();
        for (OperationPayload.Chunk chunk : payload.chunkSet()) {
            keys.add(chunk.chunk());
        }
        return Set.copyOf(keys);
    }

    private static String operationTypeOf(MutationKind kind) {
        return switch (kind) {
            case LAND_CREATE -> "CLAIM";
            case LAND_CHUNK_ADD -> "EXPAND";
            case LAND_CHUNK_REMOVE -> "SHRINK";
            case LAND_DELETE -> "DELETE";
            case LAND_RENAME -> "RENAME";
            default -> kind.name();
        };
    }

    private static String reconciliationKey(LedgerEntry entry) {
        return switch (entry.operationType()) {
            case "SHRINK" -> "shrink.reconciliation";
            case "DELETE" -> "delete.reconciliation";
            case "RENAME" -> "rename.reconciliation";
            default -> "claim.reconciliation";
        };
    }

    private CompletionStage<TypedMutationResult> convergeOnExisting(
            TypedMutationRequest request, UUID operationId) {
        CompletionStage<Optional<LedgerEntry>> lookup;
        try {
            lookup = ledger.findById(operationId);
        } catch (RuntimeException failure) {
            return completed(TypedMutationResult.failedBeforeSubmit("ledger.failed", operationId));
        }
        if (lookup == null) {
            return completed(TypedMutationResult.failedBeforeSubmit("ledger.failed", operationId));
        }
        return lookup.toCompletableFuture().thenApply(existing -> {
            if (existing != null && existing.isPresent()) {
                return mapResubmit(request, existing.get());
            }
            return TypedMutationResult.failedBeforeSubmit("ledger.failed", operationId);
        }).exceptionally(failure ->
                TypedMutationResult.failedBeforeSubmit("ledger.failed", operationId));
    }

    private CompletionStage<TypedMutationResult> convergeOnExisting(UUID operationId) {
        CompletionStage<Optional<LedgerEntry>> lookup;
        try {
            lookup = ledger.findById(operationId);
        } catch (RuntimeException failure) {
            return completed(TypedMutationResult.failedBeforeSubmit("ledger.failed", operationId));
        }
        if (lookup == null) {
            return completed(TypedMutationResult.failedBeforeSubmit("ledger.failed", operationId));
        }
        return lookup.toCompletableFuture().thenApply(existing -> {
            if (existing != null && existing.isPresent()) {
                return mapResubmitStateOnly(existing.get(), operationId);
            }
            return TypedMutationResult.failedBeforeSubmit("ledger.failed", operationId);
        }).exceptionally(failure ->
                TypedMutationResult.failedBeforeSubmit("ledger.failed", operationId));
    }

    private CompletionStage<TypedMutationResult> convergeOnExisting(
            UUID operationId, TypedMutationResult fallback) {
        CompletionStage<Optional<LedgerEntry>> lookup;
        try {
            lookup = ledger.findById(operationId);
        } catch (RuntimeException failure) {
            return completed(fallback);
        }
        if (lookup == null) {
            return completed(fallback);
        }
        return lookup.toCompletableFuture().thenApply(existing -> {
            if (existing != null && existing.isPresent()) {
                return mapResubmitStateOnly(existing.get(), operationId);
            }
            return fallback;
        }).exceptionally(failure -> fallback);
    }

    private TypedMutationResult mapResubmitStateOnly(LedgerEntry entry, UUID operationId) {
        LedgerState state;
        try {
            state = LedgerState.parse(entry.state());
        } catch (RuntimeException malformed) {
            return TypedMutationResult.failedAfterCommit("mutation.failed", operationId);
        }
        return switch (state) {
            case ACTIVE -> entry.targetLandId() == null
                    ? TypedMutationResult.failedAfterCommit("mutation.failed", operationId)
                    : TypedMutationResult.success(entry.targetLandId(), operationId);
            case COMPENSATED -> "CLAIM".equals(entry.operationType())
                    || "EXPAND".equals(entry.operationType())
                    ? TypedMutationResult.failedAfterCommit("claim.compensated", operationId)
                    : entry.targetLandId() == null
                            ? TypedMutationResult.failedAfterCommit("mutation.failed", operationId)
                            : TypedMutationResult.success(entry.targetLandId(), operationId);
            default -> TypedMutationResult.failedAfterCommit(IN_PROGRESS, operationId);
        };
    }

    // ------------------------------------------------------------------
    // Shared guards
    // ------------------------------------------------------------------

    /** Live snapshot land, {@code null} when the target does not exist. */
    private LandSnapshot liveLand(LandId landId) throws SnapshotUnavailableException {
        LandRegistry snapshot;
        try {
            snapshot = snapshots.get();
        } catch (RuntimeException failure) {
            throw new SnapshotUnavailableException(failure);
        }
        if (snapshot == null) {
            throw new SnapshotUnavailableException();
        }
        try {
            return snapshot.land(landId);
        } catch (RuntimeException failure) {
            throw new SnapshotUnavailableException(failure);
        }
    }

    private static final class SnapshotUnavailableException extends Exception {
        SnapshotUnavailableException() {
            super(null, null, true, false);
        }

        SnapshotUnavailableException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Whether the caller-claimed target owner still describes the live
     * target: a player land requires the claimed player to be the durable
     * owner, a Server land requires the claimed Server owner. A stale
     * cognition (transferred or converted land) fails, so destructive
     * operations never run on a target the caller no longer describes.
     */
    private static boolean claimedOwnerMatches(OwnerRef claimed, LandSnapshot live) {
        OwnerRef durable = live.ownerRef();
        if (durable instanceof OwnerRef.ServerOwnerRef) {
            return claimed instanceof OwnerRef.ServerOwnerRef;
        }
        if (durable instanceof OwnerRef.PlayerOwnerRef player) {
            return claimed instanceof OwnerRef.PlayerOwnerRef observed
                    && observed.uuid().equals(player.uuid());
        }
        return false;
    }

    /**
     * Whether the resubmitted claimed owner still describes the live
     * target. The ledger never persisted the owner, so the live snapshot
     * is the oracle for this one dimension: a resubmit whose claimed
     * owner no longer matches is a different operation and rejects as a
     * mismatch. When the target is gone (deleted land, not-yet-created
     * row) there is nothing to compare against and the stored payload
     * comparison decides alone.
     */
    private boolean resubmitOwnerMatches(TypedMutationRequest request, LedgerEntry entry) {
        LandId check = request.kind() == MutationKind.LAND_CREATE
                ? entry.targetLandId() : request.landId();
        if (check == null) {
            return true;
        }
        LandSnapshot live;
        try {
            live = liveLand(check);
        } catch (SnapshotUnavailableException unavailable) {
            return true;
        }
        if (live == null) {
            return true;
        }
        return claimedOwnerMatches(request.targetOwner(), live);
    }

    /**
     * Request owner from the durable target owner, mirroring the chat
     * handlers: a Server-owned target carries the Server owner so a steward
     * passes validation, while a player-owned target carries the actor so a
     * non-owner keeps failing the validator's owner check. Returns
     * {@code null} when the actor must not act on this target at all.
     */
    private OwnerRef sagaOwnerFor(LandSnapshot target, UUID actor) {
        OwnerRef durable = target.ownerRef();
        if (durable instanceof OwnerRef.ServerOwnerRef) {
            return isSteward(actor) ? OwnerRef.server() : null;
        }
        if (durable instanceof OwnerRef.PlayerOwnerRef player) {
            return player.uuid().equals(actor) ? OwnerRef.player(actor) : null;
        }
        return null;
    }

    private static boolean renameAuthorised(UUID actor, LandSnapshot target, boolean steward) {
        if (target.ownerRef() instanceof OwnerRef.ServerOwnerRef) {
            return steward;
        }
        if (target.ownerRef() instanceof OwnerRef.PlayerOwnerRef player) {
            return player.uuid().equals(actor);
        }
        return false;
    }

    private boolean gateAllows(UUID actor, LandId landId, ProtectionActionType action) {
        LandRegistry snapshot;
        PermissionContextProvider provider;
        try {
            snapshot = snapshots.get();
            provider = contexts.get();
        } catch (RuntimeException failure) {
            return false;
        }
        if (snapshot == null || provider == null) {
            return false;
        }
        try {
            return ManagementPermissionGate.check(
                    actor, landId, action, snapshot, isBypass(actor), isSteward(actor), provider)
                    .outcome() == PermissionState.ALLOW;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    /**
     * Compare every carried expectation dimension against the live target.
     * Returns the rejection key, or {@code null} when all carried
     * dimensions agree.
     */
    private static String checkExpected(ExpectedVersion expected, LandSnapshot target) {
        if (expected == null) {
            return "mutation.missing-expected-version";
        }
        if (expected.structureRevision() != null
                && !expected.structureRevision().equals(target.structureRevision())) {
            return TypedMutationResult.STALE_EXPECTED_VERSION;
        }
        if (expected.landPolicyRevision() != null
                && !expected.landPolicyRevision().equals(target.landPolicyRevision())) {
            return TypedMutationResult.STALE_EXPECTED_VERSION;
        }
        return null;
    }

    private boolean isSteward(UUID actor) {
        if (stewards == null || actor == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stewards.apply(actor));
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private boolean isBypass(UUID actor) {
        if (bypassStates == null || actor == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(bypassStates.apply(actor));
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private CompletionStage<TypedMutationResult> markRenameTerminal(
            UUID operationId, LedgerState expected, LedgerState next, TypedMutationResult result) {
        CompletionStage<Void> marked;
        try {
            marked = ledger.compareAndSetState(operationId, expected, next, clock.instant());
        } catch (RuntimeException failure) {
            return completed(result);
        }
        if (marked == null) {
            return completed(result);
        }
        return marked.toCompletableFuture()
                .thenApply(ignored -> result)
                .exceptionallyCompose(failure -> convergeOnExisting(operationId, result));
    }

    /**
     * Failure after a saga was entered: when the ledger already holds this
     * operation the failure is reported as submitted (something may have
     * taken effect — reconcile by operation id); otherwise nothing ran.
     */
    private CompletionStage<TypedMutationResult> failedWithLedgerCheckStage(UUID operationId) {
        CompletionStage<Optional<LedgerEntry>> lookup;
        try {
            lookup = ledger.findById(operationId);
        } catch (RuntimeException failure) {
            return completed(TypedMutationResult.failedAfterCommit("mutation.failed", operationId));
        }
        if (lookup == null) {
            return completed(TypedMutationResult.failedAfterCommit("mutation.failed", operationId));
        }
        return lookup.toCompletableFuture().thenApply(existing -> {
            if (existing != null && existing.isPresent()) {
                return TypedMutationResult.failedAfterCommit("mutation.failed", operationId);
            }
            return TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId);
        }).exceptionally(failure -> TypedMutationResult.failedAfterCommit("mutation.failed", operationId));
    }

    private static CompletableFuture<TypedMutationResult> completed(TypedMutationResult result) {
        return CompletableFuture.completedFuture(result);
    }
}

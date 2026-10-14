package com.smile.chunkland.runtime.mutation;

import com.smile.chunkland.api.event.PermissionChangedEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.mutation.ChunkLandMutations;
import com.smile.chunkland.api.mutation.ExpectedVersion;
import com.smile.chunkland.api.mutation.MutationKind;
import com.smile.chunkland.api.mutation.MutationRequest;
import com.smile.chunkland.api.mutation.MutationResult;
import com.smile.chunkland.api.mutation.TypedMutationRequest;
import com.smile.chunkland.api.mutation.TypedMutationResult;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
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
import com.smile.chunkland.event.PublicEventCancelledException;
import com.smile.chunkland.persistence.BindingRejectedException;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.LedgerState;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.LandDefaultConflictException;
import com.smile.chunkland.persistence.LandRuleConflictException;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.StaleAuthorisationException;
import com.smile.chunkland.protection.DirectTrustWhitelist;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.rename.LandRenameResult;
import com.smile.chunkland.rename.LandRenameService;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.subland.DepthExtendConfirmationRequired;
import com.smile.chunkland.subland.SubLandMutationRunner;
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
 * handlers use — the four claim-family sagas, the rename service, the
 * SubLand mutation runner, the direct-trust and binding services, and
 * the conditional rule write — with the same validators, quotas,
 * reservations, pricing, ledger, economy, atomic commits, public events
 * and recovery. There is no second write path: this facade only
 * translates the typed envelope into the service request shapes and
 * maps the outcomes back.
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
    private final SublandWritePort sublands;
    private final PermissionWritePort permissions;
    private final RuleWritePort rules;
    private final Supplier<LandAuthorisationSnapshot> policySnapshots;
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
        this(legacyDelegate, legacySupported, claimSaga, expandSaga, shrinkSaga, deleteSaga,
                renameService, null, null, null, null, ledger, snapshots, contexts,
                stewards, bypassStates, clock);
    }

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
     * @param sublands production SubLand commits; {@code null} keeps
     *         subland kinds rejected with {@code mutation.unsupported}
     * @param permissions production permission commits; {@code null} keeps
     *         permission kinds rejected with {@code mutation.unsupported}
     * @param rules production rule commits; {@code null} keeps rule kinds
     *         rejected with {@code mutation.unsupported}
     * @param policySnapshots live authorisation snapshots carrying the
     *         durable policy generation per land; {@code null} falls back
     *         to the structural snapshot for the policy expectation
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
            SublandWritePort sublands,
            PermissionWritePort permissions,
            RuleWritePort rules,
            Supplier<LandAuthorisationSnapshot> policySnapshots,
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
        this.sublands = sublands;
        this.permissions = permissions;
        this.rules = rules;
        this.policySnapshots = policySnapshots;
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
                case TypedMutationRequest.SublandCreate create -> sublandCreateFlow(create);
                case TypedMutationRequest.SublandDelete delete -> sublandDeleteFlow(delete);
                case TypedMutationRequest.PermissionChange change -> permissionFlow(change);
                case TypedMutationRequest.RuleChange change -> ruleFlow(change);
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
    // Subland / permission / rule flows
    //
    // Every flow enforces the same five safety fields before touching the
    // ledger or a service, in the same order the claim-family flows use:
    // live snapshot, claimed owner against the durable owner, actor as
    // owner-or-steward, the shared management gate, then the expected
    // version. Anything failing there rejects with no ledger row, no
    // domain write and no event. Only then is the ledger row created and
    // the production service entered; service outcomes settle the row to
    // ACTIVE (or DOMAIN_COMMITTED for recovery when the publish lags,
    // FAILED when nothing committed).
    // ------------------------------------------------------------------

    private CompletionStage<TypedMutationResult> sublandCreateFlow(
            TypedMutationRequest.SublandCreate request) {
        UUID operationId = request.operationId();
        UUID actor = request.actorUuid();
        LandId parentId = request.parentLandId();
        LandSnapshot parent;
        try {
            parent = liveLand(parentId);
        } catch (SnapshotUnavailableException unavailable) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId));
        }
        if (parent == null) {
            return completed(TypedMutationResult.rejected("subland.unknown_land", operationId));
        }
        if (!request.worldId().equals(parent.worldId())) {
            return completed(TypedMutationResult.rejected("subland.world_mismatch", operationId));
        }
        if (!claimedOwnerMatches(request.targetOwner(), parent)) {
            return completed(TypedMutationResult.rejected("subland.owner_mismatch", operationId));
        }
        if (sagaOwnerFor(parent, actor) == null) {
            return completed(TypedMutationResult.rejected("subland.owner_mismatch", operationId));
        }
        if (!gateAllows(actor, parentId, ProtectionActionType.MANAGE_SUBLAND)) {
            return completed(TypedMutationResult.rejected("subland.not_allowed", operationId));
        }
        String stale = checkExpected(request.expectedVersion(), parent);
        if (stale != null) {
            return completed(TypedMutationResult.rejected(stale, operationId));
        }
        if (request.cuboid() == null) {
            return completed(TypedMutationResult.rejected(
                    "subland.legacy-geometry-unsupported", operationId));
        }
        if (sublands == null) {
            return completed(TypedMutationResult.rejected(
                    MutationServiceAdapter.UNSUPPORTED_DIAGNOSTIC, operationId));
        }
        SubLandSnapshot candidate;
        try {
            candidate = new SubLandSnapshot(
                    new SubLandId(operationId), parentId, request.subLandName(),
                    request.cuboid(), request.worldId());
        } catch (RuntimeException invalid) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId));
        }
        long structure = request.expectedVersion().requireStructureRevision();
        CompletionStage<Void> inserted = insertLedgerRow(
                operationId, request.kind().name(), actor, request.worldId(), parentId,
                sublandCreatePayload(request));
        if (inserted == null) {
            return convergeOnExisting(request, operationId);
        }
        return inserted.toCompletableFuture().thenCompose(ignored -> {
            CompletionStage<SubLandSnapshot> stage;
            try {
                stage = sublands.create(actor, parent, candidate, structure);
            } catch (Throwable failure) {
                return settleSublandFailure(operationId, parentId, failure);
            }
            if (stage == null) {
                return settleLedgerTerminal(operationId, parentId,
                        TypedMutationResult.failedBeforeSubmit("subland.failed", operationId));
            }
            return stage.thenCompose(created -> settleCommittedToActive(
                            operationId, parentId, "subland.publish_failed"))
                    .exceptionallyCompose(failure ->
                            settleSublandFailure(operationId, parentId, failure));
        }).exceptionallyCompose(failure -> convergeOnExisting(request, operationId));
    }

    private CompletionStage<TypedMutationResult> sublandDeleteFlow(
            TypedMutationRequest.SublandDelete request) {
        UUID operationId = request.operationId();
        UUID actor = request.actorUuid();
        LandId parentId = request.parentLandId();
        LandSnapshot parent;
        try {
            parent = liveLand(parentId);
        } catch (SnapshotUnavailableException unavailable) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId));
        }
        if (parent == null) {
            return completed(TypedMutationResult.rejected("subland.unknown_land", operationId));
        }
        if (!claimedOwnerMatches(request.targetOwner(), parent)) {
            return completed(TypedMutationResult.rejected("subland.owner_mismatch", operationId));
        }
        if (sagaOwnerFor(parent, actor) == null) {
            return completed(TypedMutationResult.rejected("subland.owner_mismatch", operationId));
        }
        if (!gateAllows(actor, parentId, ProtectionActionType.MANAGE_SUBLAND)) {
            return completed(TypedMutationResult.rejected("subland.not_allowed", operationId));
        }
        String stale = checkExpected(request.expectedVersion(), parent);
        if (stale != null) {
            return completed(TypedMutationResult.rejected(stale, operationId));
        }
        if (findSubland(parent, request.subLandId()) == null) {
            return completed(TypedMutationResult.rejected("subland.unknown_subland", operationId));
        }
        if (sublands == null) {
            return completed(TypedMutationResult.rejected(
                    MutationServiceAdapter.UNSUPPORTED_DIAGNOSTIC, operationId));
        }
        long structure = request.expectedVersion().requireStructureRevision();
        CompletionStage<Void> inserted = insertLedgerRow(
                operationId, request.kind().name(), actor, parent.worldId(), parentId,
                sublandDeletePayload(request));
        if (inserted == null) {
            return convergeOnExisting(request, operationId);
        }
        return inserted.toCompletableFuture().thenCompose(ignored -> {
            CompletionStage<Void> stage;
            try {
                stage = sublands.delete(actor, parent, request.subLandId(), structure);
            } catch (Throwable failure) {
                return settleSublandFailure(operationId, parentId, failure);
            }
            if (stage == null) {
                return settleLedgerTerminal(operationId, parentId,
                        TypedMutationResult.failedBeforeSubmit("subland.failed", operationId));
            }
            return stage.thenCompose(done -> settleCommittedToActive(
                            operationId, parentId, "subland.publish_failed"))
                    .exceptionallyCompose(failure ->
                            settleSublandFailure(operationId, parentId, failure));
        }).exceptionallyCompose(failure -> convergeOnExisting(request, operationId));
    }

    private CompletionStage<TypedMutationResult> permissionFlow(
            TypedMutationRequest.PermissionChange request) {
        UUID operationId = request.operationId();
        UUID actor = request.actorUuid();
        String scopeReject = request.landId() == null
                ? permissionScopeReject(request) : null;
        if (scopeReject != null) {
            return completed(TypedMutationResult.rejected(scopeReject, operationId));
        }
        String subjectReject = permissionSubjectReject(request);
        if (subjectReject != null) {
            return completed(TypedMutationResult.rejected(subjectReject, operationId));
        }
        LandSnapshot scopeParent;
        try {
            scopeParent = resolvePermissionScope(request);
        } catch (SnapshotUnavailableException unavailable) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId));
        }
        if (scopeParent == null) {
            String key = request.landId() == null
                    ? "permission.unknown_subland" : "permission.unknown_land";
            return completed(TypedMutationResult.rejected(key, operationId));
        }
        LandId scopeId = scopeParent.id();
        if (request.changeKind() == PermissionChangedEvent.ChangeKind.DEFAULT_SET
                && !DirectTrustWhitelist.isAllowed(request.action())) {
            return completed(TypedMutationResult.rejected("permission.unknown_action", operationId));
        }
        if (ManagementPermissionGate.isServerLand(scopeParent)) {
            return completed(TypedMutationResult.rejected(
                    "permission.server-unsupported", operationId));
        }
        if (!claimedOwnerMatches(request.targetOwner(), scopeParent)) {
            return completed(TypedMutationResult.rejected("permission.owner_mismatch", operationId));
        }
        if (sagaOwnerFor(scopeParent, actor) == null) {
            return completed(TypedMutationResult.rejected("permission.owner_mismatch", operationId));
        }
        if (!gateAllows(actor, scopeId, permissionGateAction(request))) {
            return completed(TypedMutationResult.rejected("permission.not_allowed", operationId));
        }
        String stale = checkPolicyRevision(request.expectedVersion(), scopeId, scopeParent);
        if (stale != null) {
            return completed(TypedMutationResult.rejected(stale, operationId));
        }
        if (permissions == null) {
            return completed(TypedMutationResult.rejected(
                    MutationServiceAdapter.UNSUPPORTED_DIAGNOSTIC, operationId));
        }
        long policy = request.expectedVersion().requireLandPolicyRevision();
        CompletionStage<Void> inserted = insertLedgerRow(
                operationId, request.kind().name(), actor, scopeParent.worldId(), scopeId,
                permissionPayload(request));
        if (inserted == null) {
            return convergeOnExisting(request, operationId);
        }
        return inserted.toCompletableFuture().thenCompose(ignored -> {
            CompletionStage<Void> stage;
            try {
                stage = permissions.apply(request, scopeParent);
            } catch (Throwable failure) {
                return settleLedgerTerminal(operationId, scopeId,
                        mapPermissionFailure(failure, operationId, policy));
            }
            if (stage == null) {
                return settleLedgerTerminal(operationId, scopeId,
                        TypedMutationResult.failedBeforeSubmit("permission.failed", operationId));
            }
            return stage.thenCompose(done -> settleCommittedToActive(
                            operationId, scopeId, "permission.publish_failed"))
                    .exceptionallyCompose(failure -> settleLedgerTerminal(operationId, scopeId,
                            mapPermissionFailure(failure, operationId, policy)));
        }).exceptionallyCompose(failure -> convergeOnExisting(request, operationId));
    }

    private CompletionStage<TypedMutationResult> ruleFlow(TypedMutationRequest.RuleChange request) {
        UUID operationId = request.operationId();
        UUID actor = request.actorUuid();
        LandSnapshot target;
        try {
            target = liveLand(request.landId());
        } catch (SnapshotUnavailableException unavailable) {
            return completed(TypedMutationResult.failedBeforeSubmit("mutation.failed", operationId));
        }
        if (target == null) {
            return completed(TypedMutationResult.rejected("rule.unknown_land", operationId));
        }
        if (ManagementPermissionGate.isServerLand(target)) {
            return completed(TypedMutationResult.rejected("rule.server-unsupported", operationId));
        }
        if (!claimedOwnerMatches(request.targetOwner(), target)) {
            return completed(TypedMutationResult.rejected("rule.owner_mismatch", operationId));
        }
        if (sagaOwnerFor(target, actor) == null) {
            return completed(TypedMutationResult.rejected("rule.owner_mismatch", operationId));
        }
        if (!gateAllows(actor, request.landId(), ProtectionActionType.MANAGE_PERMISSION)) {
            return completed(TypedMutationResult.rejected("rule.not_allowed", operationId));
        }
        String stale = checkPolicyRevision(request.expectedVersion(), request.landId(), target);
        if (stale != null) {
            return completed(TypedMutationResult.rejected(stale, operationId));
        }
        if (rules == null) {
            return completed(TypedMutationResult.rejected(
                    MutationServiceAdapter.UNSUPPORTED_DIAGNOSTIC, operationId));
        }
        long policy = request.expectedVersion().requireLandPolicyRevision();
        CompletionStage<Void> inserted = insertLedgerRow(
                operationId, request.kind().name(), actor, target.worldId(), request.landId(),
                rulePayload(request));
        if (inserted == null) {
            return convergeOnExisting(request, operationId);
        }
        return inserted.toCompletableFuture().thenCompose(ignored -> {
            CompletionStage<Void> stage;
            try {
                stage = rules.apply(actor, request.landId(), request.rule(), request.state(), policy);
            } catch (Throwable failure) {
                return settleLedgerTerminal(operationId, request.landId(),
                        mapRuleFailure(failure, operationId));
            }
            if (stage == null) {
                return settleLedgerTerminal(operationId, request.landId(),
                        TypedMutationResult.failedBeforeSubmit("rule.failed", operationId));
            }
            return stage.thenCompose(done -> settleCommittedToActive(
                            operationId, request.landId(), "rule.publish_failed"))
                    .exceptionallyCompose(failure -> settleLedgerTerminal(operationId, request.landId(),
                            mapRuleFailure(failure, operationId)));
        }).exceptionallyCompose(failure -> convergeOnExisting(request, operationId));
    }

    // ------------------------------------------------------------------
    // Shared guards and ledger helpers for the new flows
    // ------------------------------------------------------------------

    private LandSnapshot resolvePermissionScope(TypedMutationRequest.PermissionChange request)
            throws SnapshotUnavailableException {
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
            if (request.landId() != null) {
                return snapshot.land(request.landId());
            }
            SubLandId sub = request.subLandId();
            if (sub == null) {
                return null;
            }
            for (LandSnapshot land : snapshot.lands().values()) {
                for (SubLandSnapshot existing : land.subLands()) {
                    if (existing.id().equals(sub)) {
                        return land;
                    }
                }
            }
            return null;
        } catch (RuntimeException failure) {
            throw new SnapshotUnavailableException(failure);
        }
    }

    /**
     * Scope rejections for permission kinds that cannot address a
     * subland. Returns the diagnostic key, or {@code null} when the
     * scope is usable.
     */
    private static String permissionScopeReject(TypedMutationRequest.PermissionChange request) {
        if (request.landId() != null) {
            return null;
        }
        return switch (request.changeKind()) {
            case BIND, UNBIND -> null;
            case TRUST, UNTRUST, BAN, UNBAN, DEFAULT_SET -> "permission.subland-unsupported";
        };
    }

    private static String permissionSubjectReject(TypedMutationRequest.PermissionChange request) {
        return switch (request.changeKind()) {
            case TRUST, UNTRUST, BAN, UNBAN ->
                request.subjectKind() == PermissionChangedEvent.SubjectKind.PLAYER
                        && request.subjectId() != null
                        ? null : "permission.unsupported-subject";
            case DEFAULT_SET, BIND, UNBIND -> null;
        };
    }

    private static ProtectionActionType permissionGateAction(
            TypedMutationRequest.PermissionChange request) {
        return switch (request.changeKind()) {
            case TRUST, UNTRUST, BAN, UNBAN -> ProtectionActionType.MANAGE_MEMBER;
            case DEFAULT_SET, BIND, UNBIND -> ProtectionActionType.MANAGE_PERMISSION;
        };
    }

    private static SubLandSnapshot findSubland(LandSnapshot parent, SubLandId id) {
        for (SubLandSnapshot sub : parent.subLands()) {
            if (sub.id().equals(id)) {
                return sub;
            }
        }
        return null;
    }

    private CompletionStage<Void> insertLedgerRow(
            UUID operationId, String operationType, UUID actor, UUID world, LandId target,
            String payloadJson) {
        java.time.Instant now = clock.instant();
        LedgerEntry entry = new LedgerEntry(operationId, operationType,
                LedgerState.CREATED.name(), actor, world, target, 0L, RENAME_PROVIDER_ID, null,
                payloadJson, 1, now, now);
        try {
            return ledger.insert(entry);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private CompletionStage<TypedMutationResult> settleCommittedToActive(
            UUID operationId, LandId landId, String publishFailedKey) {
        CompletionStage<Void> committed;
        try {
            committed = ledger.compareAndSetState(operationId,
                    LedgerState.CREATED, LedgerState.DOMAIN_COMMITTED, clock.instant());
        } catch (RuntimeException failure) {
            return convergeOnExisting(operationId,
                    TypedMutationResult.failedAfterCommit("mutation.failed", operationId));
        }
        if (committed == null) {
            return completed(TypedMutationResult.failedAfterCommit("mutation.failed", operationId));
        }
        return committed.toCompletableFuture().thenCompose(ignored -> {
            CompletionStage<Void> activated;
            try {
                activated = ledger.compareAndSetState(operationId,
                        LedgerState.DOMAIN_COMMITTED, LedgerState.ACTIVE, clock.instant());
            } catch (RuntimeException failure) {
                return completed(TypedMutationResult.failedAfterCommit("mutation.failed", operationId));
            }
            if (activated == null) {
                return completed(TypedMutationResult.failedAfterCommit("mutation.failed", operationId));
            }
            return activated.toCompletableFuture()
                    .thenApply(done -> TypedMutationResult.success(landId, operationId))
                    .exceptionally(failure ->
                            TypedMutationResult.failedAfterCommit("mutation.failed", operationId));
        }).exceptionallyCompose(failure -> convergeOnExisting(operationId,
                TypedMutationResult.failedAfterCommit(publishFailedKey, operationId)));
    }

    private CompletionStage<TypedMutationResult> settleCommittedUnpublished(
            UUID operationId, String publishFailedKey) {
        CompletionStage<Void> committed;
        try {
            committed = ledger.compareAndSetState(operationId,
                    LedgerState.CREATED, LedgerState.DOMAIN_COMMITTED, clock.instant());
        } catch (RuntimeException failure) {
            return convergeOnExisting(operationId,
                    TypedMutationResult.failedAfterCommit(publishFailedKey, operationId));
        }
        if (committed == null) {
            return completed(TypedMutationResult.failedAfterCommit(publishFailedKey, operationId));
        }
        TypedMutationResult degraded =
                TypedMutationResult.failedAfterCommit(publishFailedKey, operationId);
        return committed.toCompletableFuture()
                .thenApply(ignored -> degraded)
                .exceptionallyCompose(failure -> convergeOnExisting(operationId, degraded));
    }

    private CompletionStage<TypedMutationResult> settleLedgerTerminal(
            UUID operationId, LandId landId, TypedMutationResult result) {
        CompletionStage<Void> marked;
        try {
            marked = ledger.compareAndSetState(operationId,
                    LedgerState.CREATED, LedgerState.FAILED, clock.instant());
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

    private CompletionStage<TypedMutationResult> settleSublandFailure(
            UUID operationId, LandId parentId, Throwable failure) {
        if (isPublishPending(failure)) {
            return settleCommittedUnpublished(operationId, "subland.publish_failed");
        }
        return settleLedgerTerminal(operationId, parentId, mapSublandFailure(failure, operationId));
    }

    private static TypedMutationResult mapSublandFailure(Throwable failure, UUID operationId) {
        Throwable root = rootCause(failure);
        if (root instanceof PublicEventCancelledException) {
            return TypedMutationResult.rejected("subland.pre-cancelled", operationId);
        }
        if (root instanceof DepthExtendConfirmationRequired) {
            return TypedMutationResult.rejected("subland.confirm_depth", operationId);
        }
        if (root instanceof IllegalArgumentException) {
            return TypedMutationResult.rejected("subland.invalid", operationId);
        }
        if (root instanceof IllegalStateException illegal) {
            String message = illegal.getMessage();
            if (message != null && message.contains("stale")) {
                return TypedMutationResult.rejected(
                        TypedMutationResult.STALE_EXPECTED_VERSION, operationId);
            }
            return TypedMutationResult.rejected("subland.conflict", operationId);
        }
        return TypedMutationResult.failedBeforeSubmit("subland.failed", operationId);
    }

    private static TypedMutationResult mapPermissionFailure(
            Throwable failure, UUID operationId, long policy) {
        Throwable root = rootCause(failure);
        if (root instanceof StaleAuthorisationException
                || root instanceof LandRuleConflictException
                || root instanceof LandDefaultConflictException) {
            return TypedMutationResult.rejected(
                    TypedMutationResult.STALE_EXPECTED_VERSION, operationId);
        }
        if (root instanceof BindingRejectedException
                || root instanceof IllegalArgumentException) {
            return TypedMutationResult.rejected("permission.invalid", operationId);
        }
        if (root instanceof IllegalStateException) {
            return TypedMutationResult.rejected("permission.conflict", operationId);
        }
        return TypedMutationResult.failedBeforeSubmit("permission.failed", operationId);
    }

    private static TypedMutationResult mapRuleFailure(Throwable failure, UUID operationId) {
        Throwable root = rootCause(failure);
        if (root instanceof StaleAuthorisationException
                || root instanceof LandRuleConflictException) {
            return TypedMutationResult.rejected(
                    TypedMutationResult.STALE_EXPECTED_VERSION, operationId);
        }
        if (root instanceof IllegalArgumentException) {
            return TypedMutationResult.rejected("rule.invalid", operationId);
        }
        if (root instanceof IllegalStateException) {
            return TypedMutationResult.rejected("rule.conflict", operationId);
        }
        return TypedMutationResult.failedBeforeSubmit("rule.failed", operationId);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current instanceof java.util.concurrent.CompletionException completed
                && completed.getCause() != null
                && completed.getCause() != current) {
            current = completed.getCause();
        }
        return current == null ? failure : current;
    }

    private static boolean isPublishPending(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SubLandMutationRunner.RuntimeRebuildPendingException) {
                return true;
            }
            Throwable next;
            if (current instanceof java.util.concurrent.CompletionException completed) {
                next = completed.getCause();
            } else {
                next = current.getCause();
            }
            if (next == current) {
                return false;
            }
            current = next;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Canonical ledger payloads for the new flows
    //
    // The claim-family payloads reuse OperationPayload; the new flows
    // carry no price and no chunk cost basis, so they store a small
    // canonical JSON document instead. Resubmission compares the stored
    // document for exact equality together with the kind and the actor,
    // so no parser is needed and a different id with the same content
    // stays an independent operation.
    // ------------------------------------------------------------------

    private static String canonicalPayload(TypedMutationRequest request) {
        return switch (request) {
            case TypedMutationRequest.SublandCreate create -> sublandCreatePayload(create);
            case TypedMutationRequest.SublandDelete delete -> sublandDeletePayload(delete);
            case TypedMutationRequest.PermissionChange change -> permissionPayload(change);
            case TypedMutationRequest.RuleChange change -> rulePayload(change);
            default -> "";
        };
    }

    private static String sublandCreatePayload(TypedMutationRequest.SublandCreate request) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"v\":1,\"parent\":\"").append(request.parentLandId().value()).append("\",\"name\":\"")
                .append(escapeJson(request.subLandName())).append("\",\"chunks\":[");
        java.util.List<String> tokens = new java.util.ArrayList<>(request.chunks().size());
        for (ChunkKey chunk : request.chunks()) {
            tokens.add(chunk.worldId() + ":" + chunk.chunkX() + ":" + chunk.chunkZ());
        }
        tokens.sort(java.util.Comparator.naturalOrder());
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(tokens.get(i)).append('"');
        }
        out.append("],\"cuboid\":");
        Cuboid cuboid = request.cuboid();
        if (cuboid == null) {
            out.append("null");
        } else {
            out.append('[').append(cuboid.minX()).append(',').append(cuboid.minY()).append(',')
                    .append(cuboid.minZ()).append(',').append(cuboid.maxX()).append(',')
                    .append(cuboid.maxY()).append(',').append(cuboid.maxZ()).append(']');
        }
        out.append('}');
        return out.toString();
    }

    private static String sublandDeletePayload(TypedMutationRequest.SublandDelete request) {
        return "{\"v\":1,\"parent\":\"" + request.parentLandId().value()
                + "\",\"subland\":\"" + request.subLandId().value() + "\"}";
    }

    private static String permissionPayload(TypedMutationRequest.PermissionChange request) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"v\":1,\"land\":");
        if (request.landId() == null) {
            out.append("null");
        } else {
            out.append('"').append(request.landId().value()).append('"');
        }
        out.append(",\"subland\":");
        if (request.subLandId() == null) {
            out.append("null");
        } else {
            out.append('"').append(request.subLandId().value()).append('"');
        }
        out.append(",\"kind\":\"").append(request.changeKind().name()).append("\",\"subjectKind\":");
        if (request.subjectKind() == null) {
            out.append("null");
        } else {
            out.append('"').append(request.subjectKind().name()).append('"');
        }
        out.append(",\"subject\":");
        if (request.subjectId() == null) {
            out.append("null");
        } else {
            out.append('"').append(request.subjectId()).append('"');
        }
        out.append(",\"action\":");
        if (request.action() == null) {
            out.append("null");
        } else {
            out.append('"').append(request.action().name()).append('"');
        }
        out.append(",\"state\":");
        if (request.state() == null) {
            out.append("null");
        } else {
            out.append('"').append(request.state().name()).append('"');
        }
        out.append(",\"profile\":");
        if (request.profileId() == null) {
            out.append("null");
        } else {
            out.append('"').append(request.profileId()).append('"');
        }
        out.append('}');
        return out.toString();
    }

    private static String rulePayload(TypedMutationRequest.RuleChange request) {
        return "{\"v\":1,\"land\":\"" + request.landId().value()
                + "\",\"rule\":\"" + request.rule().name()
                + "\",\"state\":\"" + request.state().name() + "\"}";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
        if (isLedgerJsonKind(request.kind())) {
            if (!request.kind().name().equals(entry.operationType())) {
                return false;
            }
            if (!request.actorUuid().equals(entry.actor())) {
                return false;
            }
            return entry.payloadJson().equals(canonicalPayload(request));
        }
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

    private static boolean isLedgerJsonKind(MutationKind kind) {
        return kind == MutationKind.SUBLAND_CREATE
                || kind == MutationKind.SUBLAND_DELETE
                || kind == MutationKind.PERMISSION_CHANGE
                || kind == MutationKind.RULE_CHANGE;
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
            case "SUBLAND_CREATE", "SUBLAND_DELETE" -> "subland.reconciliation";
            case "PERMISSION_CHANGE" -> "permission.reconciliation";
            case "RULE_CHANGE" -> "rule.reconciliation";
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
        LandId check;
        if (request.kind() == MutationKind.LAND_CREATE) {
            check = entry.targetLandId();
        } else {
            check = request.landId();
            if (check == null
                    && request instanceof TypedMutationRequest.PermissionChange change
                    && change.subLandId() != null) {
                check = parentOfSubland(change.subLandId());
            }
        }
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

    private LandId parentOfSubland(SubLandId sublandId) {
        LandRegistry snapshot;
        try {
            snapshot = snapshots.get();
        } catch (RuntimeException failure) {
            return null;
        }
        if (snapshot == null || sublandId == null) {
            return null;
        }
        try {
            for (LandSnapshot land : snapshot.lands().values()) {
                for (SubLandSnapshot existing : land.subLands()) {
                    if (existing.id().equals(sublandId)) {
                        return land.id();
                    }
                }
            }
        } catch (RuntimeException failure) {
            return null;
        }
        return null;
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
     * Compare the policy expectation against the live authorisation
     * generation. The structural snapshot freezes its policy revision
     * once the land exists — permission and rule commits only republish
     * the authorisation snapshot — so the durable generation is read
     * from there when available, falling back to the structural target.
     * A land absent from a loaded authorisation snapshot pins zero, the
     * durable value of a land no authorisation write has touched yet;
     * the conditional transaction stays the authority either way.
     * Returns the rejection key, or {@code null} when the carried
     * policy dimension agrees.
     */
    private String checkPolicyRevision(
            ExpectedVersion expected, LandId landId, LandSnapshot fallback) {
        Long wanted;
        try {
            wanted = expected.requireLandPolicyRevision();
        } catch (RuntimeException missing) {
            return "mutation.missing-expected-version";
        }
        Long live = null;
        if (policySnapshots != null) {
            try {
                LandAuthorisationSnapshot snapshot = policySnapshots.get();
                if (snapshot != null && snapshot.loaded()) {
                    live = snapshot.policyRevision(landId).orElse(0L);
                }
            } catch (RuntimeException failure) {
                live = null;
            }
        }
        if (live == null && fallback != null) {
            live = fallback.landPolicyRevision();
        }
        if (live == null) {
            return "mutation.failed";
        }
        return wanted.equals(live) ? null : TypedMutationResult.STALE_EXPECTED_VERSION;
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

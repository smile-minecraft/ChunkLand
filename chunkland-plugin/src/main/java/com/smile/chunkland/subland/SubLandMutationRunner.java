package com.smile.chunkland.subland;

import com.smile.chunkland.api.event.SubLandPostEvent;
import com.smile.chunkland.api.event.SubLandPreEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.event.SubLandPreEvent.Operation;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.event.PublicEventCancelledException;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.event.bukkit.SubLandPostBukkitEvent;
import com.smile.chunkland.event.bukkit.SubLandPreBukkitEvent;
import com.smile.chunkland.persistence.LandRepository;
import com.smile.chunkland.persistence.SubLandAtomicCommit;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.vertical.DepthExtendRequest;
import com.smile.chunkland.runtime.vertical.DepthWriteResult;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.subland.SubLandConfirmService.Accepted;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Durable commit path for SubLand create/update/delete.
 *
 * <p>One runner call corresponds to exactly one accepted confirmation triple
 * ({@code generation + selectionRevision + parent structureRevision}); the
 * Bukkit-facing handler confirms first through {@link SubLandConfirmService}
 * and only then enters here, so a token can never drive two mutations. The
 * runner re-reads the live parent, re-resolves the effective floor, and
 * re-applies the pure {@link SubLandService} check before touching any row:
 * a stale structure revision therefore fails without SQL, economy, or domain
 * side effects (the confirmation mark is released so the operator can retry).
 *
 * <p>Commit order per mutation: SubLand row, parent structure-revision row,
 * audit row, then a single volatile {@link LandRegistry} publish built
 * off-thread from a fresh {@code findAll} (the published value is one
 * immutable snapshot; partial state is never visible). The three durable
 * writes land in one SQLite transaction through
 * {@link SubLandAtomicCommit}: the parent bump is a durable compare-and-set
 * on the live {@code structure_revision}, so two mutations racing on the
 * same revision serialize to exactly one winner. Selection cleanup runs
 * last and best-effort: delete clears every session matching the removed
 * SubLand, and every success additionally releases the actor's own session.
 * No Bukkit, world, or chunk loading happens on this path; the hot read path
 * keeps serving the previous immutable snapshot until the single publish.
 *
 * <p>Publish contract: once the atomic commit succeeds, a publish failure
 * can only degrade. The mutation completes exceptionally with
 * {@link RuntimeRebuildPendingException} (diagnostic key
 * {@code subland.runtime_rebuild_pending}), the confirmation mark stays
 * consumed so the same token cannot redo the durable rows, and the caller
 * repairs the runtime with {@link #rebuildRuntime()}, which re-reads the
 * authoritative repositories and publishes once. The retry never saves,
 * deletes, audits, or bumps the parent again.
 */
public final class SubLandMutationRunner {

    /** Rejection when the confirmation triple does not match the live session. */
    public static final class ConfirmRejectedException extends IllegalStateException {
        ConfirmRejectedException(String message) {
            super(message);
        }
    }

    /**
     * Durable commit succeeded but the runtime publish did not complete.
     * The diagnostic key is stable for message lookup; retry with
     * {@link #rebuildRuntime()} instead of resubmitting the mutation.
     */
    public static final class RuntimeRebuildPendingException extends IllegalStateException {
        /** Stable key for the degraded reply. */
        public static final String DIAGNOSTIC_KEY = "subland.runtime_rebuild_pending";

        RuntimeRebuildPendingException(String message, Throwable cause) {
            super(message, cause);
        }

        /** Stable key for the degraded reply. */
        public String diagnosticKey() {
            return DIAGNOSTIC_KEY;
        }
    }

    /**
     * Rebuilds the immutable runtime from the authoritative repositories
     * and publishes it once. Implementations must not touch child rows,
     * audits, or the parent revision; they only read and publish.
     */
    @FunctionalInterface
    public interface RuntimePublisher {
        /** Rebuild from durable state and publish with a single volatile write. */
        CompletionStage<LandRegistry> rebuildAndPublish();
    }

    /** Result of one successful SubLand depth-extension operation. */
    public record DepthExtensionResult(int chunkCount) {
        public DepthExtensionResult {
            if (chunkCount <= 0) {
                throw new IllegalArgumentException("chunkCount must be positive");
            }
        }
    }

    private final LandRepository lands;
    private final SubLandAtomicCommit atomic;
    private final LandRegistryStore store;
    private final SelectionSessionManager selections;
    private final SubLandConfirmService confirm;
    private final SubLandDepthSource depths;
    private final DepthExtensionPort depthPort;
    private final SubLandDepthExtendPort depthExtender;
    private final SubLandDepthConfirmations depthConfirmations;
    private final LimitSettings limits;
    private final Clock clock;
    private final PublicEvents events;
    private final RuntimePublisher publisher;
    private final AtomicReference<CompletableFuture<LandRegistry>> inflight = new AtomicReference<>();

    // Deterministic hook for the complete-versus-clear window in rebuildRuntime.
    // Invoked inside the publisher completion callback after the joined future
    // completes but before the in-flight slot is cleared, without holding any
    // lock. Production leaves this null; tests use a latch-based hook to force
    // a second caller into the gap deterministically. The second caller must
    // still observe the (already completed) in-flight future and join it, so
    // the gap never starts a second publisher.
    volatile Runnable rebuildCompletionHookForTest;

    public SubLandMutationRunner(
            LandRepository lands,
            SubLandAtomicCommit atomic,
            LandRegistryStore store,
            SelectionSessionManager selections,
            SubLandConfirmService confirm,
            SubLandDepthSource depths,
            DepthExtensionPort depthPort,
            LimitSettings limits,
            Clock clock) {
        this(lands, atomic, store, selections, confirm, depths, depthPort, limits, clock,
                defaultPublisher(lands, store));
    }

    public SubLandMutationRunner(
            LandRepository lands,
            SubLandAtomicCommit atomic,
            LandRegistryStore store,
            SelectionSessionManager selections,
            SubLandConfirmService confirm,
            SubLandDepthSource depths,
            DepthExtensionPort depthPort,
            LimitSettings limits,
            Clock clock,
            RuntimePublisher publisher) {
        this(lands, atomic, store, selections, confirm, depths, depthPort, limits, clock,
                PublicEvents.noop(), publisher);
    }

    /**
     * @param events public Pre/Post dispatch; {@code null} means no public
     *         events (the mutation still commits exactly as before)
     */
    public SubLandMutationRunner(
            LandRepository lands,
            SubLandAtomicCommit atomic,
            LandRegistryStore store,
            SelectionSessionManager selections,
            SubLandConfirmService confirm,
            SubLandDepthSource depths,
            DepthExtensionPort depthPort,
            LimitSettings limits,
            Clock clock,
            PublicEvents events) {
        this(lands, atomic, store, selections, confirm, depths, depthPort, limits, clock,
                events, defaultPublisher(lands, store));
    }

    /**
     * @param events public Pre/Post dispatch; {@code null} means no public
     *         events (the mutation still commits exactly as before)
     */
    public SubLandMutationRunner(
            LandRepository lands,
            SubLandAtomicCommit atomic,
            LandRegistryStore store,
            SelectionSessionManager selections,
            SubLandConfirmService confirm,
            SubLandDepthSource depths,
            DepthExtensionPort depthPort,
            LimitSettings limits,
            Clock clock,
            PublicEvents events,
            RuntimePublisher publisher) {
        this(lands, atomic, store, selections, confirm, depths, depthPort, limits, clock,
                events, publisher, null, null);
    }

    /**
     * Full production constructor with the existing durable depth store seam
     * and the actor-scoped confirmation set shared with the command handler.
     */
    public SubLandMutationRunner(
            LandRepository lands,
            SubLandAtomicCommit atomic,
            LandRegistryStore store,
            SelectionSessionManager selections,
            SubLandConfirmService confirm,
            SubLandDepthSource depths,
            DepthExtensionPort depthPort,
            LimitSettings limits,
            Clock clock,
            PublicEvents events,
            RuntimePublisher publisher,
            SubLandDepthExtendPort depthExtender,
            SubLandDepthConfirmations depthConfirmations) {
        this.lands = Objects.requireNonNull(lands, "lands");
        this.atomic = Objects.requireNonNull(atomic, "atomic");
        this.store = Objects.requireNonNull(store, "store");
        this.selections = Objects.requireNonNull(selections, "selections");
        this.confirm = Objects.requireNonNull(confirm, "confirm");
        this.depths = Objects.requireNonNull(depths, "depths");
        this.depthPort = Objects.requireNonNull(depthPort, "depthPort");
        this.depthExtender = depthExtender;
        this.depthConfirmations = depthConfirmations;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = events == null ? PublicEvents.noop() : events;
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /** Production convenience constructor using the standard runtime publisher. */
    public SubLandMutationRunner(
            LandRepository lands,
            SubLandAtomicCommit atomic,
            LandRegistryStore store,
            SelectionSessionManager selections,
            SubLandConfirmService confirm,
            SubLandDepthSource depths,
            DepthExtensionPort depthPort,
            LimitSettings limits,
            Clock clock,
            PublicEvents events,
            SubLandDepthExtendPort depthExtender,
            SubLandDepthConfirmations depthConfirmations) {
        this(lands, atomic, store, selections, confirm, depths, depthPort, limits, clock,
                events, defaultPublisher(lands, store), depthExtender, depthConfirmations);
    }

    /**
     * Durably create one SubLand.
     *
     * @param actor confirming player
     * @param accepted captured confirmation triple (parent + structure revision)
     * @param candidate precise candidate for the accepted parent
     * @return the persisted candidate
     */
    public CompletionStage<SubLandSnapshot> create(UUID actor, Accepted accepted, SubLandSnapshot candidate) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(candidate, "candidate");
        if (!accepted.parentId().equals(candidate.parentLandId())) {
            confirm.releaseIfNotSuccess(actor, accepted);
            return failed(new ConfirmRejectedException(
                    "candidate parent " + candidate.parentLandId()
                            + " does not match confirmed parent " + accepted.parentId()));
        }
        // Public Pre: synchronous veto after confirm accept but before any
        // durable write. A veto (or any dispatch failure, which fails closed)
        // releases the confirmation for retry and writes nothing.
        if (fireSubLandPre(actor, accepted.parentId(), candidate.id(), Operation.CREATE)) {
            confirm.releaseIfNotSuccess(actor, accepted);
            return failed(new PublicEventCancelledException());
        }
        return lands.findById(accepted.parentId()).thenCompose(parentOpt -> {
            if (parentOpt.isEmpty()) {
                confirm.releaseIfNotSuccess(actor, accepted);
                return this.<SubLandSnapshot>failed(new IllegalStateException(
                        "unknown parent land " + accepted.parentId()));
            }
            LandSnapshot parent = parentOpt.get();
            if (parent.structureRevision() != accepted.structureRevision()) {
                confirm.releaseIfNotSuccess(actor, accepted);
                return this.<SubLandSnapshot>failed(new IllegalStateException(
                        "stale parent structure revision: expected " + accepted.structureRevision()
                                + " but live is " + parent.structureRevision()));
            }
            int effectiveMinY;
            try {
                effectiveMinY = depths.effectiveMinProtectedY(parent);
            } catch (RuntimeException failure) {
                confirm.releaseIfNotSuccess(actor, accepted);
                return this.<SubLandSnapshot>failed(failure);
            }
            LandSnapshot next;
            try {
                next = SubLandService.applyCreate(parent, candidate,
                        parent.subLands().size(), limits.maxSublandsPerLand(),
                        effectiveMinY, actor, depthPort);
            } catch (RuntimeException rejected) {
                confirm.releaseIfNotSuccess(actor, accepted);
                return this.<SubLandSnapshot>failed(rejected);
            }
            boolean depthConfirmed = candidate.cuboid().minY() < effectiveMinY;
            return atomic.commitCreate(candidate, next,
                            accepted.structureRevision(), limits.maxSublandsPerLand(),
                            SubLandAudit.createEntry(
                                    actor, parent, candidate,
                                    accepted.session().sessionGeneration(),
                                    accepted.session().selectionRevision(),
                                    depthConfirmed, effectiveMinY, clock.instant()))
                    .exceptionallyCompose(failure -> {
                        if (depthConfirmed) {
                            depthPort.onDurableFailure(actor, accepted.parentId(), candidate.cuboid().minY());
                        }
                        return this.<Void>failed(failure);
                    })
                    .thenCompose(ignored -> rebuildRuntime()
                            .thenApply(ignoredRegistry -> {
                                // Public Post: exactly once, after the atomic
                                // commit plus the runtime publish. Listener
                                // failures are isolated and never roll back.
                                fireSubLandPost(actor, accepted.parentId(), candidate.id(),
                                        Operation.CREATE);
                                clearActorSession(actor);
                                return candidate;
                            })
                            .<SubLandSnapshot>exceptionallyCompose(publishFailure -> {
                                clearActorSession(actor);
                                return this.<SubLandSnapshot>failed(
                                        pendingFailure(publishFailure));
                            }))
                    .exceptionallyCompose(failure -> {
                        if (isPending(failure)) {
                            return this.<SubLandSnapshot>failed(failure);
                        }
                        confirm.releaseIfNotSuccess(actor, accepted);
                        return this.<SubLandSnapshot>failed(failure);
                    });
        });
    }

    /**
     * Durably replace one SubLand.
     *
     * @param actor confirming player
     * @param accepted captured confirmation triple (parent + structure revision)
     * @param candidate replacement precise snapshot (same id as the stored one)
     * @return the persisted replacement
     */
    public CompletionStage<SubLandSnapshot> update(UUID actor, Accepted accepted, SubLandSnapshot candidate) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(candidate, "candidate");
        if (!accepted.parentId().equals(candidate.parentLandId())) {
            confirm.releaseIfNotSuccess(actor, accepted);
            return failed(new ConfirmRejectedException(
                    "candidate parent " + candidate.parentLandId()
                            + " does not match confirmed parent " + accepted.parentId()));
        }
        // Public Pre: synchronous veto after confirm accept but before any
        // durable write. A veto (or any dispatch failure, which fails closed)
        // releases the confirmation for retry and writes nothing.
        if (fireSubLandPre(actor, accepted.parentId(), candidate.id(), Operation.UPDATE)) {
            confirm.releaseIfNotSuccess(actor, accepted);
            return failed(new PublicEventCancelledException());
        }
        return lands.findById(accepted.parentId()).thenCompose(parentOpt -> {
            if (parentOpt.isEmpty()) {
                confirm.releaseIfNotSuccess(actor, accepted);
                return this.<SubLandSnapshot>failed(new IllegalStateException(
                        "unknown parent land " + accepted.parentId()));
            }
            LandSnapshot parent = parentOpt.get();
            SubLandSnapshot stored = findById(parent, candidate.id());
            if (stored == null) {
                confirm.releaseIfNotSuccess(actor, accepted);
                return this.<SubLandSnapshot>failed(new IllegalStateException(
                        "unknown SubLand id: " + candidate.id()));
            }
            int effectiveMinY;
            try {
                effectiveMinY = depths.effectiveMinProtectedY(parent);
            } catch (RuntimeException failure) {
                confirm.releaseIfNotSuccess(actor, accepted);
                return this.<SubLandSnapshot>failed(failure);
            }
            LandSnapshot next;
            try {
                next = SubLandService.applyUpdate(parent, candidate,
                        accepted.structureRevision(), effectiveMinY, actor, depthPort);
            } catch (RuntimeException rejected) {
                confirm.releaseIfNotSuccess(actor, accepted);
                return this.<SubLandSnapshot>failed(rejected);
            }
            boolean depthConfirmed = candidate.cuboid().minY() < effectiveMinY;
            return atomic.commitUpdate(candidate, next,
                            accepted.structureRevision(),
                            SubLandAudit.updateEntry(
                                    actor, parent, stored, candidate,
                                    accepted.session().sessionGeneration(),
                                    accepted.session().selectionRevision(),
                                    depthConfirmed, effectiveMinY, clock.instant()))
                    .exceptionallyCompose(failure -> {
                        if (depthConfirmed) {
                            depthPort.onDurableFailure(actor, accepted.parentId(), candidate.cuboid().minY());
                        }
                        return this.<Void>failed(failure);
                    })
                    .thenCompose(ignored -> rebuildRuntime()
                            .thenApply(ignoredRegistry -> {
                                // Public Post: exactly once, after the atomic
                                // commit plus the runtime publish. Listener
                                // failures are isolated and never roll back.
                                fireSubLandPost(actor, accepted.parentId(), candidate.id(),
                                        Operation.UPDATE);
                                clearActorSession(actor);
                                return candidate;
                            })
                            .<SubLandSnapshot>exceptionallyCompose(publishFailure -> {
                                clearActorSession(actor);
                                return this.<SubLandSnapshot>failed(
                                        pendingFailure(publishFailure));
                            }))
                    .exceptionallyCompose(failure -> {
                        if (isPending(failure)) {
                            return this.<SubLandSnapshot>failed(failure);
                        }
                        confirm.releaseIfNotSuccess(actor, accepted);
                        return this.<SubLandSnapshot>failed(failure);
                    });
        });
    }

    /**
     * Durably delete one SubLand and clear every selection matching it.
     *
     * @param actor confirming player
     * @param accepted captured confirmation triple (parent + structure revision)
     * @param target SubLand to remove
     */
    public CompletionStage<Void> delete(UUID actor, Accepted accepted, SubLandId target) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(target, "target");
        // Public Pre: synchronous veto after confirm accept but before any
        // durable write. A veto (or any dispatch failure, which fails closed)
        // releases the confirmation for retry and writes nothing.
        if (fireSubLandPre(actor, accepted.parentId(), target, Operation.DELETE)) {
            confirm.releaseIfNotSuccess(actor, accepted);
            return failed(new PublicEventCancelledException());
        }
        return lands.findById(accepted.parentId()).thenCompose(parentOpt -> {
            if (parentOpt.isEmpty()) {
                confirm.releaseIfNotSuccess(actor, accepted);
                return this.<Void>failed(new IllegalStateException(
                        "unknown parent land " + accepted.parentId()));
            }
            LandSnapshot parent = parentOpt.get();
            SubLandSnapshot stored = findById(parent, target);
            if (stored == null) {
                confirm.releaseIfNotSuccess(actor, accepted);
                return this.<Void>failed(new IllegalStateException("unknown SubLand id: " + target));
            }
            LandSnapshot next;
            try {
                next = SubLandService.applyDelete(parent, target, accepted.structureRevision());
            } catch (RuntimeException rejected) {
                confirm.releaseIfNotSuccess(actor, accepted);
                return this.<Void>failed(rejected);
            }
            return atomic.commitDelete(target, next,
                            accepted.structureRevision(),
                            SubLandAudit.deleteEntry(
                                    actor, parent, stored,
                                    accepted.session().sessionGeneration(),
                                    accepted.session().selectionRevision(), clock.instant()))
                    .thenCompose(ignored -> rebuildRuntime()
                            .thenApply(ignoredRegistry -> {
                                // Public Post: exactly once, after the atomic
                                // commit plus the runtime publish. Listener
                                // failures are isolated and never roll back.
                                fireSubLandPost(actor, accepted.parentId(), target,
                                        Operation.DELETE);
                                clearSubLandSelections(target);
                                clearActorSession(actor);
                                return (Void) null;
                            })
                            .<Void>exceptionallyCompose(publishFailure -> {
                                clearSubLandSelections(target);
                                clearActorSession(actor);
                                return this.<Void>failed(
                                        pendingFailure(publishFailure));
                            }))
                    .exceptionallyCompose(failure -> {
                        if (isPending(failure)) {
                            return this.<Void>failed(failure);
                        }
                        confirm.releaseIfNotSuccess(actor, accepted);
                        return this.<Void>failed(failure);
                    });
        });
    }

    /**
     * Durably extend the parent's protected depth for every chunk covered by
     * the live SubLand selection. All durable writes use the existing depth
     * store seam; the confirmation is recorded only after every result is a
     * successful applied or already-deep-enough outcome.
     */
    public CompletionStage<DepthExtensionResult> extend(UUID actor, Accepted accepted) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(accepted, "accepted");
        Cuboid cuboid = cuboidOf(accepted.session());
        if (cuboid == null) {
            return failed(new IllegalStateException("SubLand selection has no complete cuboid"));
        }
        if (depthExtender == null || depthConfirmations == null) {
            return failed(new IllegalStateException("SubLand depth extension is unavailable"));
        }

        final CompletionStage<Optional<LandSnapshot>> parentStage;
        try {
            parentStage = lands.findById(accepted.parentId());
        } catch (RuntimeException failure) {
            return failed(failure);
        }
        if (parentStage == null) {
            return failed(new IllegalStateException("land repository returned null"));
        }

        CompletionStage<DepthExtensionResult> work;
        try {
            work = parentStage.thenCompose(parentOpt -> {
                if (parentOpt == null || parentOpt.isEmpty()) {
                    return this.<DepthExtensionResult>failed(
                            new IllegalStateException("unknown parent land " + accepted.parentId()));
                }
                LandSnapshot parent = parentOpt.get();
                if (parent.structureRevision() != accepted.structureRevision()) {
                    return this.<DepthExtensionResult>failed(new IllegalStateException(
                            "stale parent structure revision: expected " + accepted.structureRevision()
                                    + " but live is " + parent.structureRevision()));
                }
                List<ChunkKey> covered;
                try {
                    covered = new ArrayList<>(cuboid.coveredChunks(accepted.session().worldId()));
                } catch (RuntimeException invalid) {
                    return this.<DepthExtensionResult>failed(invalid);
                }
                if (covered.isEmpty() || !parent.chunks().containsAll(covered)) {
                    return this.<DepthExtensionResult>failed(new IllegalStateException(
                            "SubLand selection is outside the parent land"));
                }

                List<CompletionStage<DepthWriteResult>> writes = new ArrayList<>(covered.size());
                for (ChunkKey chunk : covered) {
                    DepthExtendRequest request = new DepthExtendRequest(
                            chunk, accepted.parentId(), actor, cuboid.minY(), cuboid.minY());
                    final CompletionStage<DepthWriteResult> write;
                    try {
                        write = depthExtender.extend(request);
                    } catch (RuntimeException failure) {
                        return this.<DepthExtensionResult>failed(failure);
                    }
                    if (write == null) {
                        return this.<DepthExtensionResult>failed(
                                new IllegalStateException("depth store returned null"));
                    }
                    writes.add(write.thenApply(result -> validateDepthResult(result, request)));
                }
                CompletableFuture<Void> all = CompletableFuture.allOf(
                        writes.stream().map(CompletionStage::toCompletableFuture)
                                .toArray(CompletableFuture[]::new));
                return all.thenApply(ignored -> {
                    if (!depthConfirmations.record(
                            actor, accepted.session(), accepted.parentId(), cuboid.minY())) {
                        throw new IllegalStateException("SubLand depth confirmation could not be recorded");
                    }
                    return new DepthExtensionResult(covered.size());
                });
            });
        } catch (RuntimeException failure) {
            work = failed(failure);
        }
        return work.whenComplete((ignored, failure) ->
                confirm.releaseIfNotSuccess(actor, accepted));
    }

    private static DepthWriteResult validateDepthResult(
            DepthWriteResult result, DepthExtendRequest request) {
        if (result == null || !request.chunk().equals(result.chunk())
                || !request.landId().equals(result.landId())) {
            throw new IllegalStateException("depth store returned an invalid result");
        }
        if (result.applied()) {
            return result;
        }
        if (result.afterStored() <= request.requestedDepth()) {
            return result;
        }
        throw new IllegalStateException("depth extension did not reach the requested depth");
    }

    static Cuboid cuboidOf(SelectionSession session) {
        Optional<SelectionPoint> first = session.pointA();
        Optional<SelectionPoint> second = session.pointB();
        if (first.isEmpty() || second.isEmpty()) {
            return null;
        }
        try {
            SelectionPoint a = first.get();
            SelectionPoint b = second.get();
            return new Cuboid(
                    Math.min(a.blockX(), b.blockX()),
                    Math.min(a.blockY(), b.blockY()),
                    Math.min(a.blockZ(), b.blockZ()),
                    Math.max(a.blockX(), b.blockX()),
                    Math.max(a.blockY(), b.blockY()),
                    Math.max(a.blockZ(), b.blockZ()));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /**
     * Rebuild the runtime from the authoritative repositories and publish once.
     *
     * <p>Concurrent callers share one in-flight rebuild: the first caller runs
     * the injected publisher while the rest join the same future, so a retry
     * storm cannot publish twice. Sequential retries each run again but stay
     * side-effect free beyond the single volatile write. The stage never
     * touches child rows, audits, or the parent revision.
     *
     * @return the freshly published immutable registry
     */
    public CompletionStage<LandRegistry> rebuildRuntime() {
        while (true) {
            CompletableFuture<LandRegistry> current = inflight.get();
            if (current != null) {
                return current;
            }
            CompletableFuture<LandRegistry> fresh = new CompletableFuture<>();
            if (!inflight.compareAndSet(current, fresh)) {
                continue;
            }
            CompletionStage<LandRegistry> actual;
            try {
                actual = publisher.rebuildAndPublish();
            } catch (Throwable failure) {
                fresh.completeExceptionally(failure);
                inflight.compareAndSet(fresh, null);
                return fresh;
            }
            if (actual == null) {
                IllegalStateException empty =
                        new IllegalStateException("runtime publisher returned null");
                fresh.completeExceptionally(empty);
                inflight.compareAndSet(fresh, null);
                return fresh;
            }
            actual.whenComplete((registry, failure) -> {
                if (failure != null) {
                    fresh.completeExceptionally(failure);
                } else if (registry == null) {
                    fresh.completeExceptionally(
                            new IllegalStateException("runtime publisher returned null"));
                } else {
                    fresh.complete(registry);
                }
                try {
                    Runnable hook = rebuildCompletionHookForTest;
                    if (hook != null) {
                        hook.run();
                    }
                } finally {
                    inflight.compareAndSet(fresh, null);
                }
            });
            return fresh;
        }
    }

    private static RuntimePublisher defaultPublisher(LandRepository lands, LandRegistryStore store) {
        Objects.requireNonNull(lands, "lands");
        Objects.requireNonNull(store, "store");
        return () -> {
            CompletionStage<List<LandSnapshot>> read;
            try {
                read = lands.findAll();
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            if (read == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("land repository returned null"));
            }
            return read.thenApply(all -> {
                List<LandSnapshot> copy = List.copyOf(all);
                LandRegistry next =
                        LandRegistry.fromWithDepths(copy, store.snapshot().chunkDepths());
                store.publish(next);
                return next;
            });
        };
    }

    private static RuntimeRebuildPendingException pendingFailure(Throwable failure) {
        Throwable root = failure;
        if (failure instanceof java.util.concurrent.CompletionException completed
                && completed.getCause() != null) {
            root = completed.getCause();
        }
        if (root instanceof RuntimeRebuildPendingException pending) {
            return pending;
        }
        Throwable cause = root == null ? failure : root;
        String detail = cause == null || cause.getMessage() == null
                ? "unknown" : cause.getMessage();
        return new RuntimeRebuildPendingException(
                "SubLand durable commit succeeded but runtime publish failed ("
                        + detail + "); retry via rebuildRuntime",
                cause);
    }

    private static boolean isPending(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof RuntimeRebuildPendingException) {
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

    /**
     * Fire the public SubLand Pre on the caller thread. Any dispatch failure
     * fails closed to cancelled so the mutation writes nothing.
     */
    private boolean fireSubLandPre(UUID actor, LandId parentLandId, SubLandId subLandId,
            Operation operation) {
        final SubLandPreEvent pre;
        try {
            pre = new SubLandPreEvent(actor, parentLandId, subLandId, operation);
        } catch (RuntimeException failure) {
            return true;
        }
        try {
            return events.firePre(pre, () -> new SubLandPreBukkitEvent(pre.actorUuid(),
                    pre.parentLandId(), pre.subLandId(), pre.operation(), false));
        } catch (Throwable failure) {
            return true;
        }
    }

    /**
     * Fire the public SubLand Post after the atomic commit plus the runtime
     * publish. Never throws: listener failures are isolated by the facade.
     */
    private void fireSubLandPost(UUID actor, LandId parentLandId, SubLandId subLandId,
            Operation operation) {
        try {
            SubLandPostEvent post = new SubLandPostEvent(actor, parentLandId, subLandId, operation);
            events.firePost(post, () -> new SubLandPostBukkitEvent(post.actorUuid(),
                    post.parentLandId(), post.subLandId(), post.operation(), true));
        } catch (Throwable ignored) {
            // Post dispatch must never break the committed SubLand path.
        }
    }

    private void clearActorSession(UUID actor) {
        try {
            selections.cancel(actor);
        } catch (RuntimeException ignored) {
            // Selection cleanup must never break a durable mutation.
        }
    }

    private void clearSubLandSelections(SubLandId target) {
        try {
            selections.onSubLandDeleted(target);
        } catch (RuntimeException ignored) {
            // Selection cleanup must never break a durable mutation.
        }
    }

    private static SubLandSnapshot findById(LandSnapshot parent, SubLandId id) {
        for (SubLandSnapshot sub : parent.subLands()) {
            if (sub.id().equals(id)) {
                return sub;
            }
        }
        return null;
    }

    private static <T> CompletionStage<T> failed(Throwable failure) {
        CompletableFuture<T> out = new CompletableFuture<>();
        out.completeExceptionally(failure instanceof RuntimeException runtime ? runtime
                : new IllegalStateException(failure));
        return out;
    }
}

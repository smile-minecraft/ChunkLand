package com.smile.chunkland.api.mutation;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Stable public mutation entry point.
 *
 * <p>Callable from any thread. Implementations serialize through the existing
 * mutation pipeline (validate, reservation, ledger, economy, domain commit,
 * publish, finalize) on a single serial executor and never block the caller:
 * {@link #submit(MutationRequest)} enqueues and returns immediately.
 *
 * <p>The API surface carries only immutable request/value/result types. It never
 * returns a domain registry, a mutable collection, a JDBC object or a
 * Bukkit/Paper type. A {@code null} request is rejected synchronously with
 * {@link NullPointerException}; every other failure is delivered asynchronously
 * as a {@link MutationResult} and never as a thrown exception from the returned
 * stage. Success is reported only after the durable finalize step completes.
 *
 * <p>Fail-closed diagnostics (stable message keys, never localized text):
 * unsupported kinds are {@code REJECTED / mutation.unsupported}, and a
 * synchronous throw, {@code null} stage, {@code null} result or exceptional
 * completion from the injected delegate is {@code FAILED / mutation.failed}.
 * Coordinator terminal states (including a closed coordinator's own
 * {@code coordinator.rejected} marker) pass through unchanged. Callers
 * must not interpret any other key as success.
 *
 * <p>Contract-only scope: this task provides the stable contract plus an
 * injected coordinator adapter. No default plugin instance is installed by
 * this task; integration owns the explicit supported kind set and the
 * production assembly, so an unwired path simply has no facade to call.
 *
 * @since 0.1.0
 */
public interface ChunkLandMutations {

    /**
     * Submit an immutable mutation request without blocking the caller.
     *
     * @param request immutable envelope, never {@code null}
     * @return non-null stage completing with an immutable result
     * @since 0.1.0
     */
    CompletionStage<MutationResult> submit(MutationRequest request);

    /**
     * Submit a typed mutation request without blocking the caller.
     *
     * <p>The typed request carries actor, target owner, target land, expected
     * version and operation id as separate fields (see {@link
     * TypedMutationRequest}). The production implementation behind the
     * plugin's typed mutation handle delivers all five fields to stages that
     * really enforce them — authorisation, version comparison and
     * idempotency — before any side effect. This default method, in
     * contrast, refuses every typed request with {@code REJECTED /
     * mutation.unsupported} (echoing the request's operation id) and never
     * touches {@link #submit(MutationRequest)}, because the legacy envelope
     * has no room for an expected version or an operation id and converting
     * would silently drop them. Keeping this a default method preserves
     * existing third-party implementations: they compile and run unchanged,
     * and their typed callers get an explicit rejection instead of a
     * silently degraded execution. Override it only with a pipeline that
     * enforces every field it accepts.
     *
     * <p><b>Refuse-or-enforce invariant.</b> Any entry point that accepts an
     * expected version or an operation id must either deliver it to a stage
     * that really enforces it, or explicitly refuse the request. Accepting
     * such a request and then dropping the fields is forbidden.
     *
     * <p><b>Cancellation contract.</b> The returned stage is a caller-side
     * handle only: cancelling it means this caller stops waiting for the
     * result. Cancellation is not propagated to the pipeline, so it does not
     * guarantee that a started operation stops, and it never reverts effects
     * that already completed (durable writes, economy movements). An
     * operation that already took effect stays in effect: reconcile by
     * operation id.
     *
     * <p><b>Disabled contract.</b> After the implementation is closed or
     * otherwise disabled, legacy submissions complete with {@code FAILED /
     * coordinator.rejected} and create no new side effects. The typed entry
     * above short-circuits before any lifecycle check, so it keeps returning
     * the explicit unsupported rejection.
     *
     * <p><b>Event thread contract.</b> Committed mutations publish their
     * public events on the coordinator's single serial executor thread, never
     * on a region thread. That thread also runs every subsequent mutation, so
     * a slow listener delays all later operations until it returns.
     * Listeners must not block, must not perform I/O, must not wait, and must
     * hop through the player scheduler before touching any player or world
     * state.
     *
     * @param request immutable typed request, never {@code null}
     * @return non-null stage completing with an immutable typed result
     * @since 0.1.0
     */
    default CompletionStage<TypedMutationResult> submitTyped(TypedMutationRequest request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.completedFuture(
                TypedMutationResult.rejected("mutation.unsupported", request.operationId()));
    }
}

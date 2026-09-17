package com.smile.chunkland.api.mutation;

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
}

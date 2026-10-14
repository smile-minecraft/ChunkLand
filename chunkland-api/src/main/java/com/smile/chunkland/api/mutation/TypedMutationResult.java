package com.smile.chunkland.api.mutation;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable result of a typed mutation request.
 *
 * <p>Beyond the outcome, every result carries two facts callers need before
 * they decide what to do next:
 *
 * <ul>
 *   <li>{@code diagnosticKey} — a stable, localizable message key that names
 *   the exact reason (never a catch-all failure and never rendered text).</li>
 *   <li>{@code submitted} — whether the operation reached the durable
 *   pipeline. A {@code REJECTED} result is never submitted: rejections happen
 *   before commit by definition. A {@code SUCCESS} is always submitted. A
 *   {@code FAILED} result is reported as submitted unless the implementation
 *   can prove nothing ran: after validation anything may already have taken
 *   effect (ledger row, economy movement, domain write), so callers must
 *   assume the worst and reconcile instead of blindly retrying.</li>
 * </ul>
 *
 * <p><b>Post-commit failure contract.</b> {@code FAILED} with {@code
 * submitted} set means the failure surfaced after the operation entered the
 * pipeline: the durable state, the economy, or both may already reflect it.
 * Callers must re-read the affected snapshot and compare revisions (or query
 * by {@code operationId}) before deciding between "already applied, do
 * nothing" and "compensate and retry with the same {@code operationId}". A
 * {@code FAILED} result without {@code submitted} means nothing ran and a
 * corrected request may simply be sent again.
 *
 * <p>The API never returns rendered message text: {@link #diagnosticKey()} is
 * a stable message key for the caller to localize. {@code landId} and {@code
 * diagnosticKey} may be {@code null} depending on the outcome. {@code
 * operationId} is {@code null} only when the request itself carried no
 * idempotency key (the missing-operation-id rejection); every submitted or
 * submittable result carries the operation it belongs to.
 *
 * <p>Thread-safe immutable value object.
 *
 * @since 0.1.0
 */
public record TypedMutationResult(
        MutationOutcome outcome,
        LandId landId,
        String diagnosticKey,
        UUID operationId,
        boolean submitted) {

    /**
     * Reserved key for a stale expectation mismatch. Produced once the
     * pipeline compares {@link ExpectedVersion} against live state; no
     * producer reads it yet.
     */
    public static final String STALE_EXPECTED_VERSION = "mutation.stale-expected-version";

    public TypedMutationResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    /**
     * Durably committed result.
     *
     * @param landId affected land, never {@code null}
     * @param operationId idempotency key of the committed operation, never {@code null}
     */
    public static TypedMutationResult success(LandId landId, UUID operationId) {
        Objects.requireNonNull(landId, "landId");
        return new TypedMutationResult(
                MutationOutcome.SUCCESS, landId, null, requireOperation(operationId), true);
    }

    /**
     * Rejected before commit; nothing ran.
     *
     * @param diagnosticKey stable reason key, never blank
     * @param operationId idempotency key of the rejected operation, or {@code
     *     null} when the request carried none (missing-operation-id rejection)
     */
    public static TypedMutationResult rejected(String diagnosticKey, UUID operationId) {
        return new TypedMutationResult(MutationOutcome.REJECTED, null,
                requireDiagnosticKey(diagnosticKey), operationId, false);
    }

    /**
     * Failed before reaching the durable pipeline; nothing ran.
     *
     * @param diagnosticKey stable reason key, never blank
     * @param operationId idempotency key of the failed operation, or {@code
     *     null} when the request carried none
     */
    public static TypedMutationResult failedBeforeSubmit(String diagnosticKey, UUID operationId) {
        return new TypedMutationResult(MutationOutcome.FAILED, null,
                requireDiagnosticKey(diagnosticKey), operationId, false);
    }

    /**
     * Failed after entering the durable pipeline; effects may already exist.
     * Reconcile by {@code operationId} before retrying.
     *
     * @param diagnosticKey stable reason key, never blank
     * @param operationId idempotency key of the failed operation, never {@code null}
     */
    public static TypedMutationResult failedAfterCommit(String diagnosticKey, UUID operationId) {
        return new TypedMutationResult(MutationOutcome.FAILED, null,
                requireDiagnosticKey(diagnosticKey), requireOperation(operationId), true);
    }

    private static UUID requireOperation(UUID operationId) {
        return Objects.requireNonNull(operationId, "operationId");
    }

    private static String requireDiagnosticKey(String diagnosticKey) {
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        if (diagnosticKey.isBlank()) {
            throw new IllegalArgumentException("diagnosticKey must not be blank");
        }
        return diagnosticKey;
    }
}

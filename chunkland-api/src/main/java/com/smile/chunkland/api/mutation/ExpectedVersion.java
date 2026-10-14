package com.smile.chunkland.api.mutation;

import java.util.Objects;

/**
 * Optimistic-concurrency expectation carried by a typed mutation request.
 *
 * <p>A caller reads the target through the public read API, records the
 * revisions it saw, and sends them back with the mutation. The pipeline
 * compares the expectation against the live state at submit time and rejects
 * a stale request with {@code mutation.stale-expected-version} instead of
 * acting on a view the caller no longer holds.
 *
 * <p>The two dimensions mirror the immutable land snapshot: {@code
 * structureRevision} is the optimistic lock for chunk set, subland geometry
 * and owner changes, while {@code landPolicyRevision} is bumped on permission
 * and rule changes. A {@code null} dimension carries no expectation on that
 * axis; at least one dimension must be present. Structural requests require
 * the structure dimension, permission and rule requests require the policy
 * dimension, and create requests carry no expectation because the target does
 * not exist yet.
 *
 * <p><b>Confirmation contract.</b> Implementations must compare every
 * non-null dimension against the live state before any side effect (including
 * economy charge) and reject mismatches without side effects. A request that
 * reaches the pipeline without a required dimension is rejected with {@code
 * mutation.missing-expected-version}, never executed as "no expectation".
 *
 * <p>Thread-safe immutable value object.
 *
 * @since 0.1.0
 */
public record ExpectedVersion(Long structureRevision, Long landPolicyRevision) {

    public ExpectedVersion {
        if (structureRevision == null && landPolicyRevision == null) {
            throw new NullPointerException("at least one revision dimension must be present");
        }
        if (structureRevision != null && structureRevision < 0) {
            throw new IllegalArgumentException("structureRevision must not be negative");
        }
        if (landPolicyRevision != null && landPolicyRevision < 0) {
            throw new IllegalArgumentException("landPolicyRevision must not be negative");
        }
    }

    /**
     * Expectation on the structure dimension only.
     *
     * @param structureRevision non-negative structure revision observed by the caller
     */
    public static ExpectedVersion structure(long structureRevision) {
        return new ExpectedVersion(structureRevision, null);
    }

    /**
     * Expectation on the policy dimension only.
     *
     * @param landPolicyRevision non-negative policy revision observed by the caller
     */
    public static ExpectedVersion policy(long landPolicyRevision) {
        return new ExpectedVersion(null, landPolicyRevision);
    }

    /**
     * Expectation on both dimensions.
     *
     * @param structureRevision non-negative structure revision observed by the caller
     * @param landPolicyRevision non-negative policy revision observed by the caller
     */
    public static ExpectedVersion of(long structureRevision, long landPolicyRevision) {
        return new ExpectedVersion(structureRevision, landPolicyRevision);
    }

    /**
     * Return the required structure dimension.
     *
     * @throws IllegalArgumentException if this expectation carries no structure dimension
     */
    public long requireStructureRevision() {
        if (structureRevision == null) {
            throw new IllegalArgumentException("expected version carries no structureRevision");
        }
        return structureRevision;
    }

    /**
     * Return the required policy dimension.
     *
     * @throws IllegalArgumentException if this expectation carries no policy dimension
     */
    public long requireLandPolicyRevision() {
        if (landPolicyRevision == null) {
            throw new IllegalArgumentException("expected version carries no landPolicyRevision");
        }
        return landPolicyRevision;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExpectedVersion version
                && Objects.equals(structureRevision, version.structureRevision)
                && Objects.equals(landPolicyRevision, version.landPolicyRevision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(structureRevision, landPolicyRevision);
    }
}

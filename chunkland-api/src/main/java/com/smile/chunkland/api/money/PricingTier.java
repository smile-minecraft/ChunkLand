package com.smile.chunkland.api.money;

import java.util.Objects;

/**
 * A single pricing tier in the unified tier model (spec §49).
 *
 * <p>A tier prices every chunk whose 1-based global index falls in {@code (previousUntil, until]}.
 * The sentinel {@link #UNBOUNDED} ({@code -1}) marks an open-ended top tier that covers every
 * chunk index above the previous tier's upper bound. Tiers are immutable and validated as a
 * contiguous, non-overlapping partition by {@link PricingTable}.
 */
public final class PricingTier {

    /** Sentinel {@code until} value meaning "no upper bound" (open-ended top tier). */
    public static final long UNBOUNDED = -1L;

    private final long until;
    private final Money price;

    public PricingTier(long until, Money price) {
        if (until != UNBOUNDED && until < 1) {
            throw new IllegalArgumentException("until must be >= 1 or UNBOUNDED(-1), got " + until);
        }
        this.until = until;
        this.price = Objects.requireNonNull(price, "price");
        if (price.isNegative()) {
            throw new IllegalArgumentException("tier price must not be negative");
        }
    }

    /** Build a tier covering chunk indices up to {@code until} (or unbounded) at {@code price}. */
    public static PricingTier of(long until, Money price) {
        return new PricingTier(until, price);
    }

    public long until() {
        return until;
    }

    public Money price() {
        return price;
    }

    public boolean isUnbounded() {
        return until == UNBOUNDED;
    }

    /**
     * Whether this tier prices the given 1-based chunk index.
     *
     * <p>An unbounded tier covers every index {@code >= 1}; a finite tier covers
     * {@code index <= until}. Contiguity with neighbouring tiers is guaranteed by
     * {@link PricingTable}, so exactly one tier covers any valid index.
     */
    public boolean covers(long chunkIndex) {
        if (chunkIndex < 1) {
            throw new IllegalArgumentException("chunkIndex must be >= 1, got " + chunkIndex);
        }
        return isUnbounded() || chunkIndex <= until;
    }
}
